# Spec · Fase 1b · Motor OPUS dentro de la app

- **Fecha:** 2026-10-02
- **Rama:** `feat/motor-opus`
- **Estado:** diseño aprobado por Juan en conversación (secciones 1, 2 y 3); pendiente revisión de esta spec.
- **Agentes:** `infraestructura` (nativo, CMake, CI, conversión del modelo), `estructura` (partidor, `:engine:opus` Kotlin, pantalla, benchmark), `seguridad` (revisión del puente JNI y del script de conversión), `diseno` (revisión rápida de la pantalla de prueba).

## 1. Objetivo

Traducir un párrafo inglés → español con **OPUS-MT tc-big en-es (int8)** vía **CTranslate2**, dentro de la app, sin internet, en el Pixel 7.

**Puerta 1b** (con evidencia):
1. Teléfono en modo avión.
2. Un párrafo típico se traduce en **< 2 s** (beam 1, 4 hilos).
3. Benchmark con los 25 textos privados de Juan: **≥ 15 palabras/s** (beam 1, 4 hilos).
4. Captura de la pantalla de benchmark, CI en verde y revisión de `seguridad` del puente JNI sin hallazgos críticos/altos abiertos.

### Fuera de alcance
- Descarga de modelos, catálogo firmado, importación con selector de archivos (fase 2).
- Motor Firefox, `EngineSelector` (fase 2).
- Comparación de calidad beam 1 vs beam 4 (fase 2).
- Caché Room, lector EPUB (fase 3).
- `armeabi-v7a` y x86.

## 2. Decisiones tomadas

| Tema | Decisión | Por qué |
|---|---|---|
| Compilación nativa | **Desde fuente dentro de Gradle** (`externalNativeBuild` + CMake + NDK). CTranslate2 y SentencePiece como **submódulos de git fijados a un tag** | F-Droid exige compilar todo desde fuente; misma receta en laptop, CI y F-Droid |
| ABI | Solo `arm64-v8a` | Pixel 7 y la gran mayoría de teléfonos actuales; menos tiempo de compilación y APK más pequeño |
| Conversión del modelo | **GitHub Actions manual** (`model.yml`, `workflow_dispatch`) con el script `tools/models/convert_opus.py` | Reproducible; sin Python pesado en la laptop; "convertimos nosotros desde la fuente oficial" |
| Fuente del modelo | `Helsinki-NLP/opus-mt-tc-big-en-es` fijado a un **commit exacto** de Hugging Face | Reproducibilidad y cadena de suministro |
| Partidor de oraciones | **Propio en Kotlin puro**, con reglas explícitas (no `BreakIterator`) | Mismo comportamiento en JVM (pruebas/CI) y en Android; TDD sin dependencias. Cambia lo dicho en `docs/agentes/01-estructura.md` → se actualiza esa guía |
| Textos de prueba | Los 25 textos de Juan son **privados** (derechos de autor): nunca en git ni en el CI. El repo trae **25 textos sustitutos libres** escritos para el proyecto, con las mismas 6 categorías | Repo público; licencia |
| Llegada de archivos privados al teléfono | `adb push` a `/data/local/tmp/` + `adb shell run-as <paquete> cp …` hacia `filesDir` | Sin permisos de almacenamiento; funciona con el build *debug* |
| Concurrencia | Un solo motor cargado; un `Mutex` serializa `load`/`translate`/`unload` | Regla de `01-estructura.md` §2 |
| Hilos | Por defecto 4 (ya en `EngineConfig`); el puente rechaza > 8 | Regla 6 de `CLAUDE.md` |
| Plan B | Reloj de **7 días** desde el inicio de la tarea nativa. Si CTranslate2 no traduce en el Pixel al día 7, se detiene y se entrega a Juan una comparación con ONNX Runtime | Prompt de la fase 1b |

## 3. Estructura

