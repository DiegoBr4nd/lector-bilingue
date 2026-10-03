#!/usr/bin/env python3
"""Crea o actualiza catalog.json (catálogo de modelos) para lector-bilingue.

Solo biblioteca estándar. NUNCA toca llaves: la firma (minisign) la hace Juan aparte
(ver docs/catalogo.md).

Ejemplo:
  python tools/catalog/build_catalog.py --sums ruta/SHA256SUMS --sizes-from carpeta/ \
    --id opus-en-es-tcbig-2026.10 --pair en-es --engine opus \
    --model-version tc-big-2026.10 --license CC-BY-4.0 \
    --attribution "Helsinki-NLP / OPUS-MT, Tiedemann et al." \
    --release opus-en-es-v1 --catalog catalog.json

Las reglas de validación son las mismas de CatalogParser.kt en la app (módulo :models).
Si cambia una, hay que cambiar la otra.
"""
import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

URL_PREFIX = "https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/"
MAX_BYTES = 1 << 20
MAX_TEXT = 256
MAX_MODELS = 200
MAX_FILES = 32
MAX_FILE_SIZE = 1 << 31
MAX_YEAR = 2100

ID_RE = re.compile(r"[a-z0-9][a-z0-9.-]{0,63}")
PAIR_RE = re.compile(r"[a-z]{2,3}-[a-z]{2,3}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
NAME_RE = re.compile(r"[A-Za-z0-9._-]{1,128}")
ENGINES = ("opus", "firefox")
GENERATED_RE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
DATE_FORMAT = "%Y-%m-%dT%H:%M:%SZ"


class CatalogError(Exception):
    """Entrada o catálogo inválido. El mensaje nombra el campo, nunca contenido de archivos."""


def _fail(at):
    raise CatalogError(f"catálogo inválido: {at}")


def valid_file_name(name):
    return bool(NAME_RE.fullmatch(name)) and ".." not in name and not name.startswith(".")


def _text(value, at):
    if not isinstance(value, str) or not value or len(value) > MAX_TEXT:
        _fail(at)


def _parse_generated(value, at):
    if not isinstance(value, str) or not GENERATED_RE.fullmatch(value):
        _fail(at)
    try:
        moment = datetime.strptime(value, DATE_FORMAT)
    except ValueError:
        _fail(at)
    if moment.year > MAX_YEAR:
        _fail(at)
    return moment.replace(tzinfo=timezone.utc)


def _valid_url(url, name):
    if not isinstance(url, str) or not url.startswith(URL_PREFIX):
        return False
    rest = url[len(URL_PREFIX):]
    if ".." in rest:
        return False
    parts = rest.split("/")
    if len(parts) != 2:
        return False
    tag, last = parts
    return (
        bool(NAME_RE.fullmatch(tag)) and tag != "." and bool(NAME_RE.fullmatch(last)) and last == name
    )


def validate_catalog(data):
    """Mismas reglas que CatalogParser.kt. Lanza CatalogError si algo falla."""
    if not isinstance(data, dict):
        _fail("raíz")
    version = data.get("version")
    if type(version) is not int or version != 1:
        _fail("version")
    _parse_generated(data.get("generated"), "generated")
    models = data.get("models")
    if not isinstance(models, list) or len(models) > MAX_MODELS:
        _fail("models")
    ids = set()
    for i, model in enumerate(models):
        at = f"models[{i}]"
        if not isinstance(model, dict):
            _fail(at)
        mid = model.get("id")
        if not isinstance(mid, str) or not ID_RE.fullmatch(mid) or ".." in mid:
            _fail(f"{at}.id")
        if mid in ids:
            _fail(f"{at}.id")
        ids.add(mid)
        pair = model.get("pair")
        if not isinstance(pair, str) or not PAIR_RE.fullmatch(pair):
            _fail(f"{at}.pair")
        if model.get("engine") not in ENGINES:
            _fail(f"{at}.engine")
        for key in ("modelVersion", "license", "attribution"):
            _text(model.get(key), f"{at}.{key}")
        files = model.get("files")
        if not isinstance(files, list) or not 1 <= len(files) <= MAX_FILES:
            _fail(f"{at}.files")
        names = set()
        for j, f in enumerate(files):
            fat = f"{at}.files[{j}]"
            if not isinstance(f, dict):
                _fail(fat)
            name = f.get("name")
            if not isinstance(name, str) or not valid_file_name(name):
                _fail(f"{fat}.name")
            if name in names:
                _fail(f"{fat}.name")
            names.add(name)
            size = f.get("size")
            if type(size) is not int or not 1 <= size <= MAX_FILE_SIZE:
                _fail(f"{fat}.size")
            sha = f.get("sha256")
            if not isinstance(sha, str) or not SHA256_RE.fullmatch(sha):
                _fail(f"{fat}.sha256")
            if not _valid_url(f.get("url"), name):
                _fail(f"{fat}.url")


def parse_sums(path):
    """Lee SHA256SUMS ('<64 hex>  <nombre>') y devuelve [(nombre, sha256)] en orden."""
    try:
        text = Path(path).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        raise CatalogError("no se pudo leer el archivo de sumas")
    entries = []
    seen = set()
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        sha, sep, name = line.partition("  ")
        if not sep or not SHA256_RE.fullmatch(sha):
            raise CatalogError(f"sumas: línea {number} mal formada")
        if name == "SHA256SUMS":
            continue
        if not valid_file_name(name):
            raise CatalogError(f"sumas: línea {number}: nombre de archivo no permitido")
        if name in seen:
            raise CatalogError(f"sumas: línea {number}: nombre repetido")
        seen.add(name)
        entries.append((name, sha))
    if not entries:
        raise CatalogError("sumas: no hay archivos")
    return entries


def _sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _no_duplicate_keys(pairs):
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise CatalogError("catálogo existente con claves repetidas")
    return dict(pairs)


def _load_existing(path):
    p = Path(path)
    if not p.exists():
        return None
    try:
        raw = p.read_bytes()
        if len(raw) > MAX_BYTES:
            raise CatalogError("catálogo existente mayor que 1 MiB")
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=_no_duplicate_keys)
    except (OSError, UnicodeDecodeError, ValueError):
        raise CatalogError("catálogo existente ilegible")
    validate_catalog(data)
    return data


