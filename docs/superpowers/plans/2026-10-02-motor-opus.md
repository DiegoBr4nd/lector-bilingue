# Motor OPUS (Fase 1b) · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Traducir un párrafo inglés → español con OPUS-MT tc-big (int8) vía CTranslate2 dentro de la app, sin internet, en < 2 s y ≥ 15 palabras/s en el Pixel 7.

**Architecture:** CTranslate2 v4.8.2 y SentencePiece v0.2.2 como submódulos de git, compilados desde fuente por Gradle (`externalNativeBuild` + CMake + NDK) en una sola `libct2bridge.so` arm64. Kotlin: `SentenceSplitter` puro en `:core:text`, `OpusEngine` sobre una interfaz `NativeBridge` en `:engine:opus`, `BenchmarkRunner` y una pantalla temporal en `:app`. El modelo se convierte en GitHub Actions (`model.yml`) y se copia al teléfono con `adb` + `run-as`.

**Tech Stack:** NDK 30.0.16248370 · CMake 4.1.2 · CTranslate2 v4.8.2 (`d44d2d069eb88c7b7804da864c10c201501cb4a9`) · SentencePiece v0.2.2 (`e0cce7d37b065b5140349dbe12c6bcf6192fdd78`) · kotlinx-coroutines 1.11.0 · androidx.test runner 1.7.0 / ext-junit 1.3.0 · Python 3.12 · modelo `Helsinki-NLP/opus-mt-tc-big-en-es` revisión `8f4d4924189681076e9c642b2fd85278d793fd4d`.

**Spec:** `docs/superpowers/specs/2026-10-02-motor-opus-design.md`

**Ejecución:** subagent-driven. Tareas 1, 2, 3, 4, 9 → `infraestructura`; Tareas 5, 6, 7, 8 → `estructura`; Tarea 10 → `seguridad` + `diseno` (solo lectura) y arreglos por el agente dueño; Tarea 11 → sesión principal con Juan.

## Global Constraints

- Paquete: `io.github.diegobr4nd.lectorbilingue`. Rama `feat/motor-opus`; nunca `main`; **nunca push** sin permiso de Juan.
- Solo ABI `arm64-v8a`.
- Límites del puente: oración ≤ **1000** caracteres (UTF-16), lote ≤ **64** oraciones, `1 ≤ threads ≤ 8`, `1 ≤ beam ≤ 8`, `max_decoding_length = 512`. Por defecto `EngineConfig()` = beam 1, 4 hilos.
- Nunca registrar (log), ni incluir en mensajes de error, ni enviar por red el texto de los usuarios, de los libros o de sus traducciones.
- **Los textos privados de Juan nunca entran a git, al CI, a un reporte ni a un mensaje.** Viven solo en `textos_para_firefox.html` (raíz, sin seguimiento) y en `private/`. Antes de cada commit: `git status --short` y confirmar que no aparecen.
- Prohibido: Firebase, Play Services, analítica, MuPDF, NLLB, dependencias no libres. Dependencias nuevas permitidas en esta fase: las de Tech Stack y las de `tools/models/requirements.txt`.
- Formato de archivos de benchmark (`bench/sustitutos.txt` y `private/textos.txt`): UTF-8; párrafos separados por **una línea en blanco**; dentro de un párrafo, cada línea es una oración y se unen con un espacio; líneas que empiezan con `#` son comentarios.
- Modelo en el teléfono: `filesDir/models/en-es/` con `model.bin`, `source.spm`, `target.spm`, `config.json`, `shared_vocabulary.json` (o los que produzca el conversor), `LICENSE`, `ATTRIBUTION.txt`. Textos privados: `filesDir/bench/textos.txt`.
- El modelo tc-big en-es tiene un solo idioma destino (`spa`): **no** lleva etiqueta `>>spa<<`. La entrada se tokeniza con `source.spm` y se le agrega `</s>` al final.
- El código nativo se compila optimizado (`-O2` o superior) también en la variante *debug* (la puerta se mide con el APK debug).
- Gradle en Git Bash: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew <tareas>`.
- Commits Conventional Commits, terminando en `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Plan B: el reloj de 7 días empieza con el primer commit de la Tarea 3. Si al día 7 el puente no traduce en el Pixel, la sesión principal detiene la ejecución y prepara la comparación con ONNX Runtime para Juan.

## Review Focus

1. Texto privado filtrado al repo (`git add -A`, reporte de subagente, fixture de prueba). Esperado: `.gitignore` cubre `private/` y `textos_*.html` (Tarea 1 lo prueba con `git check-ignore`); ninguna tarea lee el HTML salvo el script local.
2. Entrada que rompe el nativo (oración vacía, 1.001 caracteres, emoji/sustitutos sueltos, 65 oraciones, handle liberado). Esperado: excepción de Kotlin con mensaje sin texto, nunca un crash. Probado en `OpusEngineTest` (Tarea 6) y en la prueba instrumentada (Tarea 11).
3. Código nativo sin optimizar en debug → benchmark falso negativo. Esperado: la Tarea 3 verifica las banderas de compilación en `compile_commands.json`.
4. Submódulos recursivos que bajan CUDA/cutlass (GB) en CI o en la laptop. Esperado: `tools/native/init-submodules.sh` solo inicia los necesarios (Tarea 3) y el CI lo usa (Tarea 9).
5. Párrafos con abreviaturas, iniciales, decimales y diálogos partidos mal → traducción peor. Esperado: casos explícitos en `SentenceSplitterTest` (Tarea 5).

---

### Task 1: Proteger los textos privados y convertir el HTML (agente `infraestructura`)

**Files:**
- Modify: `.gitignore`
- Create: `tools/bench/html_to_txt.py`, `tools/bench/test_html_to_txt.py`

**Interfaces:**
- Produces: `python tools/bench/html_to_txt.py <entrada.html> <salida.txt>`; función `convert(html_text: str) -> list[str]`.

- [ ] **Step 1: Agregar a `.gitignore`**

```gitignore
# Textos privados de Juan (derechos de autor): nunca a git
private/
textos_*.html
```

- [ ] **Step 2: Verificar**

Run: `git check-ignore -v textos_para_firefox.html private/textos.txt && git status --short`
Expected: ambas rutas aparecen ignoradas; `textos_para_firefox.html` ya no sale en `git status`.

- [ ] **Step 3: Prueba que falla** (`tools/bench/test_html_to_txt.py`, HTML sintético, sin contenido privado)

```python
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
```

Run: `cd tools/bench && python -m unittest -v test_html_to_txt` → FAIL (`ModuleNotFoundError: No module named 'html_to_txt'`).

- [ ] **Step 4: Implementación** (`tools/bench/html_to_txt.py`, solo biblioteca estándar)

```python
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
```

- [ ] **Step 5: Pasa la prueba y convierte el archivo real (local)**

Run: `cd tools/bench && python -m unittest -v test_html_to_txt` → 3 tests OK.
Run (raíz): `python tools/bench/html_to_txt.py textos_para_firefox.html private/textos.txt`
Expected: `25 párrafos escritos en private/textos.txt`. **No** mostrar ni copiar el contenido en reportes; solo el conteo.

- [ ] **Step 6: Commit** (confirmar con `git status --short` que `private/` y el HTML no aparecen)

```bash
git add .gitignore tools/bench/html_to_txt.py tools/bench/test_html_to_txt.py
git commit -m "chore(bench): ignorar textos privados y convertir HTML al formato del benchmark"
```

---

### Task 2: Conversión del modelo en GitHub Actions (agente `infraestructura`)

**Files:**
- Create: `tools/models/convert_opus.py`, `tools/models/test_convert_opus.py`, `tools/models/requirements.in`, `tools/models/requirements.txt`, `.github/workflows/model.yml`

**Interfaces:**
- Consumes: `bench/sustitutos.txt` (lo crea la Tarea 5; el script lo usa si existe y si no, omite las salidas de referencia con un aviso).
- Produces: artefacto `modelo-en-es` con `en-es.tar.zst`, `SHA256SUMS` y `reference-outputs.txt`. El tar contiene la carpeta `en-es/`.

- [ ] **Step 1: Pruebas que fallan para las funciones puras** (`tools/models/test_convert_opus.py`)

```python
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
```

Run: `cd tools/models && python -m unittest -v test_convert_opus` → FAIL (`ModuleNotFoundError`).

- [ ] **Step 2: `tools/models/convert_opus.py`** (importaciones pesadas dentro de las funciones, para que las pruebas no las necesiten)

```python
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
```

- [ ] **Step 3: Pruebas pasan**

Run: `cd tools/models && python -m unittest -v test_convert_opus` → 4 tests OK.

- [ ] **Step 4: Requisitos fijados con huellas**

