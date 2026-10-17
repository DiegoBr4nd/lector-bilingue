# 6a · Listos para la tienda — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** «Acerca de», «Licencias» y «Enviar comentarios» en la app, fuentes sin duplicar y lint con 0 avisos, sin tocar la lectura ni la traducción.

**Architecture:** Dos pantallas nuevas en la navegación de la app (Navigation 3, `Route`), alimentadas por un catálogo de licencias que es un JSON en `assets/licencias/`: una parte generada por el plugin AboutLibraries (solo al compilar, guardada en el repo y vigilada por el CI) y otra escrita a mano (app, código nativo, fuentes, íconos). El correo de comentarios se arma con una función pura y se abre con un intent `mailto:`. Las fuentes de la interfaz pasan a leerse de los mismos assets que Readium sirve al libro.

**Tech Stack:** Kotlin 2.4.21, Jetpack Compose M3, Navigation 3, AGP 9.4.1, Gradle 9.8.1, plugin `com.mikepenz.aboutlibraries.plugin` 15.3.0 (solo build), org.json, JUnit 4 + kotlin.test, Compose UI test en el Pixel.

**Spec:** `docs/superpowers/specs/2026-10-17-listos-tienda-6a-design.md`

## Global Constraints

- Responder y comentar el código en español, como el resto del repo. Reglas de `CLAUDE.md` siempre vigentes.
- **Prohibido:** Firebase, Play Services, AdMob, Crashlytics, analítica, cualquier dependencia no libre. AboutLibraries entra **solo como plugin de Gradle**: ninguna librería suya en el APK.
- Nunca registrar (log) ni enviar texto de libros o traducciones. El correo de comentarios lleva **solo**: versión de la app, flavor, versión de Android, fabricante y modelo.
- Sin permisos nuevos (el CI tiene la lista exacta).
- La compilación no usa red para licencias (`offlineMode = true`). El JSON generado no lleva fechas ni rutas locales.
- `FEEDBACK_EMAIL` = la dirección que dé Juan (el controlador la pone en el brief de la Tarea 4 antes de despacharla). **Nunca** el correo personal de Juan.
- URL del código: `https://github.com/DiegoBr4nd/lector-bilingue`. Modelos: `https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-es`, `https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-cat_oci_spa-en`, `https://github.com/mozilla/translations`.
- Botones ≥ 48 dp; títulos de sección con `heading()`; todo legible con letra al 200 %.
- Pruebas en el Pixel: **nunca** `connectedAndroidTest`, `pm clear` ni desinstalar. Antes de instalar o correr, comprobar en un comando aparte qué app está al frente (`adb shell dumpsys activity activities | grep -m1 topResumedActivity`); si es la nuestra, preguntar. `MSYS_NO_PATHCONV=1` con `adb`. Nunca `adb logcat -c`.
- Comandos con el JBR: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` delante de `./gradlew` en el Windows de Juan.
- Commits con la línea `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Una librería nueva sin licencia en la lista** (alguien añade una dependencia y no regenera): el CI debe fallar → paso del CI en la Tarea 3.
2. **Fuente que cae en silencio a la del sistema** (ruta de asset mal escrita, `install` no llamado): el texto debe verse con Literata/Inter/Atkinson de verdad → `FontsOnDeviceTest` compara píxeles con la fuente leída directo del asset y con la del sistema (Tarea 2).
3. **Teléfono sin app de correo**: no se cierra la app y la dirección queda a la vista para copiarla → prueba en `AboutOnDeviceTest` con intent que falla (Tarea 5) y `startSafely` ya probado.
4. **Caracteres especiales en el correo** («·», «ü», saltos de línea, `&`, `+` en el modelo del teléfono): el asunto y el cuerpo llegan íntegros → `FeedbackTest` de codificación (Tarea 4).
5. **Id de licencia raro en la pila guardada** (`license:` con un id que ya no existe tras actualizar la app): la pantalla muestra el error y atrás funciona → `LicensesTest`/`RoutesTest` y estado `Broken` en la Tarea 5.

---

## Mapa de archivos

