# Motor Firefox + EngineSelector (Fase 2c) · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** La app traduce con OPUS o con Firefox (modelos de Mozilla corridos con slimt). Elige el motor por RAM (≥ 4 GB → OPUS) y guarda cada modelo en la carpeta de su motor, cargando solo los instalados por el gestor.

**Architecture:**
- Módulo nuevo `:engine:firefox`, con slimt en C++ detrás de un puente JNI propio (`native/slimtbridge/`), compilado con CMake y el NDK igual que `ct2bridge`.
- `EngineSelector`: función pura en `:engine:api`.
- `:models` pasa a rutas `models/<engine>/<pair>/`, con migración automática y `installedDir(engine, pair)` como única puerta para que un motor obtenga su carpeta.
- Flujo de GitHub Actions para copiar los modelos de Mozilla. `model.yml` recibe el par como parámetro.
- Pantalla de prueba con interruptor de motor, selector de par y lista de modelos.

**Tech Stack:**
- Kotlin · AGP 9.4.1.
- NDK 30.0.16248370 · CMake 4.1.2.
- slimt (`jerinphilip/slimt`, GPL-2.0-or-later) y sus dependencias como submódulos con commit fijo (las fija la Tarea 1).
- Modelos Firefox de Mozilla (MPL-2.0) · Python 3.12 (stdlib) · GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-03-motor-firefox-design.md`

**Ejecución:** subagent-driven.
- Tareas 1, 5 y 7 → `infraestructura`.
- Tareas 2, 3, 4, 6 y 8 → `estructura`.
- Tarea 9 → `seguridad` + `diseno` (solo lectura) y arreglos por el dueño.
- Tarea 10 → sesión principal con Juan.

**La Tarea 1 es una puerta:** si su veredicto es NO-GO, el controlador **para** y presenta los datos a Juan antes de cualquier otra tarea.

## Global Constraints

- **Paquetes:** base `io.github.diegobr4nd.lectorbilingue`; módulo nuevo `:engine:firefox`, paquete `io.github.diegobr4nd.lectorbilingue.engine.firefox`.
- **Rama y push:** rama `feat/motor-firefox`; nunca `main`; nunca push sin permiso de Juan.
- **Regla de RAM:** RAM total ≥ 4 GiB (`4L * 1024 * 1024 * 1024` bytes) → OPUS; si no, Firefox. La RAM total sale de `ActivityManager.MemoryInfo.totalMem`.
- **Motores:**
  - máximo 4 hilos;
  - una sola llamada nativa a la vez por motor (candado);
  - el texto llega en oraciones, nunca párrafos;
  - un solo motor cargado a la vez en la app.
- **Rutas en el teléfono:**
  - modelos instalados en `filesDir/models/<engine>/<pair>/`, más `.installed.json`, con `<engine>` ∈ {`opus`, `firefox`};
  - temporales en `filesDir/models/.tmp/<id>/` (sin cambio);
  - copias viejas en `filesDir/models/<engine>/.old-<pair>-<nanoTime>`.
- **Un motor solo carga una carpeta devuelta por `ModelStore.installedDir(engine, pair)`**, que exige:
  - un `.installed.json` válido, con el mismo `engine` y el mismo `pair`;
  - que existan, como archivos regulares (no enlaces), todos los `files` que lista.
- **Modelos Firefox:** desde la fuente oficial de Mozilla, **sin modificar**, copiados a releases `firefox-<pair>-v1` de `DiegoBr4nd/lector-bilingue-modelos`. Cada uno lleva `LICENSE` (MPL-2.0), `ATTRIBUTION.txt` y `MODEL_CARD.md`.
- **Red:** la lista blanca de hosts de la 2b **no cambia** (`github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`).
- **Permisos:** la lista de la 2b **no cambia**: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE` y `<paquete>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- **Texto del usuario:**
  - nunca se registra ni se envía texto de libros o traducciones;
  - los mensajes de error no llevan rutas internas ni contenido;
  - las traducciones de los textos privados de Juan van solo a `private/` en la laptop, nunca al repo ni a la conversación.
- **Licencias:** prohibido MuPDF, NLLB y cualquier dependencia no libre. Los avisos de copyright de slimt y sus dependencias se conservan.
- **Gradle y teléfono:**
  - Gradle: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew …`.
  - Teléfono: `export MSYS_NO_PATHCONV=1`. Las rutas locales que se pasan a herramientas Windows (`adb push`, etc.) van como `C:/…`.
  - Nunca desinstalar, `pm clear` ni `connectedAndroidTest`. Instrumentadas con `installFdroidDebug installFdroidDebugAndroidTest` y `am instrument`, tras `am force-stop`.
