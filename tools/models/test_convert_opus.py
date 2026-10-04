import hashlib
import tempfile
import unittest
from pathlib import Path

from convert_opus import (
    PAIRS,
    PairSpec,
    add_eos,
    can_run_bench,
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
        for bad in ("../x", "en-es/../..", "EN-ES", "", "xx-yy", "es-en"):
            with self.assertRaises(ValueError, msg=bad):
                spec_for(bad)

    def test_bench_solo_si_el_origen_es_ingles(self):
        self.assertTrue(can_run_bench("en-es"))
        self.assertFalse(can_run_bench("es-en"))

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