| Archivo | Responsabilidad | Tarea |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew*` | Gradle 9.8.1 | 1 |
| `gradle/libs.versions.toml` | Kotlin 2.4.21; plugin AboutLibraries | 1, 3 |
| `gradle/verification-metadata.xml` | Huellas de las dependencias nuevas | 1, 3 |
| `lint.xml` (raíz, nuevo) | Ignorar `ChromeOsAbiSupport` con motivo | 1 |
| `app/src/main/kotlin/.../LectorApp.kt` | `Bitmap.scale` (UseKtx); instalar fuentes | 1, 2 |
| `books/src/main/kotlin/.../books/BookImporter.kt` | `@SuppressLint("UsableSpace")` con motivo | 1 |
| `app/src/debug/kotlin/.../ui/enginetest/EngineTestViewModel.kt`, `EngineTestScreen.kt` | Recycle, ModifierParameter | 1 |
| `app/src/debug/res/values/strings.xml`, `app/src/main/res/values/strings.xml`, `core/ui/src/main/res/values/strings.xml` | String sin uso, `Typos`, `PluralsCandidate` | 1 |
| `core/ui/src/main/assets/lector/fuentes/` (movido desde `app/src/main/assets/lector/fuentes/`) | Única copia de las fuentes + OFL | 2 |
| `core/ui/src/main/kotlin/.../core/ui/theme/Fonts.kt` (nuevo) | `LectorFonts`: fuentes desde assets | 2 |
| `core/ui/.../theme/Type.kt`, `Theme.kt` | Familias y tipografía vía `LectorFonts` | 2 |
| `app/src/main/kotlin/.../ui/reader/ReaderActivity.kt` | Usar `FONTS_DIR` | 2 |
| `app/src/androidTest/kotlin/.../FontsOnDeviceTest.kt` (nuevo) | Fuentes reales, no las del sistema | 2 |
| `docs/licencias/fuentes.md` | Nueva ubicación, sin duplicado | 2 |
| `app/build.gradle.kts`, `build.gradle.kts` | Plugin AboutLibraries; `buildConfig` | 3, 4 |
| `app/src/main/assets/licencias/librerias.json` (generado, en el repo) | Librerías de Gradle | 3 |
| `app/src/main/assets/licencias/extra.json` (nuevo) | App, nativo, fuentes, íconos, desugar | 3 |
| `app/src/main/assets/licencias/textos/*.txt`, `avisos/*.txt` | Textos de licencia y avisos de copyright | 3 |
| `app/src/main/kotlin/.../data/Licenses.kt` (nuevo) | Modelo + lectura del catálogo | 3 |
| `app/src/test/kotlin/.../data/LicensesTest.kt` (nuevo) | El catálogo real está completo | 3 |
| `.github/workflows/ci.yml` | Lista de licencias al día | 3 |
| `docs/licencias/textos.md` (nuevo), `docs/build.md`, `CLAUDE.md` | Origen de los textos; comando de regenerar | 3 |
| `app/src/main/kotlin/.../ui/about/Feedback.kt` (nuevo) | Correo de comentarios (puro + intent) | 4 |
| `app/src/test/kotlin/.../ui/about/FeedbackTest.kt` (nuevo) | Contenido y codificación del correo | 4 |
| `app/src/main/kotlin/.../ui/about/AboutScreen.kt`, `LicensesScreen.kt`, `AboutPreviews.kt` (nuevos) | Pantallas | 5 |
| `app/src/main/kotlin/.../ui/nav/Routes.kt`, `AppNav.kt`, `MainActivity.kt`, `ui/library/LibraryScreen.kt` | Rutas y entrada del menú | 5 |
| `core/ui/src/main/res/drawable/ic_info.xml`, `.../components/Icons.kt` | Ícono «Acerca de» | 5 |
| `app/src/test/kotlin/.../ui/nav/RoutesTest.kt`, `app/src/androidTest/kotlin/.../AboutOnDeviceTest.kt` (nuevo), `LanguagesOnDeviceTest.kt`, `WelcomeOnDeviceTest.kt` | Pruebas | 5 |

`...` = `io/github/diegobr4nd/lectorbilingue`.

---

### Task 1: Lint en 0 avisos (Gradle 9.8.1, Kotlin 2.4.21 y correcciones)

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar` (si cambia), `gradlew`, `gradlew.bat` (si cambian)
- Modify: `gradle/libs.versions.toml:3`
- Modify: `gradle/verification-metadata.xml`
- Create: `lint.xml`
- Modify: `app/src/main/kotlin/.../LectorApp.kt:84`
- Modify: `books/src/main/kotlin/.../books/BookImporter.kt:26`
- Modify: `app/src/debug/kotlin/.../ui/enginetest/EngineTestViewModel.kt:432-434`
- Modify: `app/src/debug/kotlin/.../ui/enginetest/EngineTestScreen.kt:~105-115`
- Modify: `app/src/debug/res/values/strings.xml:22,47`, `app/src/main/res/values/strings.xml:144`, `core/ui/src/main/res/values/strings.xml:14`

**Interfaces:**
- Consumes: nada.
- Produces: Gradle 9.8.1 y Kotlin 2.4.21 para las tareas siguientes; `lint.xml` en la raíz.

Los 13 avisos de partida (`app/build/reports/lint-results-fdroidDebug.txt`, 2026-10-15): Gradle 9.8.1 (1), Kotlin 2.4.21 (2), ModifierParameter debug (1), Typos «momento» (2), PluralsCandidate (1), ChromeOsAbiSupport (2), Recycle debug (1), UnusedResources debug (1), UsableSpace (1), UseKtx (1).

- [ ] **Step 1: Gradle 9.8.1 con su suma oficial**

Run (dos veces: la segunda actualiza `gradle-wrapper.jar` con la versión nueva):
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew wrapper --gradle-version 9.8.1 --gradle-distribution-sha256-sum dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew wrapper --gradle-version 9.8.1 --gradle-distribution-sha256-sum dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f
```
Expected: `gradle-wrapper.properties` con `gradle-9.8.1-bin.zip` y `distributionSha256Sum=dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`. Las demás líneas (`networkTimeout`, `retries`, `validateDistributionUrl`…) se conservan; si el comando las borró, se restauran a mano. (La suma es la publicada en `https://services.gradle.org/distributions/gradle-9.8.1-bin.zip.sha256`; la de 9.8.0 en el mismo sitio coincide con la que ya teníamos.)

- [ ] **Step 2: Kotlin 2.4.21**

En `gradle/libs.versions.toml` línea 3:
```toml
kotlin = "2.4.21"
```

- [ ] **Step 3: Regenerar las huellas de dependencias**

Run (comando de `docs/build.md`):
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks resolveAapt2AllPlatforms lint test assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest
```
Expected: BUILD SUCCESSFUL. En `git diff --stat gradle/verification-metadata.xml` solo deben aparecer componentes `org.jetbrains.kotlin:*:2.4.21` (y sus módulos `kotlin-*`). Listar en el reporte los grupos nuevos. Si aparece algo fuera de `org.jetbrains.kotlin`, **BLOCKED** con la lista.

- [ ] **Step 4: Correcciones de código**

`LectorApp.kt` (UseKtx). Añadir `import androidx.core.graphics.scale` y cambiar la línea 84:
```kotlin
        val small = if (scale < 1f) bitmap.scale((bitmap.width * scale).toInt().coerceAtLeast(1), 480) else bitmap
```
(`scale` de core-ktx usa `filter = true` por defecto, igual que antes.)

`BookImporter.kt` (UsableSpace). Añadir `import android.annotation.SuppressLint` y sobre `suspend fun import(`:
```kotlin
    // usableSpace solo clasifica un fallo que ya ocurrió (¿fue por falta de espacio?): no reserva espacio.
    // getAllocatableBytes contaría cachés que Android podría liberar y diría "hay espacio" justo cuando la copia falló por falta de él.
    @SuppressLint("UsableSpace")
```

`EngineTestViewModel.kt` (Recycle). Reemplazar:
```kotlin
                    val input = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri) }
                        ?: throw IOException()
                    input.use { Models.importModel(app, it) }
```
por:
```kotlin
                    withContext(Dispatchers.IO) {
                        (app.contentResolver.openInputStream(uri) ?: throw IOException()).use { Models.importModel(app, it) }
                    }
```
(Si `Models.importModel` no es `suspend`, ahora además corre fuera del hilo principal, que es lo correcto. Anotarlo en el reporte.)

`EngineTestScreen.kt` (ModifierParameter): mover `modifier: Modifier = Modifier,` para que sea el **primer** parámetro con valor por defecto (justo después de `onBenchmark: () -> Unit,`). Compilar: si alguna llamada pasaba argumentos por posición después de `onBenchmark`, cambiarla a argumentos con nombre.

`app/src/debug/res/values/strings.xml`: borrar la línea `<string name="cancel_download_button">Cancelar descarga</string>` (comprobar antes con `grep -rn cancel_download_button app core --include=*.kt --include=*.xml | grep -v /build/` que solo aparece ahí). En la línea 22, y en `app/src/main/res/values/strings.xml` línea 144, añadir `tools:ignore="Typos"` al `<string>` con un comentario XML encima:
```xml
    <!-- «momento» está bien escrito en español; el corrector de lint lo confunde con el inglés "memento". -->
    <string name="languages_working" tools:ignore="Typos">Un momento…</string>
```
Asegurar `xmlns:tools="http://schemas.android.com/tools"` en el `<resources>` de cada archivo (el de `main` ya lo tiene).

`core/ui/src/main/res/values/strings.xml` línea 14:
```xml
    <!-- «por ciento» no cambia con el número: no hace falta plural. -->
    <string name="download_progress_description_percent" tools:ignore="PluralsCandidate">Descarga en curso, %1$d por ciento</string>
```
(con `xmlns:tools` en `<resources>`).

Crear `lint.xml` en la raíz del repo:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<lint>
    <!-- Los motores nativos se compilan solo para arm64-v8a (teléfonos). Los Chromebook x86 no están en el
         lanzamiento; añadir x86_64 duplicaría ~20 MB de .so. Se revisa si algún día se publica para ChromeOS. -->
    <issue id="ChromeOsAbiSupport" severity="ignore" />
</lint>
```
Si con `checkDependencies = true` lint no toma el `lint.xml` de la raíz para `engine/opus` y `engine/firefox`, poner el mismo archivo en `engine/opus/lint.xml` y `engine/firefox/lint.xml` y borrar el de la raíz.

- [ ] **Step 5: Lint en 0**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew lint :app:lintPlayDebug
tail -1 app/build/reports/lint-results-fdroidDebug.txt app/build/reports/lint-results-playDebug.txt
```
Expected: `No issues found.` (o `0 errors, 0 warnings`) en ambos. Si aparece un aviso `NewerVersionAvailable`/`GradleDependency` de una versión publicada **después** del 2026-10-17, no se persigue: se anota en el reporte como DONE_WITH_CONCERNS.

- [ ] **Step 6: Pruebas y compilación**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test assembleFdroidRelease assemblePlayRelease
```
Expected: BUILD SUCCESSFUL; mismo número de pruebas que antes (447 en la 3c-3), todas verdes.

- [ ] **Step 7: Commit**

```bash
git add -A gradle gradlew gradlew.bat lint.xml app/src books/src core/ui/src
git commit -m "chore(6a): lint en 0 avisos; Gradle 9.8.1 y Kotlin 2.4.21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Fuentes sin duplicar

**Files:**
- Move: `app/src/main/assets/lector/fuentes/` → `core/ui/src/main/assets/lector/fuentes/` (los 7 `.ttf` y los 3 `OFL-*.txt`)
- Delete: `core/ui/src/main/res/font/` (7 `.ttf`), `core/ui/licencias/` (3 `OFL-*.txt`, copias idénticas)
- Create: `core/ui/src/main/kotlin/.../core/ui/theme/Fonts.kt`
- Modify: `core/ui/src/main/kotlin/.../core/ui/theme/Type.kt`, `Theme.kt`
- Modify: `app/src/main/kotlin/.../LectorApp.kt` (`onCreate`)
- Modify: `app/src/main/kotlin/.../ui/reader/ReaderActivity.kt:135`
- Modify: `docs/licencias/fuentes.md`
- Test: `app/src/androidTest/kotlin/.../FontsOnDeviceTest.kt`

**Interfaces:**
- Consumes: nada de otras tareas.
- Produces: `const val FONTS_DIR = "lector/fuentes/"` y `object LectorFonts { fun install(assets: AssetManager) }` en `io.github.diegobr4nd.lectorbilingue.core.ui.theme`. `InterFamily`, `ReadingFontFamily`, `AtkinsonFamily` y `LectorTypography` conservan nombre y tipo (pasan a ser propiedades con getter). La Tarea 3 enlaza los OFL en `lector/fuentes/OFL-*.txt`.

¿Por qué mover a `core/ui`? Las fuentes son del tema (core/ui). Los assets de un módulo de librería se fusionan en los assets del APK con la misma ruta, así que Readium sigue sirviendo `lector/fuentes/…` sin cambios.

- [ ] **Step 0: Medir el APK antes**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleFdroidRelease && ls -l app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
```
Anotar el tamaño en bytes en el reporte.

- [ ] **Step 1: Escribir la prueba en el teléfono (falla hasta el Step 4)**

`app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/FontsOnDeviceTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue

import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.AtkinsonFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.FONTS_DIR
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.InterFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Las fuentes de la interfaz salen de assets (una sola copia en el APK) y se ven igual que el archivo original:
 * se compara el dibujo con la misma fuente leída directo del asset, y se comprueba que no es la del sistema.
 */
@RunWith(AndroidJUnit4::class)
class FontsOnDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val sample = "Hamburgefonstiv ñ ü 0123"

    private fun ImageBitmap.pixels(): IntArray {
        val b = asAndroidBitmap()
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    private fun shot(tag: String) = compose.onNodeWithTag(tag).captureToImage().pixels()

    @Test fun lasFuentesDeLaInterfazSonLasDeAssets() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val cases = listOf(
            "literata" to "literata_regular.ttf",
            "atkinson" to "atkinson_hyperlegible_regular.ttf",
            "inter" to "inter_variable.ttf",
        )
        compose.setContent {
            LectorTheme {
                Column {
                    for ((name, file) in cases) {
                        val family = when (name) {
                            "literata" -> ReadingFontFamily
                            "atkinson" -> AtkinsonFamily
                            else -> InterFamily
                        }
                        Text(sample, fontFamily = family, fontSize = 24.sp, modifier = Modifier.testTag("$name-app"))
                        Text(
                            sample,
                            fontFamily = FontFamily(Typeface.createFromAsset(assets, FONTS_DIR + file)),
                            fontSize = 24.sp,
                            modifier = Modifier.testTag("$name-ref"),
                        )
                    }
                    Text(sample, fontFamily = FontFamily.SansSerif, fontSize = 24.sp, modifier = Modifier.testTag("system"))
                }
            }
        }
        val system = shot("system")
        for ((name) in cases) {
            val app = shot("$name-app")
            assertTrue(app.contentEquals(shot("$name-ref")), "$name no coincide con su archivo en assets")
            assertFalse(app.contentEquals(system), "$name se ve con la fuente del sistema")
        }
    }
}
```

Si solo falla `inter` por el peso por defecto del archivo variable, la referencia pasa a ser
`Typeface.Builder(assets, FONTS_DIR + "inter_variable.ttf").setFontVariationSettings("'wght' 400").build()` (misma
fuente, eje fijado como en la app); no se quita el caso.

- [ ] **Step 2: Mover los assets y borrar las copias**

Run:
```bash
mkdir -p core/ui/src/main/assets/lector
git mv app/src/main/assets/lector/fuentes core/ui/src/main/assets/lector/fuentes
git rm -r core/ui/src/main/res/font core/ui/licencias
```
Si `app/src/main/assets/lector/` queda vacío, no pasa nada (git no guarda carpetas vacías). Comprobar que `app/src/main/assets/lector/` no tenía nada más que `fuentes/` que dependa de esa carpeta: `ls app/src/main/assets/lector`.

- [ ] **Step 3: `Fonts.kt`**

`core/ui/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/core/ui/theme/Fonts.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import android.content.res.AssetManager
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Carpeta de assets con las fuentes: la misma que Readium sirve al libro. Una sola copia en el APK. */
const val FONTS_DIR = "lector/fuentes/"

/**
 * Las fuentes de la interfaz, leídas de assets (antes había una segunda copia en res/font: ~1 MB más).
 * [install] se llama al arrancar la app y al empezar [LectorTheme] (también en previews y pruebas); la primera gana.
 * Las familias se crean la primera vez que se piden.
 */
object LectorFonts {
    @Volatile private var assets: AssetManager? = null

    fun install(assets: AssetManager) {
        if (this.assets == null) this.assets = assets
    }

    private fun assets(): AssetManager = checkNotNull(assets) { "LectorFonts.install() no se llamó antes de usar una fuente" }

    private fun font(file: String, weight: FontWeight, style: FontStyle = FontStyle.Normal): Font =
        Font(FONTS_DIR + file, assets(), weight, style)

    /** Inter variable: un solo archivo; el peso se elige con el eje "wght". */
    @OptIn(ExperimentalTextApi::class)
    val inter: FontFamily by lazy {
        FontFamily(
            listOf(400, 500, 600, 700).map { w ->
                Font(
                    FONTS_DIR + "inter_variable.ttf",
                    assets(),
                    FontWeight(w),
                    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
                )
            },
        )
    }

