# Prueba rápida de slimt en el Pixel 7 (Fase 2c, Tarea 1)

Fecha: 2026-10-03. Agente: infraestructura. Código desechable en la rama local `spike/slimt` (commit `ebf6b6c`, carpeta `native/spike-slimt/`), nunca subida.

## Veredicto: **GO** (con 2 parches de una línea cada uno)

| Criterio | Resultado |
|---|---|
| Compila para `arm64-v8a` (NDK 30.0.16248370, CMake 4.1.2, API 26) | Sí, con el parche 0001 |
| Carga los modelos actuales de Mozilla sin parches grandes | Sí. Carga sin parches; la lista corta necesita el parche 0002 (1 línea) para dar traducciones correctas |
| ≥ 15 palabras/s | Sí: 68 a 112 palabras/s (peor corrida: 68) |
| Mediana < 2000 ms por párrafo | Sí: 183 a 314 ms |
| Oraciones propias sensatas | Sí (5 en→es, 3 es→en; ver abajo) |

Términos:
- **slimt**: motor C++ pequeño que ejecuta los modelos de traducción de Firefox (formato Marian). Como CTranslate2, pero para estos modelos.
- **Lista corta (shortlist, `lex.*.bin`)**: lista de palabras de destino probables por palabra de origen. El motor solo puntúa esas, y no las 32 000 del vocabulario. Por eso va 2 veces más rápido.
- **ruy**: biblioteca de Google para multiplicar matrices de enteros de 8 bits en ARM.

## 1. Fuentes fijadas (commits exactos)

| Componente | Origen | Commit / versión | Licencia |
|---|---|---|---|
| slimt | https://github.com/jerinphilip/slimt | `9f0b1a20d14871cc94dbe65b7a3df128e5e81f55` (2024-04-11, último de `main`) | GPL-2.0-or-later (`COPYRIGHT`: "either version 2 ... or any later version") |
| sentencepiece (fork browsermt, submódulo de slimt) | https://github.com/browsermt/sentencepiece | `8cbdf13794284c30877936f91c6f31e2c1d5aef7` | Apache-2.0 |
| ruy (submódulo de slimt) | https://github.com/google/ruy | `c04e5e52ae6b144f74ac032652e3c538bda15c9b` | Apache-2.0 |
| cpuinfo (submódulo de ruy) | https://github.com/pytorch/cpuinfo | `082deffc80ce517f81dc2f3aebe6ba671fcd09c9` | BSD-2-Clause |
| PCRE2 (lo exige `find_package(PCRE2 REQUIRED)` de slimt) | https://github.com/PCRE2Project/pcre2 | tag `pcre2-10.44` = `6ae58beca071f13ccfed31d03b3f479ab520639b` | BSD-3-Clause (con excepción de la biblioteca) |

No se usan los submódulos `intgemm` (solo x86), `gemmology` (necesita xsimd) ni `ruy/third_party/googletest`.

Para la Tarea 5 basta: `git submodule update --init 3rd-party/sentencepiece 3rd-party/ruy` y `git -C 3rd-party/ruy submodule update --init third_party/cpuinfo`.

## 2. Opciones de CMake que funcionaron

Toolchain del NDK (`build/cmake/android.toolchain.cmake`), generador Ninja del SDK.

```
-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static
-DCMAKE_BUILD_TYPE=Release
-DCMAKE_POLICY_VERSION_MINIMUM=3.5      # CMake 4 rechaza el cmake_minimum_required(3.1) de sentencepiece
-DWITH_RUY=ON -DWITH_GEMMOLOGY=OFF -DWITH_BLAS=OFF -DWITH_INTGEMM=OFF
-DUSE_NEON=ON                           # NEON es obligatorio en aarch64; activa las rutas vectoriales de slimt
-DBUILD_SHARED=OFF -DBUILD_STATIC=ON
-DPCRE2_LIBRARIES=<pcre2>/lib/libpcre2-8.a -DPCRE2_INCLUDE_DIR=<pcre2>/include
-DCMAKE_CXX_FLAGS=-DPCRE2_STATIC
```

