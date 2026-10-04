"""Convierte OPUS-MT tc-big en-es (fuente oficial de Helsinki-NLP) a CTranslate2 int8.

Uso (lo corre .github/workflows/model.yml):
    python tools/models/convert_opus.py --pair en-es --out out
Resultado: out/<par>/ (modelo + LICENSE + ATTRIBUTION.txt + MODEL_CARD.md + SHA256SUMS),
out/<par>.tar.zst y, solo si el idioma de origen es inglés, out/reference-outputs.txt.

Pares: la tabla PAIRS fija repositorio, revisión y SHA-256 de cada par. Agregar un par es agregar
una entrada (con sus huellas); un par que no esté en la tabla se rechaza.
"""
import argparse
import hashlib
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH_FILE = ROOT / "bench" / "sustitutos.txt"

# SHA-256 de los archivos de la revisión fijada (fuente: API de Hugging Face para
# los archivos LFS y descarga directa de la misma revisión para los pequeños).
_EN_ES_SHA256 = {
    "README.md": "c42997ca3767c6750c346d1da84758a9755422c7f5c5294f12a8f4d92f8a94bc",
    "config.json": "40cf2a78774927cd8374514a73c96a3fc2285748fae1bcdfe197e1dc1cc54692",
    "generation_config.json": "8f8c2aeb22e87bcb1ea19cb206f4d9341d178574ab41778ab104ee7cf8db8379",
    "model.safetensors": "1cc0d454aed713ccd07b4cb7801f08f72c1f885c79c6fd76d2b0d99ea0d3cd2b",
    "source.spm": "b1763341d3a71262ed5aacc3f8287d3c3c2f2ae82295e11d03aea80879c98009",
    "special_tokens_map.json": "09059cedc26bc46bc09a52f05b92d4922e11917e87f3b92059bb1a63a59ab2c4",
    "target.spm": "c70086dcac73d9c759cca0b5f195a073033906dcd29433d6c5fe74e66b2cc8da",
    "tokenizer_config.json": "87237f0fcc8644b8acf407ddc3fd941748c4392c6083fa6f1d81da0ca66d2f23",
    "vocab.json": "ee2728c5deb7c2dbe255df52b02b45285fda8a7c4dafc236e6d2af5dbeab5a36",
}



@dataclass(frozen=True)
class PairSpec:
    repo_id: str
    revision: str
    expected_sha256: dict
    source_name: str
    target_name: str


# Un par solo se puede convertir si está aquí. es-en NO está: Helsinki-NLP no publica
# opus-mt-tc-big-es-en (ver informe de la Tarea 7); hay que elegir otro modelo y fijar sus huellas.
PAIRS = {
    "en-es": PairSpec(
        "Helsinki-NLP/opus-mt-tc-big-en-es",
        "8f4d4924189681076e9c642b2fd85278d793fd4d",
        _EN_ES_SHA256,
        "inglés",
        "español",
    ),
}
PAIR_RE = re.compile(r"[a-z]{2}-[a-z]{2}")


def spec_for(pair, table=None) -> PairSpec:
    table = PAIRS if table is None else table
    if not isinstance(pair, str) or not PAIR_RE.fullmatch(pair) or pair not in table:
        raise ValueError("par no permitido: " + ", ".join(sorted(table)))
    return table[pair]


def can_run_bench(pair: str) -> bool:
    """bench/sustitutos.txt está en inglés: solo sirve si el origen del par es inglés."""
    return pair.split("-")[0] == "en"


CC_BY_4_0 = """Creative Commons Attribution 4.0 International (CC BY 4.0)
https://creativecommons.org/licenses/by/4.0/legalcode
"""


def add_eos(tokens: list[str]) -> list[str]:
    return [*tokens, "</s>"]


def read_bench_paragraphs(path: Path) -> list[str]:
    paragraphs, current = [], []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
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


def read_bench_sentences(path: Path) -> list[list[str]]:
    """Como read_bench_paragraphs, pero cada párrafo es la lista de sus líneas (una oración por línea)."""
    paragraphs, current = [], []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip().startswith("#"):
            continue
        if line.strip():
            current.append(line.strip())
        elif current:
            paragraphs.append(current)
            current = []
    if current:
        paragraphs.append(current)
    return paragraphs