    val literata: FontFamily by lazy {
        FontFamily(
            font("literata_regular.ttf", FontWeight.Normal),
            font("literata_semibold.ttf", FontWeight.SemiBold),
            font("literata_italic.ttf", FontWeight.Normal, FontStyle.Italic),
        )
    }

    val atkinson: FontFamily by lazy {
        FontFamily(
            font("atkinson_hyperlegible_regular.ttf", FontWeight.Normal),
            font("atkinson_hyperlegible_bold.ttf", FontWeight.Bold),
            font("atkinson_hyperlegible_italic.ttf", FontWeight.Normal, FontStyle.Italic),
        )
    }
}
```
Si la firma `Font(path: String, assetManager: AssetManager, weight, style, variationSettings)` de la versión de Compose del BOM `2026.09.00` pide `@OptIn` distinto, ajustar el opt-in; no cambiar a otra API sin anotarlo en el reporte.

- [ ] **Step 4: `Type.kt`, `Theme.kt`, `LectorApp.kt`, `ReaderActivity.kt`**

`Type.kt`: borrar `import ...core.ui.R`, `ExperimentalTextApi`, `Font`, `FontStyle`, `FontVariation`, `FontWeight` si quedan sin uso, y reemplazar las tres familias y `LectorTypography` por:
```kotlin
/** Interfaz: Inter variable. */
val InterFamily: FontFamily get() = LectorFonts.inter

/** Lectura y títulos grandes: Literata. */
val ReadingFontFamily: FontFamily get() = LectorFonts.literata

/**
 * Atkinson Hyperlegible (Braille Institute, OFL 1.1): letra pensada para baja visión. Aquí solo para mostrarla en la
 * hoja de ajustes; en el libro la sirve Readium desde los mismos assets.
 */
val AtkinsonFamily: FontFamily get() = LectorFonts.atkinson

private fun TextStyle.inter() = copy(fontFamily = InterFamily)

private val typography: Typography by lazy {
    Typography().let { b ->
        Typography(
            displayLarge = b.displayLarge.inter(),
            displayMedium = b.displayMedium.inter(),
            displaySmall = b.displaySmall.inter(),
            headlineLarge = b.headlineLarge.inter(),
            headlineMedium = b.headlineMedium.inter(),
            headlineSmall = b.headlineSmall.inter(),
            titleLarge = b.titleLarge.inter(),
            titleMedium = b.titleMedium.inter(),
            titleSmall = b.titleSmall.inter(),
            bodyLarge = b.bodyLarge.inter(),
            bodyMedium = b.bodyMedium.inter(),
            bodySmall = b.bodySmall.inter(),
            labelLarge = b.labelLarge.inter(),
            labelMedium = b.labelMedium.inter(),
            labelSmall = b.labelSmall.inter(),
        )
    }
}

val LectorTypography: Typography get() = typography
```

`Theme.kt`: añadir `import androidx.compose.ui.platform.LocalContext` y como primera línea de `LectorTheme`:
```kotlin
    LectorFonts.install(LocalContext.current.assets) // antes de leer LectorTypography
```

`LectorApp.kt`, en `onCreate()` justo después de `super.onCreate()`:
```kotlin
        LectorFonts.install(assets) // fuentes de la interfaz desde assets (una sola copia, compartida con el lector)
```
(con `import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorFonts`).

`ReaderActivity.kt:135`: `private const val FONTS = FONTS_DIR` con `import io.github.diegobr4nd.lectorbilingue.core.ui.theme.FONTS_DIR`.

- [ ] **Step 5: Compilar, pruebas JVM y tamaño**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew lint test assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest && ls -l app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk && unzip -l app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk | grep -c "\.ttf"
```
Expected: BUILD SUCCESSFUL, lint en 0, el APK ~1 MB más chico que en el Step 0 y **7** `.ttf` (antes 14). Anotar ambos tamaños. Si una prueba JVM falla con «LectorFonts.install() no se llamó», es una prueba que dibuja tipografía fuera de `LectorTheme`: añadir `LectorFonts.install(...)` en esa prueba, no quitar el `checkNotNull`.

- [ ] **Step 6: Prueba en el Pixel**

Comprobar la app al frente (comando aparte) y luego:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew installFdroidDebug installFdroidDebugAndroidTest
MSYS_NO_PATHCONV=1 adb shell am instrument -w -e class io.github.diegobr4nd.lectorbilingue.FontsOnDeviceTest,io.github.diegobr4nd.lectorbilingue.ReadingSettingsSheetOnDeviceTest io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: `OK`. (`ReadingSettingsSheetOnDeviceTest` muestra las tres fuentes en la hoja Aa.) Si el Pixel no está conectado, DONE_WITH_CONCERNS y el controlador lo corre al final.

- [ ] **Step 7: Documentación**

`docs/licencias/fuentes.md`: cambiar el párrafo de arriba para decir que hay **una sola copia** en `core/ui/src/main/assets/lector/fuentes/` (la usan la interfaz y Readium), con su `OFL-*.txt` al lado; quitar las menciones a `res/font` y a `core/ui/licencias/`; en la tabla de Atkinson quitar la columna «En res/font». Las sumas SHA-256 no cambian (los archivos se movieron, no se tocaron): verificar con `sha256sum core/ui/src/main/assets/lector/fuentes/*`.

- [ ] **Step 8: Commit**

```bash
git add -A app/src core/ui docs/licencias/fuentes.md
git commit -m "refactor(6a): una sola copia de las fuentes (assets de core/ui); la interfaz las lee de ahí

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Catálogo de licencias (AboutLibraries al compilar + archivo a mano)

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/verification-metadata.xml`
- Create: `app/src/main/assets/licencias/librerias.json` (generado)
- Create: `app/src/main/assets/licencias/extra.json`
- Create: `app/src/main/assets/licencias/textos/<clave>.txt` (uno por licencia), `app/src/main/assets/licencias/avisos/<id>.txt`
- Move: `core/ui/ICONOS-LICENSE.txt` → `app/src/main/assets/licencias/avisos/material-symbols.txt`
- Create: `app/src/main/kotlin/.../data/Licenses.kt`
- Test: `app/src/test/kotlin/.../data/LicensesTest.kt`
- Modify: `.github/workflows/ci.yml`, `docs/build.md`, `CLAUDE.md` (sección «Comandos útiles»)
- Create: `docs/licencias/textos.md`

**Interfaces:**
- Consumes: `FONTS_DIR` = `"lector/fuentes/"` (Tarea 2): los OFL están en `lector/fuentes/OFL-Literata.txt`, `OFL-Inter.txt`, `OFL-AtkinsonHyperlegible.txt` dentro de los assets de `core/ui`.
- Produces (paquete `io.github.diegobr4nd.lectorbilingue.data`):
  ```kotlin
  enum class LicenseGroup { APP, LIBRARIES, NATIVE, FONTS_ICONS }
  data class LicenseEntry(val id: String, val name: String, val license: String, val group: LicenseGroup, val texts: List<String>, val url: String?)
  object Licenses {
      const val GENERATED = "licencias/librerias.json"
      const val EXTRA = "licencias/extra.json"
      fun parse(generated: String, extra: String, exists: (String) -> Boolean): List<LicenseEntry>?
      fun load(assets: AssetManager): List<LicenseEntry>?
      fun readTexts(assets: AssetManager, entry: LicenseEntry): String?
  }
  ```
  `parse` devuelve las filas ordenadas: primero `APP`, luego `LIBRARIES`, `NATIVE`, `FONTS_ICONS`; dentro de cada grupo por nombre. JSON roto → `null`.

- [ ] **Step 1: Plugin (solo build)**

`gradle/libs.versions.toml`: en `[versions]` `aboutlibraries = "15.3.0"`; en `[plugins]`:
```toml
aboutlibraries = { id = "com.mikepenz.aboutlibraries.plugin", version.ref = "aboutlibraries" }
```
`build.gradle.kts` (raíz), dentro de `plugins {}`: `alias(libs.plugins.aboutlibraries) apply false`.

`app/build.gradle.kts`: en `plugins {}` `alias(libs.plugins.aboutlibraries)`, y después del bloque `androidComponents {}`:
```kotlin
// Lista de licencias de las librerías (pantalla «Licencias»). Se genera A MANO y se guarda en el repo:
//   ./gradlew :app:exportLibraryDefinitions -PaboutLibraries.exportVariant=playRelease
// El CI la vuelve a generar y falla si cambió (una dependencia nueva sin su licencia en la lista).
// Es el plugin "core" (no el de Android): no se engancha a la compilación ni mete nada en el APK.
aboutLibraries {
    offlineMode = true // sin red al compilar: los textos de licencia van en assets/licencias/textos/
    collect {
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }
    export {
        outputFile = file("src/main/assets/licencias/librerias.json")
        prettyPrint = true
        // Sin fecha de generación ni datos que no mostramos: el archivo sale igual en cualquier máquina.
        excludeFields.addAll("metadata", "description", "developers", "organization", "funding", "scm")
    }
}
```
Los nombres de `excludeFields` deben ser los que acepta 15.3.0: si alguno no existe o la fecha sigue saliendo, ajustar hasta que la prueba `el archivo generado no trae fechas ni rutas locales` pase, y anotar los nombres finales en el reporte.