```
.gitmodules
native/
├─ third_party/CTranslate2      → submódulo (tag estable; con sus submódulos internos)
├─ third_party/sentencepiece    → submódulo (tag estable)
└─ ct2bridge/
   ├─ CMakeLists.txt            → compila CT2 + SentencePiece (estático) + libct2bridge.so
   └─ ct2bridge.cpp             → puente JNI
engine/opus/
├─ build.gradle.kts             → externalNativeBuild apuntando a native/ct2bridge/CMakeLists.txt; abiFilters arm64-v8a
├─ consumer-rules.pro           → keep de los métodos native
└─ src/main/kotlin/.../engine/opus/
   ├─ NativeBridge.kt           → interfaz que abstrae las 3 funciones nativas (para probar sin .so)
   ├─ Ct2NativeBridge.kt        → implementación real con `external fun` + System.loadLibrary("ct2bridge")
   └─ OpusEngine.kt             → implementa TranslationEngine
core/text/src/main/kotlin/.../core/text/
   └─ SentenceSplitter.kt
app/src/main/
├─ assets/bench/sustitutos.txt  → 25 textos sustitutos libres
└─ kotlin/.../
   ├─ benchmark/BenchmarkRunner.kt  → lógica pura del benchmark (palabras/s, mediana)
   └─ ui/EngineTestScreen.kt         → pantalla temporal (reemplaza HomeScreen)
tools/
├─ models/convert_opus.py
├─ models/requirements.txt      → versiones fijas con --require-hashes
└─ bench/html_to_txt.py         → convierte el HTML privado de Juan a private/textos.txt (local)
.github/workflows/model.yml
private/                        → ignorado por git
```

## 4. Motor nativo

