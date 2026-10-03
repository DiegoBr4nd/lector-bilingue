"""Pruebas con datos sintéticos. Nunca usar aquí textos reales de Juan."""
import json
import random
import re
import tempfile
import unittest
from pathlib import Path

from bench_format import read_bench_paragraphs
from make_eval_html import build_eval, main


def comp(n=3, **over):
    paragraphs = []
    for i in range(n):
        paragraphs.append(
            {
                "index": i,
                "words": 10 * (i + 1),
                "beam1": {"text": f"uno-{i}", "ms": 1000 * (i + 1)},
                "beam4": {"text": f"cuatro-{i}", "ms": 2000 * (i + 1)},
            }
        )
    data = {"version": 1, "pair": "en-es", "threads": 4, "source": "substitutes", "paragraphs": paragraphs}
    data.update(over)
    return data


ORIG = ["orig zero", "orig one", "orig two"]


class FormatTest(unittest.TestCase):
    def test_read_bench_paragraphs(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "b.txt"
            f.write_text("# c\nOne.\nTwo.\n\nThree.\n", encoding="utf-8")
            self.assertEqual(read_bench_paragraphs(f), ["One. Two.", "Three."])


class BuildEvalTest(unittest.TestCase):
    def test_seed_determinista_y_clave_coincide(self):
        html1, key1 = build_eval(ORIG, comp(), seed=7)
        html2, key2 = build_eval(ORIG, comp(), seed=7)
        self.assertEqual(html1, html2)
        self.assertEqual(key1, key2)
        self.assertEqual(key1["version"], 1)
        rng = random.Random(7)
        for i, entry in enumerate(key1["key"]):
            self.assertEqual(entry["index"], i)
            beam4_en_a = rng.random() < 0.5
            self.assertEqual(entry["A"], "beam4" if beam4_en_a else "beam1")
            self.assertEqual(entry["B"], "beam1" if beam4_en_a else "beam4")

    def test_la_clave_dice_donde_quedo_cada_texto(self):
        html, key = build_eval(ORIG, comp(), seed=3)
        for entry in key["key"]:
            i = entry["index"]
            textos = {"beam1": f"uno-{i}", "beam4": f"cuatro-{i}"}
            self.assertIn(f'data-slot="A" data-i="{i}">{textos[entry["A"]]}<', html)
            self.assertIn(f'data-slot="B" data-i="{i}">{textos[entry["B"]]}<', html)

    def test_semillas_distintas_dan_asignaciones_distintas(self):
        claves = {json.dumps(build_eval(ORIG * 10, comp(30), seed=s)[1]) for s in range(5)}
        self.assertGreater(len(claves), 1)

    def test_sin_semilla_funciona(self):
        html, key = build_eval(ORIG, comp())
        self.assertEqual(len(key["key"]), 3)

    def test_longitudes_distintas(self):
        with self.assertRaises(ValueError):
            build_eval(ORIG[:2], comp())

    def test_version_incorrecta(self):
        with self.assertRaises(ValueError):
            build_eval(ORIG, comp(version=2))

    def test_indices_fuera_de_orden(self):
        c = comp()
        c["paragraphs"][0]["index"], c["paragraphs"][1]["index"] = 1, 0
        with self.assertRaises(ValueError):
            build_eval(ORIG, c)

    def test_campos_mal_tipados_no_filtran_texto(self):
        secreto = "TEXTO-PRIVADO-XYZ"
        c = comp()
        c["paragraphs"][1]["beam4"]["ms"] = "rapido"
        c["paragraphs"][1]["beam1"]["text"] = secreto
        with self.assertRaises(ValueError) as cm:
            build_eval(["a", secreto, "c"], c)
        self.assertNotIn(secreto, str(cm.exception))
        c = comp()
        del c["paragraphs"][2]["beam1"]
        with self.assertRaises(ValueError):
            build_eval(ORIG, c)
        c = comp()
        c["paragraphs"][0]["beam1"]["ms"] = True  # bool no cuenta como entero
        with self.assertRaises(ValueError):
            build_eval(ORIG, c)

    def test_escapa_html(self):
        c = comp()
        c["paragraphs"][0]["beam1"]["text"] = "<script>alert(1)</script>"
        c["paragraphs"][0]["beam4"]["text"] = "<script>alert(1)</script>"
        html, _ = build_eval(["<b>x</b> & y", "o", "p"], c, seed=1)
        self.assertNotIn("<script>alert(1)</script>", html)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", html)
        self.assertIn("&lt;b&gt;x&lt;/b&gt; &amp; y", html)

    def test_velocidad(self):
        html, _ = build_eval(ORIG, comp(), seed=1)
        # beam 1: 60 palabras / 6 s = 10.0 p/s; mediana 2000 ms; peor 3000 ms
        # beam 4: 60 palabras / 12 s = 5.0 p/s; mediana 4000 ms; peor 6000 ms
        m = re.search(r'<script id="velocidad" type="application/json">(.*?)</script>', html, re.S)
        v = json.loads(m.group(1))
        self.assertEqual(v["1"], {"wps": 10.0, "median_ms": 2000, "max_ms": 3000})
        self.assertEqual(v["4"], {"wps": 5.0, "median_ms": 4000, "max_ms": 6000})

    def test_la_clave_no_se_filtra_en_el_html(self):
        # Regla: las palabras literales "beam1" y "beam4" (junto, sin espacio) no
        # aparecen en ningún lugar de la página. La tabla de velocidad usa "Beam 1".
        html, _ = build_eval(ORIG, comp(), seed=5)
        self.assertNotIn("beam1", html)
        self.assertNotIn("beam4", html)

    def test_sin_recursos_externos(self):
        html, _ = build_eval(ORIG, comp(), seed=5)
        self.assertNotRegex(html, r'(src|href)\s*=\s*"https?:')
        self.assertNotIn("<link", html)
        self.assertIn("localStorage", html)
        self.assertIn("FileReader", html)
        self.assertIn("try", html)
        self.assertIn("Lee el original", html)


class CliTest(unittest.TestCase):
    def test_main_escribe_archivos_e_imprime_solo_conteos(self):
        import contextlib
        import io

        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / "t.txt").write_text("orig zero\n\norig one\n\norig two\n", encoding="utf-8")
            (d / "c.json").write_text(json.dumps(comp()), encoding="utf-8")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                rc = main([str(d / "t.txt"), str(d / "c.json"), str(d / "e.html"), str(d / "k.json")])
            self.assertEqual(rc, 0)
            self.assertTrue((d / "e.html").read_text(encoding="utf-8").startswith("<!DOCTYPE html>"))
            key = json.loads((d / "k.json").read_text(encoding="utf-8"))
            self.assertEqual(len(key["key"]), 3)
            self.assertIn("3 textos", out.getvalue())
            self.assertNotIn("orig", out.getvalue())
            self.assertNotIn("uno-", out.getvalue())


if __name__ == "__main__":
    unittest.main()