- **Commits:** Conventional Commits en español, terminando en `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **El teléfono de Juan ya tiene `models/en-es/` (OPUS):** al actualizar, la migración debe moverlo a `models/opus/en-es/` sin perderlo, incluso si la app muere a mitad. Lo prueba la Tarea 3: migración cortada a mitad y repetible.
2. **Carpeta del modelo con un archivo listado que falta, o que es un enlace simbólico:** el motor no debe cargarla. Tarea 3 (`installedDir` → null) y Tarea 4 (`load` lanza `ModelNotInstalledException`).
3. **Interruptor en "Firefox" sin el modelo Firefox instalado:** la pantalla ofrece descargarlo. Nunca cae en OPUS sin avisar. Tarea 2 (`Missing(FIREFOX)`) y Tarea 8.
4. **Exactamente 4 GiB de RAM:** con 4 GiB va OPUS; con un byte menos, Firefox. Tarea 2 (prueba de borde).
5. **Borrar el modelo Firefox `en-es` no toca el OPUS `en-es`, y viceversa:** Tarea 3 (`delete` por motor).

---

### Task 1: Prueba rápida de slimt en el Pixel, con decisión (agente `infraestructura`)

**Files:**
- Create (en la rama desechable `spike/slimt`, **nunca** fusionada): lo que haga falta para compilar y correr.
- Create (en `feat/motor-firefox`): `docs/superpowers/notes/2026-10-03-spike-slimt.md`, con el informe de la prueba (sin textos privados).

**Interfaces:**
- Produces (en el informe, para las Tareas 5 y 7):
  - commit exacto de slimt y de cada dependencia;
  - opciones de CMake que funcionaron (ruy o gemmology, flags ARM);
  - parches necesarios, con su origen y licencia;
  - **URL oficial y versión** de los modelos Mozilla `en-es` y `es-en`;
  - nombres exactos de sus archivos y si Mozilla publica huellas SHA-256;
  - la firma de la API C++ de slimt que se usa (cargar, traducir, liberar);
  - si slimt admite varios hilos y beam;
  - números medidos.

- [ ] **Step 1:** Crear la rama `spike/slimt` desde `feat/motor-firefox`. Clonar slimt y sus submódulos en `native/third_party/slimt` y fijar un commit. Revisar `slimt/` y `CMakeLists.txt` para ver qué API expone (`Model`, `Translator`/`Service`, `Config`).
- [ ] **Step 2:** Encontrar la fuente oficial de los modelos Firefox de Mozilla para `en-es` y `es-en`:
  - el registro que usa Firefox hoy (Remote Settings / bucket público de Mozilla), o los releases de `mozilla/translations`;
  - anotar la URL exacta, la versión, los archivos (modelo `.bin`, vocabulario `.spm` o par src/trg, lista corta `lex.*.bin`) y las huellas publicadas;
  - bajarlos al scratchpad.
- [ ] **Step 3:** Compilar slimt para `arm64-v8a`:
  - NDK 30.0.16248370 y CMake 4.1.2, `ANDROID_STL=c++_static`, `ANDROID_PLATFORM=android-26`;
  - preferir `ruy` en aarch64;
  - salida: un ejecutable de línea de comandos que lea oraciones de stdin y escriba traducciones en stdout. slimt trae `app/`; si no sirve, escribir uno mínimo.
- [ ] **Step 4:** En el Pixel:
  - `adb push` del binario y del modelo a `/data/local/tmp/slimt/`;
  - traducir 5 oraciones **propias** (por ejemplo "The old library was quiet and the rain kept falling all afternoon.") y anotar en el informe si son sensatas;
  - si no carga el modelo actual de Mozilla, probar parches (tomados del fork que usa Offline Translator, con licencia y aviso) y anotar cuáles.
- [ ] **Step 5:** Benchmark con los 25 textos privados:
  - Copiar `private/` → `/data/local/tmp/slimt/bench.txt`.
  - Partir en oraciones como `:core:text`, o usar `bench/` si ya está partido.
  - Medir:
    - palabras/s;
    - mediana y peor caso por párrafo;
    - RAM pico (`/proc/<pid>/status` VmHWM);
    - con 1 y con 4 hilos, si slimt los admite.
  - Las traducciones van **solo** a `C:/Users/JUAN/Documents/lector-bilingue/private/firefox-2c/` (ignorado por git). Nunca se imprimen en la salida.
  - Al terminar, `adb shell rm -r /data/local/tmp/slimt`.
- [ ] **Step 6:** Escribir el informe `docs/superpowers/notes/2026-10-03-spike-slimt.md` con todo lo de "Produces", los números y un **veredicto**:
  - **GO**, si se cumple todo:
    - compila;
    - carga los modelos actuales sin parches grandes;
    - ≥ 15 palabras/s y < 2000 ms de mediana por párrafo;
    - las oraciones propias salen sensatas.
  - **NO-GO**, si falla cualquiera, con el motivo y los datos.
- [ ] **Step 7:** Commit del informe en `feat/motor-firefox`: `docs(2c): informe de la prueba rápida de slimt`. La rama `spike/slimt` se queda local y no se sube.

---

### Task 2: `EngineId` y `EngineSelector` (agente `estructura`)

**Files:**
- Create: `engine/api/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/api/EngineId.kt`, `engine/api/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/api/EngineSelector.kt`, `engine/api/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/api/ModelNotInstalledException.kt`
- Test: `engine/api/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/engine/api/EngineSelectorTest.kt`

**Interfaces:**
- Produces:
  - `enum class EngineId(val wire: String) { OPUS("opus"), FIREFOX("firefox") }`, con `companion fun fromWire(s: String): EngineId?`.
  - `sealed interface EngineChoice { data class Use(val engine: EngineId, val reason: Reason); data class Missing(val engine: EngineId) }` y `enum class Reason { FORCED, RAM, ONLY_INSTALLED }`.
  - `object EngineSelector { const val OPUS_MIN_RAM_BYTES: Long = 4L * 1024 * 1024 * 1024; fun choose(installed: Set<EngineId>, totalRamBytes: Long, forced: EngineId?): EngineChoice }`.
  - `class ModelNotInstalledException(val engine: EngineId, val pair: LanguagePair) : Exception("modelo no instalado")`.

- [ ] **Step 1: Pruebas que fallan** (`EngineSelectorTest.kt`):
```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.api

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineChoice.Missing
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineChoice.Use
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId.FIREFOX
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId.OPUS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineSelectorTest {
    private val gib = 1024L * 1024 * 1024
    private val both = setOf(OPUS, FIREFOX)

    @Test fun ochoGbConAmbosEligeOpus() = assertEquals(Use(OPUS, Reason.RAM), EngineSelector.choose(both, 8 * gib, null))
    @Test fun cuatroGbExactosEligeOpus() = assertEquals(Use(OPUS, Reason.RAM), EngineSelector.choose(both, 4 * gib, null))
    @Test fun unByteMenosDeCuatroEligeFirefox() = assertEquals(Use(FIREFOX, Reason.RAM), EngineSelector.choose(both, 4 * gib - 1, null))
    @Test fun tresGbEligeFirefox() = assertEquals(Use(FIREFOX, Reason.RAM), EngineSelector.choose(both, 3 * gib, null))
    @Test fun dosGbEligeFirefox() = assertEquals(Use(FIREFOX, Reason.RAM), EngineSelector.choose(both, 2 * gib, null))
    @Test fun soloOpusConPocaRamUsaOpus() = assertEquals(Use(OPUS, Reason.ONLY_INSTALLED), EngineSelector.choose(setOf(OPUS), 2 * gib, null))
    @Test fun soloFirefoxConMuchaRamUsaFirefox() = assertEquals(Use(FIREFOX, Reason.ONLY_INSTALLED), EngineSelector.choose(setOf(FIREFOX), 8 * gib, null))
    @Test fun ningunoConMuchaRamPideOpus() = assertEquals(Missing(OPUS), EngineSelector.choose(emptySet(), 8 * gib, null))
    @Test fun ningunoConPocaRamPideFirefox() = assertEquals(Missing(FIREFOX), EngineSelector.choose(emptySet(), 2 * gib, null))
    @Test fun forzadoInstaladoGana() = assertEquals(Use(FIREFOX, Reason.FORCED), EngineSelector.choose(both, 8 * gib, FIREFOX))
    @Test fun forzadoOpusConPocaRam() = assertEquals(Use(OPUS, Reason.FORCED), EngineSelector.choose(both, 2 * gib, OPUS))
    @Test fun forzadoSinInstalarPideEseMotor() = assertEquals(Missing(FIREFOX), EngineSelector.choose(setOf(OPUS), 8 * gib, FIREFOX))
    @Test fun wireIdaYVuelta() {
        assertEquals(OPUS, EngineId.fromWire("opus"))
        assertEquals(FIREFOX, EngineId.fromWire("firefox"))
        assertNull(EngineId.fromWire("Opus"))
        assertNull(EngineId.fromWire(""))
    }
}
```
- [ ] **Step 2:** `./gradlew :engine:api:test` → FALLA (no compila: `EngineSelector` no existe).
- [ ] **Step 3: Implementación**
```kotlin
// EngineId.kt
package io.github.diegobr4nd.lectorbilingue.engine.api

