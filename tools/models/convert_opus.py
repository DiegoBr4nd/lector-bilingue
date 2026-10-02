"""Convierte OPUS-MT tc-big en-es (fuente oficial de Helsinki-NLP) a CTranslate2 int8.

Uso (lo corre .github/workflows/model.yml):
    python tools/models/convert_opus.py --out out
Resultado: out/en-es/ (modelo + LICENSE + ATTRIBUTION.txt + SHA256SUMS),
out/en-es.tar.zst y out/reference-outputs.txt.
"""
import argparse
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ID = "Helsinki-NLP/opus-mt-tc-big-en-es"
REVISION = "8f4d4924189681076e9c642b2fd85278d793fd4d"
PAIR = "en-es"
ROOT = Path(__file__).resolve().parents[2]
BENCH_FILE = ROOT / "bench" / "sustitutos.txt"

CC_BY_4_0 = """Creative Commons Attribution 4.0 International (CC BY 4.0)
https://creativecommons.org/licenses/by/4.0/legalcode
"""


def add_eos(tokens: list[str]) -> list[str]:
    return [*tokens, "</s>"]


def read_bench_paragraphs(path: Path) -> list[str]:
    paragraphs, current = [], []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            continue
        if line.strip():
            current.append(line.strip())
        elif current:
            paragraphs.append(" ".join(current))
            current = []
    if current:
        paragraphs.append(" ".join(current))
    return paragraphs


def write_sha256sums(directory: Path) -> None:
    lines = []
    for f in sorted(p for p in directory.rglob("*") if p.is_file() and p.name != "SHA256SUMS"):
        digest = hashlib.sha256(f.read_bytes()).hexdigest()
        lines.append(f"{digest}  {f.relative_to(directory).as_posix()}")
    (directory / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_attribution(directory: Path, revision: str) -> None:
    (directory / "LICENSE").write_text(CC_BY_4_0, encoding="utf-8")
    (directory / "ATTRIBUTION.txt").write_text(
        "Modelo de traducción OPUS-MT tc-big inglés → español.\n"
        f"Fuente: https://huggingface.co/{REPO_ID} (revisión {revision})\n"
        "Autores: Helsinki-NLP / OPUS-MT, Jörg Tiedemann et al.\n"
        "Licencia: CC-BY-4.0. Convertido a CTranslate2 int8 por el proyecto lector-bilingue.\n",
        encoding="utf-8",
    )


def download(dest: Path) -> Path:
    from huggingface_hub import snapshot_download

    return Path(
        snapshot_download(
            repo_id=REPO_ID,
            revision=REVISION,
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


def reference_outputs(model_dir: Path, paragraphs: list[str]) -> list[str]:
    import ctranslate2
    import sentencepiece as spm

    sp_src = spm.SentencePieceProcessor(model_file=str(model_dir / "source.spm"))
    sp_tgt = spm.SentencePieceProcessor(model_file=str(model_dir / "target.spm"))
    translator = ctranslate2.Translator(str(model_dir), device="cpu", compute_type="int8", intra_threads=4)
    batch = [add_eos(sp_src.encode(p, out_type=str)) for p in paragraphs]
    results = translator.translate_batch(batch, beam_size=1, max_decoding_length=512)
    return [sp_tgt.decode(r.hypotheses[0]) for r in results]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)

    src = download(out / "hf")
    model_dir = out / PAIR
    if model_dir.exists():
        shutil.rmtree(model_dir)
    convert(src, model_dir)
    write_attribution(model_dir, REVISION)

    if BENCH_FILE.exists():
        outputs = reference_outputs(model_dir, read_bench_paragraphs(BENCH_FILE))
        (out / "reference-outputs.txt").write_text("\n\n".join(outputs) + "\n", encoding="utf-8")
    else:
        print(f"Aviso: {BENCH_FILE} no existe; sin salidas de referencia", file=sys.stderr)

    write_sha256sums(model_dir)
    subprocess.run(["tar", "--zstd", "-cf", str(out / f"{PAIR}.tar.zst"), "-C", str(out), PAIR], check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
