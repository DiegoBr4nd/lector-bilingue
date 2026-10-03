"""Pruebas de la revisión del subproyecto 2a. Datos sintéticos únicamente."""
import contextlib
import copy
import io
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from bench_format import read_bench_paragraphs
from make_eval_html import build_eval, main
from test_make_eval_html import ORIG, comp

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "models"))
import convert_opus  # noqa: E402


class PaginaTest(unittest.TestCase):
    def test_la_clave_lleva_el_id_de_la_pagina(self):
        html, key = build_eval(ORIG, comp(), seed=2)
        self.assertEqual(key["version"], 1)
        self.assertRegex(key["page"], r"^[0-9a-f]{16}$")
        self.assertIn(f'"{key["page"]}"', html)
        self.assertIn(f'lb-eval-" + ID_PAGINA', html)

    def test_csp_presente(self):
        html, _ = build_eval(ORIG, comp(), seed=2)
        self.assertIn(
            '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; '
            "style-src 'unsafe-inline'; script-src 'unsafe-inline'\">",
            html,
        )

    def test_aviso_de_clave_ajena(self):
        html, _ = build_eval(ORIG, comp(), seed=2)
        self.assertIn("La clave no corresponde a esta página.", html)


def _codigo_puro(html: str) -> str:
    return re.search(r"// @@PURE-START(.*?)// @@PURE-END", html, re.S).group(1)


@unittest.skipUnless(shutil.which("node"), "node no está instalado")
class RevelarNodeTest(unittest.TestCase):
    def setUp(self):
        self.html, self.key = build_eval(ORIG, comp(), seed=11)

    def correr(self, notas, clave, page=None):
        prog = (
            _codigo_puro(self.html)
            + "\nconst e = JSON.parse(require('fs').readFileSync(0, 'utf8'));\n"
            + "const ok = validarClave(e.clave, e.n, e.page);\n"
            + "console.log(JSON.stringify({ok: ok, r: ok ? calcularResultados(e.notas, e.clave) : null}));\n"
        )
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "p.js"
            f.write_text(prog, encoding="utf-8")
            out = subprocess.run(
                ["node", str(f)],
                input=json.dumps({"notas": notas, "clave": clave, "n": 3, "page": page or self.key["page"]}),
                capture_output=True,
                text=True,
                check=True,
            ).stdout
        return json.loads(out)

    def test_aritmetica(self):
        notas = {}
        for e in self.key["key"]:
            i = e["index"]
            if i == 2:
                notas[f"A{i}"] = "4"  # falta la nota de B: no cuenta
                continue
            b1, b4 = (5, 3) if i == 0 else (3, 3)
            for slot in ("A", "B"):
                notas[f"{slot}{i}"] = str(b1 if e[slot] == "beam1" else b4)
        res = self.correr(notas, self.key)
        self.assertTrue(res["ok"])
        self.assertEqual(
            res["r"], {"prom1": 4.0, "prom4": 3.0, "gana1": 1, "gana4": 0, "empates": 1, "completos": 2}
        )

    def test_gana_beam4(self):
        notas = {}
        for e in self.key["key"]:
            for slot in ("A", "B"):
                notas[f"{slot}{e['index']}"] = "5" if e[slot] == "beam4" else "2"
        r = self.correr(notas, self.key)["r"]
        self.assertEqual((r["gana1"], r["gana4"], r["empates"], r["prom4"], r["prom1"]), (0, 3, 0, 5.0, 2.0))

    def test_rechaza_claves_invalidas(self):
        malas = []
        c = copy.deepcopy(self.key); c["page"] = "otra"; malas.append(c)
        c = copy.deepcopy(self.key); c["key"][1]["index"] = 0; malas.append(c)
        c = copy.deepcopy(self.key); c["key"][0]["B"] = c["key"][0]["A"]; malas.append(c)
        c = copy.deepcopy(self.key); c["key"][0]["index"] = 3; malas.append(c)
        c = copy.deepcopy(self.key); c["key"][0]["index"] = 0.5; malas.append(c)
        c = copy.deepcopy(self.key); c["key"][0]["index"] = -1; malas.append(c)
        c = copy.deepcopy(self.key); c["key"].pop(); malas.append(c)
        c = copy.deepcopy(self.key); c["version"] = 2; malas.append(c)
        c = copy.deepcopy(self.key); del c["page"]; malas.append(c)
        for mala in malas:
            res = self.correr({}, mala)
            self.assertFalse(res["ok"], mala)
            self.assertIsNone(res["r"])


class CliSeguridadTest(unittest.TestCase):
    def preparar(self, d):
        (d / "t.txt").write_text("orig zero\n\norig one\n\norig two\n", encoding="utf-8")
        (d / "c.json").write_text(json.dumps(comp()), encoding="utf-8")

    def correr(self, args):
        err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            rc = main(args)
        return rc, err.getvalue()

    def test_no_sobrescribe_sin_force_y_avisa_fuera_de_private(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            self.preparar(d)
            args = [str(d / "t.txt"), str(d / "c.json"), str(d / "e.html"), str(d / "k.json")]
            rc, err = self.correr(args)
            self.assertEqual(rc, 0)
            self.assertIn("private", err)
            antes = ((d / "e.html").read_bytes(), (d / "k.json").read_bytes())
            rc, err = self.correr(args)
            self.assertEqual(rc, 1)
            self.assertIn("--force", err)
            self.assertNotIn("orig", err)
            self.assertEqual(antes, ((d / "e.html").read_bytes(), (d / "k.json").read_bytes()))
            rc, _ = self.correr(["--force", *args])
            self.assertEqual(rc, 0)
            self.assertNotEqual(antes[1], (d / "k.json").read_bytes())

    def test_sin_aviso_dentro_de_private(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            self.preparar(d)
            (d / "private").mkdir()
            rc, err = self.correr(
                [str(d / "t.txt"), str(d / "c.json"), str(d / "private" / "e.html"), str(d / "private" / "k.json")]
            )
            self.assertEqual((rc, err), (0, ""))


class LectoresDeAcuerdoTest(unittest.TestCase):
    def test_mismo_archivo_mismos_resultados(self):
        contenido = "﻿  # comentario con sangría\r\nOne.\r\n  Two.  \r\n\r\n# otro\r\nThree.\r\n"
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "b.txt"
            f.write_bytes(contenido.encode("utf-8"))
            a = read_bench_paragraphs(f)
            b = convert_opus.read_bench_paragraphs(f)
            c = [" ".join(x) for x in convert_opus.read_bench_sentences(f)]
        self.assertEqual(a, ["One. Two.", "Three."])
        self.assertEqual(a, b)
        self.assertEqual(a, c)


if __name__ == "__main__":
    unittest.main()