`tools/models/requirements.in`:
```
ctranslate2==4.8.2
sentencepiece==0.2.2
huggingface_hub
transformers
torch
safetensors
```
Generar `requirements.txt` **para Linux x86_64 / Python 3.12** con huellas, en un entorno virtual del scratchpad (no instalar nada global en la laptop de Juan):
```bash
python -m venv "$SCRATCH/venv-tools" && "$SCRATCH/venv-tools/Scripts/python" -m pip install uv
"$SCRATCH/venv-tools/Scripts/uv" pip compile tools/models/requirements.in \
  --generate-hashes --python-version 3.12 --python-platform x86_64-manylinux_2_28 \
  --index-url https://pypi.org/simple --extra-index-url https://download.pytorch.org/whl/cpu \
  --index-strategy unsafe-best-match -o tools/models/requirements.txt
```
`torch` debe resolver a la variante `+cpu` (sin CUDA). Si `transformers` 5.x no es compatible con el conversor de ctranslate2 4.8.2 (lo dirá el primer `model.yml`), fijar `transformers<5` en `requirements.in`, regenerar y anotarlo.

- [ ] **Step 5: `.github/workflows/model.yml`**

```yaml
name: model

on:
  workflow_dispatch:
  pull_request:
    paths:
      - "tools/models/**"
      - "bench/sustitutos.txt"
      - ".github/workflows/model.yml"

permissions:
  contents: read

jobs:
  convert:
    runs-on: ubuntu-24.04
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false

      - uses: actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0
        with:
          python-version: "3.12"

      - name: Instalar dependencias con huellas
        run: |
          python -m pip install --no-deps --require-hashes -r tools/models/requirements.txt

      - name: Pruebas del script
        working-directory: tools/models
        run: python -m unittest -v test_convert_opus

      - name: Convertir el modelo
        run: python tools/models/convert_opus.py --out out

      - uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: modelo-en-es
          path: |
            out/en-es.tar.zst
            out/en-es/SHA256SUMS
            out/reference-outputs.txt
          if-no-files-found: error
          retention-days: 90
```

- [ ] **Step 6: Commit**

```bash
git add tools/models .github/workflows/model.yml
git commit -m "feat(models): convertir OPUS-MT tc-big en-es a CTranslate2 int8 en GitHub Actions"
```
(La conversión real se valida en la Tarea 11, al abrir el PR.)

---

### Task 3: Submódulos y compilación nativa mínima (agente `infraestructura`) — **empieza el reloj de 7 días**

**Files:**
- Create: `.gitmodules`, `native/third_party/CTranslate2` (submódulo), `native/third_party/sentencepiece` (submódulo)
- Create: `tools/native/init-submodules.sh`
- Create: `native/ct2bridge/CMakeLists.txt`, `native/ct2bridge/ct2bridge.cpp` (versión mínima)
- Modify: `engine/opus/build.gradle.kts`, `build-logic/convention/src/main/kotlin/ProjectConfig.kt`

**Interfaces:**
- Produces: `libct2bridge.so` (arm64-v8a) dentro del AAR de `:engine:opus` y del APK; símbolo `JNI_OnLoad`. `ProjectConfig.NDK_VERSION = "30.0.16248370"`, `ProjectConfig.CMAKE_VERSION = "4.1.2"`.

- [ ] **Step 1: Pedir a Juan que instale NDK y CMake** (la sesión principal le pasa el comando; el agente no instala SDK por su cuenta):
```
"%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "ndk;30.0.16248370" "cmake;4.1.2"
```
Si no están instalados al empezar, reportar NEEDS_CONTEXT.

- [ ] **Step 2: Submódulos fijados**

```bash
git submodule add https://github.com/OpenNMT/CTranslate2.git native/third_party/CTranslate2
git -C native/third_party/CTranslate2 checkout d44d2d069eb88c7b7804da864c10c201501cb4a9
git submodule add https://github.com/google/sentencepiece.git native/third_party/sentencepiece
git -C native/third_party/sentencepiece checkout e0cce7d37b065b5140349dbe12c6bcf6192fdd78
```

- [ ] **Step 3: `tools/native/init-submodules.sh`** (no baja CUDA, cutlass, thrust ni googletest)

```bash
#!/usr/bin/env bash
# Inicia solo los submódulos que necesita el motor (evita CUDA/cutlass/thrust, varios GB).
set -euo pipefail
cd "$(dirname "$0")/../.."
git submodule update --init native/third_party/CTranslate2 native/third_party/sentencepiece
git -C native/third_party/CTranslate2 submodule update --init \
  third_party/ruy third_party/cpu_features third_party/spdlog
git -C native/third_party/CTranslate2/third_party/ruy submodule update --init third_party/cpuinfo
```
`git update-index --chmod=+x tools/native/init-submodules.sh`. Si CTranslate2 necesita otro submódulo para compilar sin CUDA/MKL/DNNL, agregarlo aquí y explicarlo en el reporte.

- [ ] **Step 4: `ProjectConfig.kt`** — agregar:
```kotlin
    const val NDK_VERSION = "30.0.16248370"
    const val CMAKE_VERSION = "4.1.2"
```

- [ ] **Step 5: `native/ct2bridge/CMakeLists.txt`**

```cmake
cmake_minimum_required(VERSION 3.22)
project(ct2bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# La puerta se mide con el APK debug: el nativo va optimizado siempre.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g0" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g0" CACHE STRING "" FORCE)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(THIRD_PARTY "${REPO_ROOT}/native/third_party")
# Sin rutas de la máquina dentro del binario (builds reproducibles).
add_compile_options("-ffile-prefix-map=${REPO_ROOT}=." -ffunction-sections -fdata-sections)

# --- CTranslate2: CPU, sin MKL/DNNL/OpenMP/CUDA, con Ruy para ARM ---
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WITH_MKL OFF CACHE BOOL "" FORCE)
set(WITH_DNNL OFF CACHE BOOL "" FORCE)
set(WITH_CUDA OFF CACHE BOOL "" FORCE)
set(WITH_RUY ON CACHE BOOL "" FORCE)
set(OPENMP_RUNTIME NONE CACHE STRING "" FORCE)
set(BUILD_CLI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/CTranslate2" ctranslate2 EXCLUDE_FROM_ALL)

# --- SentencePiece estático ---
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/sentencepiece" sentencepiece EXCLUDE_FROM_ALL)

add_library(ct2bridge SHARED ct2bridge.cpp)
target_include_directories(ct2bridge PRIVATE "${THIRD_PARTY}/sentencepiece/src")
target_link_libraries(ct2bridge PRIVATE ctranslate2 sentencepiece-static log)
target_link_options(ct2bridge PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
```
Ajustes permitidos (documentar cada uno con el error que lo motivó): nombres de opciones o de targets distintos en v4.8.2, `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` para dependencias viejas con CMake 4, `-DCT2_...` que pida el issue OpenNMT/CTranslate2#1683.

- [ ] **Step 6: `ct2bridge.cpp` mínimo** (comprueba enlace y carga; la Tarea 4 lo reemplaza)

```cpp
#include <jni.h>

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }
```
Para comprobar que CTranslate2 y SentencePiece enlazan de verdad, agregar temporalmente una referencia a `ctranslate2::str_to_compute_type("int8")` y a `sentencepiece::SentencePieceProcessor` dentro de una función estática usada por `JNI_OnLoad`.

- [ ] **Step 7: `engine/opus/build.gradle.kts`**

```kotlin
plugins {
    id("lectorbilingue.android.library")
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue.engine.opus"
    ndkVersion = ProjectConfig.NDK_VERSION

    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("../../native/ct2bridge/CMakeLists.txt")
            version = ProjectConfig.CMAKE_VERSION
        }
    }
}

dependencies {
    api(project(":engine:api"))
}
```
Crear `engine/opus/consumer-rules.pro` vacío con un comentario (la Tarea 4 lo llena). Si `ProjectConfig` no es visible desde el script del módulo, exponer los valores con una extensión o propiedad en el plugin `lectorbilingue.android.library` y anotarlo.

- [ ] **Step 8: Compilar y comprobar**

```bash
tools/native/init-submodules.sh
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :engine:opus:assembleDebug assembleFdroidDebug
unzip -l app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk | grep lib/arm64-v8a/libct2bridge.so
find engine/opus/.cxx -name compile_commands.json | head -1 | xargs grep -o -- "-O2" | head -1
```
Expected: BUILD SUCCESSFUL; la línea del `.so` (reportar su tamaño); `-O2` presente en la variante debug. Reportar el tiempo de la primera compilación.

- [ ] **Step 9: Commit**

```bash
git add .gitmodules native tools/native engine/opus build-logic/convention/src/main/kotlin/ProjectConfig.kt
git commit -m "build(native): CTranslate2 y SentencePiece como submódulos compilados con CMake para arm64"
```

---

### Task 4: Puente JNI completo y `Ct2NativeBridge` (agente `infraestructura`)

**Files:**
- Modify: `native/ct2bridge/ct2bridge.cpp` (reemplazo completo), `engine/opus/consumer-rules.pro`
- Create: `engine/opus/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/opus/NativeBridge.kt`
- Create: `engine/opus/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/opus/Ct2NativeBridge.kt`
- Delete: `engine/opus/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/opus/package-info.kt`

