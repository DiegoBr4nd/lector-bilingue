#!/usr/bin/env python3
"""Espeja los modelos oficiales de Firefox Translations (Mozilla) para un par de idiomas.

Solo biblioteca estándar (la descompresión zstd la hace el programa `zstd`, ver abajo).

Uso (lo corre .github/workflows/firefox-model.yml):
    python tools/models/fetch_firefox.py --pair en-es --out out
Resultado: out/firefox-en-es/ con los archivos de Mozilla SIN modificar (ya descomprimidos,
con los nombres originales) más slimt.json (nuestro), LICENSE (MPL-2.0), ATTRIBUTION.txt,
MODEL_CARD.md y SHA256SUMS.

Seguridad:
- Solo HTTPS, solo desde los hosts de "allowed_hosts" de firefox_sources.json, sin seguir redirecciones.
- Se verifican DOS huellas por archivo: la del .zst que Mozilla publica y la del archivo descomprimido.
- Si algo falla no queda carpeta de salida (se arma en una carpeta temporal y se renombra al final).
- Nunca se imprime contenido de archivos; los errores solo nombran archivos.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
SOURCES = HERE / "firefox_sources.json"
MPL_TEXT = HERE / "licenses" / "MPL-2.0.txt"

ALLOWED_PAIRS = ("en-es", "es-en")
NAME_RE = re.compile(r"[A-Za-z0-9._-]{1,128}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
ROLES = ("model", "vocabulary", "shortlist")
RESERVED = {"LICENSE", "ATTRIBUTION.txt", "MODEL_CARD.md", "slimt.json", "SHA256SUMS"}
MAX_DOWNLOAD = 128 << 20  # tope del .zst descargado
MAX_DECOMPRESSED = 256 << 20  # tope absoluto del archivo descomprimido
TIMEOUT = 60
QUALITY_NOTE = "Nota de calidad de nuestras pruebas: 3,28/5 (menor que OPUS tc-big, 4,44/5)."


class FetchError(Exception):
    """Error de descarga o verificación. El mensaje nombra archivos, nunca su contenido."""


def validate_pair(pair) -> None:
    if not isinstance(pair, str) or pair not in ALLOWED_PAIRS:
        raise FetchError("par no permitido (usa en-es o es-en)")


def _valid_name(name) -> bool:
    return (
        isinstance(name, str)
        and bool(NAME_RE.fullmatch(name))
        and ".." not in name
        and not name.startswith(".")
    )


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def _download(url: str, dest: Path, name: str) -> None:
    opener = urllib.request.build_opener(_NoRedirect)
    req = urllib.request.Request(url, headers={"User-Agent": "lector-bilingue-models/1"})
    try:
        with opener.open(req, timeout=TIMEOUT) as resp, dest.open("wb") as fh:
            total = 0
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_DOWNLOAD:
                    raise FetchError(f"{name}: la descarga es más grande de lo esperado")
                fh.write(chunk)
    except FetchError:
        raise
    except (urllib.error.URLError, OSError, ValueError):
        raise FetchError(f"{name}: no se pudo descargar") from None


def zstd_decompress(src: Path, dest: Path, max_bytes: int) -> None:
    """Descomprime con el programa `zstd` (argumentos fijos, sin shell). Corta si pasa de max_bytes."""
    exe = shutil.which("zstd")
    if exe is None:
        raise FetchError("falta el programa zstd (en Ubuntu: sudo apt-get install zstd)")
    written = 0
    with subprocess.Popen([exe, "-d", "-c", "-q", str(src)], stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as proc:
        try:
            with dest.open("wb") as fh:
                while True:
                    chunk = proc.stdout.read(1 << 20)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > max_bytes:
                        raise FetchError("la descompresión da más bytes de lo esperado")
                    fh.write(chunk)
        except FetchError:
            proc.kill()
            raise
        if proc.wait() != 0:
            raise FetchError("zstd no pudo descomprimir el archivo")


def write_slimt_json(folder: Path, spec: dict) -> None:
    """Escribe slimt.json con el formato de native/slimtbridge/README.md (6 claves, nada más)."""
    by_role = {f.get("role"): f.get("name") for f in spec["files"]}
    cfg = spec["slimt"]
    for role in ROLES:
        if not _valid_name(by_role.get(role)):
            raise FetchError("slimt.json: nombre de archivo no permitido")
    limits = (("encoder_layers", 1, 12), ("decoder_layers", 1, 12), ("heads", 1, 16))
    for key, lo, hi in limits:
        v = cfg.get(key)
        if type(v) is not int or not lo <= v <= hi:
            raise FetchError(f"slimt.json: {key} fuera de rango")
    data = {
        "model": by_role["model"],
        "vocabulary": by_role["vocabulary"],
        "shortlist": by_role["shortlist"],
        "encoder_layers": cfg["encoder_layers"],
        "decoder_layers": cfg["decoder_layers"],
        "heads": cfg["heads"],
    }
    (folder / "slimt.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def _write_docs(folder: Path, pair: str, spec: dict, sources: dict) -> None:
    src, dst = pair.split("-")
    langs = {"en": "inglés", "es": "español"}
    version = spec["mozilla_version"]
    arch = spec["architecture"]
    shutil.copyfile(MPL_TEXT, folder / "LICENSE")
    (folder / "ATTRIBUTION.txt").write_text(
        f"Modelo de traducción de Firefox Translations, {langs[src]} → {langs[dst]}.\n"
        f"Fuente: {sources['source']} (versión {version}, arquitectura {arch}).\n"
        "Autor: Mozilla y colaboradores del proyecto Firefox Translations (Bergamot), https://github.com/mozilla/translations\n"
        "Licencia: MPL-2.0 (texto completo en el archivo LICENSE).\n"
        "Los archivos del modelo son copias sin modificar de los de Mozilla; slimt.json lo agrega el proyecto lector-bilingue.\n"
        "Se distribuye sin garantías; ver la licencia MPL-2.0.\n",
        encoding="utf-8",
    )
    rows = "\n".join(f"- `{f['name']}`: SHA-256 `{f['sha256']}`" for f in spec["files"])
    (folder / "MODEL_CARD.md").write_text(
        f"# Firefox Translations {pair}\n\n"
        f"- Origen: {sources['source']}\n"
        f"- Versión de Mozilla: {version} ({arch})\n"
        f"- Huellas verificadas el: {sources['verified_on']}\n"
        f"- Licencia: {sources['license']}\n"
        f"- {QUALITY_NOTE}\n"
        "- Motor: slimt (Marian), solo beam 1.\n"
        "- `slimt.json` (capas y cabezas del modelo) lo escribe nuestro script; no viene de Mozilla.\n\n"
        f"Archivos de Mozilla sin modificar:\n{rows}\n",
        encoding="utf-8",
    )


def write_sha256sums(folder: Path) -> None:
    lines = [
        f"{_sha256_file(p)}  {p.name}"
        for p in sorted(folder.iterdir(), key=lambda p: p.name)
        if p.is_file() and p.name != "SHA256SUMS"
    ]
    (folder / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")


def _check_spec(spec: dict, allowed_hosts: list, schemes: tuple) -> None:
    names = set()
    roles = [f.get("role") for f in spec["files"]]
    if roles != list(ROLES):
        raise FetchError("firefox_sources.json: faltan archivos (model, vocabulary, shortlist)")
    for f in spec["files"]:
        name = f["name"]
        if not _valid_name(name) or name in RESERVED or name in names:
            raise FetchError("firefox_sources.json: nombre de archivo no permitido")
        names.add(name)
        if not SHA256_RE.fullmatch(f["sha256"]) or not SHA256_RE.fullmatch(f["zst_sha256"]):
            raise FetchError(f"{name}: huella mal formada en firefox_sources.json")
        if type(f["size"]) is not int or not 1 <= f["size"] <= MAX_DECOMPRESSED:
            raise FetchError(f"{name}: tamaño no permitido en firefox_sources.json")
        u = urlparse(f["url"])
        if u.scheme not in schemes or u.hostname not in allowed_hosts or u.username or u.password:
            raise FetchError(f"{name}: dirección fuera de los hosts permitidos")


def fetch(pair, out, sources_path=SOURCES, *, decompress=zstd_decompress, schemes=("https",)) -> Path:
    """Descarga, verifica y arma out/firefox-<pair>/. Devuelve esa carpeta."""
    validate_pair(pair)
    try:
        sources = json.loads(Path(sources_path).read_text(encoding="utf-8"))
        spec = sources["pairs"][pair]
        allowed_hosts = list(sources["allowed_hosts"])
        _check_spec(spec, allowed_hosts, tuple(schemes))
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        raise FetchError("firefox_sources.json ilegible o incompleto") from None

    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    final = out / f"firefox-{pair}"
    work = Path(tempfile.mkdtemp(prefix=f".firefox-{pair}.", dir=out))
    try:
        folder = work / "model"
        folder.mkdir()
        for f in spec["files"]:
            name = f["name"]
            zst = work / (name + ".zst")
            _download(f["url"], zst, name)
            if _sha256_file(zst) != f["zst_sha256"]:
                raise FetchError(f"{name}: la huella del archivo comprimido no coincide con la de Mozilla")
            dest = folder / name
            decompress(zst, dest, f["size"])
            zst.unlink()
            if not dest.is_file() or dest.stat().st_size != f["size"]:
                raise FetchError(f"{name}: el tamaño descomprimido no coincide")
            if _sha256_file(dest) != f["sha256"]:
                raise FetchError(f"{name}: la huella del archivo descomprimido no coincide")
        write_slimt_json(folder, spec)
        _write_docs(folder, pair, spec, sources)
        write_sha256sums(folder)
        if final.exists():
            shutil.rmtree(final)
        folder.rename(final)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return final


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Espeja un modelo oficial de Firefox Translations.")
    p.add_argument("--pair", required=True, help="en-es o es-en")
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args(argv)
    try:
        folder = fetch(a.pair, a.out)
    except FetchError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    print(f"Listo: {folder}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
