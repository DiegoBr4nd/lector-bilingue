import unittest

from html_to_txt import convert, render


class ConvertTest(unittest.TestCase):
    def test_quita_marcador_y_decodifica_entidades(self):
        html = (
            '<html><body>'
            '<p><span translate="no">⟦00⟧</span> She didn&#x27;t say &quot;hi&quot;.</p>\n'
            '<p><span translate="no">⟦01⟧</span> Second   paragraph\n spans lines.</p>'
            '</body></html>'
        )
        self.assertEqual(
            convert(html),
            ["She didn't say \"hi\".", "Second paragraph spans lines."],
        )

    def test_ignora_parrafos_vacios(self):
        self.assertEqual(convert("<p> </p><p><span>⟦02⟧</span> Hi.</p>"), ["Hi."])

    def test_render_separa_con_linea_en_blanco(self):
        self.assertEqual(render(["A.", "B."]), "A.\n\nB.\n")


if __name__ == "__main__":
    unittest.main()