**Interfaces:**
- Produces:
  - `interface NativeBridge { fun load(modelDir: String, threads: Int, beamSize: Int): Long; fun translate(handle: Long, sentences: Array<String>): Array<String>; fun unload(handle: Long) }`
  - `object Ct2NativeBridge : NativeBridge` y `internal fun utf8RoundTrip(text: String): String` (solo para la prueba instrumentada).
  - Constantes en `NativeBridge.Companion`: `MAX_SENTENCE_CHARS = 1000`, `MAX_BATCH = 64`, `MAX_THREADS = 8`, `MAX_BEAM = 8`.

- [ ] **Step 1: `NativeBridge.kt`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.opus

/**
 * Las tres operaciones del motor nativo. Existe como interfaz para probar
 * OpusEngine en la JVM sin la librería nativa.
 * Los errores llegan como IllegalArgumentException / IllegalStateException
 * con mensajes que nunca contienen el texto traducido.
 */
interface NativeBridge {
    fun load(modelDir: String, threads: Int, beamSize: Int): Long
    fun translate(handle: Long, sentences: Array<String>): Array<String>
    fun unload(handle: Long)

    companion object {
        const val MAX_SENTENCE_CHARS = 1000
        const val MAX_BATCH = 64
        const val MAX_THREADS = 8
        const val MAX_BEAM = 8
    }
}
```

- [ ] **Step 2: `Ct2NativeBridge.kt`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.opus

/** Implementación real: llama a libct2bridge.so (CTranslate2 + SentencePiece). */
object Ct2NativeBridge : NativeBridge {
    init {
        System.loadLibrary("ct2bridge")
    }

    override fun load(modelDir: String, threads: Int, beamSize: Int): Long =
        nativeLoad(modelDir, threads, beamSize)

    override fun translate(handle: Long, sentences: Array<String>): Array<String> =
        nativeTranslate(handle, sentences)

    override fun unload(handle: Long) = nativeUnload(handle)

    /** Solo para pruebas: UTF-16 → UTF-8 → UTF-16 dentro del nativo. */
    internal fun utf8RoundTrip(text: String): String = nativeUtf8RoundTrip(text)

    @JvmStatic private external fun nativeLoad(modelDir: String, threads: Int, beamSize: Int): Long
    @JvmStatic private external fun nativeTranslate(handle: Long, sentences: Array<String>): Array<String>
    @JvmStatic private external fun nativeUnload(handle: Long)
    @JvmStatic private external fun nativeUtf8RoundTrip(text: String): String
}
```

- [ ] **Step 3: `ct2bridge.cpp` completo**

```cpp
// Puente JNI entre Kotlin (Ct2NativeBridge) y CTranslate2 + SentencePiece.
// Reglas: validar toda entrada, no dejar escapar excepciones C++, no registrar texto.
#include <jni.h>

#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_set>
#include <vector>
#include <sys/stat.h>

#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>

namespace {

constexpr int kMaxSentenceChars = 1000;
constexpr int kMaxBatch = 64;
constexpr int kMaxThreads = 8;
constexpr int kMaxBeam = 8;
constexpr size_t kMaxDecodingLength = 512;

struct Engine {
    std::unique_ptr<ctranslate2::Translator> translator;
    sentencepiece::SentencePieceProcessor source;
    sentencepiece::SentencePieceProcessor target;
    size_t beam = 1;
};

std::mutex g_registry_mutex;
std::unordered_set<Engine*> g_registry;  // handles válidos: evita usar punteros liberados o inventados

struct InvalidArgument : std::runtime_error { using std::runtime_error::runtime_error; };

void throwJava(JNIEnv* env, const char* cls, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c != nullptr) env->ThrowNew(c, msg);
}

bool fileExists(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

// UTF-16 (Java) → UTF-8 estándar. Sustitutos sueltos → U+FFFD.
std::string toUtf8(JNIEnv* env, jstring s) {
    const jsize len = env->GetStringLength(s);
    std::u16string u(static_cast<size_t>(len), u'\0');
    env->GetStringRegion(s, 0, len, reinterpret_cast<jchar*>(u.data()));
    std::string out;
    out.reserve(u.size() * 3);
    for (size_t i = 0; i < u.size(); ++i) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < u.size() && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    return out;
}

// UTF-8 → UTF-16 (Java). Secuencias inválidas → U+FFFD.
jstring toJava(JNIEnv* env, const std::string& s) {
    std::u16string u;
    u.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        const auto b0 = static_cast<unsigned char>(s[i]);
        uint32_t cp;
        size_t n;
        if (b0 < 0x80) { cp = b0; n = 1; }
        else if ((b0 & 0xE0) == 0xC0) { cp = b0 & 0x1F; n = 2; }
        else if ((b0 & 0xF0) == 0xE0) { cp = b0 & 0x0F; n = 3; }
        else if ((b0 & 0xF8) == 0xF0) { cp = b0 & 0x07; n = 4; }
        else { u += u'�'; ++i; continue; }
        if (i + n > s.size()) { u += u'�'; break; }
        bool ok = true;
        for (size_t k = 1; k < n; ++k) {
            const auto b = static_cast<unsigned char>(s[i + k]);
            if ((b & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (b & 0x3F);
        }
        if (!ok || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) { u += u'�'; ++i; continue; }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u += static_cast<char16_t>(0xD800 + (cp >> 10));
            u += static_cast<char16_t>(0xDC00 + (cp & 0x3FF));
        } else {
            u += static_cast<char16_t>(cp);
        }
        i += n;
    }
    return env->NewString(reinterpret_cast<const jchar*>(u.data()), static_cast<jsize>(u.size()));
}

Engine* lookup(jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    if (g_registry.count(e) == 0) throw std::logic_error("handle inválido");
    return e;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_opus_Ct2NativeBridge_nativeLoad(
        JNIEnv* env, jclass, jstring jModelDir, jint threads, jint beam) {
    try {
        if (jModelDir == nullptr) throw InvalidArgument("modelDir nulo");
        if (threads < 1 || threads > kMaxThreads) throw InvalidArgument("threads fuera de rango (1..8)");
        if (beam < 1 || beam > kMaxBeam) throw InvalidArgument("beam fuera de rango (1..8)");
        const std::string dir = toUtf8(env, jModelDir);
        for (const char* f : {"/model.bin", "/source.spm", "/target.spm"}) {
            if (!fileExists(dir + f)) throw InvalidArgument("falta un archivo del modelo");
        }
        auto engine = std::make_unique<Engine>();
        if (!engine->source.Load(dir + "/source.spm").ok()) throw std::runtime_error("source.spm inválido");
        if (!engine->target.Load(dir + "/target.spm").ok()) throw std::runtime_error("target.spm inválido");

        ctranslate2::models::ModelLoader loader(dir);
        loader.device = ctranslate2::Device::CPU;
        loader.compute_type = ctranslate2::ComputeType::INT8;
        loader.num_replicas_per_device = 1;
        ctranslate2::ReplicaPoolConfig pool;
        pool.num_threads_per_replica = static_cast<size_t>(threads);
        engine->translator = std::make_unique<ctranslate2::Translator>(loader, pool);
        engine->beam = static_cast<size_t>(beam);

        Engine* raw = engine.release();
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        g_registry.insert(raw);
        return reinterpret_cast<jlong>(raw);
    } catch (const InvalidArgument& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());
    } catch (const std::exception&) {
        throwJava(env, "java/lang/IllegalStateException", "no se pudo cargar el modelo");
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "error nativo desconocido");
    }
    return 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_opus_Ct2NativeBridge_nativeTranslate(
        JNIEnv* env, jclass, jlong handle, jobjectArray jSentences) {
    try {
        if (jSentences == nullptr) throw InvalidArgument("lista nula");
        const jsize n = env->GetArrayLength(jSentences);
        if (n < 1 || n > kMaxBatch) throw InvalidArgument("lote fuera de rango (1..64)");
        Engine* engine = lookup(handle);

        std::vector<std::vector<std::string>> batch;
        batch.reserve(static_cast<size_t>(n));
        for (jsize i = 0; i < n; ++i) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(jSentences, i));
            if (js == nullptr) throw InvalidArgument("oración nula");
            if (env->GetStringLength(js) > kMaxSentenceChars) throw InvalidArgument("oración demasiado larga (máx. 1000)");
            const std::string text = toUtf8(env, js);
            env->DeleteLocalRef(js);
            std::vector<std::string> pieces;
            if (!engine->source.Encode(text, &pieces).ok()) throw std::runtime_error("tokenización");
            pieces.emplace_back("</s>");
            batch.push_back(std::move(pieces));
        }

        ctranslate2::TranslationOptions options;
        options.beam_size = engine->beam;
        options.max_decoding_length = kMaxDecodingLength;
        const auto results = engine->translator->translate_batch(batch, options);

        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
        if (out == nullptr) return nullptr;
        for (jsize i = 0; i < n; ++i) {
            std::string decoded;
            if (!engine->target.Decode(results[static_cast<size_t>(i)].output(), &decoded).ok()) {
                throw std::runtime_error("destokenización");
            }
            jstring js = toJava(env, decoded);
            if (js == nullptr) return nullptr;
            env->SetObjectArrayElement(out, i, js);
            env->DeleteLocalRef(js);
        }
        return out;
    } catch (const InvalidArgument& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());
    } catch (const std::logic_error&) {
        throwJava(env, "java/lang/IllegalStateException", "motor no cargado");
    } catch (const std::exception&) {
        throwJava(env, "java/lang/IllegalStateException", "error al traducir");
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "error nativo desconocido");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_opus_Ct2NativeBridge_nativeUnload(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    auto* e = reinterpret_cast<Engine*>(handle);
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        if (g_registry.erase(e) == 0) return;  // ya liberado o inválido: no-op
    }
    delete e;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_opus_Ct2NativeBridge_nativeUtf8RoundTrip(
        JNIEnv* env, jclass, jstring text) {
    if (text == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "texto nulo");
        return nullptr;
    }
    try {
        return toJava(env, toUtf8(env, text));
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "error nativo desconocido");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }
```
Si la API de CTranslate2 v4.8.2 difiere (`ReplicaPoolConfig`, `ModelLoader`, `output()`), adaptar con la forma de esa versión (ver `include/ctranslate2/translator.h` del submódulo) sin cambiar el comportamiento, y documentarlo.