- [ ] **Step 2: Huellas y generar**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks resolveAapt2AllPlatforms lint test assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest :app:exportLibraryDefinitions -PaboutLibraries.exportVariant=playRelease
```
Expected: BUILD SUCCESSFUL y existe `app/src/main/assets/licencias/librerias.json`. Si la tarea no se llama `exportLibraryDefinitions`, buscarla con `./gradlew :app:tasks --all | grep -i librar` y usar el nombre real en todo el plan (CI, docs). Listar en el reporte los grupos nuevos de `verification-metadata.xml` (esperado: `com.mikepenz.*` y transitivas de build, p. ej. `org.jetbrains.kotlinx:kotlinx-serialization-*`). Comprobar con `./gradlew :app:dependencies --configuration playReleaseRuntimeClasspath | grep -i mikepenz` que **no** sale nada (nada en el APK).

- [ ] **Step 3: Textos de licencia y avisos**

1. Listar las claves de licencia del archivo generado:
   ```bash
   python -c "import json;d=json.load(open('app/src/main/assets/licencias/librerias.json',encoding='utf-8'));print(sorted(d['licenses'].keys()))"
   ```
2. Por cada clave `K`, crear `app/src/main/assets/licencias/textos/K.txt` con el texto oficial de SPDX: `https://raw.githubusercontent.com/spdx/license-list-data/<tag>/text/<spdxId>.txt` (usar la última etiqueta `vX.Y` del repo `spdx/license-list-data` y la misma para todos). Si una clave no es un id SPDX (un hash), abrir su `url` en el JSON y copiar el texto de esa página; anotar el origen.
3. Textos de las entradas a mano (si no existen ya por el punto 2): `GPL-3.0-or-later.txt` = copia exacta de `LICENSE` de la raíz; `GPL-2.0-with-classpath-exception.txt` (SPDX `GPL-2.0-with-classpath-exception`), `MPL-2.0.txt` (copia de `tools/models/licenses/MPL-2.0.txt`), `Apache-2.0.txt`, `MIT.txt`, `BSD-2-Clause.txt`, `BSD-3-Clause.txt`.
4. Avisos de copyright (`avisos/`): cada componente nativo lleva su `LICENSE` del árbol **tal cual** (tiene su línea de copyright):
   | id | Archivo de origen | Licencia |
   |---|---|---|
   | `ctranslate2` | `native/third_party/CTranslate2/LICENSE` | MIT |
   | `ruy` | `native/third_party/CTranslate2/third_party/ruy/LICENSE` | Apache-2.0 |
   | `cpuinfo` | `native/third_party/CTranslate2/third_party/ruy/third_party/cpuinfo/LICENSE` | BSD-2-Clause |
   | `cpu-features` | `native/third_party/CTranslate2/third_party/cpu_features/LICENSE` | Apache-2.0 |
   | `spdlog` | `native/third_party/CTranslate2/third_party/spdlog/LICENSE` | MIT |
   | `abseil` | `native/third_party/abseil-cpp/LICENSE` | Apache-2.0 |
   | `sentencepiece` | `native/third_party/sentencepiece/LICENSE` | Apache-2.0 |
   | `protobuf-lite` | `native/third_party/sentencepiece/third_party/protobuf-lite/LICENSE` | BSD-3-Clause |
   | `darts-clone` | `native/third_party/sentencepiece/third_party/darts_clone/LICENSE` | BSD-2-Clause |
   | `slimt` | `native/third_party/slimt/COPYRIGHT` + línea en blanco + `native/third_party/slimt/LICENSE` | GPL-2.0-or-later |
   | `ssplit` | (sin archivo propio: el aviso está en el COPYRIGHT de slimt) → `textos/Apache-2.0.txt` | Apache-2.0 |
   | `bergamot-marian` | (ídem) → `textos/MPL-2.0.txt` | MPL-2.0 |
   | `material-symbols` | `git mv core/ui/ICONOS-LICENSE.txt app/src/main/assets/licencias/avisos/material-symbols.txt` | Apache-2.0 |

   **Verificar que cada uno se compila de verdad** dentro de `libct2bridge.so` o `libslimtbridge.so`: leer `native/ct2bridge/CMakeLists.txt`, `native/slimtbridge/CMakeLists.txt` y los `CMakeLists.txt` de CTranslate2/SentencePiece que se incluyen (opciones `WITH_RUY=ON`, `SPM_USE_BUILTIN_PROTOBUF=ON`, `SPM_USE_EXTERNAL_ABSL=OFF` en slimt). Ruta real distinta (p. ej. cpuinfo dentro de `slimt/3rd-party/ruy`): usar la que exista. Uno que **no** se compila (p. ej. `cpu-features` si solo se usa en x86, `darts-clone` si solo lo usa el entrenador): se quita de `extra.json` y de la lista de la prueba, y se anota con la evidencia. Otro que **sí** se compila y no está en la tabla (p. ej. `esaxx`, `XHScanner` con licencia propia, la copia de absl de SentencePiece): se añade igual. Si `XHScanner.cc` lleva una cabecera de licencia propia, va como entrada `xhscanner` con esa cabecera como aviso.
5. Librerías de Gradle con licencia MIT o BSD: su texto genérico de SPDX no trae el nombre del autor, así que cada una lleva además su aviso de copyright en `avisos/<uniqueId con ':' cambiado por '_'>.txt` (p. ej. `avisos/org.jsoup_jsoup.txt`), copiado de su `LICENSE` en el repositorio del proyecto (la URL `website` del JSON). La prueba del Step 4 lo exige.

- [ ] **Step 4: `extra.json`**

`app/src/main/assets/licencias/extra.json` (rutas relativas a la raíz de assets; los OFL están en los assets de `core/ui`). Escribir **todas** las filas de la tabla del Step 3 que pasaron la verificación, con este formato:
```json
{
  "version": 1,
  "entries": [
    { "id": "lector-bilingue", "group": "app", "name": "Lector bilingüe", "license": "GPL-3.0-or-later", "texts": ["licencias/textos/GPL-3.0-or-later.txt"], "url": "https://github.com/DiegoBr4nd/lector-bilingue" },
    { "id": "desugar-jdk-libs", "group": "libraries", "name": "desugar_jdk_libs (Google)", "license": "GPL-2.0-with-classpath-exception", "texts": ["licencias/textos/GPL-2.0-with-classpath-exception.txt"], "url": "https://github.com/google/desugar_jdk_libs" },
    { "id": "ctranslate2", "group": "native", "name": "CTranslate2", "license": "MIT", "texts": ["licencias/avisos/ctranslate2.txt"], "url": "https://github.com/OpenNMT/CTranslate2" },
    { "id": "ruy", "group": "native", "name": "ruy (Google)", "license": "Apache-2.0", "texts": ["licencias/avisos/ruy.txt"], "url": "https://github.com/google/ruy" },
    { "id": "cpuinfo", "group": "native", "name": "cpuinfo", "license": "BSD-2-Clause", "texts": ["licencias/avisos/cpuinfo.txt"], "url": "https://github.com/pytorch/cpuinfo" },
    { "id": "cpu-features", "group": "native", "name": "cpu_features (Google)", "license": "Apache-2.0", "texts": ["licencias/avisos/cpu-features.txt"], "url": "https://github.com/google/cpu_features" },
    { "id": "spdlog", "group": "native", "name": "spdlog", "license": "MIT", "texts": ["licencias/avisos/spdlog.txt"], "url": "https://github.com/gabime/spdlog" },
    { "id": "abseil", "group": "native", "name": "Abseil", "license": "Apache-2.0", "texts": ["licencias/avisos/abseil.txt"], "url": "https://github.com/abseil/abseil-cpp" },
    { "id": "sentencepiece", "group": "native", "name": "SentencePiece", "license": "Apache-2.0", "texts": ["licencias/avisos/sentencepiece.txt"], "url": "https://github.com/google/sentencepiece" },
    { "id": "protobuf-lite", "group": "native", "name": "Protocol Buffers lite (en SentencePiece)", "license": "BSD-3-Clause", "texts": ["licencias/avisos/protobuf-lite.txt"], "url": "https://github.com/protocolbuffers/protobuf" },
    { "id": "darts-clone", "group": "native", "name": "darts-clone (en SentencePiece)", "license": "BSD-2-Clause", "texts": ["licencias/avisos/darts-clone.txt"], "url": "https://github.com/s-yata/darts-clone" },
    { "id": "slimt", "group": "native", "name": "slimt", "license": "GPL-2.0-or-later", "texts": ["licencias/avisos/slimt.txt"], "url": "https://github.com/jerinphilip/slimt" },
    { "id": "ssplit", "group": "native", "name": "ssplit-cpp (en slimt)", "license": "Apache-2.0", "texts": ["licencias/textos/Apache-2.0.txt"], "url": "https://github.com/browsermt/ssplit-cpp" },
    { "id": "bergamot-marian", "group": "native", "name": "bergamot-translator y marian-dev (en slimt)", "license": "MPL-2.0", "texts": ["licencias/textos/MPL-2.0.txt"], "url": "https://github.com/browsermt/bergamot-translator" },
    { "id": "literata", "group": "fonts_icons", "name": "Literata (The Literata Project Authors)", "license": "OFL-1.1", "texts": ["lector/fuentes/OFL-Literata.txt"], "url": "https://github.com/googlefonts/literata" },
    { "id": "inter", "group": "fonts_icons", "name": "Inter (The Inter Project Authors)", "license": "OFL-1.1", "texts": ["lector/fuentes/OFL-Inter.txt"], "url": "https://github.com/rsms/inter" },
    { "id": "atkinson-hyperlegible", "group": "fonts_icons", "name": "Atkinson Hyperlegible (Braille Institute of America)", "license": "OFL-1.1", "texts": ["lector/fuentes/OFL-AtkinsonHyperlegible.txt"], "url": "https://www.brailleinstitute.org/freefont" },
    { "id": "material-symbols", "group": "fonts_icons", "name": "Material Symbols (Google)", "license": "Apache-2.0", "texts": ["licencias/avisos/material-symbols.txt"], "url": "https://github.com/google/material-design-icons" }
  ]
}
```
(Si `desugar_jdk_libs` ya aparece en `librerias.json`, se quita de aquí para no duplicarla.)

- [ ] **Step 5: Escribir la prueba (falla: no existe `Licenses`)**