PCRE2 se compiló antes, aparte, con el mismo toolchain:
`-DBUILD_SHARED_LIBS=OFF -DPCRE2_BUILD_PCRE2GREP=OFF -DPCRE2_BUILD_TESTS=OFF -DPCRE2_SUPPORT_JIT=ON -DCMAKE_POSITION_INDEPENDENT_CODE=ON`.

- Tiempos en la laptop: PCRE2 15 s; slimt + sentencepiece + ruy unos 15 s (`-j 8`).
- slimt compila con `-Werror`; con el clang del NDK 30 no salió ningún aviso.
- Binario de prueba (`slimt-bench`, enlazado estático, sin símbolos): 1,7 MB.

## 3. Parches necesarios

| Parche | Qué arregla | Origen | Licencia |
|---|---|---|---|
| `0001-posix-memalign.patch` (`slimt/Aligned.cc`, +5 −1) | `aligned_alloc` no existe en Bionic antes de la API 28; nuestro mínimo es 26. Se cambia por `posix_memalign` | Escrito aquí. Es el mismo cambio que el commit `b4836a2` del fork de David Ventura (https://github.com/DavidVentura/slimt), el que usa Offline Translator | GPL-2.0-or-later (obra derivada de slimt) |
| `0002-ruy-selected-bias.patch` (`slimt/qmm/Ruy.inl.cc`, 1 línea) | **Error real de slimt en ARM**: `affine_with_select` (la proyección de salida con lista corta) calcula `selected_bias` pero suma `prepared_bias` (el sesgo del vocabulario completo). Con lista corta, las traducciones salen rotas (palabras repetidas, sin traducir). Sin lista corta salían bien; así se encontró la causa | Escrito aquí. El fork lo corrige igual en el commit `ea2034a` ("use selected_bias on ruy") | GPL-2.0-or-later |
| `0003-bench-target.patch` | Solo para la prueba: agrega el ejecutable `slimt-bench` | Nuestro | — |

Ninguno de los 2 parches reales es grande.

El fork de David Ventura (`DavidVentura/slimt`, commit fijado en `slimt-sys`: `2beb403abfff5b2f4c165a6d51362c973d037e97`) tiene 65 commits sobre `main` de upstream. Entre ellos hay mejoras de rendimiento, un "beam" por lotes, que la traducción no dependa del lote, varios vocabularios y que ya no use PCRE2. Upstream no tiene cambios desde abril de 2024. Los envoltorios Rust (`slimt-sys`) son MIT, pero el fork en C++ sigue siendo GPL-2.0-or-later.

## 4. Modelos oficiales de Mozilla (`en-es`, `es-en`)

**Fuente:**
- Remote Settings de Firefox, colección `translations-models-v2` (la que usa Firefox hoy):
  - registros: `https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models-v2/records`
  - adjuntos: `https://firefox-settings-attachments.cdn.mozilla.net/` + `attachment.location`
- Versión **3.0**, arquitectura **`base-memory`**:
  - encoder de 6 capas y decoder SSRU de 4 capas;
  - `dim-emb` 384, FFN 1536, 8 cabezas;
  - vocabulario compartido de 32 000;
  - Marian v1.12.14.
- Licencia de los modelos: MPL-2.0 (archivo `LICENSE` de `mozilla/firefox-translations-models` y de `mozilla/translations`).

**Huellas:**
- Mozilla **sí publica SHA-256** en cada registro:
  - `attachment.hash` es la del `.zst` comprimido;
  - `decompressedHash` es la del archivo descomprimido.
- Todas se verificaron con `sha256sum -c`.

| Par | Archivo (nombre en el registro) | `.zst` hash (descarga) | `decompressedHash` | Tamaño descomprimido |
|---|---|---|---|---|
| en-es | `model.enes.intgemm.alphas.bin` | `ce7ba731d3352d7e595d96ed4fac513744568eaf9a271daf90b5fa8fa33b195d` | `3b1c399511c01c84c36fae5c0524df44096288efdc8236e182b5c97d7ad2244c` | 31 561 787 |
| en-es | `vocab.enes.spm` | `76b9ef220b22cec379f097852ca6312cabf065d5ddfb05149d5af31aa050ec22` | `5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad` | 816 054 |
| en-es | `lex.50.50.enes.s2t.bin` | `0dd2945dc8a5bbccad6fb1e1dfce137d363de839f4492cab717f60f1f38c09e6` | `7d51237c0a07027dcd61643cfbbb0f8c48597d19907ef53d2cae9d6bec2cf25c` | 4 198 436 |
| es-en | `model.esen.intgemm.alphas.bin` | `7d1a512f3d3c420a0d87e52dccf6c83bf7eba7f4c54608824d3f86b757993a3e` | `4aed7734152ae0045d1a69ae49c86cfda18f53c61f90e95e1d1de1c7c7c3b033` | 31 561 787 |
| es-en | `vocab.esen.spm` | `76b9ef220b22cec379f097852ca6312cabf065d5ddfb05149d5af31aa050ec22` | `5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad` | 816 054 |
| es-en | `lex.50.50.esen.s2t.bin` | `5c3aae52d8fddeb2e09098171cdecf059ea453474633edcebc77375b04cbcaee` | `e2610211d3b9577d012638fe7e7e74ed7b4b708ce96b9e792e67c282a6492daa` | 4 636 248 |

`location` de cada adjunto (prefijo `main-workspace/translations-models-v2/`):

| Archivo | `location` |
|---|---|
| model enes | `1d705201-9be0-40c4-a0b4-18d1e3973777.zst` |
| vocab enes | `b2b5907b-8759-4cc8-a721-89c283e6e45b.zst` |
| lex enes | `51318160-1249-451f-80fb-12e61f8c1def.zst` |
| model esen | `fccccf6a-6d29-407d-82a5-0cf73b23d892.zst` |
| vocab esen | `2239f64b-dfc4-41c1-9e06-d532dce7b246.zst` |
| lex esen | `64f16997-3cf3-4cc8-a069-b143760b1173.zst` |

**Notas para la Tarea 5 (flujo `firefox-model.yml`):**
- En la colección v2 los archivos vienen **comprimidos con zstd**: el flujo necesita `zstd -d`. Conviene verificar las 2 huellas: la del `.zst` y la del archivo descomprimido.
- La colección vieja `translations-models` (sin `-v2`) tiene los mismos bytes sin comprimir (versión "2.1" para en-es y "2.0" para es-en; los `hash` coinciden con los `decompressedHash` de arriba).
  - Para la prueba se usaron esos archivos. Su huella coincide con la de la v2 descomprimida.
  - Firefox ya usa la v2, así que la vieja puede desaparecer. Mejor usar la v2.
- Vocabulario: `en-es` y `es-en` usan **el mismo archivo** `.spm` (la misma huella).
- Elegir los registros filtrando por `sourceLanguage`, `targetLanguage`, `version` y `fileType` (`model`, `vocab`, `lex`).

## 5. API C++ de slimt usada

```cpp
#include "slimt/Frontend.hh"   // slimt::Config, slimt::Blocking, slimt::Async
#include "slimt/Model.hh"      // slimt::Model, slimt::Package
#include "slimt/Response.hh"   // slimt::Options, slimt::Response

// Cargar (lee y prepara los pesos; mmap de los archivos)
slimt::Model::Config mc;          // encoder_layers=6, decoder_layers, feed_forward_depth=2, num_heads=8
mc.decoder_layers = 4;            // base-memory = 4 (tiny = 2): slimt NO lo lee del modelo
slimt::Package<std::string> pkg{.model = m, .vocabulary = v, .shortlist = lex, .ssplit = ""};
auto model = std::make_shared<slimt::Model>(mc, pkg);
// Variante desde memoria: slimt::Model(const Config&, const Package<slimt::View>&)

// Traducir (bloqueante, en el hilo que llama)
slimt::Config sc; sc.cache_size = 0;      // caché interna opcional
slimt::Blocking service(sc);
std::vector<slimt::Response> r =
    service.translate(model, std::vector<std::string>{...}, slimt::Options{.alignment=false, .html=false});
r[i].target.text;   // traducción

// Liberar: destruir el shared_ptr<Model> y el Blocking (RAII; no hay función free).
```

- Al cargar escribe en stderr 2 avisos sin consecuencia: `Failed to ingest expected load of Wemb_QuantMultA` y `... special:model.yml`.
- **Riesgo:** slimt **no lee** la configuración que trae el modelo (`special:model.yml`). Si `decoder_layers` no coincide, el resultado es incorrecto en silencio.
  - Propuesta para la Tarea 5: leer `dec-depth` del YAML que trae el `.bin`, o guardarlo en el catálogo.
  - Los modelos actuales `en-es` y `es-en` tienen `dec-depth: 4`.
- slimt también parte el texto en oraciones (usa PCRE2). Nosotros le pasamos oraciones ya partidas por `:core:text`, así que ese divisor no hace falta.

## 6. Hilos y beam

- **Beam:** slimt upstream solo hace decodificación voraz (greedy), es decir, **beam 1**. No hay opción de beam. Coincide con nuestra meta (beam 1).
- **Hilos dentro de una oración:** no hay. Cada multiplicación crea un `ruy::Context` propio con 1 hilo.
- **Hilos entre oraciones:**
  - `slimt::Async(Config{.workers = N})` reparte lotes entre N hilos.
  - En la prueba se usaron N objetos `Blocking`, uno por hilo, que comparten un solo `Model` y reparten las oraciones del párrafo. Funcionó sin errores en 3 corridas.
  - Mejora poco: los párrafos tienen pocas oraciones (mediana de 2, máximo de 6).

## 7. Números medidos (Pixel 7, `adb shell`, binario en `/data/local/tmp/slimt/`)

**Método:**
- Los 25 párrafos privados (587 palabras, 37 oraciones) se partieron con un port a Python de `BenchText.parse` + `SentenceSplitter.split`.
- Cada párrafo se traduce como una llamada (sus oraciones juntas).
- Se traduce primero el párrafo 1 para calentar, como hace `BenchmarkRunner`. La caché de slimt está apagada.
- Palabras/s = palabras de origen / suma de los tiempos por párrafo.
- RAM = `VmHWM` de `/proc/self/status` al terminar.
- Se hicieron 3 rondas seguidas, en este orden: con lista y 1 hilo, con lista y 4 hilos, sin lista y 1 hilo. Las rondas finales salen más lentas, probablemente porque el teléfono se calienta. Se dan los rangos.

| Configuración | Palabras/s | Mediana por párrafo | Peor párrafo | RAM pico (VmHWM) |
|---|---|---|---|---|
| Con lista corta, **1 hilo** | **68,1 – 108,2** | **186 – 314 ms** | 783 – 1250 ms | 123,6 – 125,8 MiB |
| Con lista corta, **4 hilos** | **69,4 – 112,0** | **183 – 298 ms** | 559 – 993 ms | 126,1 – 126,9 MiB |
| Sin lista corta, 1 hilo | 40,0 – 49,3 | 454 – 531 ms | 1301 – 1623 ms | 133,7 – 134,3 MiB |
| *Referencia OPUS (CTranslate2 int8, beam 1, 4 hilos)* | *21,4* | *965 ms* | — | — |

- El peor párrafo es siempre el más largo (55 palabras, 2 oraciones).
- Carga del modelo: 72 a 126 ms con los archivos ya en la memoria del sistema.
  - Unos 3 s (3024 y 3242 ms) la primera vez que se lee el archivo justo después de copiarlo.
  - En la app, la primera carga en frío tomará unos 3 s.
- Recomendación para la Tarea 5: **1 hilo por defecto**. Con 4 hilos la mejora casi no se ve y el consumo de energía es mayor. Se puede revisar más adelante con traducción de libros completos (lotes grandes).

**Calidad (sin mostrar textos):**
- Las salidas tienen 37 de 37 oraciones.
- Ninguna tiene palabras repetidas en bucle (prueba con regex).
- Proporción de largo destino/origen entre 0,50 y 1,75.
- Con lista corta, 23 a 25 de 37 oraciones son idénticas a la versión sin lista.
- Entre 1 y 4 hilos, 32 de 37 oraciones son idénticas: la lista corta se arma con la unión de las palabras del lote, así que el resultado depende de qué oraciones van juntas. El fork lo corrigió (commit `de4bbbf`).
- Las traducciones quedaron solo en `private/firefox-2c/` (ignorado por git) para que Juan las compare con OPUS.

**Oraciones propias:**
- en→es, con lista corta y parche 0002: las 5 son correctas y naturales. Por ejemplo, "The old library was quiet and the rain kept falling all afternoon." → "La antigua biblioteca estaba tranquila y la lluvia seguía cayendo toda la tarde."
  - Antes del parche 0002 salían rotas, por ejemplo "...antes before mudarse before another city before moves...".
- es→en: 3 de 3 son sensatas. En una se omitió una palabra: "nos perdimos el comienzo del concierto" → "we missed the concert".

## 8. Riesgos y pendientes para la Tarea 5

1. **`decoder_layers` fijo en código.** Hay que leerlo del modelo o del catálogo (ver §5).
2. **Resultado según el lote.** La lista corta se calcula por lote. Opciones:
   - traducir con lotes estables (un párrafo por llamada, como en la prueba);
   - o portar el commit `de4bbbf` del fork.
3. **Ruta absoluta dentro del binario.** El binario sin símbolos contiene 1 cadena con la ruta de la laptop (`__FILE__` en los mensajes de error). En el módulo real hay que usar `-ffile-prefix-map=<repo>=.`, como en `ct2bridge`.
4. **PCRE2.** Es una dependencia más (BSD-3, libre) que solo usa el divisor de oraciones de slimt, y nosotros no lo necesitamos.
   - Si se queda: compilarla con `add_subdirectory` desde un submódulo fijado.
   - Si no: quitar `Splitter`/`Regex`, como hizo el fork. Esto ya es un parche mediano.
5. **Upstream sin mantenimiento** desde 2024-04. El fork está activo, pero su diferencia con upstream es grande.
   - Propuesta: usar upstream fijado + nuestros 2 parches mínimos.
   - Mirar el fork si aparecen otros fallos.
6. **`-Werror` de slimt.** Hoy compila sin avisos. Un NDK futuro podría romperlo. Se puede agregar `-Wno-error` en nuestro CMake.
7. Con `CMAKE_BUILD_TYPE=Release` el toolchain del NDK agrega `-g`. El binario final hay que limpiarlo de símbolos (`strip`); AGP ya lo hace con las `.so`.
8. **Calidad frente a OPUS.** El modelo Firefox (`base-memory`, 31 MB) es mucho más pequeño que OPUS tc-big. La velocidad sobra, pero Juan debe comparar la calidad con los archivos de `private/firefox-2c/`.

## 9. Higiene

- Teléfono: se usó solo `/data/local/tmp/slimt/`, borrado al final (`adb shell rm -r /data/local/tmp/slimt`; comprobado que ya no existe). No se tocó la app ni sus datos.
- Ningún texto privado ni sus traducciones están en este informe, en git ni en la conversación. Solo se dan conteos y tiempos.