- [ ] **Step 4: `engine/opus/consumer-rules.pro`**

```proguard
# Los métodos native se buscan por nombre desde C++: R8 no debe renombrarlos ni quitarlos.
-keepclasseswithmembernames,includedescriptorclasses class io.github.diegobr4nd.lectorbilingue.engine.opus.Ct2NativeBridge {
    native <methods>;
}
```

- [ ] **Step 5: Compilar debug y release**

Run: `JAVA_HOME=… ./gradlew assembleFdroidDebug assembleFdroidRelease lint`
Expected: BUILD SUCCESSFUL. Con `llvm-nm -D --defined-only` del NDK sobre el `.so` extraído del APK release, listar que existen los 4 símbolos `Java_io_github_..._Ct2NativeBridge_native*` y `JNI_OnLoad`.

- [ ] **Step 6: Commit**

```bash
git add native/ct2bridge/ct2bridge.cpp engine/opus
git commit -m "feat(engine-opus): puente JNI con CTranslate2 y SentencePiece, entradas validadas"
```

---

### Task 5: `SentenceSplitter` y los 25 textos sustitutos (agente `estructura`)

**Files:**
- Create: `bench/sustitutos.txt`
- Create: `core/text/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/core/text/SentenceSplitter.kt`
- Delete: `core/text/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/core/text/package-info.kt`
- Test: `core/text/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/core/text/SentenceSplitterTest.kt`
- Test: `core/text/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/core/text/SustitutosTest.kt`
- Modify: `core/text/build.gradle.kts`

**Interfaces:**
- Produces: `object SentenceSplitter { fun split(paragraph: String): List<String> }`; archivo `bench/sustitutos.txt` en el formato de Global Constraints.

- [ ] **Step 1: Escribir `bench/sustitutos.txt`**

25 párrafos **originales** en inglés, escritos para este proyecto (no copiados de ninguna obra), 20-70 palabras cada uno, en este orden: literatura (5), coloquial con modismos (5), académico (4), legal/formal (4), técnico (4), falsos amigos (3: *actually*, *embarrassed*, *sensible*, *library*, *assist*…). Encabezado:
```
# 25 textos sustitutos para pruebas y benchmark del proyecto lector-bilingue.
# Escritos para este proyecto; licencia GPL-3.0-or-later como el resto del repositorio.
# Formato: párrafos separados por una línea en blanco; una oración por línea.
```
Incluir de forma natural los casos difíciles: un diálogo con varias oraciones entre comillas, `Dr.`/`Mr.`, `e.g.`, un decimal, iniciales (`J. R.`), `a.m.`, puntos suspensivos con continuación en minúscula, una oración terminada en `?!`, un paréntesis que cierra tras el punto.

