"""Lector del formato de textos de benchmark.

Formato: párrafos separados por una línea en blanco; las líneas de un párrafo se
unen con un espacio; las líneas que empiezan con # son comentarios.
Mismo criterio que BenchText.parse (Kotlin). Copia deliberada de la función de
tools/models/convert_opus.py, que corre sola en CI y no puede importar de aquí.
"""
from pathlib import Path


def read_bench_paragraphs(path: Path) -> list[str]:
    paragraphs, current = [], []
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if line.strip().startswith("#"):
            continue
        if line.strip():
            current.append(line.strip())
        elif current:
            paragraphs.append(" ".join(current))
            current = []
    if current:
        paragraphs.append(" ".join(current))
    return paragraphs
