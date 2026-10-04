# slimtbridge · motor Firefox nativo

Puente JNI entre `SlimtNativeBridge` (Kotlin, módulo `:engine:firefox`) y **slimt**, el motor C++ que ejecuta los modelos de traducción de Firefox (formato Marian).

- **slimt**: como CTranslate2, pero para los modelos de Firefox. Solo hace beam 1 (voraz).
- **ruy**: multiplica matrices de enteros de 8 bits en ARM.
- **SentencePiece**: parte el texto en piezas del vocabulario.

## Cómo se compila

- Gradle (`engine/firefox/build.gradle.kts`) llama a CMake con este `CMakeLists.txt`; solo arm64-v8a, `c++_static`.
- Fuentes: submódulo `native/third_party/slimt` (commit fijo) y sus submódulos `3rd-party/sentencepiece` y `3rd-party/ruy` (+ `cpuinfo`). Se bajan con `tools/native/init-submodules.sh`.
- El submódulo **nunca se modifica**. Al configurar, CMake copia `slimt/` a la carpeta de compilación (con fin de línea LF) y aplica `patches/` con `git apply`:
  - `0001`: `posix_memalign` en vez de `aligned_alloc` (API 26).
  - `0002`: arregla el sesgo de la lista corta en ruy (sin esto, traducciones rotas).
  - `0003`: los `abort()` de slimt lanzan excepción (un modelo dañado no cierra la app).
- Sin PCRE2: no se compila el divisor de oraciones de slimt (`Splitter.cc`, `Regex.cc`). Lo reemplaza `compat/SentenceStreamNoSplit.cc`: cada texto que llega es **una** oración. Las oraciones ya vienen partidas por `:core:text`.
- Sin rutas de la laptop en el `.so`: `-ffile-prefix-map`.

## `slimt.json` (obligatorio en cada carpeta de modelo)

slimt **no lee** la configuración que trae el modelo. Si el número de capas no coincide, traduce mal sin avisar. Por eso cada carpeta de modelo Firefox trae nuestro `slimt.json` (lo escribe el script de modelos, no viene de Mozilla).

Ejemplo (en-es, `base-memory`):

```json
{
  "model": "model.enes.intgemm.alphas.bin",
  "vocabulary": "vocab.enes.spm",
  "shortlist": "lex.50.50.enes.s2t.bin",
  "encoder_layers": 6,
  "decoder_layers": 4,
  "heads": 8
}
```

Reglas (el puente rechaza todo lo demás con `IllegalArgumentException("slimt.json inválido")`):

- Las **6 claves** son obligatorias. Ninguna otra; ninguna repetida.
- `model`, `vocabulary`, `shortlist`: nombre de archivo simple, `^[A-Za-z0-9._-]{1,128}$`, sin `..` ni `/`. El archivo debe estar **directamente** en la carpeta del modelo y ser un archivo normal (no un enlace simbólico).
- `vocabulary` es **un solo** archivo: slimt usa el mismo vocabulario para origen y destino. Los pares con dos vocabularios no se pueden usar con este slimt.
- `encoder_layers`, `decoder_layers`: enteros de 1 a 12. `heads`: de 1 a 16. Sin signo, sin decimales, sin ceros a la izquierda.
- Cadenas sin escapes (`\`). Tamaño máximo del archivo: 4 KiB.
- La profundidad del FFN queda en 2 (lo que usan `base`, `base-memory` y `tiny`).

## Reglas del puente

- Handle opaco (contador que no se repite); 0 = error.
- `threads` de 1 a 4 (por defecto 1 para Firefox). Cada hilo tiene su propio `slimt::Blocking`; comparten un solo modelo.
- Lote de 1 a 64 oraciones, máximo 1000 caracteres cada una (mismos topes que OPUS).
- Toda excepción C++ se atrapa en cada entrada JNI. Mensajes fijos; nunca se registra (log) texto.
- Caché de slimt apagada: no guarda textos entre llamadas.
- Ojo: la lista corta se arma con la unión de las oraciones de cada llamada, así que una oración puede traducirse un poco distinto según con cuáles vaya. Para resultados estables, traducir un párrafo por llamada.