- [ ] **Step 2: Pruebas que fallan** (`SentenceSplitterTest.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceSplitterTest {
    private fun split(text: String) = SentenceSplitter.split(text)

    @Test fun `texto vacio devuelve lista vacia`() = assertEquals(emptyList(), split(""))
    @Test fun `solo espacios devuelve lista vacia`() = assertEquals(emptyList(), split("  \n\t "))
    @Test fun `una oracion sin punto final`() = assertEquals(listOf("Hello world"), split("Hello world"))
    @Test fun `dos oraciones simples`() =
        assertEquals(listOf("It rained.", "We stayed home."), split("It rained. We stayed home."))
    @Test fun `pregunta y exclamacion`() =
        assertEquals(listOf("Are you sure?", "Yes!", "Go."), split("Are you sure? Yes! Go."))
    @Test fun `signos combinados`() =
        assertEquals(listOf("You did what?!", "Unbelievable."), split("You did what?! Unbelievable."))
    @Test fun `tratamientos no cortan`() =
        assertEquals(listOf("Mr. Smith met Dr. Jones.", "They talked."), split("Mr. Smith met Dr. Jones. They talked."))
    @Test fun `eg e ie no cortan`() =
        assertEquals(listOf("Use a tool, e.g. Gradle, i.e. a builder."), split("Use a tool, e.g. Gradle, i.e. a builder."))
    @Test fun `decimales no cortan`() =
        assertEquals(listOf("The rate rose 3.5 percent.", "Prices fell."), split("The rate rose 3.5 percent. Prices fell."))
    @Test fun `versiones no cortan`() =
        assertEquals(listOf("Install v1.2.3 now."), split("Install v1.2.3 now."))
    @Test fun `iniciales no cortan`() =
        assertEquals(listOf("J. R. R. Tolkien wrote it.", "It sold well."), split("J. R. R. Tolkien wrote it. It sold well."))
    @Test fun `am y pm no cortan`() =
        assertEquals(listOf("We met at 9 a.m. today."), split("We met at 9 a.m. today."))
    @Test fun `US no corta antes de minuscula`() =
        assertEquals(listOf("She moved to the U.S. last year."), split("She moved to the U.S. last year."))
    @Test fun `puntos suspensivos con minuscula siguen`() =
        assertEquals(listOf("I thought... maybe not."), split("I thought... maybe not."))
    @Test fun `puntos suspensivos con mayuscula cortan`() =
        assertEquals(listOf("Wait...", "Who is there?"), split("Wait... Who is there?"))
    @Test fun `elipsis unicode`() =
        assertEquals(listOf("Well…", "Fine."), split("Well… Fine."))
    @Test fun `dialogo con comillas`() =
        assertEquals(
            listOf("\"You're late,\" Marta said.", "\"I know.\"", "\"That's it?\""),
            split("\"You're late,\" Marta said. \"I know.\" \"That's it?\""),
        )
    @Test fun `comillas tipograficas`() =
        assertEquals(listOf("“Stop.”", "“Why?”"), split("“Stop.” “Why?”"))
    @Test fun `parentesis que cierra queda con su oracion`() =
        assertEquals(listOf("See the appendix (page 4.)", "Then continue."), split("See the appendix (page 4.) Then continue."))
    @Test fun `recorta espacios y saltos de linea`() =
        assertEquals(listOf("One.", "Two."), split("  One.\n\n  Two.  "))
    @Test fun `oracion que empieza con numero`() =
        assertEquals(listOf("It ended.", "2025 was hard."), split("It ended. 2025 was hard."))
}
```

Run: `JAVA_HOME=… ./gradlew :core:text:test` → FAIL (`Unresolved reference 'SentenceSplitter'`).

- [ ] **Step 3: Implementación** (`SentenceSplitter.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.core.text

/**
 * Parte un párrafo en oraciones con reglas explícitas.
 * Es Kotlin puro a propósito: se comporta igual en la JVM (pruebas) y en Android.
 */
object SentenceSplitter {
    private val TERMINATORS = setOf('.', '!', '?', '…')
    private val CLOSERS = setOf('"', '\'', '”', '’', ')', ']', '»')
    private val OPENERS = setOf('"', '\'', '“', '‘', '(', '[', '«', '¿', '¡')
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "sr", "jr", "vs", "etc", "fig",
        "inc", "ltd", "co", "mt", "e.g", "i.e", "u.s", "u.k", "a.m", "p.m",
    )

    fun split(paragraph: String): List<String> {
        val text = paragraph.trim()
        if (text.isEmpty()) return emptyList()
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] !in TERMINATORS) {
                i++
                continue
            }
            val terminatorAt = i
            var end = i + 1
            while (end < text.length && text[end] in TERMINATORS) end++
            while (end < text.length && text[end] in CLOSERS) end++
            if (end < text.length &&
                text[end].isWhitespace() &&
                nextStartsSentence(text, end) &&
                !isAbbreviationOrInitial(text, terminatorAt, end)
            ) {
                sentences += text.substring(start, end).trim()
                start = end
            }
            i = end
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { sentences += it }
        return sentences
    }

    private fun nextStartsSentence(text: String, from: Int): Boolean {
        var j = from
        while (j < text.length && text[j].isWhitespace()) j++
        if (j >= text.length) return false
        val c = text[j]
        return c.isUpperCase() || c.isDigit() || c in OPENERS
    }

    /** Solo aplica a un punto simple: "Dr.", "e.g.", "J." (inicial). */
    private fun isAbbreviationOrInitial(text: String, dot: Int, end: Int): Boolean {
        if (text[dot] != '.' || end != dot + 1) return false
        var k = dot - 1
        while (k >= 0 && (text[k].isLetter() || text[k] == '.')) k--
        val word = text.substring(k + 1, dot).lowercase()
        if (word.length == 1 && word[0].isLetter()) return true
        return word in ABBREVIATIONS
    }
}
```

- [ ] **Step 4: Pasan las pruebas**

Run: `./gradlew :core:text:test` → 21 tests OK. Si un caso falla, corregir la regla (no la prueba) y explicarlo en el reporte.

- [ ] **Step 5: Prueba con los 25 sustitutos** (`SustitutosTest.kt`) y acceso al archivo

En `core/text/build.gradle.kts` agregar:
```kotlin
tasks.withType<Test>().configureEach {
    systemProperty("bench.dir", rootProject.file("bench").absolutePath)
    inputs.dir(rootProject.file("bench"))
}
```
```kotlin
package io.github.diegobr4nd.lectorbilingue.core.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SustitutosTest {
    private val paragraphs: List<List<String>> =
        File(System.getProperty("bench.dir"), "sustitutos.txt")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { it.startsWith("#") }
            .joinToString("\n")
            .split(Regex("\n\\s*\n"))
            .map { block -> block.lines().map(String::trim).filter(String::isNotEmpty) }
            .filter { it.isNotEmpty() }

    @Test
    fun `hay 25 parrafos`() = assertEquals(25, paragraphs.size)

    @Test
    fun `cada parrafo se parte en sus oraciones esperadas`() {
        paragraphs.forEachIndexed { index, expected ->
            assertEquals(expected, SentenceSplitter.split(expected.joinToString(" ")), "párrafo $index")
        }
    }
}
```
Run: `./gradlew :core:text:test` → 23 tests OK.

- [ ] **Step 6: Actualizar la guía** — en `docs/agentes/01-estructura.md`: la fila "Partir oraciones" de la tabla de librerías dice `SentenceSplitter` propio (Kotlin puro, ver spec de la fase 1b); en §3 "Unidad de traducción" cambiar "Partir con `BreakIterator`" por "Partir con `SentenceSplitter` (reglas explícitas, igual en JVM y Android)"; en §4 tarea 3 quitar "incluidos los 25 textos de la prueba de calidad" y poner "incluidos los 25 textos sustitutos de `bench/sustitutos.txt`".

- [ ] **Step 7: Commit**

```bash
git add bench/sustitutos.txt core/text docs/agentes/01-estructura.md
git commit -m "feat(core-text): partidor de oraciones propio con 25 textos sustitutos"
```

---

### Task 6: `OpusEngine` con TDD sobre un puente falso (agente `estructura`)

**Files:**
- Modify: `gradle/libs.versions.toml`, `engine/opus/build.gradle.kts`, `gradle/verification-metadata.xml`
- Create: `engine/opus/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/opus/OpusEngine.kt`
- Test: `engine/opus/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/engine/opus/FakeNativeBridge.kt`
- Test: `engine/opus/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/engine/opus/OpusEngineTest.kt`

**Interfaces:**
- Consumes: `NativeBridge` y sus constantes (Tarea 4); `TranslationEngine`, `EngineConfig`, `LanguagePair` (`:engine:api`).
- Produces: `class OpusEngine(modelsRoot: File, bridge: NativeBridge = Ct2NativeBridge, dispatcher: CoroutineDispatcher = Dispatchers.Default) : TranslationEngine`; `fun isModelPresent(pair: LanguagePair): Boolean`; `fun modelDir(pair: LanguagePair): File`.

- [ ] **Step 1: Dependencias**

`libs.versions.toml`:
```toml
[versions]
coroutines = "1.11.0"

[libraries]
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```
`engine/opus/build.gradle.kts` → `dependencies`:
```kotlin
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
```
Regenerar huellas con el comando de `docs/build.md` y revisar que el diff de `verification-metadata.xml` solo agrega artefactos de coroutines (y sus dependencias transitivas). Listarlos en el reporte.

- [ ] **Step 2: `FakeNativeBridge.kt`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.opus

import java.util.concurrent.atomic.AtomicInteger

/** Puente falso: "traduce" poniendo el prefijo ES: y registra llamadas y concurrencia. */
class FakeNativeBridge(private val delayMillis: Long = 0) : NativeBridge {
    val loads = mutableListOf<Triple<String, Int, Int>>()
    val unloaded = mutableListOf<Long>()
    val batchSizes = mutableListOf<Int>()
    private val active = AtomicInteger(0)
    @Volatile var maxConcurrent = 0
        private set
    private var nextHandle = 1L

    @Synchronized override fun load(modelDir: String, threads: Int, beamSize: Int): Long {
        loads += Triple(modelDir, threads, beamSize)
        return nextHandle++
    }

    override fun translate(handle: Long, sentences: Array<String>): Array<String> {
        val now = active.incrementAndGet()
        synchronized(this) { maxConcurrent = maxOf(maxConcurrent, now) }
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            synchronized(this) { batchSizes += sentences.size }
            return Array(sentences.size) { "ES:" + sentences[it] }
        } finally {
            active.decrementAndGet()
        }
    }

    @Synchronized override fun unload(handle: Long) {
        unloaded += handle
    }
}
```

- [ ] **Step 3: Pruebas que fallan** (`OpusEngineTest.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.opus

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpusEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val enEs = LanguagePair("en", "es")

    private fun engineWithModel(bridge: FakeNativeBridge = FakeNativeBridge()): Pair<OpusEngine, FakeNativeBridge> {
        File(tmp.root, "en-es").mkdirs()
        return OpusEngine(tmp.root, bridge, Dispatchers.Default) to bridge
    }

    @Test fun `id es opus`() = assertEquals("opus", OpusEngine(tmp.root, FakeNativeBridge()).id)

    @Test fun `detecta si el modelo esta presente`() {
        val engine = OpusEngine(tmp.root, FakeNativeBridge())
        assertFalse(engine.isModelPresent(enEs))
        File(tmp.root, "en-es").mkdirs()
        assertTrue(engine.isModelPresent(enEs))
    }

    @Test fun `load sin modelo falla con mensaje claro`() = runTest {
        val e = assertFailsWith<IllegalStateException> { OpusEngine(tmp.root, FakeNativeBridge()).load(enEs, EngineConfig()) }
        assertTrue(e.message!!.contains("en-es"))
    }

    @Test fun `load pasa carpeta hilos y beam al puente`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig(beamSize = 2, threads = 4))
        assertEquals(listOf(Triple(File(tmp.root, "en-es").path, 4, 2)), bridge.loads)
    }

    @Test fun `load rechaza mas de 8 hilos antes del puente`() = runTest {
        val (engine, bridge) = engineWithModel()
        assertFailsWith<IllegalArgumentException> { engine.load(enEs, EngineConfig(threads = 9)) }
        assertTrue(bridge.loads.isEmpty())
    }

    @Test fun `segundo load libera el primero`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        engine.load(enEs, EngineConfig(beamSize = 4))
        assertEquals(listOf(1L), bridge.unloaded)
    }

    @Test fun `translate antes de load falla`() = runTest {
        val (engine, _) = engineWithModel()
        assertFailsWith<IllegalStateException> { engine.translate(listOf("Hi.")) }
    }

    @Test fun `lista vacia no cruza al puente`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        assertEquals(emptyList(), engine.translate(emptyList()))
        assertTrue(bridge.batchSizes.isEmpty())
    }

    @Test fun `traduce en orden`() = runTest {
        val (engine, _) = engineWithModel()
        engine.load(enEs, EngineConfig())
        assertEquals(listOf("ES:A.", "ES:B."), engine.translate(listOf("A.", "B.")))
    }

    @Test fun `parte en lotes de 64`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        val input = (1..130).map { "S$it." }
        val output = engine.translate(input)
        assertEquals(listOf(64, 64, 2), bridge.batchSizes)
        assertEquals(input.map { "ES:$it" }, output)
    }

    @Test fun `rechaza oracion de 1001 caracteres sin cruzar al puente`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        val e = assertFailsWith<IllegalArgumentException> { engine.translate(listOf("a".repeat(1001))) }
        assertFalse(e.message!!.contains("aaaa"), "el mensaje no debe incluir el texto")
        assertTrue(bridge.batchSizes.isEmpty())
    }

    @Test fun `acepta oracion de 1000 caracteres`() = runTest {
        val (engine, _) = engineWithModel()
        engine.load(enEs, EngineConfig())
        assertEquals(1, engine.translate(listOf("a".repeat(1000))).size)
    }

    @Test fun `unload libera y despues translate falla`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        engine.unload()
        engine.unload()
        assertEquals(listOf(1L), bridge.unloaded)
        assertFailsWith<IllegalStateException> { engine.translate(listOf("Hi.")) }
    }

    @Test fun `nunca dos llamadas nativas a la vez`() = runBlocking {
        val (engine, bridge) = engineWithModel(FakeNativeBridge(delayMillis = 5))
        engine.load(enEs, EngineConfig())
        (1..20).map { async(Dispatchers.Default) { engine.translate(listOf("S$it.")) } }.awaitAll()
        assertEquals(1, bridge.maxConcurrent)
    }
}
```

Run: `./gradlew :engine:opus:testDebugUnitTest` → FAIL (`Unresolved reference 'OpusEngine'`).

- [ ] **Step 4: Implementación** (`OpusEngine.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.opus

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine
import io.github.diegobr4nd.lectorbilingue.engine.opus.NativeBridge.Companion.MAX_BATCH
import io.github.diegobr4nd.lectorbilingue.engine.opus.NativeBridge.Companion.MAX_BEAM
import io.github.diegobr4nd.lectorbilingue.engine.opus.NativeBridge.Companion.MAX_SENTENCE_CHARS
import io.github.diegobr4nd.lectorbilingue.engine.opus.NativeBridge.Companion.MAX_THREADS
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Motor OPUS-MT vía CTranslate2. Un solo modelo cargado a la vez y nunca dos
 * llamadas nativas en paralelo: todas pasan por el mismo candado.
 */