def verify_downloaded(directory: Path, expected: dict[str, str]) -> None:
    """Aborta (RuntimeError) si falta un archivo o su SHA-256 difiere. Solo nombra archivos."""
    bad = []
    for name, want in sorted(expected.items()):
        f = directory / name
        if not f.is_file():
            bad.append(f"{name} (falta)")
            continue
        h = hashlib.sha256()
        with f.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        if h.hexdigest() != want:
            bad.append(f"{name} (SHA-256 distinto)")
    if bad:
        raise RuntimeError("La descarga no coincide con lo esperado: " + ", ".join(bad))


def write_sha256sums(directory: Path) -> None:
    lines = []
    for f in sorted(p for p in directory.rglob("*") if p.is_file() and p.name != "SHA256SUMS"):
        digest = hashlib.sha256(f.read_bytes()).hexdigest()
        lines.append(f"{digest}  {f.relative_to(directory).as_posix()}")
    (directory / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_attribution(directory: Path, revision: str, spec: PairSpec | None = None) -> None:
    spec = spec or PAIRS["en-es"]
    (directory / "LICENSE").write_text(CC_BY_4_0, encoding="utf-8")
    (directory / "ATTRIBUTION.txt").write_text(
        f"Modelo de traducción OPUS-MT tc-big {spec.source_name} → {spec.target_name}.\n"
        f"Fuente: https://huggingface.co/{spec.repo_id} (revisión {revision})\n"
        "Autores: Helsinki-NLP / OPUS-MT, Jörg Tiedemann et al.\n"
        "Licencia: CC-BY-4.0. Convertido a CTranslate2 int8 por el proyecto lector-bilingue.\n"
        "Se distribuye sin garantías; ver la sección 5 de la licencia CC-BY-4.0.\n",
        encoding="utf-8",
    )


def download(dest: Path, spec: PairSpec) -> Path:
    from huggingface_hub import snapshot_download

    return Path(
        snapshot_download(
            repo_id=spec.repo_id,
            revision=spec.revision,
            local_dir=dest,
            allow_patterns=["*.json", "*.spm", "model.safetensors", "README.md"],
        )
    )


def convert(src: Path, out_model: Path) -> None:
    subprocess.run(
        [
            "ct2-transformers-converter",
            "--model", str(src),
            "--output_dir", str(out_model),
            "--quantization", "int8",
            "--copy_files", "source.spm", "target.spm",
        ],
        check=True,
    )


def reference_outputs(model_dir: Path, paragraphs: list[list[str]]) -> list[str]:
    """Traduce oración por oración (todas en un lote, beam 1) y une cada párrafo con un espacio."""
    import ctranslate2
    import sentencepiece as spm

    sp_src = spm.SentencePieceProcessor(model_file=str(model_dir / "source.spm"))
    sp_tgt = spm.SentencePieceProcessor(model_file=str(model_dir / "target.spm"))
    translator = ctranslate2.Translator(str(model_dir), device="cpu", compute_type="int8", intra_threads=4)
    sentences = [s for p in paragraphs for s in p]
    batch = [add_eos(sp_src.encode(s, out_type=str)) for s in sentences]
    results = translator.translate_batch(batch, beam_size=1, max_decoding_length=512)
    decoded = iter(sp_tgt.decode(r.hypotheses[0]) for r in results)
    return [" ".join(next(decoded) for _ in p) for p in paragraphs]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pair", default="en-es", help="par de idiomas (por defecto en-es)")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        spec = spec_for(args.pair)
    except ValueError as e:
        parser.error(str(e))
    pair = args.pair
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)

    src = download(out / "hf", spec)
    verify_downloaded(src, spec.expected_sha256)
    model_dir = out / pair
    if model_dir.exists():
        shutil.rmtree(model_dir)
    convert(src, model_dir)
    write_attribution(model_dir, spec.revision, spec)
    shutil.copyfile(src / "README.md", model_dir / "MODEL_CARD.md")

    if not can_run_bench(pair):
        print(f"Aviso: el banco de pruebas está en inglés; sin salidas de referencia para {pair}", file=sys.stderr)
    elif BENCH_FILE.exists():
        outputs = reference_outputs(model_dir, read_bench_sentences(BENCH_FILE))
        (out / "reference-outputs.txt").write_text("\n\n".join(outputs) + "\n", encoding="utf-8")
    else:
        print(f"Aviso: {BENCH_FILE} no existe; sin salidas de referencia", file=sys.stderr)

    write_sha256sums(model_dir)
    subprocess.run(["tar", "--zstd", "-cf", str(out / f"{pair}.tar.zst"), "-C", str(out), pair], check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