/** Motores de traducción. [wire] es el nombre en el catálogo, en `.installed.json` y en las carpetas. */
enum class EngineId(val wire: String) {
    OPUS("opus"),
    FIREFOX("firefox");

    companion object {
        fun fromWire(s: String): EngineId? = entries.firstOrNull { it.wire == s }
    }
}
```
```kotlin
// EngineSelector.kt
package io.github.diegobr4nd.lectorbilingue.engine.api

/** Qué motor usar para un par. El motivo es un enum: la interfaz lo traduce. */
sealed interface EngineChoice {
    data class Use(val engine: EngineId, val reason: Reason) : EngineChoice
    /** No hay modelo instalado; [engine] es el que conviene descargar. */
    data class Missing(val engine: EngineId) : EngineChoice
}

enum class Reason { FORCED, RAM, ONLY_INSTALLED }

/**
 * Regla de la fase 2c (decisión de Juan): con RAM total ≥ 4 GiB, OPUS; si no, Firefox.
 * Si solo hay un motor instalado para el par, se usa ese. [forced] (interruptor de prueba)
 * manda si ese motor está instalado; si no, se pide ese motor. Nunca cambia de motor en silencio.
 */
object EngineSelector {
    const val OPUS_MIN_RAM_BYTES: Long = 4L * 1024 * 1024 * 1024

    fun choose(installed: Set<EngineId>, totalRamBytes: Long, forced: EngineId?): EngineChoice {
        if (forced != null) {
            return if (forced in installed) EngineChoice.Use(forced, Reason.FORCED) else EngineChoice.Missing(forced)
        }
        val preferred = if (totalRamBytes >= OPUS_MIN_RAM_BYTES) EngineId.OPUS else EngineId.FIREFOX
        return when {
            preferred in installed -> EngineChoice.Use(preferred, Reason.RAM)
            installed.size == 1 -> EngineChoice.Use(installed.single(), Reason.ONLY_INSTALLED)
            else -> EngineChoice.Missing(preferred)
        }
    }
}
```
```kotlin
// ModelNotInstalledException.kt
package io.github.diegobr4nd.lectorbilingue.engine.api