class OpusEngine(
    private val modelsRoot: File,
    private val bridge: NativeBridge = Ct2NativeBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TranslationEngine {
    override val id = "opus"

    private val lock = Any()
    private var handle = 0L

    fun modelDir(pair: LanguagePair) = File(modelsRoot, "${pair.source}-${pair.target}")

    fun isModelPresent(pair: LanguagePair) = modelDir(pair).isDirectory

    override suspend fun load(pair: LanguagePair, config: EngineConfig) {
        require(config.threads <= MAX_THREADS) { "threads debe ser <= $MAX_THREADS" }
        require(config.beamSize <= MAX_BEAM) { "beamSize debe ser <= $MAX_BEAM" }
        val dir = modelDir(pair)
        check(dir.isDirectory) { "Modelo no encontrado en ${dir.path}" }
        withContext(dispatcher) {
            synchronized(lock) {
                releaseLocked()
                handle = bridge.load(dir.path, config.threads, config.beamSize)
            }
        }
    }

    override suspend fun translate(sentences: List<String>): List<String> {
        if (sentences.isEmpty()) return emptyList()
        sentences.forEachIndexed { index, s ->
            require(s.length <= MAX_SENTENCE_CHARS) {
                "La oración $index tiene ${s.length} caracteres (máximo $MAX_SENTENCE_CHARS)"
            }
        }
        return withContext(dispatcher) {
            synchronized(lock) {
                check(handle != 0L) { "Motor no cargado" }
                sentences.chunked(MAX_BATCH).flatMap { batch ->
                    bridge.translate(handle, batch.toTypedArray()).asList()
                }
            }
        }
    }

    override fun unload() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        if (handle != 0L) {
            bridge.unload(handle)
            handle = 0L
        }
    }
}
```
(Spec §5.2 dice `Mutex`; se usa `synchronized` porque `unload()` no es `suspend` en el contrato. El efecto exigido —nunca dos llamadas nativas a la vez— es el mismo y lo prueba `nunca dos llamadas nativas a la vez`.)

- [ ] **Step 5: Pasan las pruebas**

Run: `./gradlew :engine:opus:testDebugUnitTest` → 14 tests OK.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml gradle/verification-metadata.xml engine/opus
git commit -m "feat(engine-opus): OpusEngine con cola única, lotes de 64 y límites validados"
```

---

### Task 7: `BenchmarkRunner` y lectura de textos (agente `estructura`)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/benchmark/BenchmarkRunner.kt`
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/benchmark/BenchText.kt`
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/benchmark/BenchmarkRunnerTest.kt`
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/benchmark/BenchTextTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces:
  - `object BenchText { fun parse(content: String): List<String>; fun countWords(text: String): Int }`
  - `data class BenchmarkResult(val paragraphs: Int, val words: Int, val totalMillis: Long, val wordsPerSecond: Double, val medianMillis: Long, val maxMillis: Long) { val meetsSpeedGoal: Boolean; val meetsLatencyGoal: Boolean }`
  - `class BenchmarkRunner(private val nanoClock: () -> Long = System::nanoTime) { suspend fun run(paragraphs: List<String>, translate: suspend (String) -> String): BenchmarkResult }`
  - Constantes `BenchmarkResult.SPEED_GOAL_WPS = 15.0`, `BenchmarkResult.LATENCY_GOAL_MS = 2000L`.

- [ ] **Step 1: Dependencias de prueba en `app/build.gradle.kts`**
```kotlin
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
```
Regenerar huellas (solo debe agregar `kotlinx-coroutines-android`).

- [ ] **Step 2: Pruebas que fallan**

`BenchTextTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals

class BenchTextTest {
    @Test fun `une lineas y separa por linea en blanco`() =
        assertEquals(listOf("One. Two.", "Three."), BenchText.parse("One.\nTwo.\n\nThree.\n"))
    @Test fun `ignora comentarios y blancos extra`() =
        assertEquals(listOf("A."), BenchText.parse("# c\n\n\n  A.  \n\n# d\n"))
    @Test fun `acepta saltos de linea de Windows`() =
        assertEquals(listOf("A. B.", "C."), BenchText.parse("A.\r\nB.\r\n\r\nC.\r\n"))
    @Test fun `cuenta palabras`() = assertEquals(4, BenchText.countWords("  It's a  fine day. "))
}
```

`BenchmarkRunnerTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.benchmark

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkRunnerTest {
    /** Reloj falso: cada traducción "tarda" lo que diga la lista, en ms. */
    private class FakeClock(durationsMs: List<Long>) {
        private val durations = ArrayDeque(durationsMs)
        var now = 0L
        fun advance() { now += durations.removeFirst() * 1_000_000 }
    }

    @Test fun `calienta con el primer parrafo sin contarlo`() = runTest {
        val clock = FakeClock(listOf(9999, 100, 300))
        val calls = mutableListOf<String>()
        val result = BenchmarkRunner { clock.now }.run(listOf("a b", "c d e f")) { calls += it; clock.advance(); "x" }
        assertEquals(listOf("a b", "a b", "c d e f"), calls)
        assertEquals(400, result.totalMillis)
    }

    @Test fun `calcula palabras por segundo mediana y maximo`() = runTest {
        // 3 párrafos de 10 palabras; tiempos medidos 500, 1000, 1500 ms
        val p = "w ".repeat(10).trim()
        val clock = FakeClock(listOf(0, 500, 1000, 1500))
        val r = BenchmarkRunner { clock.now }.run(listOf(p, p, p)) { clock.advance(); "x" }
        assertEquals(3, r.paragraphs)
        assertEquals(30, r.words)
        assertEquals(3000, r.totalMillis)
        assertEquals(10.0, r.wordsPerSecond, 1e-9)
        assertEquals(1000, r.medianMillis)
        assertEquals(1500, r.maxMillis)
        assertFalse(r.meetsSpeedGoal)
        assertTrue(r.meetsLatencyGoal)
    }

    @Test fun `mediana con cantidad par`() = runTest {
        val clock = FakeClock(listOf(0, 100, 200, 300, 400))
        val r = BenchmarkRunner { clock.now }.run(List(4) { "w" }) { clock.advance(); "x" }
        assertEquals(250, r.medianMillis)
    }

    @Test fun `metas cumplidas`() = runTest {
        val p = "w ".repeat(30).trim()
        val clock = FakeClock(listOf(0, 1000, 1000))
        val r = BenchmarkRunner { clock.now }.run(listOf(p, p)) { clock.advance(); "x" }
        assertTrue(r.meetsSpeedGoal)   // 60 palabras / 2 s = 30 palabras/s
        assertTrue(r.meetsLatencyGoal) // mediana 1000 ms
    }

    @Test fun `rechaza lista vacia`() = runTest {
        assertFailsWith<IllegalArgumentException> { BenchmarkRunner().run(emptyList()) { it } }
    }
}
```

Run: `./gradlew :app:testFdroidDebugUnitTest` → FAIL (referencias sin resolver).

- [ ] **Step 3: Implementación**

`BenchText.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.benchmark

/** Lee el formato de bench/sustitutos.txt y de private/textos.txt. */
object BenchText {
    private val WHITESPACE = Regex("\\s+")

    fun parse(content: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = mutableListOf<String>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#") -> Unit
                line.isEmpty() -> if (current.isNotEmpty()) {
                    paragraphs += current.joinToString(" ")
                    current.clear()
                }
                else -> current += line
            }
        }
        if (current.isNotEmpty()) paragraphs += current.joinToString(" ")
        return paragraphs
    }

    fun countWords(text: String): Int = text.trim().split(WHITESPACE).count { it.isNotEmpty() }
}
```

