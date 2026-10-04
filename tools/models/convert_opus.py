"""Convierte OPUS-MT tc-big en-es (fuente oficial de Helsinki-NLP) a CTranslate2 int8.

Uso (lo corre .github/workflows/model.yml):
    python tools/models/convert_opus.py --pair en-es --out out
Resultado: out/<par>/ (modelo + LICENSE + ATTRIBUTION.txt + MODEL_CARD.md + SHA256SUMS),
out/<par>.tar.zst y, solo si el idioma de origen es inglés, out/reference-outputs.txt.

Pares: la tabla PAIRS fija repositorio, revisión y SHA-256 de cada par. Agregar un par es agregar
una entrada (con sus huellas); un par que no esté en la tabla se rechaza.
- en-es: Helsinki-NLP/opus-mt-tc-big-en-es (pesos en model.safetensors).
- es-en: Helsinki-NLP/opus-mt-tc-big-cat_oci_spa-en (decisión de Juan: no existe un tc-big es-en).
  Es un modelo "muchos a uno" (cat, oci, spa -> eng): NO lleva token de idioma de destino ni de
  origen (la tarjeta del modelo traduce con el texto tal cual). Sus pesos solo vienen como
  pytorch_model.bin.

Seguridad de pytorch_model.bin: un .bin es un pickle y un pickle puede ejecutar código al
cargarse. Garantía de este script: (1) el archivo se verifica contra el SHA-256 fijado antes de
abrirlo; (2) se carga SOLO con torch.load(..., weights_only=True, map_location="cpu"), que
rechaza todo lo que no sea tensores y tipos básicos; (3) se reescribe como model.safetensors
(formato sin código) y el .bin se borra, así el conversor de CTranslate2/transformers nunca ve
el pickle. La conversión solo corre en GitHub Actions o en la máquina de desarrollo, jamás en el
teléfono.
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
    weights_file: str = "model.safetensors"
    note: str = ""  # línea extra para ATTRIBUTION.txt y MODEL_CARD.md


# SHA-256 de la revisión fijada de cat_oci_spa-en (fuente: API de Hugging Face, árbol de la
# revisión, para los archivos LFS; descarga directa de la misma revisión para los pequeños).
_ES_EN_SHA256 = {
    "README.md": "b0191805e0fd4d8727148c199f9ecf93c478744500a982cde57aca34a8b9e608",
    "config.json": "20bcf8051922fc896a1daed645a2d3a0790cc6fbc69a1ee6833c758ea8a2c679",
    "generation_config.json": "df26d6b6267aee9f16f8be224304864e6605a1e851f18ac14099016b8f9f26d9",
    "pytorch_model.bin": "cbd1e70a9eb5ec95aaf1407319e91acfbe0e1b0d5f7855b3e4f3cfd2422ba7e3",
    "source.spm": "0a23783d3c79054e9033fa55aaaeeeb6513cf712063ea87b4eeff5014ee49134",
    "special_tokens_map.json": "09059cedc26bc46bc09a52f05b92d4922e11917e87f3b92059bb1a63a59ab2c4",
    "target.spm": "e8ba58a95c4029a5c5a16c43f6784ee30fe197a1766bf2dbacc9f9b255f9361e",
    "tokenizer_config.json": "43786f76f99a5e137030ed97021ed0e4d04f8c3e86d93f1d7ffb36d72311d01d",
    "vocab.json": "8ca07a99593025777e3d72bac2fbc8fecc225c47c9c085c1b87cc7da7246ccd1",
}

# Un par solo se puede convertir si está aquí. No existe opus-mt-tc-big-es-en en Hugging Face:
# para es-en Juan eligió el modelo muchos-a-uno cat/oci/spa -> en (misma familia tc-big, CC-BY-4.0).
PAIRS = {
    "en-es": PairSpec(
        "Helsinki-NLP/opus-mt-tc-big-en-es",
        "8f4d4924189681076e9c642b2fd85278d793fd4d",
        _EN_ES_SHA256,
        "inglés",
        "español",
    ),
    "es-en": PairSpec(
        "Helsinki-NLP/opus-mt-tc-big-cat_oci_spa-en",
        "bf61da0ad53492bb780ec0b2c52981b9c95332e9",
        _ES_EN_SHA256,
        "español",
        "inglés",
        weights_file="pytorch_model.bin",
        note="Es el modelo OPUS-MT tc-big multi-origen catalán/occitano/español -> inglés "
        "(cat+oci+spa-eng). No usa tokens de idioma. La calidad es -> en se comprueba en la puerta de la fase.",
    ),
}
PAIR_RE = re.compile(r"[a-z]{2}-[a-z]{2}")


def spec_for(pair, table=None) -> PairSpec:
    table = PAIRS if table is None else table
    if not isinstance(pair, str) or not PAIR_RE.fullmatch(pair) or pair not in table:
        raise ValueError("par no permitido: " + ", ".join(sorted(table)))
    return table[pair]


BENCH_BY_SOURCE = {
    "en": ROOT / "bench" / "sustitutos.txt",
    "es": ROOT / "bench" / "sustitutos-es.txt",  # opcional: si no existe, no hay salidas de referencia
}


def bench_file_for(pair: str, table=None):
    """Archivo de entradas del idioma de origen del par (None si no hay uno para ese idioma)."""
    return (BENCH_BY_SOURCE if table is None else table).get(pair.split("-")[0])


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
        "Se distribuye sin garantías; ver la sección 5 de la licencia CC-BY-4.0.\n"
        + (spec.note + "\n" if spec.note else ""),
        encoding="utf-8",
    )


def write_model_card(directory: Path, readme: Path, spec: PairSpec) -> None:
    """MODEL_CARD.md = README del repositorio; si el par tiene nota, va primero."""
    text = readme.read_text(encoding="utf-8")
    if spec.note:
        text = (
            f"> {spec.note}\n> Fuente: https://huggingface.co/{spec.repo_id} (revisión {spec.revision}).\n\n" + text
        )
    (directory / "MODEL_CARD.md").write_text(text, encoding="utf-8")


def bin_to_safetensors(directory: Path, weights_file: str = "pytorch_model.bin", torch_mod=None, save_file=None) -> None:
    """Reescribe un pytorch_model.bin (pickle) como model.safetensors sin ejecutar código.

    torch.load con weights_only=True rechaza todo lo que no sea tensores y tipos básicos. Después
    se borra el .bin para que nadie más lo cargue. Los tensores se clonan porque safetensors no
    admite tensores que comparten memoria (embeddings atados).
    """
    if torch_mod is None:
        import torch as torch_mod
    if save_file is None:
        from safetensors.torch import save_file
    path = directory / weights_file
    state = torch_mod.load(path, weights_only=True, map_location="cpu")
    if not isinstance(state, dict) or not state:
        raise RuntimeError("pytorch_model.bin no contiene un diccionario de pesos")
    clean = {}
    for key, tensor in state.items():
        if not isinstance(key, str) or not hasattr(tensor, "contiguous"):
            raise RuntimeError("pytorch_model.bin contiene algo que no es un tensor")
        clean[key] = tensor.contiguous().clone()
    save_file(clean, str(directory / "model.safetensors"), metadata={"format": "pt"})
    path.unlink()


def download(dest: Path, spec: PairSpec) -> Path:
    from huggingface_hub import snapshot_download

    return Path(
        snapshot_download(
            repo_id=spec.repo_id,
            revision=spec.revision,
            local_dir=dest,
            allow_patterns=["*.json", "*.spm", spec.weights_file, "README.md"],
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
    if spec.weights_file.endswith(".bin"):
        bin_to_safetensors(src, spec.weights_file)
    model_dir = out / pair
    if model_dir.exists():
        shutil.rmtree(model_dir)
    convert(src, model_dir)
    write_attribution(model_dir, spec.revision, spec)
    write_model_card(model_dir, src / "README.md", spec)

    bench = bench_file_for(pair)
    if bench is None or not bench.exists():
        print(f"Aviso: no hay textos de prueba del idioma de origen de {pair}; sin salidas de referencia", file=sys.stderr)
    else:
        outputs = reference_outputs(model_dir, read_bench_sentences(bench))
        (out / "reference-outputs.txt").write_text("\n\n".join(outputs) + "\n", encoding="utf-8")

    write_sha256sums(model_dir)
    subprocess.run(["tar", "--zstd", "-cf", str(out / f"{pair}.tar.zst"), "-C", str(out), pair], check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