def build(*, sums, sizes_from, id, pair, engine, model_version, license, attribution,
          release, catalog, now=None):
    now = (now or datetime.now(timezone.utc)).replace(microsecond=0)
    files = []
    base = Path(sizes_from)
    for name, sha in parse_sums(sums):
        f = base / name
        if not f.is_file():
            raise CatalogError("sumas: un archivo de la lista no está en la carpeta")
        if _sha256_of(f) != sha:
            raise CatalogError("sumas: un archivo no coincide con su suma SHA-256")
        files.append({
            "name": name,
            "size": f.stat().st_size,
            "sha256": sha,
            "url": f"{URL_PREFIX}{release}/{name}",
        })
    model = {
        "id": id,
        "pair": pair,
        "engine": engine,
        "modelVersion": model_version,
        "license": license,
        "attribution": attribution,
        "files": files,
    }
    existing = _load_existing(catalog)
    models = []
    replaced = False
    if existing is not None:
        previous = _parse_generated(existing["generated"], "generated")
        if now <= previous:
            raise CatalogError("el reloj está atrasado respecto al catálogo existente")
        for m in existing["models"]:
            if m["id"] == id:
                models.append(model)
                replaced = True
            else:
                models.append(m)
    if not replaced:
        models.append(model)
    data = {"version": 1, "generated": now.strftime(DATE_FORMAT), "models": models}
    validate_catalog(data)
    out = (json.dumps(data, indent=2, ensure_ascii=True) + "\n").encode("utf-8")
    if len(out) > MAX_BYTES:
        raise CatalogError("el catálogo pasaría de 1 MiB")
    Path(catalog).write_bytes(out)
    return data


def main(argv=None):
    p = argparse.ArgumentParser(description="Crea o actualiza catalog.json (sin tocar llaves).")
    p.add_argument("--sums", required=True, help="archivo SHA256SUMS del modelo")
    p.add_argument("--sizes-from", required=True, help="carpeta con los archivos (para leer tamaños)")
    p.add_argument("--id", required=True)
    p.add_argument("--pair", required=True)
    p.add_argument("--engine", required=True)
    p.add_argument("--model-version", required=True)
    p.add_argument("--license", required=True)
    p.add_argument("--attribution", required=True)
    p.add_argument("--release", required=True, help="etiqueta del release donde están los archivos")
    p.add_argument("--catalog", required=True, help="catalog.json a crear o actualizar")
    a = p.parse_args(argv)
    try:
        data = build(
            sums=a.sums, sizes_from=a.sizes_from, id=a.id, pair=a.pair, engine=a.engine,
            model_version=a.model_version, license=a.license, attribution=a.attribution,
            release=a.release, catalog=a.catalog,
        )
    except CatalogError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    print(f"Listo: {a.catalog} ({len(data['models'])} modelo(s), generado {data['generated']}).")
    print("Siguiente paso: firmarlo con minisign (ver docs/catalogo.md).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