`BenchmarkRunner.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.benchmark

data class BenchmarkResult(
    val paragraphs: Int,
    val words: Int,
    val totalMillis: Long,
    val wordsPerSecond: Double,
    val medianMillis: Long,
    val maxMillis: Long,
) {
    val meetsSpeedGoal: Boolean get() = wordsPerSecond >= SPEED_GOAL_WPS
    val meetsLatencyGoal: Boolean get() = medianMillis < LATENCY_GOAL_MS

    companion object {
        const val SPEED_GOAL_WPS = 15.0
        const val LATENCY_GOAL_MS = 2000L
    }
}

/**
 * Mide la traducción párrafo por párrafo. El primer párrafo se traduce una vez
 * antes de medir (calentamiento) y luego se mide junto con los demás.
 */
class BenchmarkRunner(private val nanoClock: () -> Long = System::nanoTime) {
    suspend fun run(paragraphs: List<String>, translate: suspend (String) -> String): BenchmarkResult {
        require(paragraphs.isNotEmpty()) { "No hay párrafos para medir" }
        translate(paragraphs.first())
        val nanos = paragraphs.map { p ->
            val start = nanoClock()
            translate(p)
            nanoClock() - start
        }
        val millis = nanos.map { it / 1_000_000 }.sorted()
        val totalNanos = nanos.sum()
        val words = paragraphs.sumOf(BenchText::countWords)
        val median = if (millis.size % 2 == 1) millis[millis.size / 2]
        else (millis[millis.size / 2 - 1] + millis[millis.size / 2]) / 2
        return BenchmarkResult(
            paragraphs = paragraphs.size,
            words = words,
            totalMillis = totalNanos / 1_000_000,
            wordsPerSecond = if (totalNanos == 0L) 0.0 else words / (totalNanos / 1e9),
            medianMillis = median,
            maxMillis = millis.last(),
        )
    }
}
```

- [ ] **Step 4: Pasan las pruebas**

Run: `./gradlew :app:testFdroidDebugUnitTest` → 9 tests OK.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts gradle/verification-metadata.xml app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/benchmark app/src/test
git commit -m "feat(app): benchmark de traducción con calentamiento, palabras/s y mediana"
```

---

### Task 8: Pantalla de prueba del motor (agente `estructura`)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/enginetest/EngineTestViewModel.kt`
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/enginetest/EngineTestScreen.kt`
- Delete: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/HomeScreen.kt`
- Modify: `MainActivity.kt`, `app/build.gradle.kts`, `app/src/main/res/values/strings.xml`, `gradle/libs.versions.toml`

**Interfaces:**
- Consumes: `OpusEngine`, `SentenceSplitter`, `BenchText`, `BenchmarkRunner`, `EngineConfig`, `LanguagePair`.
- Produces: `class EngineTestViewModel(application: Application) : AndroidViewModel(application)` con `val state: StateFlow<EngineTestUiState>`, `fun onInputChange(text: String)`, `fun translate()`, `fun runBenchmark()`; `@Composable fun EngineTestScreen(modifier: Modifier = Modifier, viewModel: EngineTestViewModel = viewModel())`.

- [ ] **Step 1: Dependencias** (catálogo + `app/build.gradle.kts`): `androidx.lifecycle:lifecycle-viewmodel-compose` y `androidx.lifecycle:lifecycle-runtime-compose` versión `2.11.0`, fijada en `[versions] lifecycle = "2.11.0"`. Regenerar huellas.

- [ ] **Step 2: `EngineTestViewModel.kt`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchText
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchmarkResult
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchmarkRunner
import io.github.diegobr4nd.lectorbilingue.core.text.SentenceSplitter
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.opus.OpusEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModelStatus { LOADING, READY, MISSING, ERROR }
enum class BenchSource { PRIVATE, SUBSTITUTES }

data class EngineTestUiState(
    val modelStatus: ModelStatus = ModelStatus.LOADING,
    val modelPath: String = "",
    val input: String = "",
    val output: String = "",
    val lastMillis: Long? = null,
    val busy: Boolean = false,
    val benchmark: BenchmarkResult? = null,
    val benchSource: BenchSource? = null,
    val errorMessage: String? = null,
)

/** Temporal (fase 1b): prueba el motor y mide el benchmark. Nunca registra el texto. */
class EngineTestViewModel(application: Application) : AndroidViewModel(application) {
    private val pair = LanguagePair("en", "es")
    private val engine = OpusEngine(File(application.filesDir, "models"))
    private val _state = MutableStateFlow(EngineTestUiState(modelPath = engine.modelDir(pair).path))
    val state: StateFlow<EngineTestUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (!engine.isModelPresent(pair)) {
                _state.update { it.copy(modelStatus = ModelStatus.MISSING) }
                return@launch
            }
            runCatching { engine.load(pair, EngineConfig()) }
                .onSuccess { _state.update { it.copy(modelStatus = ModelStatus.READY) } }
                .onFailure { e -> _state.update { it.copy(modelStatus = ModelStatus.ERROR, errorMessage = e.message) } }
        }
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }

    fun translate() {
        val text = _state.value.input
        if (text.isBlank() || _state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null) }
        viewModelScope.launch {
            val start = System.nanoTime()
            runCatching { translateParagraph(text) }
                .onSuccess { out ->
                    val ms = (System.nanoTime() - start) / 1_000_000
                    _state.update { it.copy(output = out, lastMillis = ms, busy = false) }
                }
                .onFailure { e -> _state.update { it.copy(errorMessage = e.message, busy = false) } }
        }
    }

    fun runBenchmark() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null, benchmark = null) }
        viewModelScope.launch {
            runCatching {
                val (source, paragraphs) = loadBenchParagraphs()
                source to BenchmarkRunner().run(paragraphs, ::translateParagraph)
            }.onSuccess { (source, result) ->
                _state.update { it.copy(benchmark = result, benchSource = source, busy = false) }
            }.onFailure { e -> _state.update { it.copy(errorMessage = e.message, busy = false) } }
        }
    }

    private suspend fun translateParagraph(paragraph: String): String =
        engine.translate(SentenceSplitter.split(paragraph)).joinToString(" ")

    private suspend fun loadBenchParagraphs(): Pair<BenchSource, List<String>> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val private = File(app.filesDir, "bench/textos.txt")
        if (private.isFile) {
            BenchSource.PRIVATE to BenchText.parse(private.readText(Charsets.UTF_8))
        } else {
            BenchSource.SUBSTITUTES to BenchText.parse(
                app.assets.open("sustitutos.txt").bufferedReader(Charsets.UTF_8).use { it.readText() },
            )
        }
    }

    override fun onCleared() {
        engine.unload()
    }
}
```

- [ ] **Step 3: `EngineTestScreen.kt`** — Material 3, dentro de `Scaffold` con `innerPadding`, columna desplazable con margen horizontal de 16 dp:
  - Tarjeta de estado del modelo: `LOADING` → indicador + "Cargando modelo…"; `READY` → "Modelo en-es listo"; `MISSING` → "Modelo no encontrado en {ruta}" + "Cópialo con los comandos de docs/build.md"; `ERROR` → mensaje.
  - `OutlinedTextField` multilínea (mín. 4 líneas) con etiqueta "Texto en inglés".
  - Botón "Traducir" (deshabilitado si `busy`, modelo no `READY` o texto vacío).
  - Resultado (seleccionable con `SelectionContainer`) y "{ms} ms".
  - Botón "Benchmark" (deshabilitado si `busy` o modelo no `READY`) e indicador de progreso mientras `busy`.
  - Resultado del benchmark: fuente ("Textos privados" / "Textos sustitutos"), párrafos, palabras, palabras/s con 1 decimal y ✅/❌ frente a 15, mediana ms y ✅/❌ frente a 2000, máximo ms.
  - Error en `MaterialTheme.colorScheme.error`.
  - Todas las cadenas en `strings.xml` (español). `@Preview` del contenido con un estado de ejemplo (sin ViewModel: separar `EngineTestContent(state, onInputChange, onTranslate, onBenchmark)` sin estado, y `EngineTestScreen` que lo conecta con `collectAsStateWithLifecycle()`).

- [ ] **Step 4: Conectar** — `MainActivity` muestra `EngineTestScreen()` dentro de `LectorBilingueTheme`; borrar `HomeScreen.kt`. En `app/build.gradle.kts` → `android { sourceSets { getByName("main") { assets.srcDir(rootProject.file("bench")) } } }` para empaquetar `sustitutos.txt` como asset.

- [ ] **Step 5: Verificar**

Run: `./gradlew lint test assembleFdroidDebug` → BUILD SUCCESSFUL, sin errores de lint.
Run: `unzip -l app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk | grep -E "assets/sustitutos.txt|lib/arm64-v8a/libct2bridge.so"` → ambas líneas.

- [ ] **Step 6: Commit**

```bash
git add app gradle/libs.versions.toml gradle/verification-metadata.xml
git commit -m "feat(app): pantalla temporal para probar el motor y correr el benchmark"
```

---

### Task 9: CI con nativo, pruebas Python y documentación (agente `infraestructura`)

**Files:**
- Modify: `.github/workflows/ci.yml`, `docs/build.md`
- Create: `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/OpusOnDeviceTest.kt`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`, `build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`

