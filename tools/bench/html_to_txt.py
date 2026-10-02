"""Convierte el HTML de textos de prueba (párrafos <p> con marcador ⟦NN⟧)
al formato del benchmark: párrafos separados por una línea en blanco.

Uso: python tools/bench/html_to_txt.py textos_para_firefox.html private/textos.txt
La salida es privada: va a private/, que git ignora.
"""
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

_MARKER = re.compile(r"⟦\d+⟧")
_SPACES = re.compile(r"\s+")


class _ParagraphParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.paragraphs = []
        self._current = None

    def handle_starttag(self, tag, attrs):
        if tag == "p":
            self._current = []

    def handle_endtag(self, tag):
        if tag == "p" and self._current is not None:
            self.paragraphs.append("".join(self._current))
            self._current = None

    def handle_data(self, data):
        if self._current is not None:
            self._current.append(data)


def convert(html_text: str) -> list[str]:
    parser = _ParagraphParser()
    parser.feed(html_text)
    parser.close()
    result = []
    for raw in parser.paragraphs:
        text = _SPACES.sub(" ", _MARKER.sub("", raw)).strip()
        if text:
            result.append(text)
    return result


def render(paragraphs: list[str]) -> str:
    return "\n\n".join(paragraphs) + "\n"


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("Uso: html_to_txt.py <entrada.html> <salida.txt>", file=sys.stderr)
        return 2
    paragraphs = convert(Path(argv[1]).read_text(encoding="utf-8"))
    out = Path(argv[2])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render(paragraphs), encoding="utf-8")
    print(f"{len(paragraphs)} párrafos escritos en {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
