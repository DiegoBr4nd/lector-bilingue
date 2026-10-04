import hashlib
import tempfile
import unittest
from pathlib import Path

from convert_opus import (
    PAIRS,
    PairSpec,
    add_eos,
    bench_file_for,
    bin_to_safetensors,
    write_model_card,
    spec_for,
    read_bench_paragraphs,
    read_bench_sentences,
    verify_downloaded,
    write_attribution,
    write_sha256sums,
)


class HelpersTest(unittest.TestCase):
    def test_add_eos(self):
        self.assertEqual(add_eos(["▁Hello", ","]), ["▁Hello", ",", "</s>"])

    def test_read_bench_paragraphs(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "b.txt"
            f.write_text("# comentario\nOne.\nTwo.\n\nThree.\n", encoding="utf-8")
            self.assertEqual(read_bench_paragraphs(f), ["One. Two.", "Three."])

    def test_read_bench_sentences(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "b.txt"
            f.write_text("# c\nOne.\n  Two.  \n\n\nThree.\n", encoding="utf-8")
            self.assertEqual(read_bench_sentences(f), [["One.", "Two."], ["Three."]])

    def test_sha256sums_ordenado_y_relativo(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "b.txt").write_bytes(b"b")
            (root / "a.txt").write_bytes(b"a")
            write_sha256sums(root)
            lines = (root / "SHA256SUMS").read_text().splitlines()
            self.assertEqual(
                lines,
                [
                    "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb  a.txt",
                    "3e23e8160039594a33894f6564e1b1348bbd7a0088d42c4acb73eeaed59c009d  b.txt",
                ],
            )

    def test_attribution_menciona_licencia_y_fuente(self):
        with tempfile.TemporaryDirectory() as d:
            write_attribution(Path(d), "abc123")
            text = (Path(d) / "ATTRIBUTION.txt").read_text(encoding="utf-8")
            self.assertIn("CC-BY-4.0", text)
            self.assertIn("Helsinki-NLP/opus-mt-tc-big-en-es", text)
            self.assertIn("abc123", text)
            self.assertIn("Se distribuye sin garantías; ver la sección 5 de la licencia CC-BY-4.0.", text)


class PairTest(unittest.TestCase):
    def test_en_es_por_defecto_es_tc_big(self):
        spec = spec_for("en-es")
        self.assertEqual(spec.repo_id, "Helsinki-NLP/opus-mt-tc-big-en-es")
        self.assertEqual(len(spec.revision), 40)
        self.assertIn("model.safetensors", spec.expected_sha256)

    def test_id_del_repo_sale_del_par(self):
        table = {"es-en": PairSpec("Helsinki-NLP/opus-mt-tc-big-es-en", "a" * 40, {}, "español", "inglés")}
        self.assertEqual(spec_for("es-en", table).repo_id, "Helsinki-NLP/opus-mt-tc-big-es-en")

    def test_par_invalido_falla(self):
        for bad in ("../x", "en-es/../..", "EN-ES", "", "xx-yy", "fr-en"):
            with self.assertRaises(ValueError, msg=bad):
                spec_for(bad)

    def test_bench_solo_si_el_origen_es_ingles(self):
        self.assertEqual(bench_file_for("en-es").name, "sustitutos.txt")
        self.assertEqual(bench_file_for("es-en").name, "sustitutos-es.txt")
        self.assertIsNone(bench_file_for("fr-en", {"en": 1}))

    def test_es_en_apunta_al_modelo_fijado(self):
        spec = spec_for("es-en")
        self.assertEqual(spec.repo_id, "Helsinki-NLP/opus-mt-tc-big-cat_oci_spa-en")
        self.assertEqual(spec.revision, "bf61da0ad53492bb780ec0b2c52981b9c95332e9")
        self.assertEqual(spec.weights_file, "pytorch_model.bin")
        self.assertEqual(
            spec.expected_sha256["pytorch_model.bin"],
            "cbd1e70a9eb5ec95aaf1407319e91acfbe0e1b0d5f7855b3e4f3cfd2422ba7e3",
        )
        for name in ("config.json", "source.spm", "target.spm", "vocab.json", "README.md"):
            self.assertEqual(len(spec.expected_sha256[name]), 64, name)

    def test_cada_par_fija_la_huella_de_sus_pesos(self):
        for pair, spec in PAIRS.items():
            self.assertIn(spec.weights_file, spec.expected_sha256, pair)
            self.assertEqual(len(spec.revision), 40)

    def test_es_en_huella_distinta_falla(self):
        spec = spec_for("es-en")
        with tempfile.TemporaryDirectory() as d:
            for name in spec.expected_sha256:
                (Path(d) / name).write_bytes(b"x")
            with self.assertRaises(RuntimeError) as cm:
                verify_downloaded(Path(d), spec.expected_sha256)
            self.assertIn("pytorch_model.bin", str(cm.exception))

    def test_attribution_y_ficha_es_en(self):
        spec = spec_for("es-en")
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            write_attribution(d, spec.revision, spec)
            text = (d / "ATTRIBUTION.txt").read_text(encoding="utf-8")
            self.assertIn("español → inglés", text)
            self.assertIn("opus-mt-tc-big-cat_oci_spa-en", text)
            self.assertIn("Tiedemann", text)
            self.assertIn("CC-BY-4.0", text)
            (d / "README.md").write_text("tarjeta original", encoding="utf-8")
            write_model_card(d, d / "README.md", spec)
            card = (d / "MODEL_CARD.md").read_text(encoding="utf-8")
            self.assertIn("catalán/occitano/español", card)
            self.assertIn("tarjeta original", card)

    def test_ficha_en_es_es_copia_exacta(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / "README.md").write_text("tarjeta", encoding="utf-8")
            write_model_card(d, d / "README.md", spec_for("en-es"))
            self.assertEqual((d / "MODEL_CARD.md").read_text(encoding="utf-8"), "tarjeta")


class _FakeTensor:
    def __init__(self, v):
        self.v = v

    def contiguous(self):
        return self

    def clone(self):
        return _FakeTensor(self.v)


class WeightsOnlyTest(unittest.TestCase):
    def test_carga_con_weights_only_y_borra_el_bin(self):
        calls = {}

        class FakeTorch:
            @staticmethod
            def load(path, **kwargs):
                calls["kwargs"] = kwargs
                return {"a": _FakeTensor(1)}

        saved = {}

        def fake_save(tensors, path, metadata=None):
            saved["keys"] = sorted(tensors)
            saved["path"] = Path(path).name
            Path(path).write_bytes(b"st")

        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "pytorch_model.bin").write_bytes(b"pickle")
            bin_to_safetensors(Path(d), torch_mod=FakeTorch, save_file=fake_save)
            self.assertIs(calls["kwargs"].get("weights_only"), True)
            self.assertEqual(calls["kwargs"].get("map_location"), "cpu")
            self.assertEqual(saved, {"keys": ["a"], "path": "model.safetensors"})
            self.assertFalse((Path(d) / "pytorch_model.bin").exists())

    def test_rechaza_lo_que_no_son_tensores(self):
        class FakeTorch:
            @staticmethod
            def load(path, **kwargs):
                return {"a": "no soy tensor"}

        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "pytorch_model.bin").write_bytes(b"pickle")
            with self.assertRaises(RuntimeError):
                bin_to_safetensors(Path(d), torch_mod=FakeTorch, save_file=lambda *a, **k: None)

    def test_attribution_usa_el_par(self):
        spec = spec_for("en-es")
        with tempfile.TemporaryDirectory() as d:
            write_attribution(Path(d), "abc", spec)
            text = (Path(d) / "ATTRIBUTION.txt").read_text(encoding="utf-8")
            self.assertIn("inglés → español", text)


class VerifyDownloadedTest(unittest.TestCase):
    @staticmethod
    def _sha(data: bytes) -> str:
        return hashlib.sha256(data).hexdigest()

    def test_coincide(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "a.json").write_bytes(b"uno")
            verify_downloaded(Path(d), {"a.json": self._sha(b"uno")})

    def test_difiere_nombra_el_archivo(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "a.json").write_bytes(b"CAMBIADO-secreto")
            with self.assertRaises(RuntimeError) as cm:
                verify_downloaded(Path(d), {"a.json": self._sha(b"uno")})
            self.assertIn("a.json", str(cm.exception))
            self.assertNotIn("secreto", str(cm.exception))

    def test_falta_nombra_el_archivo(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(RuntimeError) as cm:
                verify_downloaded(Path(d), {"b.spm": self._sha(b"x")})
            self.assertIn("b.spm", str(cm.exception))


if __name__ == "__main__":
    unittest.main()