/** El motor no encontró un modelo instalado por el gestor para [pair]. Mensaje fijo: sin rutas. */
class ModelNotInstalledException(val engine: EngineId, val pair: LanguagePair) : Exception("modelo no instalado")
```
- [ ] **Step 4:** `./gradlew :engine:api:test` → PASA (13 pruebas nuevas).
- [ ] **Step 5: Commit** `feat(engine): EngineSelector por RAM y EngineId`.

---

### Task 3: `:models` con carpetas por motor y migración (agente `estructura`)

**Files:**
- Modify: `models/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/models/ModelStore.kt`, `ModelInstaller.kt`, `ModelImporter.kt` (solo la ruta de instalación), `Models.kt`, `ModelsCoordinator.kt`, `ModelFiles.kt` (si hace falta un helper)
- Test: `models/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/models/ModelStoreTest.kt`, `ModelInstallerTest.kt`, `ModelImporterTest.kt`, `ModelsCoordinatorTest.kt`, y uno nuevo, `ModelLayoutMigrationTest.kt`

**Interfaces:**
- Consumes: la API de la 2b (`ModelStore(modelsDir)`, `installed()`, `isInstalled(pair)`, `delete(pair)`, `recover(catalogIds)`, `ModelInstaller.install(model, staging)`, `ModelImporter.import(zip, catalog)`, `InstalledModel(id, pair, engine, modelVersion, files)`).
- Produces (`:models` sigue sin depender de `:engine:api`: los motores son `String` `"opus"`/`"firefox"`):
  - `ModelStore.installed(): List<InstalledModel>`: todos los motores, ordenados por (engine, pair).
  - `ModelStore.installedDir(engine: String, pair: String): File?`: carpeta canónica si cumple la regla de Global Constraints; si no, `null`. Nunca lanza.
  - `ModelStore.isInstalled(engine: String, pair: String): Boolean = installedDir(engine, pair) != null`.
  - `ModelStore.delete(engine: String, pair: String)`: borra `models/<engine>/<pair>/` y sus `.old-<pair>-*` dentro de `models/<engine>/`, sin tocar otros motores. Mantiene el orden de la 2b (primero todos los `.installed.json`).
  - `ModelStore.migrateLegacyLayout()`: mueve cada `models/<pair>/` de primer nivel con `.installed.json` válido a `models/<engine>/<pair>/`. Si ya existe el destino, deja el legado sin tocar. Idempotente y nunca lanza.
  - `ModelStore.recover(catalogIds)`: recupera `.old-*` dentro de cada `models/<engine>/`; `.tmp/` como en la 2b. Además recupera `.old-<pair>-*` de primer nivel heredados de la 2b, moviéndolos a su motor según su `.installed.json`.
  - `ModelStore.sizeOnDisk(engine, pair)`.
  - `ModelInstaller` instala en `models/<model.engine>/<model.pair>/`; copia vieja `models/<engine>/.old-<pair>-<nanoTime>`.
  - `ModelsCoordinator`: la recuperación única ahora corre `migrateLegacyLayout()` y **luego** `recover(...)`, ambos dentro del candado.
  - `Models.deleteModel(context, engine, pair)` y `Models.installedDir(context, engine, pair): File?`.
  - Se quitan las sobrecargas viejas de un solo argumento `pair` (los usos se actualizan).

- [ ] **Step 1: Pruebas que fallan** (carpetas temporales, como en la 2b):
  - **`installedDir`:**
    - devuelve la carpeta si `.installed.json` (engine y pair correctos) y todos los `files` existen como archivos regulares;
    - `null` si:
      - falta el JSON;
      - el JSON es de otro motor (`firefox` dentro de `opus/`);
      - el JSON es de otro par;
      - falta un archivo listado;
      - un archivo listado es enlace simbólico (solo en Linux; `assumeTrue` si no se pueden crear enlaces);
      - la carpeta es un enlace;
    - `engine` o `pair` inválido → `IllegalArgumentException` (como `requirePair` de la 2b), con engine ∈ {opus, firefox}.
  - **`delete("firefox","en-es")`** deja intacto `opus/en-es/` y `opus/.old-en-es-1`, y borra `firefox/en-es/` y `firefox/.old-en-es-*`.
  - **Migración:**
    - `models/en-es/` con JSON `engine=opus` → `models/opus/en-es/`, con los mismos bytes;
    - una carpeta sin JSON se deja quieta;
    - si `models/opus/en-es/` ya existe, no se toca el legado ni el destino;
    - llamarla dos veces da el mismo resultado;
    - **corte a mitad:** con un `move` inyectado que falla en el segundo par, el primero queda migrado, el segundo sigue en su sitio y una segunda llamada termina el trabajo.
  - **`recover`:**
    - restaura `opus/.old-en-es-5` → `opus/en-es` si falta;
    - un `.old-en-es-3` de primer nivel con JSON `engine=opus` termina en `opus/en-es` (si falta) o se borra (si ya existe).
  - **Instalador:** instala un modelo `engine=firefox` en `firefox/en-es/` sin tocar `opus/en-es/`. El reemplazo deja `firefox/.old-en-es-*` y lo limpia.
  - **Importador:** un zip de un modelo Firefox termina en `firefox/<pair>/`.
  - **`ModelsCoordinatorTest`:** la recuperación única llama primero a migrar y después a recuperar, una sola vez, con el candado tomado.
- [ ] **Step 2:** `./gradlew :models:testDebugUnitTest` → las nuevas FALLAN.
- [ ] **Step 3: Implementación.**
  - `ModelStore` recibe `modelsDir` (la raíz `models/`) y calcula `engineDir(engine) = ModelFiles.child(modelsDir, engine)`.
  - Validar `engine` con `Regex("^(opus|firefox)$")`.
  - `installedDir` reutiliza `readInstalled` y comprueba cada archivo con `ModelFiles.isRegularFileNoFollow`.
  - La migración usa la misma función `move` inyectable de la 2b (renombre atómico).
  - Actualizar el KDoc de cada clase con la nueva estructura.
- [ ] **Step 4:** `./gradlew :models:testDebugUnitTest` → todo verde (las pruebas de la 2b, adaptadas a las rutas nuevas, y las nuevas).
- [ ] **Step 5:** Actualizar los usos en `app/` para que compile: `Models.deleteModel` y la pantalla (de momento, con motor `"opus"` fijo; la Tarea 8 la rehace). `./gradlew lint test assembleFdroidDebug` → BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** `feat(models): una carpeta por motor, migración y installedDir`.

---

### Task 4: OPUS solo carga modelos instalados (agente `estructura`)

**Files:**
- Modify: `engine/opus/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/opus/OpusEngine.kt`, `engine/opus/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/engine/opus/OpusEngineTest.kt`, `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/enginetest/EngineTestViewModel.kt` (construcción del motor), `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/OpusOnDeviceTest.kt` y `CalidadBeamTest.kt` (cómo obtienen la carpeta)

**Interfaces:**
- Consumes: `EngineId`, `ModelNotInstalledException` (Tarea 2); `Models.installedDir(context, engine, pair)` (Tarea 3).
- Produces: `class OpusEngine(private val modelDir: (LanguagePair) -> File?, bridge: NativeBridge = Ct2NativeBridge, dispatcher: CoroutineDispatcher = Dispatchers.Default)`.
  - `modelDir` devuelve la carpeta ya validada por `ModelStore`, o `null`.
  - `load` lanza `ModelNotInstalledException(EngineId.OPUS, pair)` si es `null`, y `IllegalStateException("carpeta de modelo no válida")` si la carpeta no es directorio.
  - Se eliminan `modelsRoot`, `modelDir(pair)` público e `isModelPresent`.
  - `id` sigue siendo `"opus"` (`EngineId.OPUS.wire`).

- [ ] **Step 1: Pruebas que fallan** en `OpusEngineTest` (con `FakeNativeBridge`):
  - `modelDir` devuelve `null` → `load` lanza `ModelNotInstalledException` con `engine == OPUS` y `pair` correcto, y **no** llama al puente;
  - carpeta válida → el puente recibe esa ruta;
  - el mensaje de la excepción no contiene `/` ni `\`;
  - las pruebas existentes de hilos, beam, lotes y descarga se mantienen.
- [ ] **Step 2:** `./gradlew :engine:opus:test` → FALLAN.
- [ ] **Step 3: Implementación** (cambio de constructor y `load`; quitar el `check(dir.isDirectory) { "Modelo no encontrado en ${dir.path}" }`, que filtraba la ruta).
- [ ] **Step 4:** Actualizar los usos:
  - `EngineTestViewModel`: `OpusEngine({ pair -> Models.installedDir(app, "opus", "${pair.source}-${pair.target}") })`;
  - las pruebas instrumentadas obtienen la carpeta igual, y se saltan con `assumeTrue` si es `null`.
- [ ] **Step 5:** `./gradlew lint test assembleFdroidDebug` → BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** `feat(engine): OPUS carga solo modelos instalados por el gestor`.

---

### Task 5: Compilación nativa de slimt y módulo `:engine:firefox` (agente `infraestructura`)

**Files:**
- Modify: `.gitmodules`, `tools/native/init-submodules.sh`, `settings.gradle.kts` (`include(":engine:firefox")`), `app/build.gradle.kts` (dependencia), `.github/workflows/ci.yml` (paso del `.so`), `gradle/verification-metadata.xml` (solo si cambia algo)
- Create:
  - `native/third_party/slimt` (submódulo con el commit de la Tarea 1) y las dependencias que la Tarea 1 haya fijado;
  - `native/slimtbridge/CMakeLists.txt`, `native/slimtbridge/slimtbridge.cpp`, `native/slimtbridge/NOTICE`;
  - `engine/firefox/build.gradle.kts`, `engine/firefox/consumer-rules.pro`, `engine/firefox/src/main/AndroidManifest.xml`;
  - `engine/firefox/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/firefox/NativeBridge.kt`, `SlimtNativeBridge.kt`.

**Interfaces:**
- Consumes: el informe de la Tarea 1 (commits, flags, parches, API de slimt).
- Produces:
  - **Biblioteca** `libslimtbridge.so` (arm64-v8a, `c++_static`).
  - **`NativeBridge`**, que copia el estilo de `engine/opus/.../NativeBridge.kt`:
    - `fun load(modelDir: String, threads: Int): Long`: 0 = error;
    - `fun translate(handle: Long, sentences: Array<String>): Array<String>`;
    - `fun release(handle: Long)`;
    - topes en `companion`: `MAX_THREADS = 4`, `MAX_BATCH`, `MAX_SENTENCE_CHARS` (mismos valores que OPUS).
  - **`SlimtNativeBridge`**: `object` que hace `System.loadLibrary("slimtbridge")`.
  - **El puente C++:**
    - **Archivos del modelo:** los busca **por nombre fijo** dentro de `modelDir` (los nombres que dé la Tarea 1) y nunca acepta rutas de fuera.
    - **Errores y logs:** atrapa toda excepción C++ y devuelve 0 o lanza una `RuntimeException` Java con mensaje fijo. Nunca escribe texto en el log.
    - **Tamaños:** comprueba los tamaños de entrada.
- **`.so` en el APK:** el CI comprueba `lib/arm64-v8a/libslimtbridge.so` en el APK release, además de `libct2bridge.so`.

- [ ] **Step 1:** Submódulos con commit fijo y `init-submodules.sh` actualizado. Verificar que `tools/native/init-submodules.sh` deja el árbol listo desde cero.
- [ ] **Step 2:** `native/slimtbridge/CMakeLists.txt` que compila slimt como biblioteca estática y el puente como `SHARED`, con las opciones de la Tarea 1. `NOTICE` con las licencias:
  - slimt (GPL-2.0-or-later);
  - partes MPL-2.0 de bergamot/marian;
  - Apache-2.0 de ssplit y ruy;
  - sentencepiece, si aplica.
- [ ] **Step 3:** `slimtbridge.cpp`, con funciones JNI `Java_io_github_diegobr4nd_lectorbilingue_engine_firefox_SlimtNativeBridge_*`, siguiendo el patrón de `native/ct2bridge/ct2bridge.cpp`: handle opaco, `try/catch` en cada entrada, `GetStringUTFChars` liberado siempre y sin logs del contenido.
- [ ] **Step 4:** `engine/firefox/build.gradle.kts`, copiado de `engine/opus/build.gradle.kts` con:
  - `namespace = "io.github.diegobr4nd.lectorbilingue.engine.firefox"`;
  - `targets += "slimtbridge"`;
  - `path = file("../../native/slimtbridge/CMakeLists.txt")`.

  Dependencias: `api(project(":engine:api"))` y coroutines. `:app` → `implementation(project(":engine:firefox"))`.
- [ ] **Step 5:** `./gradlew :engine:firefox:assembleRelease assembleFdroidRelease`, y luego `unzip -l app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk | grep libslimtbridge.so`. Anotar en el reporte el tamaño del APK antes y después.
- [ ] **Step 6:** CI: en `ci.yml`, el paso "El APK release trae el motor arm64" comprueba los dos `.so`:
```yaml
      - name: El APK release trae los motores arm64
        run: |
          apk=app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
          unzip -l "$apk" | grep -q "lib/arm64-v8a/libct2bridge.so"
          unzip -l "$apk" | grep -q "lib/arm64-v8a/libslimtbridge.so"