`app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/data/LicensesTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** El catálogo REAL de assets (app + core/ui): completo, con textos y sin nada de la máquina que lo generó. */
class LicensesTest {
    // Gradle corre las pruebas JVM con el módulo app como carpeta de trabajo.
    private val roots = listOf(File("src/main/assets"), File("../core/ui/src/main/assets"))
    private fun file(path: String): File? = roots.map { File(it, path) }.firstOrNull { it.isFile }
    private fun read(path: String): String = assertNotNull(file(path), "falta el asset $path").readText()
    private val entries by lazy {
        assertNotNull(Licenses.parse(read(Licenses.GENERATED), read(Licenses.EXTRA)) { file(it) != null }, "catálogo ilegible")
    }

    @Test fun `la primera fila es la app con GPL y el enlace al codigo`() {
        val first = entries.first()
        assertEquals(LicenseGroup.APP, first.group)
        assertEquals("GPL-3.0-or-later", first.license)
        assertEquals("https://github.com/DiegoBr4nd/lector-bilingue", first.url)
        assertEquals(1, entries.count { it.group == LicenseGroup.APP })
    }

    @Test fun `el texto de la app es la LICENSE del repositorio`() {
        val norm = { s: String -> s.replace("\r\n", "\n").trim() }
        assertEquals(norm(File("../LICENSE").readText()), norm(read(entries.first().texts.single())))
    }

    @Test fun `cada fila tiene licencia y textos que existen y no estan vacios`() {
        for (e in entries) {
            assertTrue(e.license.isNotBlank(), "${e.id} sin licencia")
            assertTrue(e.texts.isNotEmpty(), "${e.id} sin textos")
            for (t in e.texts) assertTrue(read(t).isNotBlank(), "${e.id}: $t vacío")
        }
    }

    @Test fun `las librerias MIT o BSD llevan su aviso de copyright propio`() {
        val needsNotice = Regex("MIT|BSD", RegexOption.IGNORE_CASE)
        for (e in entries.filter { it.group == LicenseGroup.LIBRARIES && needsNotice.containsMatchIn(it.license) }) {
            assertTrue(e.texts.first().startsWith("licencias/avisos/"), "${e.id} (${e.license}) sin aviso de copyright")
        }
    }

    @Test fun `estan los componentes nativos fuentes e iconos conocidos`() {
        val ids = entries.map { it.id }.toSet()
        val expected = setOf(
            "ctranslate2", "ruy", "cpuinfo", "abseil", "sentencepiece", "protobuf-lite", "slimt",
            "literata", "inter", "atkinson-hyperlegible", "material-symbols",
        )
        assertTrue(ids.containsAll(expected), "faltan: ${expected - ids}")
    }

    @Test fun `estan las librerias principales del APK`() {
        val ids = entries.map { it.id }.toSet()
        for (id in listOf("org.readium.kotlin-toolkit:readium-navigator", "androidx.room:room-runtime", "org.bouncycastle:bcprov-jdk18on")) {
            assertTrue(id in ids, "falta $id")
        }
        assertTrue(ids.any { it.startsWith("androidx.compose.material3:") }, "falta Compose Material 3")
        assertTrue(ids.any { "desugar" in it }, "falta desugar_jdk_libs")
    }

    @Test fun `ninguna libreria de pruebas o de depuracion`() {
        val ids = entries.map { it.id }
        assertTrue(ids.none { it.startsWith("junit:") || "ui-test" in it || it.endsWith(":ui-tooling") || "mockwebserver" in it }, ids.joinToString())
    }

    @Test fun `sin nombres sin resolver`() {
        assertTrue(entries.none { "\${" in it.name || it.name.isBlank() })
    }

    @Test fun `el archivo generado no trae fechas ni rutas locales`() {
        val g = read(Licenses.GENERATED)
        assertFalse(JSONObject(g).has("metadata"), "trae metadata (fecha de generación)")
        assertFalse(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:""").containsMatchIn(g), "trae una fecha")
        assertFalse(Regex("""[A-Za-z]:\\\\|/home/|/Users/|\\\\Users\\\\""").containsMatchIn(g), "trae una ruta local")
    }

    @Test fun `ordenado por grupos`() {
        val order = entries.map { it.group.ordinal }
        assertEquals(order.sorted(), order)
    }

    @Test fun `json roto da null sin excepcion`() {
        assertNull(Licenses.parse("{", read(Licenses.EXTRA)) { true })
        assertNull(Licenses.parse(read(Licenses.GENERATED), "no es json") { true })
        assertNull(Licenses.parse("""{"libraries":[],"licenses":{}}""", """{"entries":[{"id":"x","group":"rara","name":"x","license":"x","texts":["a"]}]}""") { true })
    }
}
```

Run: `JAVA_HOME=... ./gradlew :app:testFdroidDebugUnitTest --tests "*LicensesTest"` → Expected: FAIL (no compila: `Licenses` no existe).

- [ ] **Step 6: `Licenses.kt`**

`app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/Licenses.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.data

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/** Grupos de la pantalla «Licencias», en el orden en que se muestran. */
enum class LicenseGroup { APP, LIBRARIES, NATIVE, FONTS_ICONS }

/**
 * Una fila de «Licencias». [texts]: rutas en assets que se muestran en orden (aviso de copyright propio primero,
 * luego el texto de la licencia). [url]: código o sitio del componente.
 */
data class LicenseEntry(
    val id: String,
    val name: String,
    val license: String,
    val group: LicenseGroup,
    val texts: List<String>,
    val url: String?,
)

/**
 * El catálogo: [GENERATED] lo escribe AboutLibraries al compilar (librerías de Gradle); [EXTRA] lo escribimos a mano
 * (la app, el código nativo, fuentes, íconos). Todo vive en assets: funciona sin internet.
 */
object Licenses {
    const val GENERATED = "licencias/librerias.json"
    const val EXTRA = "licencias/extra.json"

    /** Aviso de copyright propio de una librería de Gradle (opcional; obligatorio para MIT/BSD, lo vigila la prueba). */
    fun noticePath(uniqueId: String): String = "licencias/avisos/" + uniqueId.replace(':', '_') + ".txt"

    fun textPath(licenseKey: String): String = "licencias/textos/$licenseKey.txt"

    /** [exists]: si una ruta está en assets. Cualquier JSON roto o inesperado → null (la pantalla muestra el error). */
    fun parse(generated: String, extra: String, exists: (String) -> Boolean): List<LicenseEntry>? = runCatching {
        (parseExtra(extra) + parseGenerated(generated, exists))
            .sortedWith(compareBy<LicenseEntry>({ it.group.ordinal }, { it.name.lowercase() }))
    }.getOrNull()

    fun load(assets: AssetManager): List<LicenseEntry>? = runCatching {
        parse(assets.readText(GENERATED), assets.readText(EXTRA)) { path -> assets.has(path) }
    }.getOrNull()

    /** Los textos de [entry] unidos por una línea en blanco; si falta alguno → null. */
    fun readTexts(assets: AssetManager, entry: LicenseEntry): String? = runCatching {
        entry.texts.joinToString("\n\n") { assets.readText(it).trim() }
    }.getOrNull()

    private fun parseGenerated(json: String, exists: (String) -> Boolean): List<LicenseEntry> {
        val root = JSONObject(json)
        val licenses = root.getJSONObject("licenses")
        val libs = root.getJSONArray("libraries")
        return (0 until libs.length()).map { i ->
            val lib = libs.getJSONObject(i)
            val id = lib.getString("uniqueId")
            val keys = lib.optJSONArray("licenses").strings()
            LicenseEntry(
                id = id,
                name = lib.optString("name").ifBlank { id },
                license = keys.joinToString(" / ") { k -> licenses.optJSONObject(k)?.optString("name")?.ifBlank { null } ?: k },
                group = LicenseGroup.LIBRARIES,
                texts = listOfNotNull(noticePath(id).takeIf(exists)) + keys.map(::textPath),
                url = lib.optString("website").ifBlank { null },
            )
        }
    }

    private fun parseExtra(json: String): List<LicenseEntry> {
        val entries = JSONObject(json).getJSONArray("entries")
        return (0 until entries.length()).map { i ->
            val e = entries.getJSONObject(i)
            LicenseEntry(
                id = e.getString("id"),
                name = e.getString("name"),
                license = e.getString("license"),
                group = when (val g = e.getString("group")) {
                    "app" -> LicenseGroup.APP
                    "libraries" -> LicenseGroup.LIBRARIES
                    "native" -> LicenseGroup.NATIVE
                    "fonts_icons" -> LicenseGroup.FONTS_ICONS
                    else -> error("grupo desconocido: $g")
                },
                texts = e.getJSONArray("texts").strings().also { require(it.isNotEmpty()) },
                url = e.optString("url").ifBlank { null },
            )
        }
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map(::getString)

    private fun AssetManager.readText(path: String): String = open(path).bufferedReader().use { it.readText() }

    private fun AssetManager.has(path: String): Boolean = runCatching { open(path).close() }.isSuccess
}
```
Si el JSON de AboutLibraries 15.3.0 usa otros nombres (`uniqueId`, `licenses` como lista de claves, mapa `licenses` arriba con `name`), ajustar `parseGenerated` a lo que se ve en el archivo generado y anotarlo.

- [ ] **Step 7: Pruebas en verde**

Run: `JAVA_HOME=... ./gradlew :app:testFdroidDebugUnitTest --tests "*LicensesTest"` → Expected: PASS (11 pruebas). Si falla `faltan:` o `sin aviso`, completar los archivos de los Steps 3–4 (no aflojar la prueba). Si una entrada nativa se quitó con evidencia en el Step 3, quitarla también de `expected` y anotarlo.

- [ ] **Step 8: CI y documentación**

`.github/workflows/ci.yml`, justo después del paso «Lint, pruebas y APKs»:
```yaml
      # La lista de licencias de las librerías está en el repo (assets/licencias/librerias.json). Si alguien cambia
      # dependencias sin regenerarla, este paso falla: regenerar con el comando de docs/build.md.
      - name: La lista de licencias está al día
        run: |
          ./gradlew --no-daemon :app:exportLibraryDefinitions -PaboutLibraries.exportVariant=playRelease
          git diff --exit-code -- app/src/main/assets/licencias/librerias.json
```
`docs/build.md`: sección nueva «Licencias de las librerías» con el comando `./gradlew :app:exportLibraryDefinitions -PaboutLibraries.exportVariant=playRelease`, cuándo correrlo (al añadir o actualizar una dependencia) y que hay que crear `textos/<clave>.txt` para una licencia nueva y `avisos/<id>.txt` si es MIT/BSD.
`CLAUDE.md`, sección «Comandos útiles», una línea: `- Regenerar la lista de licencias (tras cambiar dependencias): ./gradlew :app:exportLibraryDefinitions -PaboutLibraries.exportVariant=playRelease`.
`docs/licencias/textos.md`: de dónde salió cada archivo de `textos/` y `avisos/` (etiqueta SPDX usada, archivo del árbol `native/` o URL del proyecto).

- [ ] **Step 9: Verificación y commit**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew lint test assembleFdroidRelease assemblePlayRelease :app:exportLibraryDefinitions -PaboutLibraries.exportVariant=playRelease && git status --short app/src/main/assets/licencias/librerias.json
```
Expected: BUILD SUCCESSFUL, lint en 0, y `git status` sin cambios en `librerias.json` después de regenerar (sale idéntico).
```bash
git add -A gradle build.gradle.kts app/build.gradle.kts app/src/main/assets/licencias app/src/main/kotlin app/src/test core/ui .github/workflows/ci.yml docs CLAUDE.md
git commit -m "feat(6a): catálogo de licencias (AboutLibraries al compilar + nativo, fuentes e íconos a mano)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Correo de comentarios

**Files:**
- Modify: `app/build.gradle.kts` (bloque `android {}`)
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/kotlin/.../ui/about/Feedback.kt`
- Test: `app/src/test/kotlin/.../ui/about/FeedbackTest.kt`

**Interfaces:**
- Consumes: `internal fun startSafely(context: Context, intent: Intent): Boolean` de `ui/tips/FreeBooks.kt` (mismo módulo).
- Produces (paquete `io.github.diegobr4nd.lectorbilingue.ui.about`):
  ```kotlin
  const val FEEDBACK_EMAIL: String
  data class DeviceInfo(val versionName: String, val flavor: String, val android: String, val manufacturer: String, val model: String)
  data class FeedbackMail(val to: String, val subject: String, val body: String)
  fun feedbackMail(appName: String, subjectTemplate: String, bodyTemplate: String, info: DeviceInfo): FeedbackMail
  fun mailtoUri(mail: FeedbackMail): String
  internal fun feedbackIntent(mail: FeedbackMail): Intent
  fun currentDeviceInfo(): DeviceInfo
  ```
  Strings: `feedback_subject` (`%1$s` nombre, `%2$s` versión) y `feedback_body` (`%1$s` versión, `%2$s` flavor, `%3$s` Android, `%4$s` fabricante, `%5$s` modelo).

- [ ] **Step 1: BuildConfig y strings**

`app/build.gradle.kts`, dentro de `android {}`:
```kotlin
    // BuildConfig.VERSION_NAME y FLAVOR para «Acerca de» y el correo de comentarios.
    buildFeatures { buildConfig = true }
