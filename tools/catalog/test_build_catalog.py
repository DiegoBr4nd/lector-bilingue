"""Pruebas de build_catalog.py (solo biblioteca estándar)."""
import copy
import hashlib
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

import build_catalog as bc

NOW = datetime(2026, 10, 3, 12, 0, 0, tzinfo=timezone.utc)
PREFIX = "https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/"


class BuildCatalogTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = Path(self._tmp.name)
        self.files = {"model.bin": b"abc" * 10, "source.spm": b"xyz"}
        for name, data in self.files.items():
            (self.dir / name).write_bytes(data)
        (self.dir / "extra.txt").write_bytes(b"no esta en las sumas")
        lines = [f"{hashlib.sha256(d).hexdigest()}  {n}" for n, d in self.files.items()]
        (self.dir / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.catalog = self.dir / "catalog.json"

    def args(self, **over):
        base = {
            "sums": str(self.dir / "SHA256SUMS"),
            "sizes_from": str(self.dir),
            "id": "opus-en-es-tcbig-2026.10",
            "pair": "en-es",
            "engine": "opus",
            "model_version": "tc-big-2026.10",
            "license": "CC-BY-4.0",
            "attribution": "Helsinki-NLP / OPUS-MT, Tiedemann et al.",
            "release": "opus-en-es-v1",
            "catalog": str(self.catalog),
        }
        base.update(over)
        return base

    def run_build(self, **over):
        return bc.build(now=NOW, **self.args(**over))

    def test_crea_catalogo_valido(self):
        self.run_build()
        data = json.loads(self.catalog.read_text(encoding="utf-8"))
        self.assertEqual(data["version"], 1)
        self.assertEqual(data["generated"], "2026-10-03T12:00:00Z")
        (model,) = data["models"]
        self.assertEqual(model["id"], "opus-en-es-tcbig-2026.10")
        self.assertEqual(model["modelVersion"], "tc-big-2026.10")
        names = [f["name"] for f in model["files"]]
        self.assertEqual(names, ["model.bin", "source.spm"])  # sin SHA256SUMS ni extra.txt
        f = model["files"][0]
        self.assertEqual(f["size"], 30)
        self.assertEqual(f["sha256"], hashlib.sha256(self.files["model.bin"]).hexdigest())
        self.assertEqual(f["url"], PREFIX + "opus-en-es-v1/model.bin")
        bc.validate_catalog(data)

    def test_reemplaza_mismo_id_y_conserva_otros(self):
        self.run_build(id="otro-modelo", pair="fr-es")
        later = datetime(2026, 10, 4, tzinfo=timezone.utc)
        bc.build(now=later, **self.args())
        later2 = datetime(2026, 10, 5, tzinfo=timezone.utc)
        bc.build(now=later2, **self.args(model_version="v2"))
        data = json.loads(self.catalog.read_text(encoding="utf-8"))
        self.assertEqual([m["id"] for m in data["models"]], ["otro-modelo", "opus-en-es-tcbig-2026.10"])
        self.assertEqual(data["models"][1]["modelVersion"], "v2")
        self.assertEqual(data["generated"], "2026-10-05T00:00:00Z")

    def test_carpeta_firefox_con_slimt_json_se_agrega_a_un_catalogo_opus(self):
        self.run_build()
        fx = self.dir / "firefox-en-es"
        fx.mkdir()
        files = {
            "model.enes.intgemm.alphas.bin": b"m" * 20,
            "vocab.enes.spm": b"v" * 5,
            "lex.50.50.enes.s2t.bin": b"l" * 7,
            "slimt.json": b'{"model": "model.enes.intgemm.alphas.bin"}' + bytes([10]),
            "LICENSE": b"MPL",
        }
        for n, d in files.items():
            (fx / n).write_bytes(d)
        (fx / "SHA256SUMS").write_text(
            "".join(f"{hashlib.sha256(d).hexdigest()}  {n}" + chr(10) for n, d in sorted(files.items())), encoding="utf-8")
        later = datetime(2026, 10, 4, tzinfo=timezone.utc)
        bc.build(now=later, **self.args(
            sums=str(fx / "SHA256SUMS"), sizes_from=str(fx), id="firefox-en-es-3.0", engine="firefox",
            model_version="3.0", license="MPL-2.0", attribution="Mozilla, Firefox Translations",
            release="firefox-en-es-v1"))
        data = json.loads(self.catalog.read_text(encoding="utf-8"))
        self.assertEqual([m["id"] for m in data["models"]], ["opus-en-es-tcbig-2026.10", "firefox-en-es-3.0"])
        fx_model = data["models"][1]
        self.assertEqual(fx_model["engine"], "firefox")
        self.assertIn("slimt.json", [f["name"] for f in fx_model["files"]])
        self.assertTrue(all(f["url"].startswith(PREFIX + "firefox-en-es-v1/") for f in fx_model["files"]))

    def test_reloj_no_retrocede(self):
        self.run_build()
        with self.assertRaises(bc.CatalogError):
            self.run_build(id="otro")

    def test_salida_determinista(self):
        self.run_build()
        first = self.catalog.read_bytes()
        self.catalog.unlink()
        self.run_build()
        self.assertEqual(first, self.catalog.read_bytes())

    def test_archivo_de_las_sumas_no_existe(self):
        (self.dir / "source.spm").unlink()
        with self.assertRaises(bc.CatalogError):
            self.run_build()

    def test_archivo_vacio_rechazado(self):
        (self.dir / "source.spm").write_bytes(b"")
        with self.assertRaises(bc.CatalogError):
            self.run_build()

    def test_nombres_peligrosos_en_sumas(self):
        for bad in ["../x", ".oculto", "a b", "sub/x.bin", "a..b", "x" * 129]:
            (self.dir / "SHA256SUMS").write_text(f"{'0' * 64}  {bad}\n", encoding="utf-8")
            with self.assertRaises(bc.CatalogError, msg=bad):
                self.run_build()

    def test_suma_mal_formada(self):
        for line in ["ABC  model.bin", f"{'A' * 64}  model.bin", f"{'0' * 64} model.bin", "basura"]:
            (self.dir / "SHA256SUMS").write_text(line + "\n", encoding="utf-8")
            with self.assertRaises(bc.CatalogError, msg=line):
                self.run_build()

    def test_suma_distinta_al_contenido(self):
        (self.dir / "SHA256SUMS").write_text(f"{'0' * 64}  model.bin\n", encoding="utf-8")
        with self.assertRaises(bc.CatalogError):
            self.run_build()

    def test_nombre_repetido_en_sumas(self):
        h = hashlib.sha256(self.files["model.bin"]).hexdigest()
        (self.dir / "SHA256SUMS").write_text(f"{h}  model.bin\n{h}  model.bin\n", encoding="utf-8")
        with self.assertRaises(bc.CatalogError):
            self.run_build()

    def test_campos_invalidos(self):
        casos = [
            {"id": "Mayuscula"}, {"id": "-x"}, {"id": "a..b"}, {"id": "a" * 65}, {"id": ""},
            {"pair": "en_es"}, {"pair": "e-es"}, {"engine": "nllb"},
            {"release": "../x"}, {"release": "."}, {"release": "a/b"}, {"release": ""},
            {"license": ""}, {"license": "x" * 257}, {"attribution": ""}, {"model_version": "x" * 257},
        ]
        for caso in casos:
            with self.assertRaises(bc.CatalogError, msg=str(caso)):
                self.run_build(**caso)

    def test_limites_validate(self):
        base = {
            "version": 1, "generated": "2026-10-03T12:00:00Z",
            "models": [{
                "id": "a", "pair": "en-es", "engine": "opus", "modelVersion": "1",
                "license": "x", "attribution": "y",
                "files": [{"name": "f", "size": 1, "sha256": "0" * 64, "url": PREFIX + "t/f"}],
            }],
        }
        bc.validate_catalog(base)

        def mut(fn):
            d = copy.deepcopy(base)
            fn(d)
            with self.assertRaises(bc.CatalogError):
                bc.validate_catalog(d)

        mut(lambda d: d["models"][0]["files"][0].update(size=2**31 + 1))
        mut(lambda d: d["models"][0]["files"][0].update(size=0))
        mut(lambda d: d["models"][0]["files"][0].update(url=PREFIX + "t/otro"))
        mut(lambda d: d["models"][0]["files"][0].update(url="http://github.com/x/t/f"))
        mut(lambda d: d["models"][0]["files"][0].update(sha256="g" * 64))
        mut(lambda d: d.update(generated="2101-01-01T00:00:00Z"))
        mut(lambda d: d.update(generated="2026-10-03T12:00:00+00:00"))
        mut(lambda d: d.update(version=2))
        mut(lambda d: d.update(version=1.0))
        mut(lambda d: d.update(version=True))
        mut(lambda d: d.update(generated="2026-1-3T1:2:3Z"))
        mut(lambda d: d["models"][0]["files"][0].update(size=True))
        mut(lambda d: d["models"][0].update(files=[]))
        mut(lambda d: d["models"][0].update(files=[
            {"name": f"f{i}", "size": 1, "sha256": "0" * 64, "url": PREFIX + f"t/f{i}"} for i in range(33)]))
        mut(lambda d: d.update(models=[dict(d["models"][0], id=f"m{i}") for i in range(201)]))
        mut(lambda d: d.update(models=[d["models"][0], d["models"][0]]))

    def test_catalogo_existente_con_claves_repetidas_rechazado(self):
        self.catalog.write_text('{"version": 1, "version": 1}', encoding="utf-8")
        with self.assertRaises(bc.CatalogError):
            self.run_build()

    def test_catalogo_existente_invalido_no_se_sobrescribe(self):
        self.catalog.write_text("{}", encoding="utf-8")
        with self.assertRaises(bc.CatalogError):
            self.run_build()
        self.assertEqual(self.catalog.read_text(encoding="utf-8"), "{}")

    def test_error_no_deja_archivo(self):
        with self.assertRaises(bc.CatalogError):
            self.run_build(engine="nllb")
        self.assertFalse(self.catalog.exists())


if __name__ == "__main__":
    unittest.main()