### 4.1 Compilación
- CTranslate2 con: `-DWITH_MKL=OFF -DWITH_RUY=ON -DOPENMP_RUNTIME=NONE -DBUILD_CLI=OFF -DWITH_CUDA=OFF -DWITH_DNNL=OFF -DBUILD_SHARED_LIBS=OFF` (punto de partida; `infraestructura` ajusta según el issue OpenNMT/CTranslate2#1683 y documenta cada cambio).
- SentencePiece estático, sin herramientas de línea de comandos.
- Resultado: una sola `libct2bridge.so` por ABI (todo enlazado estáticamente), con `-ffile-prefix-map=<raíz>=.` para no filtrar rutas locales.
- Versiones de NDK y CMake fijadas en `ProjectConfig`/`build.gradle.kts` y documentadas en `docs/build.md`.
- Release: los métodos `native` sobreviven a R8 (`consumer-rules.pro`).

### 4.2 Contrato JNI
```kotlin
interface NativeBridge {
    /** Devuelve un handle > 0. Lanza IllegalArgumentException / IllegalStateException con mensaje sin texto del usuario. */
    fun load(modelDir: String, threads: Int, beamSize: Int): Long
    fun translate(handle: Long, sentences: Array<String>): Array<String>
    fun unload(handle: Long)
}
```
Del lado C++ (`ct2bridge.cpp`):
- `load`: valida que `modelDir` exista y contenga `model.bin`, `source.spm`, `target.spm`; `1 ≤ threads ≤ 8`; `1 ≤ beam ≤ 8`. Crea `ctranslate2::Translator` (CPU, `int8`, `inter_threads=1`, `intra_threads=threads`) y los dos `SentencePieceProcessor`. Guarda todo en un objeto en el heap; devuelve su puntero como handle.
- `translate`: valida handle no nulo; `0 < n ≤ 64` oraciones; cada oración `≤ 1000` caracteres (UTF-16) y no nula. Convierte UTF-16 → UTF-8 correctamente (incluidos pares sustitutos/emojis; nunca con `GetStringUTFChars`, que usa UTF-8 modificado). Tokeniza con `source.spm`, agrega `</s>` si el modelo Marian lo requiere (y la etiqueta de idioma si `infraestructura` confirma que hace falta), traduce con `beam_size` del `load`, `max_decoding_length = 512`, destokeniza con `target.spm`, devuelve UTF-8 → `String`. La salida tiene el mismo tamaño que la entrada.
- `unload`: libera; un handle 0 es no-op.
- Toda excepción C++ se captura en el borde y se lanza como excepción Java (`IllegalArgumentException` para entradas, `IllegalStateException` para el resto). Los mensajes **nunca** incluyen texto del usuario.
- Sin `__android_log_print` de contenido.

## 5. Lado Kotlin

### 5.1 `SentenceSplitter` (`:core:text`)
```kotlin
object SentenceSplitter {
    fun split(paragraph: String): List<String>
}
```
- Devuelve oraciones recortadas, sin vacías; la concatenación conserva el contenido.
- Reglas: fin de oración en `.`, `!`, `?`, `…` seguidos de espacio + mayúscula/comilla/apertura; no corta en abreviaturas comunes (`Mr.`, `Mrs.`, `Dr.`, `Prof.`, `St.`, `vs.`, `etc.`, `e.g.`, `i.e.`, `U.S.`, `a.m.`, `p.m.`), iniciales (`J. R. R.`), decimales (`3.5`), versiones (`v1.2.3`), puntos suspensivos internos; el cierre de comillas/paréntesis queda con su oración; diálogos con comillas.
- Pruebas: ≥ 15 casos explícitos + cada uno de los 25 textos sustitutos con su partición esperada.
- `docs/agentes/01-estructura.md` §3 y §4 se actualizan: partidor propio en vez de `BreakIterator`.

### 5.2 `OpusEngine` (`:engine:opus`)
```kotlin
class OpusEngine(
    private val modelsRoot: File,
    private val bridge: NativeBridge = Ct2NativeBridge,
) : TranslationEngine {
    override val id = "opus"
    override suspend fun load(pair: LanguagePair, config: EngineConfig)   // modelo en modelsRoot/<source>-<target>/
    override suspend fun translate(sentences: List<String>): List<String>  // lotes de ≤ 64, valida ≤ 1000 chars antes de cruzar JNI
    override fun unload()
}
```
- `withContext(Dispatchers.Default)` + `Mutex`: nunca dos llamadas nativas en paralelo.
- `translate` antes de `load` → `IllegalStateException`. Lista vacía → lista vacía sin cruzar JNI.
- `load` con otro par/config descarga el anterior.
- Pruebas JVM con un `FakeNativeBridge`: lotes, límites, orden, estado, que `unload` libere, que no haya llamadas concurrentes.
- Dependencia nueva: `org.jetbrains.kotlinx:kotlinx-coroutines-core` (+ `-test` para pruebas), versión fija, con revisión de `seguridad` y huellas en `verification-metadata.xml`.

### 5.3 Benchmark y pantalla (`:app`)
- `BenchmarkRunner` (lógica pura, probada en JVM): recibe párrafos, una función `translate(paragraph) -> String` y un reloj; hace 1 párrafo de calentamiento (no cuenta), luego mide cada párrafo; devuelve palabras/s = palabras origen totales / segundos totales, mediana de ms por párrafo, máximo, y ✅/❌ frente a 15 palabras/s y 2.000 ms.
- Traducción de un párrafo = `SentenceSplitter.split` → `OpusEngine.translate` → unir con espacio.
- Textos del benchmark: si existe `filesDir/bench/textos.txt` (privados) se usan esos; si no, `assets/bench/sustitutos.txt`. La pantalla dice cuál se usó. Formato: un párrafo por línea, UTF-8.
- `EngineTestScreen` reemplaza a `HomeScreen`: estado del modelo (cargado / no encontrado con la ruta esperada y el comando para copiarlo), campo de texto, botón **Traducir**, resultado y ms, botón **Benchmark** con resultados; botones deshabilitados mientras trabaja; el motor se carga al abrir y se libera en `onCleared` del ViewModel.
- Sin logs del texto. Textos en `strings.xml`.
- Revisión rápida de `diseno`.

## 6. Modelo y datos privados

### 6.1 `tools/models/convert_opus.py` + `model.yml`
1. Descarga `Helsinki-NLP/opus-mt-tc-big-en-es` en una revisión (commit) fija.
2. `ct2-transformers-converter --quantization int8 --copy_files source.spm target.spm` → `out/en-es/`.
3. Agrega `LICENSE` (CC-BY-4.0) y `ATTRIBUTION.txt` (Helsinki-NLP / OPUS-MT, Tiedemann et al., enlace a la fuente).
4. Traduce los 25 sustitutos con CTranslate2 en Python (beam 1) → `reference-outputs.txt`; así también se confirma si hace falta etiqueta de idioma o `</s>`.
5. `SHA256SUMS` de todos los archivos; empaqueta `en-es.tar.zst`.
- `model.yml`: `workflow_dispatch`, `permissions: contents: read`, acciones fijadas por SHA, Python fijado, `pip install --require-hashes -r tools/models/requirements.txt`, sube `en-es.tar.zst` + `SHA256SUMS` como artefacto (retención 90 días).

### 6.2 Archivos privados de Juan
- `.gitignore`: `private/` y `textos_*.html`.
- `tools/bench/html_to_txt.py`: lee el HTML (párrafos `<p>` con marcador `⟦NN⟧`), quita el marcador y escribe `private/textos.txt` (un párrafo por línea). Solo biblioteca estándar de Python. Sin contenido privado en el script ni en sus pruebas.
- Comandos para Juan (en `docs/build.md`):
  ```
  adb push en-es /data/local/tmp/en-es
  adb shell run-as io.github.diegobr4nd.lectorbilingue mkdir -p files/models
  adb shell run-as io.github.diegobr4nd.lectorbilingue cp -r /data/local/tmp/en-es files/models/
  adb push private/textos.txt /data/local/tmp/textos.txt
  adb shell run-as io.github.diegobr4nd.lectorbilingue mkdir -p files/bench
  adb shell run-as io.github.diegobr4nd.lectorbilingue cp /data/local/tmp/textos.txt files/bench/textos.txt
  adb shell rm -r /data/local/tmp/en-es /data/local/tmp/textos.txt
  ```

## 7. Pruebas

| Qué | Tipo | Dónde corre |
|---|---|---|
| `SentenceSplitter` (≥ 15 casos + 25 sustitutos) | Unitaria JVM, TDD | Local y CI |
| `OpusEngine` con `FakeNativeBridge` | Unitaria JVM, TDD | Local y CI |
| `BenchmarkRunner` (palabras/s, mediana, calentamiento) | Unitaria JVM, TDD | Local y CI |
| `html_to_txt.py` | Prueba Python con HTML sintético | Local y CI |
| Puente real + modelo: "Hello, world." traduce a texto no vacío; emojis y tildes ida y vuelta; 1.001 caracteres → excepción; lista de 65 → excepción; handle liberado → sin crash | Instrumentada (`androidTest`) en el Pixel; se salta si el modelo no está copiado | Pixel |
| Rendimiento | Benchmark en pantalla, modo avión | Pixel |

## 8. CI

- `ci.yml`: `git submodule update --init --recursive` (checkout con `submodules: recursive`), instala NDK y CMake en las versiones fijas con `sdkmanager`, compila (incluye nativo), y verifica con `unzip -l` que `lib/arm64-v8a/libct2bridge.so` está en el APK release. Sin caché nativa en 1b; si el job pasa de 20 min se agrega caché de `engine/opus/.cxx` con `actions/cache` (fijada por SHA).
- `model.yml`: ver 6.1.
- Los submódulos son parte de la verificación de cadena de suministro: commit fijado; cambiar de tag requiere revisión de `seguridad`.

## 9. Documentación

- `docs/build.md`: instalar NDK y CMake (comando `sdkmanager` exacto), clonar con submódulos, comandos de copia al teléfono, cómo correr el benchmark.
- `docs/agentes/01-estructura.md`: partidor propio.
- `docs/contexto-y-decisiones.md` no cambia (las decisiones de motor se mantienen).

## 10. Riesgos

| Riesgo | Mitigación |
|---|---|
| CTranslate2 no compila con el NDK | Opciones del issue #1683; ajustar flags; si al día 7 no traduce en el Pixel → comparación con ONNX Runtime para Juan |
| Compilar C++ en Windows falla (rutas largas, CMake) | Documentar; si persiste, opción C (atajo local con `.so` del CI) solo para la laptop, con aprobación de Juan |
| El modelo requiere etiqueta `>>spa<<` o manejo especial de `</s>` | Se confirma en `convert_opus.py` (paso 4) antes de cerrar el puente |
| APK pesado | Solo arm64; estático con `-Os`/`--gc-sections` si hace falta; se reporta el tamaño |
| Velocidad bajo la meta | Probar `int8` vs `int8_float32`, hilos 4 vs núcleos rápidos detectados; reportar datos a Juan |
| Memoria (~330 MB) | Un solo motor cargado; `unload` al salir de la pantalla |
| Texto privado se cuela al repo | `.gitignore`, script fuera de `private/`, revisión de `git status` antes de cada commit |