```
`app/src/main/res/values/strings.xml`, al final (antes de `</resources>`):
```xml
    <!-- 6a · Acerca de y comentarios -->
    <string name="feedback_subject">%1$s · comentarios (%2$s)</string>
    <string name="feedback_body">Escribe aquí tu comentario:\n\n\n---\nVersión de la app: %1$s (%2$s)\nAndroid: %3$s\nTeléfono: %4$s %5$s</string>
```

- [ ] **Step 2: Escribir la prueba (falla: no existe `Feedback.kt`)**

`app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/about/FeedbackTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.about

import java.io.File
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackTest {
    /** Los textos reales de strings.xml (con \n de Android ya convertidos). */
    private fun string(name: String): String {
        val xml = File("src/main/res/values/strings.xml").readText()
        val raw = Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
        return raw.replace("\\n", "\n").replace("\\'", "'")
    }

    private val info = DeviceInfo("0.1.0", "play", "14", "Google", "Pixel 7")
    private val mail by lazy { feedbackMail("Lector bilingüe", string("feedback_subject"), string("feedback_body"), info) }

    @Test fun `asunto con nombre y version`() {
        assertEquals("Lector bilingüe · comentarios (0.1.0)", mail.subject)
    }

    @Test fun `cuerpo con espacio para escribir y solo los datos permitidos`() {
        assertEquals(
            "Escribe aquí tu comentario:\n\n\n---\nVersión de la app: 0.1.0 (play)\nAndroid: 14\nTeléfono: Google Pixel 7",
            mail.body,
        )
    }

    @Test fun `DeviceInfo solo tiene los cinco datos del spec`() {
        val fields = DeviceInfo::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet()
        assertEquals(setOf("versionName", "flavor", "android", "manufacturer", "model"), fields)
    }

    @Test fun `la direccion es un correo real y no la personal de Juan`() {
        assertTrue(Regex("""^[^@\s<>]+@[^@\s<>]+\.[a-z]{2,}$""").matches(FEEDBACK_EMAIL), FEEDBACK_EMAIL)
        assertFalse(FEEDBACK_EMAIL.startsWith("diego.guevara"), "es el correo personal")
        assertEquals(FEEDBACK_EMAIL, mail.to)
    }

    @Test fun `mailto codifica todo y se puede leer de vuelta`() {
        val odd = feedbackMail("A & B", "%1\$s · ü (%2\$s)", "x %1\$s %2\$s %3\$s %4\$s %5\$s", DeviceInfo("1+2", "fdroid", "8.0", "Sam&sung", "SM-A12 #1"))
        val uri = mailtoUri(odd)
        assertTrue(uri.startsWith("mailto:$FEEDBACK_EMAIL?subject="), uri)
        assertFalse(uri.contains(' ') || uri.contains('+') || uri.contains('\n'), uri)
        val query = uri.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(setOf("subject", "body"), query.keys)
        assertEquals(odd.subject, query["subject"])
        assertEquals(odd.body, query["body"])
    }

    @Test fun `saltos de linea como 0A y espacios como 20`() {
        val uri = mailtoUri(mail)
        assertTrue("%0A" in uri && "%20" in uri, uri)
    }
}
```
Run: `JAVA_HOME=... ./gradlew :app:testFdroidDebugUnitTest --tests "*FeedbackTest"` → Expected: FAIL (no compila).

- [ ] **Step 3: `Feedback.kt`**

`app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/about/Feedback.kt` (el controlador cambia `<CORREO_DE_JUAN>` por la dirección que dio Juan **antes** de despachar esta tarea; si el brief aún dice `<CORREO_DE_JUAN>`, NEEDS_CONTEXT):
```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.about

import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import io.github.diegobr4nd.lectorbilingue.BuildConfig
import java.net.URLEncoder

/** Dirección pública para comentarios (spec 6a, decisión 3): un correo solo para la app, nunca el personal. */
const val FEEDBACK_EMAIL = "<CORREO_DE_JUAN>"

/** Lo único del teléfono que va en el correo (spec 6a §5). Nada de libros, idiomas, RAM ni registros. */
data class DeviceInfo(
    val versionName: String,
    val flavor: String,
    val android: String,
    val manufacturer: String,
    val model: String,
)

data class FeedbackMail(val to: String, val subject: String, val body: String)

/**
 * El correo preparado. [subjectTemplate]: `%1$s` nombre de la app, `%2$s` versión. [bodyTemplate]: `%1$s` versión,
 * `%2$s` flavor, `%3$s` Android, `%4$s` fabricante, `%5$s` modelo (strings feedback_subject y feedback_body).
 */
fun feedbackMail(appName: String, subjectTemplate: String, bodyTemplate: String, info: DeviceInfo): FeedbackMail =
    FeedbackMail(
        to = FEEDBACK_EMAIL,
        subject = subjectTemplate.format(appName, info.versionName),
        body = bodyTemplate.format(info.versionName, info.flavor, info.android, info.manufacturer, info.model),
    )

/** `mailto:` con asunto y cuerpo codificados (RFC 6068: los espacios van como %20, no como "+"). */
fun mailtoUri(mail: FeedbackMail): String = "mailto:${mail.to}?subject=${encode(mail.subject)}&body=${encode(mail.body)}"

private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/**
 * Abre la app de correo con el mensaje listo; la persona lo lee, lo cambia y decide si lo envía. La app no envía nada.
 * El asunto y el cuerpo van también como extras: algunas apps de correo ignoran los de la dirección mailto.
 */
internal fun feedbackIntent(mail: FeedbackMail): Intent =
    Intent(Intent.ACTION_SENDTO, mailtoUri(mail).toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(mail.to))
        putExtra(Intent.EXTRA_SUBJECT, mail.subject)
        putExtra(Intent.EXTRA_TEXT, mail.body)
    }

fun currentDeviceInfo(): DeviceInfo = DeviceInfo(
    versionName = BuildConfig.VERSION_NAME,
    flavor = BuildConfig.FLAVOR,
    android = Build.VERSION.RELEASE,
    manufacturer = Build.MANUFACTURER,
    model = Build.MODEL,
)
```

- [ ] **Step 4: Pruebas en verde**

Run: `JAVA_HOME=... ./gradlew :app:testFdroidDebugUnitTest --tests "*FeedbackTest"` → Expected: PASS (6). Luego `./gradlew lint test` → BUILD SUCCESSFUL, lint en 0.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/res/values/strings.xml app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/about/Feedback.kt app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/about/FeedbackTest.kt
git commit -m "feat(6a): correo de comentarios (mailto con versión, Android y modelo; sin datos de libros)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Pantallas «Acerca de» y «Licencias» y la entrada del menú

**Files:**
- Create: `core/ui/src/main/res/drawable/ic_info.xml`; Modify: `core/ui/src/main/kotlin/.../core/ui/components/Icons.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/kotlin/.../ui/about/AboutScreen.kt`, `LicensesScreen.kt`, `AboutPreviews.kt`
- Modify: `app/src/main/kotlin/.../ui/nav/Routes.kt`, `AppNav.kt`, `MainActivity.kt`, `ui/library/LibraryScreen.kt`
- Modify: `app/src/test/kotlin/.../ui/nav/RoutesTest.kt`
- Modify: `app/src/androidTest/kotlin/.../LanguagesOnDeviceTest.kt:192`, `WelcomeOnDeviceTest.kt:95`
- Test: `app/src/androidTest/kotlin/.../AboutOnDeviceTest.kt`

**Interfaces:**
- Consumes: `Licenses.load/readTexts`, `LicenseEntry`, `LicenseGroup` (Tarea 3); `feedbackMail`, `feedbackIntent`, `currentDeviceInfo`, `DeviceInfo`, `FEEDBACK_EMAIL` (Tarea 4); `openExternal`, `startSafely` (`ui/tips/FreeBooks.kt`); `ScreenFrame`, `BottomInsetSpacer` (`ui/ScreenFrame.kt`); `ReadingFontFamily`, `ButtonShape`, `Spacing` (core/ui).
- Produces:
  - `Route.About` ("about"), `Route.Licenses` ("licenses"), `Route.License(val id: String)` ("license:<id>").
  - `AboutScreen(onBack: () -> Unit, onLicenses: () -> Unit, modifier: Modifier = Modifier, info: DeviceInfo = currentDeviceInfo(), startMail: (Intent) -> Boolean = <startSafely con el contexto>)`.
  - `LicensesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier)`, `LicensesContent(state: LicensesState, onBack, onOpen, modifier)`, `LicenseTextScreen(id: String, onBack: () -> Unit, modifier: Modifier = Modifier)`.
  - Ranura de `AppNav`: `library: @Composable (onLanguages: () -> Unit, onDeveloper: (() -> Unit)?, onHowTo: () -> Unit, onAbout: () -> Unit) -> Unit`.
  - `LibraryScreen(app, onLanguages, onDeveloper, onHowTo, onAbout, onOpenBook, modifier)`; `LibraryContent(..., onAbout: () -> Unit = {})` (último parámetro, con valor por defecto para no tocar las pruebas).

- [ ] **Step 1: Rutas — prueba que falla**

En `RoutesTest.kt`, cambiar la lista y añadir pruebas:
```kotlin
    private val routes = listOf(
        Route.Welcome(1), Route.Welcome(2), Route.Welcome(3), Route.Library, Route.Languages, Route.HowTo,
        Route.About, Route.Licenses, Route.License("org.readium.kotlin-toolkit:readium-navigator"),
    )

    @Test fun `acerca de y licencias se guardan con nombres fijos`() {
        assertEquals("about", Route.About.encode())
        assertEquals("licenses", Route.Licenses.encode())
        assertEquals("license:slimt", Route.License("slimt").encode())
    }

    @Test fun `una licencia sin id no se recupera`() {
        assertEquals(null, decodeRoute("license:"))
        assertEquals(Route.License("a:b:c"), decodeRoute("license:a:b:c"))
    }
```
Run: `./gradlew :app:testFdroidDebugUnitTest --tests "*RoutesTest"` → FAIL (no compila).

- [ ] **Step 2: Rutas**

`Routes.kt`: en `sealed interface Route` añadir
```kotlin
    data object About : Route
    data object Licenses : Route
    /** Texto de una licencia; [id] es el de [io.github.diegobr4nd.lectorbilingue.data.LicenseEntry]. */
    data class License(val id: String) : Route