**Interfaces:**
- Consumes: `tools/native/init-submodules.sh`, `ProjectConfig.NDK_VERSION/CMAKE_VERSION`, `Ct2NativeBridge.utf8RoundTrip`, `OpusEngine`.
- Produces: job de CI que compila el nativo; APK de prueba instrumentada `app-fdroid-debug-androidTest.apk`.

- [ ] **Step 1: `ci.yml`** — después de `setup-gradle` y antes de "Lint, pruebas y APKs":

```yaml
      - name: Submódulos del motor (sin CUDA)
        run: tools/native/init-submodules.sh

      - name: NDK y CMake fijados
        run: |
          yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
          "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;30.0.16248370" "cmake;4.1.2"

      - uses: actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0
        with:
          python-version: "3.12"

      - name: Pruebas de las herramientas Python
        run: |
          (cd tools/bench && python -m unittest -v test_html_to_txt)
          (cd tools/models && python -m unittest -v test_convert_opus)
```
y después del chequeo de permisos:
```yaml
      - name: El APK release trae el motor arm64
        run: unzip -l app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk | grep -q "lib/arm64-v8a/libct2bridge.so"
```
`timeout-minutes: 60`. (Las pruebas de `test_convert_opus` no importan las librerías pesadas.)

- [ ] **Step 2: Prueba instrumentada** — dependencias `androidx.test:runner:1.7.0` y `androidx.test.ext:junit:1.3.0` en el catálogo, `androidTestImplementation` en `:app`, `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` en `AndroidApplicationConventionPlugin` (`defaultConfig`). Regenerar huellas.

`OpusOnDeviceTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.opus.Ct2NativeBridge
import io.github.diegobr4nd.lectorbilingue.engine.opus.OpusEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Corre en el Pixel con el modelo copiado (docs/build.md). Sin modelo, se salta. */
@RunWith(AndroidJUnit4::class)
class OpusOnDeviceTest {
    private val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val pair = LanguagePair("en", "es")

    @Test fun utf8IdaYVuelta() {
        val text = "Mañana 😀 café — “quote” 𝄞 ñ"
        assertEquals(text, Ct2NativeBridge.utf8RoundTrip(text))
    }

    @Test fun sustitutoSueltoNoRompe() {
        assertEquals("a�b", Ct2NativeBridge.utf8RoundTrip("a\uD800b"))
    }

    @Test fun traduceHolaMundo() = runBlocking {
        val engine = OpusEngine(File(filesDir, "models"))
        assumeTrue("modelo no copiado", engine.isModelPresent(pair))
        engine.load(pair, EngineConfig())
        try {
            val out = engine.translate(listOf("Hello, world.", "The cat is on the table."))
            assertEquals(2, out.size)
            assertTrue(out.all { it.isNotBlank() })
            assertTrue(out[1].contains("gato", ignoreCase = true))
        } finally {
            engine.unload()
        }
    }

    @Test fun entradasRarasNoRompen() = runBlocking {
        val engine = OpusEngine(File(filesDir, "models"))
        assumeTrue("modelo no copiado", engine.isModelPresent(pair))
        engine.load(pair, EngineConfig())
        try {
            assertEquals(1, engine.translate(listOf("")).size)
            assertEquals(1, engine.translate(listOf("😀😀😀")).size)
            assertEquals(1, engine.translate(listOf("x".repeat(1000))).size)
            assertFailsWith<IllegalArgumentException> { engine.translate(listOf("x".repeat(1001))) }
        } finally {
            engine.unload()
        }
    }

    @Test fun puenteRechazaHandleInvalidoYLoteGrande() {
        assertFailsWith<IllegalStateException> { Ct2NativeBridge.translate(12345L, arrayOf("Hi.")) }
        assertFailsWith<IllegalArgumentException> { Ct2NativeBridge.translate(12345L, Array(65) { "Hi." }) }
        Ct2NativeBridge.unload(12345L) // no-op, sin crash
    }
}
```
`utf8RoundTrip` es `internal` en `:engine:opus`; para usarlo desde `:app` androidTest cambiarlo a `@VisibleForTesting fun` público (anotación de `androidx.annotation`, ya transitiva) y anotarlo en el reporte.

Run: `./gradlew assembleFdroidDebugAndroidTest` → BUILD SUCCESSFUL (correr en el Pixel es la Tarea 11).

- [ ] **Step 3: `docs/build.md`** — agregar secciones:
  1. **Instalar NDK y CMake (una vez):** el comando `sdkmanager` de la Tarea 3 (PowerShell y Git Bash).
  2. **Clonar con submódulos:** `git clone …` + `tools/native/init-submodules.sh` (y por qué no `--recursive`).
  3. **Obtener el modelo:** pestaña *Actions* → *model* → *Run workflow* (o el run del PR) → descargar `modelo-en-es` → descomprimir el `.zip` → `tar --zstd -xf en-es.tar.zst` (en Windows: `tar -xf` de Git Bash con zstd, o 7-Zip) → verificar `sha256sum -c SHA256SUMS` dentro de `en-es/`.
  4. **Copiar al teléfono:** los comandos de la spec §6.2 (modelo y textos privados).
  5. **Textos privados:** `python tools/bench/html_to_txt.py textos_para_firefox.html private/textos.txt`.
  6. **Prueba en el teléfono:** `./gradlew installFdroidDebug installFdroidDebugAndroidTest` y `adb shell am instrument -w io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner` (no usar `connectedAndroidTest`: desinstala la app y borra el modelo copiado).
  7. Tabla de versiones: NDK 30.0.16248370, CMake 4.1.2, CTranslate2 v4.8.2, SentencePiece v0.2.2, modelo revisión `8f4d492…`.

- [ ] **Step 4: Verificar todo**

Run: `./gradlew lint test assembleFdroidDebug assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest` → BUILD SUCCESSFUL. Correr localmente los dos `python -m unittest` del Step 1.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml docs/build.md app gradle build-logic engine/opus
git commit -m "ci: compilar el motor nativo, probar herramientas Python y prueba instrumentada"
```

---

### Task 10: Revisiones de seguridad y diseño (agentes `seguridad` y `diseno`, solo lectura)

- [ ] **Step 1: `seguridad`** revisa con la sección 5.2 de `docs/agentes/03-seguridad.md` y la skill `c-review` si aplica:
  - `native/ct2bridge/ct2bridge.cpp`: validación de entradas, conversión UTF-8/16, excepciones en el borde JNI, registro de handles, fugas de referencias locales, mensajes sin texto, ausencia de logs.
  - `native/ct2bridge/CMakeLists.txt`, `tools/native/init-submodules.sh`, `.gitmodules` (commits fijados).
  - `tools/models/convert_opus.py`, `requirements.txt` (huellas, `+cpu`), `model.yml` (permisos, acciones fijadas, artefacto).
  - `.gitignore` y que ningún archivo del diff contenga texto de `private/`.
  - `OpusEngine` (límites antes de cruzar JNI) y `consumer-rules.pro`.
  - Nuevas dependencias en `verification-metadata.xml`.
- [ ] **Step 2: `diseno`** revisa `EngineTestScreen.kt` y `strings.xml` (pantalla temporal: legibilidad, estados, *insets*, accesibilidad básica).
- [ ] **Step 3:** críticos/altos → arreglo por el agente dueño + re-revisión. Medios/bajos baratos y ligados a reglas de `CLAUDE.md` → un lote; el resto, lista para Juan.

---

### Task 11: Puerta 1b con Juan (sesión principal)

- [ ] **Step 1:** `./gradlew lint test assembleFdroidDebug` → mostrar salida.
- [ ] **Step 2:** Pedir permiso a Juan y `git push -u origin feat/motor-opus`; Juan abre el PR. Esperar `ci` y `model` en verde (`model` corre por el filtro de rutas).
- [ ] **Step 3:** Juan descarga el artefacto `modelo-en-es` (enlace exacto), lo descomprime y verifica `SHA256SUMS` (comandos de `docs/build.md`).
- [ ] **Step 4:** Copiar al Pixel modelo y `private/textos.txt` (comandos de la spec §6.2; el controlador puede correrlos con el permiso de Juan).
- [ ] **Step 5:** `./gradlew installFdroidDebug installFdroidDebugAndroidTest` + `adb shell am instrument -w …` → todas las pruebas pasan (ninguna saltada).
- [ ] **Step 6:** Juan activa **modo avión**, abre la app: "Modelo en-es listo"; pega un párrafo → anota ms; toca **Benchmark** → fuente "Textos privados", palabras/s ≥ 15 ✅ y mediana < 2000 ms ✅. Captura (`adb exec-out screencap -p`).
- [ ] **Step 7:** Si no se cumple la meta: reportar datos (palabras/s, mediana, máximo) y proponer ajustes de la spec §10 (int8 vs int8_float32, hilos) como nueva tarea; no cerrar la puerta.
- [ ] **Step 8:** Revisión final de la rama; Juan fusiona; actualizar "Fase actual" en `CLAUDE.md` en una rama `docs/`.
