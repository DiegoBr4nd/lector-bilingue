import tempfile
import unittest
from pathlib import Path

from convert_opus import add_eos, read_bench_paragraphs, write_attribution, write_sha256sums


class HelpersTest(unittest.TestCase):
    def test_add_eos(self):
        self.assertEqual(add_eos(["▁Hello", ","]), ["▁Hello", ",", "</s>"])

    def test_read_bench_paragraphs(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "b.txt"
            f.write_text("# comentario\nOne.\nTwo.\n\nThree.\n", encoding="utf-8")
            self.assertEqual(read_bench_paragraphs(f), ["One. Two.", "Three."])

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
            text = (Path(d) / "ATTRIBUTION.txt").read_text()
            self.assertIn("CC-BY-4.0", text)
            self.assertIn("Helsinki-NLP/opus-mt-tc-big-en-es", text)
            self.assertIn("abc123", text)


if __name__ == "__main__":
    unittest.main()