```
En `encode()`: `Route.About -> "about"`, `Route.Licenses -> "licenses"`, `is Route.License -> "license:$id"`.
En `decodeRoute`: antes de `else`:
```kotlin
    text == "about" -> Route.About
    text == "licenses" -> Route.Licenses
    text.startsWith("license:") -> text.removePrefix("license:").takeIf { it.isNotBlank() }?.let { Route.License(it) }
```
Run RoutesTest → PASS.

- [ ] **Step 3: Ícono y strings**

`core/ui/src/main/res/drawable/ic_info.xml` (Material Symbols Rounded «info», de `google/material-design-icons`, `symbols/android/info/materialsymbolsrounded/info_24px.xml`, con el `tint` del resto de íconos):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960"
    android:tint="?android:attr/colorControlNormal">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M480,680Q497,680 508.5,668.5Q520,657 520,640L520,480Q520,463 508.5,451.5Q497,440 480,440Q463,440 451.5,451.5Q440,463 440,480L440,640Q440,657 451.5,668.5Q463,680 480,680ZM480,360Q497,360 508.5,348.5Q520,337 520,320Q520,303 508.5,291.5Q497,280 480,280Q463,280 451.5,291.5Q440,303 440,320Q440,337 451.5,348.5Q463,360 480,360ZM480,880Q397,880 324,848.5Q251,817 197,763Q143,709 111.5,636Q80,563 80,480Q80,397 111.5,324Q143,251 197,197Q251,143 324,111.5Q397,80 480,80Q563,80 636,111.5Q709,143 763,197Q817,251 848.5,324Q880,397 880,480Q880,563 848.5,636Q817,709 763,763Q709,817 636,848.5Q563,880 480,880ZM480,800Q614,800 707,707Q800,614 800,480Q800,346 707,253Q614,160 480,160Q346,160 253,253Q160,346 160,480Q160,614 253,707Q346,800 480,800ZM480,480Q480,480 480,480Q480,480 480,480Q480,480 480,480Q480,480 480,480Q480,480 480,480Q480,480 480,480Q480,480 480,480Q480,480 480,480Z"/>
</vector>
```
`Icons.kt`: `@DrawableRes val Info = R.drawable.ic_info`.

`strings.xml`, debajo del bloque de la Tarea 4:
```xml
    <string name="library_menu_about">Acerca de</string>
    <string name="about_title">Acerca de</string>
    <string name="about_version">Versión %1$s</string>
    <string name="about_privacy">Traduce dentro del teléfono. Tus libros y lo que lees nunca salen de él.</string>
    <string name="about_feedback">Enviar comentarios</string>
    <string name="about_feedback_no_app">No hay una app de correo en este teléfono. Escríbenos a %1$s</string>
    <string name="about_models_title">Modelos de traducción</string>
    <string name="about_model_opus">OPUS-MT, de la Universidad de Helsinki (Jörg Tiedemann y colaboradores). Licencia CC-BY-4.0. Convertidos al formato CTranslate2 (int8) para el teléfono.</string>
    <string name="about_model_opus_en_es">Modelo inglés → español</string>
    <string name="about_model_opus_es_en">Modelo español → inglés</string>
    <string name="about_model_firefox">Firefox Translations, de Mozilla. Licencia MPL-2.0.</string>
    <string name="about_model_firefox_open">Firefox Translations</string>
    <string name="about_licenses">Licencias de código abierto</string>
    <string name="licenses_title">Licencias</string>
    <string name="licenses_group_libraries">Librerías</string>
    <string name="licenses_group_native">Motores de traducción (código nativo)</string>
    <string name="licenses_group_fonts">Fuentes e íconos</string>
    <string name="licenses_open">Ver licencia</string>
    <string name="licenses_source_code">Código fuente</string>
    <string name="licenses_error">No se pudo cargar la lista de licencias.</string>
```
(Los botones de enlace usan el `free_books_open_desc` existente, «Abrir %1$s en el navegador», como descripción para TalkBack. Las flechas «→» van con `withNoBreakArrow()` si se usa en `ui/` para los demás pares; ver `ui/PairNames.kt`.)

- [ ] **Step 4: `AboutScreen.kt`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.about

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ButtonShape
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.ui.BottomInsetSpacer
import io.github.diegobr4nd.lectorbilingue.ui.ScreenFrame
import io.github.diegobr4nd.lectorbilingue.ui.tips.openExternal
import io.github.diegobr4nd.lectorbilingue.ui.tips.startSafely

const val OPUS_EN_ES_URL = "https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-es"
const val OPUS_ES_EN_URL = "https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-cat_oci_spa-en"
const val FIREFOX_TRANSLATIONS_URL = "https://github.com/mozilla/translations"

/**
 * «Acerca de» (spec 6a §3): nombre y versión, la frase de privacidad, «Enviar comentarios», la atribución de los
 * modelos (CC-BY-4.0 la exige visible) y la entrada a «Licencias». Sin sección de código fuente (va en «Licencias»).
 * [startMail] devuelve false si no hay app de correo: entonces la dirección queda escrita para copiarla.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    info: DeviceInfo = remember { currentDeviceInfo() },
    startMail: ((Intent) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val start = startMail ?: { intent: Intent -> startSafely(context, intent) }
    var noMailApp by rememberSaveable { mutableStateOf(false) }
    val appName = stringResource(R.string.app_name)
    val subject = stringResource(R.string.feedback_subject)
    val body = stringResource(R.string.feedback_body)
    ScreenFrame(modifier) {
        TitleRow(stringResource(R.string.about_title), onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(72.dp))
                Column {
                    Text(appName, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = ReadingFontFamily))
                    Text(
                        stringResource(R.string.about_version, info.versionName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = { noMailApp = !start(feedbackIntent(feedbackMail(appName, subject, body, info))) },
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.about_feedback)) }
            if (noMailApp) {
                SelectionContainer {
                    Text(
                        stringResource(R.string.about_feedback_no_app, FEEDBACK_EMAIL),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            Text(
                stringResource(R.string.about_models_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.about_model_opus), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                LinkButton(stringResource(R.string.about_model_opus_en_es)) { openExternal(context, OPUS_EN_ES_URL) }
                LinkButton(stringResource(R.string.about_model_opus_es_en)) { openExternal(context, OPUS_ES_EN_URL) }
            }
            Text(stringResource(R.string.about_model_firefox), style = MaterialTheme.typography.bodyMedium)
            LinkButton(stringResource(R.string.about_model_firefox_open)) { openExternal(context, FIREFOX_TRANSLATIONS_URL) }
            OutlinedButton(
                onClick = onLicenses,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.about_licenses)) }
            BottomInsetSpacer()
        }
    }
}

/** Botón de texto que abre el navegador; TalkBack dice «Abrir <nombre> en el navegador, botón». */
@Composable
internal fun LinkButton(label: String, onClick: () -> Unit) {
    val desc = stringResource(R.string.free_books_open_desc, label)
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc }) { Text(label) }
}

/** Fila de arriba con atrás y título (igual que «Cómo se usa»). */
@Composable
internal fun TitleRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(LectorIcons.ArrowBack), contentDescription = stringResource(R.string.languages_back))
        }
        Spacer(Modifier.width(Spacing.s))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = ReadingFontFamily),
            modifier = Modifier.semantics { heading() },
        )
    }
}
```
Si `R.drawable.ic_launcher_foreground` se ve mal (es la capa del ícono adaptable, con margen), usar `LectorIcons.MenuBook` 48 dp con `tint = primary` y anotarlo: lo decide `diseno`.

- [ ] **Step 5: `LicensesScreen.kt`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.data.LicenseEntry
import io.github.diegobr4nd.lectorbilingue.data.LicenseGroup
import io.github.diegobr4nd.lectorbilingue.data.Licenses
import io.github.diegobr4nd.lectorbilingue.ui.BottomInsetSpacer
import io.github.diegobr4nd.lectorbilingue.ui.ScreenFrame
import io.github.diegobr4nd.lectorbilingue.ui.tips.openExternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface LicensesState {
    data object Loading : LicensesState
    data class Ready(val entries: List<LicenseEntry>) : LicensesState
    data object Broken : LicensesState
}

/** «Licencias» (spec 6a §4): lee el catálogo de assets fuera del hilo principal. */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val state by produceState<LicensesState>(LicensesState.Loading) {
        value = withContext(Dispatchers.IO) { Licenses.load(assets) }?.let { LicensesState.Ready(it) } ?: LicensesState.Broken
    }
    LicensesContent(state, onBack, onOpen, modifier)
}

/** Sin estado propio: se dibuja igual con el catálogo real o de muestra (previews y pruebas). */
@Composable
fun LicensesContent(state: LicensesState, onBack: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val appName = stringResource(R.string.app_name)
    val openLabel = stringResource(R.string.licenses_open)
    ScreenFrame(modifier) {
        TitleRow(stringResource(R.string.licenses_title), onBack)
        when (state) {
            LicensesState.Loading -> Unit
            LicensesState.Broken -> Text(stringResource(R.string.licenses_error), style = MaterialTheme.typography.bodyLarge)
            is LicensesState.Ready -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                for (group in LicenseGroup.entries) {
                    val rows = state.entries.filter { it.group == group }
                    if (rows.isEmpty()) continue
                    groupTitle(group)?.let { title ->
                        item(key = "h-$group") {
                            Text(
                                stringResource(title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.xs).semantics { heading() },
                            )
                        }
                    }
                    items(rows, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = { Text(if (e.group == LicenseGroup.APP) appName else e.name) },
                            supportingContent = { Text(e.license) },
                            modifier = Modifier.heightIn(min = 48.dp).clickable(onClickLabel = openLabel) { onOpen(e.id) },
                        )
                    }
                }
                item(key = "end") { BottomInsetSpacer() }
            }
        }
    }
}

private fun groupTitle(group: LicenseGroup): Int? = when (group) {
    LicenseGroup.APP -> null
    LicenseGroup.LIBRARIES -> R.string.licenses_group_libraries
    LicenseGroup.NATIVE -> R.string.licenses_group_native
    LicenseGroup.FONTS_ICONS -> R.string.licenses_group_fonts
}

private sealed interface LicenseTextState {
    data object Loading : LicenseTextState
    data class Ready(val entry: LicenseEntry, val text: String) : LicenseTextState
    data object Broken : LicenseTextState
}

/** El texto completo de una licencia (sin internet). Para la app, arriba el enlace al código fuente (GPL). */
@Composable
fun LicenseTextScreen(id: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val assets = context.assets
    val state by produceState<LicenseTextState>(LicenseTextState.Loading, id) {
        value = withContext(Dispatchers.IO) {
            val entry = Licenses.load(assets)?.firstOrNull { it.id == id }
            val text = entry?.let { Licenses.readTexts(assets, it) }
            if (entry != null && text != null) LicenseTextState.Ready(entry, text) else LicenseTextState.Broken
        }
    }
    val appName = stringResource(R.string.app_name)
    ScreenFrame(modifier) {
        when (val s = state) {
            LicenseTextState.Loading -> TitleRow(stringResource(R.string.licenses_title), onBack)
            LicenseTextState.Broken -> {
                TitleRow(stringResource(R.string.licenses_title), onBack)
                Text(stringResource(R.string.licenses_error), style = MaterialTheme.typography.bodyLarge)
            }
            is LicenseTextState.Ready -> {
                TitleRow(if (s.entry.group == LicenseGroup.APP) appName else s.entry.name, onBack)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    Text(s.entry.license, style = MaterialTheme.typography.titleSmall)
                    val url = s.entry.url
                    if (s.entry.group == LicenseGroup.APP && url != null) {
                        LinkButton(stringResource(R.string.licenses_source_code)) { openExternal(context, url) }
                        Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodySmall) }
                    BottomInsetSpacer()
                }
            }
        }
    }
}
```