```
- [ ] **Step 7:** `./gradlew lint test assembleFdroidDebug assembleFdroidRelease` → BUILD SUCCESSFUL. **Commit** `build(engine): slimt nativo y módulo :engine:firefox`.

---

### Task 6: `FirefoxEngine` (agente `estructura`)

**Files:**
- Create: `engine/firefox/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/firefox/FirefoxEngine.kt`, `engine/firefox/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/engine/firefox/FakeNativeBridge.kt`, `FirefoxEngineTest.kt`, `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/FirefoxOnDeviceTest.kt`

**Interfaces:**
- Consumes: `NativeBridge`/`SlimtNativeBridge` (Tarea 5); `TranslationEngine`, `EngineConfig`, `LanguagePair`, `EngineId`, `ModelNotInstalledException` (Tarea 2).
- Produces: `class FirefoxEngine(private val modelDir: (LanguagePair) -> File?, bridge: NativeBridge = SlimtNativeBridge, dispatcher: CoroutineDispatcher = Dispatchers.Default) : TranslationEngine`, con:
  - `id = EngineId.FIREFOX.wire`;
  - el mismo candado y el mismo contador de motores cargados que `OpusEngine`;
  - `beamSize` validado (≥ 1) pero ignorado si slimt solo hace búsqueda voraz, según el informe de la Tarea 1 (KDoc).

- [ ] **Step 1: Pruebas que fallan** (`FirefoxEngineTest`, una por cada prueba de `OpusEngineTest` que aplique, más):
  - `null` → `ModelNotInstalledException(FIREFOX, pair)` sin llamar al puente;
  - `threads > 4` → `IllegalArgumentException`;
  - lista vacía → lista vacía sin tocar el puente;
  - oración > `MAX_SENTENCE_CHARS` → `IllegalArgumentException`, sin incluir el texto en el mensaje;
  - lotes > `MAX_BATCH` se parten y el orden se conserva;
  - `load` dos veces libera el handle anterior;
  - `unload` libera;
  - el puente devuelve 0 → `IllegalStateException` con mensaje fijo;
  - dos `translate` concurrentes nunca llaman al puente a la vez (el fake registra solapes).
- [ ] **Step 2:** `./gradlew :engine:firefox:testDebugUnitTest` → FALLAN.
- [ ] **Step 3: Implementación**, copiando la estructura de `OpusEngine` (Tarea 4), sin código compartido nuevo: dos motores pequeños y parecidos se leen mejor que una abstracción prematura.
- [ ] **Step 4:** `FirefoxOnDeviceTest`:
  - obtiene la carpeta con `Models.installedDir(ctx, "firefox", "en-es")` y hace `assumeTrue(dir != null)`;
  - carga el modelo;
  - traduce `"The old library was quiet."`;
  - comprueba que el resultado no está vacío y es distinto de la entrada;
  - no registra el texto.
- [ ] **Step 5:** `./gradlew lint test assembleFdroidDebug` → verde. **Commit** `feat(engine): motor Firefox con slimt`.

---

### Task 7: Flujos de modelos y guía (agente `infraestructura`)

**Files:**
- Create: `.github/workflows/firefox-model.yml`, `tools/models/fetch_firefox.py`, `tools/models/test_fetch_firefox.py`, `tools/models/firefox_sources.json` (URL, versión y huellas por par, según la Tarea 1)
- Modify: `.github/workflows/model.yml` y `tools/models/convert_opus.py` (par como parámetro), `tools/models/test_convert_opus.py`, `docs/catalogo.md`, `.github/workflows/ci.yml` (correr `test_fetch_firefox`)

**Interfaces:**
- Consumes: el informe de la Tarea 1 (URL oficial, archivos, huellas).
- Produces:
  - **`python tools/models/fetch_firefox.py --pair en-es --out out`:**
    - descarga los archivos listados en `firefox_sources.json`, solo por HTTPS y desde los hosts de Mozilla anotados allí;
    - verifica cada SHA-256 contra `firefox_sources.json` y falla si alguno no coincide;
    - escribe en `out/firefox-en-es/` los archivos sin modificar, más `LICENSE` (texto MPL-2.0), `ATTRIBUTION.txt` y `MODEL_CARD.md` (origen, versión, fecha, licencia y aviso de calidad 3,28/5) y `SHA256SUMS`;
    - sin dependencias fuera de stdlib.
  - **`firefox-model.yml`:** `workflow_dispatch` con entrada `pair` (`en-es` | `es-en`), `permissions: contents: read`, acciones fijadas por SHA (copiar las de `model.yml`); sube el artefacto `firefox-<pair>`.
  - **`model.yml`:** entrada `pair` (por defecto `en-es`) → `convert_opus.py --pair <pair>` convierte `Helsinki-NLP/opus-mt-tc-big-<pair>`; artefacto `modelo-<pair>`. Las huellas de `requirements.txt` no cambian.
  - **`docs/catalogo.md`:** sección nueva, "Publicar modelos de Firefox y de otro par":
    - pasos exactos en PowerShell y Git Bash para correr cada flujo y bajar el artefacto;
    - releases `firefox-en-es-v1`, `firefox-es-en-v1` y `opus-es-en-v1`;
    - `build_catalog.py --engine firefox …`, firmar y publicar;
    - ids sugeridos: `firefox-en-es-<versión Mozilla>`, `opus-es-en-tcbig-<fecha>`.

- [ ] **Step 1: Pruebas que fallan** (`test_fetch_firefox.py`, con un servidor HTTP local y un `firefox_sources.json` de prueba):
  - descarga correcta → archivos y `SHA256SUMS` correctos;
  - huella distinta → error y sin carpeta de salida;
  - host fuera de la lista del JSON → error sin conectar;
  - par inválido (`../x`) → error.
- [ ] **Step 2:** Implementar `fetch_firefox.py`. `python -m unittest -v test_fetch_firefox` → verde.
- [ ] **Step 3:** `convert_opus.py --pair`: prueba nueva de que `es-en` arma el id `Helsinki-NLP/opus-mt-tc-big-es-en`, y que un par inválido falla. Verde.
- [ ] **Step 4:** Flujos YAML. Validar la sintaxis con PyYAML en un venv del scratchpad.
- [ ] **Step 5:** `docs/catalogo.md`. Agregar `(cd tools/models && python -m unittest -v test_fetch_firefox)` al paso de pruebas Python de `ci.yml`.
- [ ] **Step 6: Commit** `ci(models): flujo de modelos Firefox y OPUS por par; guía`.

---

### Task 8: Pantalla de prueba con motor, par y lista de modelos (agente `estructura`)

**Files:**
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/enginetest/EngineTestViewModel.kt`, `EngineTestScreen.kt`, `ModelActions.kt`, `app/src/main/res/values/strings.xml`
- Create (si ayuda a separar): `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/enginetest/EnginePicker.kt` (lógica pura: estado del interruptor más `EngineSelector` → qué cargar y qué mensaje mostrar)
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/enginetest/EnginePickerTest.kt`, `ModelActionsTest.kt`

**Interfaces:**
- Consumes:
  - `EngineSelector`, `EngineChoice`, `Reason` y `EngineId` (Tarea 2);
  - `Models.installedDir`, `Models.deleteModel(engine, pair)`, `ModelStore.installed()` (Tarea 3);
  - `OpusEngine` y `FirefoxEngine` (Tareas 4 y 6);
  - la API de descargas de la 2b (`Models.enqueueDownload`, `downloadInfo`, `cancelDownload`, `importModel`).
- Produces: la pantalla de la spec §8.
  - **Interruptor:** `Automático | OPUS | Firefox`, recordado solo en memoria.
  - **Selector de par:** `en → es | es → en`.
  - **Motor en uso:** una línea con el motor y su motivo (`Reason` → `strings.xml`). La RAM se muestra en GB redondeados.
  - **Lista de modelos del catálogo** del par elegido:
    - cada fila con el motor, el tamaño en MB y el estado;
    - Descargar, Cancelar o Borrar en cada fila;
    - el estado de cada fila sale de `downloadInfo(modelId)`.
  - **Al cambiar motor o par:** `unload()` del motor anterior y carga del nuevo (uno solo cargado a la vez).
  - **`Missing(engine)`:** un mensaje fijo que ofrece descargar ese modelo.
  - **Error al cargar en Automático con otro motor instalado:** un mensaje con un botón "Usar <otro motor>", que fija el interruptor. Nunca cambia solo.
  - **Benchmark:** el de hoy, con el motor y el par cargados. Para `es-en`, se usan los textos privados solo si existe `bench/textos-es.txt`; si no, se muestra "No hay textos de prueba para es → en".
  - **Traducir:** igual que hoy.

- [ ] **Step 1: Pruebas que fallan** (`EnginePickerTest`, puro):
  - Automático con 8 GB y los dos motores → OPUS (RAM);
  - forzado Firefox sin instalar → `Missing(FIREFOX)` y mensaje "descarga Firefox";
  - error al cargar OPUS en Automático con Firefox instalado → se ofrece "Usar Firefox";
  - error al cargar en modo forzado → solo el error, sin oferta;
  - RAM 3,9 GB → Firefox;
  - el texto de RAM redondea `7.6 GiB` a "8 GB".

  En `ModelActionsTest`, la selección de modelos por par devuelve las dos filas, OPUS y Firefox, ordenadas.
- [ ] **Step 2:** `./gradlew :app:testFdroidDebugUnitTest` → FALLAN.
- [ ] **Step 3: Implementación.**
  - Lógica pura en `EnginePicker`; el ViewModel solo la conecta.
  - RAM: `(getSystemService(ActivityManager::class.java)).getMemoryInfo(mi); mi.totalMem`.
  - Todos los textos en `strings.xml`, en español: cumplir 48 dp, `liveRegion` y no depender del color.
- [ ] **Step 4:** `./gradlew lint test assembleFdroidDebug` → verde.
- [ ] **Step 5:** Si el Pixel está conectado:
  - `installFdroidDebug`;
  - captura de la pantalla en Automático (debe decir OPUS por RAM y que el modelo OPUS `en-es` está, ya migrado);
  - no tocar Descargar ni Borrar.

  Si no está conectado, anotarlo.
- [ ] **Step 6: Commit** `feat(app): elegir motor y par en la pantalla de prueba`.

---

### Task 9: Revisiones (agentes `seguridad` y `diseno`, solo lectura)

- [ ] **Step 1: `seguridad`.** Revisa:
  - **`native/slimtbridge`:** entradas JNI, tamaños, excepciones C++, rutas solo dentro de `modelDir`, nada de logs.
  - **Submódulos y licencias:** NOTICE, compatibilidad GPL-3.0 y ausencia de MuPDF.
  - **`installedDir` y migración:** enlaces, carpetas a medias, otro motor.
  - **`fetch_firefox.py` y los flujos:** hosts, huellas, `permissions`, acciones fijadas.
  - **`model.yml` con parámetro:** inyección en `run:`; usar `env:` y no `${{ inputs.pair }}` directo en shell.
  - **Permisos:** el manifest no cambia.
  - **La pantalla:** sin `e.message` ni texto en logs.
- [ ] **Step 2: `diseno`.** Revisa la pantalla de la Tarea 8: estados, textos, accesibilidad y claridad del motivo del motor.
- [ ] **Step 3:**
  - Hallazgos críticos y altos → los arregla el dueño, con re-revisión.
  - Medios y bajos baratos ligados a reglas → un lote.
  - El resto → lista para Juan y memoria de pendientes.

---

### Task 10: Puerta 2c con Juan (sesión principal)

- [ ] **Step 1:** Juan corre `firefox-model.yml` (`en-es`, `es-en`) y `model.yml` (`es-en`), comprueba las huellas, sube los releases, arma el catálogo con 4 modelos, lo firma y lo publica en `catalogo`. El controlador verifica igual que en la 2b:
  - firma con la llave actual;
  - `validate_catalog`;
  - descarga real de cada archivo con tamaño y SHA-256;
  - hosts de redirección.
- [ ] **Step 2:** El controlador (o `estructura`) reemplaza `app/src/main/assets/catalog/*` y `models/src/test/resources/catalog-prod/*` con el catálogo nuevo, byte a byte, y actualiza `ProductionCatalogTest` (4 modelos, ids nuevos). `./gradlew lint test assembleFdroidDebug assembleFdroidRelease` → verde. **Commit** `feat(models): catálogo con modelos Firefox y OPUS es-en`.
- [ ] **Step 3 (Pixel, con permiso de Juan para cada paso que borre o descargue):**
  1. `installFdroidDebug`, sin desinstalar. Al abrir, `files/models/en-es/` pasa a `files/models/opus/en-es/` (comprobar con `run-as ls`), y Automático dice "OPUS (8 GB)" y traduce.
  2. Forzar Firefox → `en-es` → Descargar → listo → benchmark: **≥ 15 palabras/s, mediana < 2000 ms**. Captura.
  3. `es-en`: descargar OPUS y Firefox, traducir una oración propia con cada uno. Benchmark si hay textos `es`.
  4. Borrar Firefox `en-es` desde la lista → OPUS `en-es` intacto (`run-as ls`).
  5. `FirefoxOnDeviceTest` y `OpusOnDeviceTest` con `am instrument`.
- [ ] **Step 4:** Revisión final de la rama (modelo más capaz), arreglos en una sola ronda, push con permiso de Juan, PR con el texto preparado, CI en verde, y Juan fusiona.
