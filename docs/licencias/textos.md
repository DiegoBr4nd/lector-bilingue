# De dónde sale cada texto de la pantalla «Licencias»

Carpeta: `app/src/main/assets/licencias/` (las OFL de nuestras fuentes están en `core/ui/src/main/assets/lector/fuentes/`,
ver `fuentes.md`). Todos los archivos se guardan con fin de línea LF. Bajados el 2026-10-17.

## `textos/`: el texto de cada licencia
Los de SPDX vienen de `https://raw.githubusercontent.com/spdx/license-list-data/v3.29.0/text/<id>.txt` (misma etiqueta
para todos).

| Archivo | Origen |
|---|---|
| `Apache-2.0.txt` | SPDX v3.29.0 `Apache-2.0` |
| `MIT.txt` | SPDX v3.29.0 `MIT` |
| `BSD-2-Clause.txt` | SPDX v3.29.0 `BSD-2-Clause` |
| `BSD-3-Clause.txt` | SPDX v3.29.0 `BSD-3-Clause` |
| `Bitstream-Vera.txt` | SPDX v3.29.0 `Bitstream-Vera` (OpenDyslexic) |
| `CC-BY-3.0.txt` | SPDX v3.29.0 `CC-BY-3.0` (OpenDyslexic) |
| `MPL-2.0.txt` | copia de `tools/models/licenses/MPL-2.0.txt` (igual al de SPDX salvo un espacio al final de una línea) |
| `GPL-3.0-or-later.txt` | copia exacta de `LICENSE` (raíz del repo); la prueba lo compara |
| `GPL-2.0-with-classpath-exception.txt` | `LICENSE` de google/desugar_jdk_libs, commit `af7193bb1f81c050395c7273ee0354630a295d2f` (último cambio de ese archivo). No se usó SPDX: su texto de esa licencia (obsoleta) es solo la excepción, con «insert GPL v2 license text here» en lugar de la GPL |
| `35191b70082118c99b05fb11c25b7a6d.txt` | clave (hash) que AboutLibraries da a la licencia de Readium («BSD-3-Clause license»): `LICENSE` de readium/kotlin-toolkit en la etiqueta `3.4.0` |
| `73252b46f36df25ef51a7994de439aea.txt` | clave (hash) de «Bouncy Castle Licence»: `META-INF/LICENSE.md` dentro de `bcprov-jdk18on-1.86.jar` (el mismo archivo que va en el APK) |

## `avisos/`: el copyright propio de cada componente
Código nativo: el `LICENSE` del submódulo **tal cual** (solo se cambió CRLF por LF).

| Archivo | Origen |
|---|---|
| `ctranslate2.txt` | `native/third_party/CTranslate2/LICENSE` (v4.8.2) |
| `ruy.txt` | `native/third_party/CTranslate2/third_party/ruy/LICENSE` (idéntico al de `slimt/3rd-party/ruy`) |
| `cpuinfo.txt` | `.../ruy/third_party/cpuinfo/LICENSE` + línea en blanco + `.../cpuinfo/deps/clog/LICENSE` (clog se enlaza con cpuinfo) |
| `spdlog.txt` | `native/third_party/CTranslate2/third_party/spdlog/LICENSE` |
| `bs-thread-pool.txt` | `LICENSE.txt` de bshoshany/thread-pool en la etiqueta `v5.1.0` (la versión de `CTranslate2/third_party/BS_thread_pool.hpp`, que no trae archivo de licencia aparte) |
| `neon-mathfun.txt` | comentario de cabecera (líneas 1–26) de `native/third_party/CTranslate2/third_party/neon_mathfun.h` (licencia zlib) |
| `abseil.txt` | `native/third_party/abseil-cpp/LICENSE` (la copia de Abseil dentro del SentencePiece de slimt solo cambia `http` por `https`) |
| `sentencepiece.txt` | `native/third_party/sentencepiece/LICENSE` (idéntico al del fork de slimt) |
| `protobuf-lite.txt` | `native/third_party/sentencepiece/third_party/protobuf-lite/LICENSE` (idéntico al del fork) |
| `darts-clone.txt` | `native/third_party/sentencepiece/third_party/darts_clone/LICENSE` (idéntico al del fork) |
| `slimt.txt` | `native/third_party/slimt/COPYRIGHT` + línea en blanco + `native/third_party/slimt/LICENSE` |
| `xhscanner.txt` | cabecera (líneas 1–2) de `native/third_party/slimt/slimt/XHScanner.cc`: dice «BSD license» y el enlace al artículo; no trae más texto |
| `material-symbols.txt` | antes `core/ui/ICONOS-LICENSE.txt` (Apache-2.0 de google/material-design-icons) |

ssplit-cpp y bergamot-translator/marian-dev no tienen archivo propio: su aviso está en el `COPYRIGHT` de slimt, y su
fila muestra `textos/Apache-2.0.txt` y `textos/MPL-2.0.txt`.

Fuentes que trae Readium dentro de `readium-navigator-3.4.0.aar` (SHA-256 del AAR
`74b25a7cfe7c456272d5dfce2d56ba1ccea0fe8ee9115afe2c9241ff2232b9b1`, el mismo de `verification-metadata.xml`):

| Archivo | Origen |
|---|---|
| `ia-writer-duospace.txt` | `assets/readium/readium-css/fonts/LICENSE-IaWriterDuospace.md` del AAR (iA Writer Duospace deriva de IBM Plex Mono: OFL-1.1 de la fuente + Apache-2.0 del código de IBM) |
| `accessible-dfa.txt` | `assets/readium/readium-css/fonts/LICENSE-AccessibleDfa` del AAR (OFL-1.1, Orange) |
| `opendyslexic.txt` | el AAR no trae licencia para `assets/readium/fonts/OpenDyslexic-Regular.otf` (versión 2.001): el aviso es el campo Copyright (tabla `name`, id 0) de la propia fuente, copiado tal cual; dice Bitstream Vera + CC-BY-3.0, por eso su fila muestra además esos dos textos de SPDX |

Librerías de Gradle con licencia MIT o BSD (`avisos/<uniqueId con ':' cambiado por '_'>.txt`):

| Archivo | Origen |
|---|---|
| `org.jsoup_jsoup.txt` | `META-INF/jsoup/LICENSE` dentro de `jsoup-1.23.2.jar` (el mismo que va en el APK) |
| `org.readium.kotlin-toolkit_readium-{navigator,shared,streamer}.txt` | `LICENSE` de readium/kotlin-toolkit en la etiqueta `3.4.0` (igual a `textos/35191b…txt`; la pantalla lo muestra una sola vez) |

## Componentes revisados que NO van en la lista
- **cpu_features** (`CTranslate2/third_party/cpu_features`): solo se compila en x86_64 (`CTranslate2/CMakeLists.txt`,
  bloque `elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "(x86_64)...")`); el APK solo trae arm64.
- **esaxx** (en los dos SentencePiece): solo lo usa el entrenador (`unigram_model_trainer.cc`), que no se compila
  (`sentencepiece-static` usa `SPM_SRCS`, sin los archivos del entrenador).
- **PCRE2** y `Splitter.cc`/`Regex.cc` de slimt: no se compilan (los reemplaza `native/slimtbridge/compat/SentenceStreamNoSplit.cc`).
  ssplit sí se queda en la lista: `Splitter.hh` (derivado de ssplit) se sigue compilando.
- intgemm, gemmology, cutlass, thrust, cxxopts, CLI11, googletest: no se compilan para el APK.