- [ ] **Step 6: Navegación y Biblioteca**

`AppNav.kt`:
- Ranura: `library: @Composable (onLanguages: () -> Unit, onDeveloper: (() -> Unit)?, onHowTo: () -> Unit, onAbout: () -> Unit) -> Unit = { _, _, _, _ -> },`
- En `Route.Library -> NavEntry(key) { library(...) }` añadir el cuarto argumento `{ backStack.add(Route.About) },`.
- Entradas nuevas (con `import io.github.diegobr4nd.lectorbilingue.ui.about.AboutScreen`, `LicensesScreen`, `LicenseTextScreen`):
```kotlin
                Route.About -> NavEntry(key) {
                    AboutScreen(onBack = { pop() }, onLicenses = { backStack.add(Route.Licenses) })
                }
                Route.Licenses -> NavEntry(key) {
                    LicensesScreen(onBack = { pop() }, onOpen = { id -> backStack.add(Route.License(id)) })
                }
                is Route.License -> NavEntry(key) {
                    LicenseTextScreen(id = key.id, onBack = { pop() })
                }
```
`MainActivity.kt`: `library = { onLanguages, onDeveloper, onHowTo, onAbout -> LibraryScreen(app, onLanguages, onDeveloper, onHowTo, onAbout, onOpenBook = { id -> startActivity(ReaderActivity.intent(this, id)) }) }`.
`LanguagesOnDeviceTest.kt:192`: `library = { onLanguages, _, _, _ ->`. `WelcomeOnDeviceTest.kt:95`: `library = { _, _, _, _ -> Text("Biblioteca de prueba") }`.

`LibraryScreen.kt`:
- `LibraryScreen(...)`: parámetro `onAbout: () -> Unit,` después de `onHowTo`; pasarlo a `LibraryContent(..., onAbout = onAbout)`.
- `LibraryContent(...)`: último parámetro `onAbout: () -> Unit = {},`; llamar `OverflowMenu(onLanguages, onHowTo, onAbout, onDeveloper)`.
- `OverflowMenu(onLanguages: () -> Unit, onHowTo: () -> Unit, onAbout: () -> Unit, onDeveloper: (() -> Unit)?)`, ítem nuevo justo después de «Cómo se usa»:
```kotlin
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_menu_about)) },
                leadingIcon = { Icon(painterResource(LectorIcons.Info), contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onAbout()
                },
            )
```

- [ ] **Step 7: Previews**

`AboutPreviews.kt` con `@Preview` (claro, oscuro con `uiMode = Configuration.UI_MODE_NIGHT_YES`, y `fontScale = 2f`) de `AboutScreen(onBack = {}, onLicenses = {}, info = DeviceInfo("0.1.0", "play", "14", "Google", "Pixel 7"), startMail = { true })` y de `LicensesContent(LicensesState.Ready(sample), {}, {})` con 4 filas de muestra (una por grupo), todas dentro de `LectorTheme`. Mismo estilo que `ui/tips/TipsPreviews.kt`.

- [ ] **Step 8: Prueba en el teléfono**

`app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/AboutOnDeviceTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue

import android.content.Intent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.Licenses
import io.github.diegobr4nd.lectorbilingue.ui.about.AboutScreen
import io.github.diegobr4nd.lectorbilingue.ui.about.DeviceInfo
import io.github.diegobr4nd.lectorbilingue.ui.about.FEEDBACK_EMAIL
import io.github.diegobr4nd.lectorbilingue.ui.about.LicenseTextScreen
import io.github.diegobr4nd.lectorbilingue.ui.about.LicensesScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** «Acerca de» y «Licencias» con el catálogo real del APK. No toca datos de Juan (sin ajustes, sin libros). */
@RunWith(AndroidJUnit4::class)
class AboutOnDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val info = DeviceInfo("0.1.0", "fdroid", "14", "Google", "Pixel 7")

    @Test fun elCorreoSaleConElMensajePreparado() {
        val sent = mutableListOf<Intent>()
        compose.setContent { LectorTheme { AboutScreen(onBack = {}, onLicenses = {}, info = info, startMail = { sent += it; true }) } }
        compose.onNodeWithText("Enviar comentarios").assertHeightIsAtLeast(48.dp).performClick()
        val intent = sent.single()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        assertEquals("Lector bilingüe · comentarios (0.1.0)", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.endsWith("Teléfono: Google Pixel 7"))
        compose.onNodeWithText(FEEDBACK_EMAIL, substring = true).assertDoesNotExist()
    }

    @Test fun sinAppDeCorreoLaDireccionQuedaEscrita() {
        compose.setContent { LectorTheme { AboutScreen(onBack = {}, onLicenses = {}, info = info, startMail = { false }) } }
        compose.onNodeWithText("Enviar comentarios").performClick()
        compose.onNodeWithText(FEEDBACK_EMAIL, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun atribucionDeLosModelosVisible() {
        compose.setContent { LectorTheme { AboutScreen(onBack = {}, onLicenses = {}, info = info, startMail = { true }) } }
        compose.onNodeWithText("CC-BY-4.0", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MPL-2.0", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Abrir Modelo inglés", substring = true)).performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Versión 0.1.0").assertIsDisplayed()
    }

    @Test fun elCatalogoDelApkCargaYEmpiezaPorLaApp() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val entries = assertNotNull(Licenses.load(assets))
        assertEquals("lector-bilingue", entries.first().id)
        for (e in entries) assertNotNull(Licenses.readTexts(assets, e), e.id) // cada texto existe en el APK
        compose.setContent { LectorTheme { LicensesScreen(onBack = {}, onOpen = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("GPL-3.0-or-later")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Motores de traducción (código nativo)")).performScrollTo().assertIsDisplayed()
    }

    @Test fun laLicenciaDeLaAppTraeElCodigoFuente() {
        compose.setContent { LectorTheme { LicenseTextScreen(id = "lector-bilingue", onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("https://github.com/DiegoBr4nd/lector-bilingue")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("GNU GENERAL PUBLIC LICENSE", substring = true)).assertExists()
    }

    @Test fun unaLicenciaQueYaNoExisteMuestraElError() {
        compose.setContent { LectorTheme { LicenseTextScreen(id = "ya-no-existe", onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No se pudo cargar la lista de licencias.")).fetchSemanticsNodes().isNotEmpty() }
    }
}
```
Y en `LibraryOnDeviceTest` añadir una prueba que abra el menú ⋮ y compruebe que «Acerca de» está y llama a `onAbout` (con `LibraryContent(..., onAbout = { clicked = true })`, mismo estilo que las pruebas de «Cómo se usa» que ya existen en ese archivo).

- [ ] **Step 9: Compilar y probar**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew lint test assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest
```
Expected: BUILD SUCCESSFUL, lint en 0. Luego en el Pixel (app al frente comprobada en comando aparte):
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew installFdroidDebug installFdroidDebugAndroidTest
MSYS_NO_PATHCONV=1 adb shell am instrument -w -e class io.github.diegobr4nd.lectorbilingue.AboutOnDeviceTest,io.github.diegobr4nd.lectorbilingue.LibraryOnDeviceTest,io.github.diegobr4nd.lectorbilingue.WelcomeOnDeviceTest,io.github.diegobr4nd.lectorbilingue.LanguagesOnDeviceTest io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: `OK`. Con letra al 200 % (`adb shell settings put system font_scale 2.0`, y al terminar devolver el valor que tenía, leído antes con `settings get system font_scale`): repetir `AboutOnDeviceTest`.

- [ ] **Step 10: Commit**

```bash
git add -A app/src core/ui/src
git commit -m "feat(6a): pantallas «Acerca de» y «Licencias», entrada en el menú de la Biblioteca

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Verificación final (la hace el controlador)

**Files:** ninguno nuevo (solo correcciones que salgan).

- [ ] **Step 1:** `./gradlew lint :app:lintPlayDebug test assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest` → BUILD SUCCESSFUL; reportes de lint en 0; contar pruebas JVM (447 + nuevas).
- [ ] **Step 2:** Que el CI pasaría: repetir localmente los pasos del CI que tocan esta rama (permisos del manifest de release sin cambios, motores arm64 en el APK, sin pantalla de desarrollador, lista de licencias al día con `git diff --exit-code`).
- [ ] **Step 3:** Tamaño del APK release antes (Tarea 2, Step 0) y después.
- [ ] **Step 4:** Revisión `seguridad`: plugin AboutLibraries (solo build, `offlineMode`, nada en `playReleaseRuntimeClasspath`), huellas nuevas en `verification-metadata.xml`, Gradle 9.8.1 (suma), Kotlin 2.4.21, intent `mailto:` (solo los 5 datos), enlaces externos.
- [ ] **Step 5:** Revisión `diseno`: capturas de «Acerca de», «Licencias» y un texto de licencia en claro y oscuro, letra 100 % y 200 %, y tableta; menú ⋮ con «Acerca de»; auditoría Material 3 ≥ 80.
- [ ] **Step 6:** Lote del Pixel con las clases que tocan pantallas o fuentes: `FontsOnDeviceTest`, `AboutOnDeviceTest`, `LibraryOnDeviceTest`, `WelcomeOnDeviceTest`, `LanguagesOnDeviceTest`, `ReadingSettingsSheetOnDeviceTest`, `TipsOnDeviceTest`, `ReaderOnDeviceTest` (con las reglas de la sección Global Constraints; `tips_seen` y ajustes de Juan intactos: compararlos antes y después con `run-as … cat shared_prefs/app_settings.xml` guardado en el scratchpad).
- [ ] **Step 7:** Puerta de Juan en su Pixel: Biblioteca ⋮ → «Acerca de» → «Enviar comentarios» (le llega el correo de prueba al correo nuevo) → «Licencias» → «Lector bilingüe» (ve el enlace al código) → una licencia nativa → atrás hasta la Biblioteca.
