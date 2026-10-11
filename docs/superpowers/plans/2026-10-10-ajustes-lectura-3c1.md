# 3c-1 · Ajustes de lectura · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** una hoja "Ajustes de lectura" en el Lector (tema, fuente, tamaño, interlineado, márgenes, alineación) iguales para todos los libros y aplicados al instante, más botones de capítulo anterior/siguiente y "Borrar traducciones guardadas" en Idiomas.

**Architecture:** los ajustes se guardan en `AppSettings` (SharedPreferences) y unas reglas puras los convierten en `EpubPreferences` de Readium, que el Lector aplica con `submitPreferences` sin recargar. Las fuentes propias se sirven por `readium_assets` y se declaran con la API pública de Readium; la tarjeta de traducción cambia de paleta con un atributo `data-lector-tema` y variables CSS.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Readium 3.4.0 (`EpubPreferences`, `submitPreferences`, declaraciones de fuentes), Room 2.8.5, corrutinas.

**Spec:** `docs/superpowers/specs/2026-10-10-ajustes-lectura-3c1-design.md` (leerla antes de cada tarea).

## Global Constraints

- Rama `feat/ajustes-lectura`; commits `feat(3c-1): …` / `fix(3c-1): …` / `test(3c-1): …` terminados en `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nunca push sin permiso de Juan; antes de cada push comprobar que el PR no está ya fusionado.
- Nunca registrar (log) ni enviar por red texto de libros, traducciones ni huellas.
- Prohibido: Firebase, Play Services, analítica, dependencias no libres. Fuentes nuevas solo OFL, con su `OFL.txt`.
- Los ajustes son **iguales para todos los libros**. Valores de fábrica: tema `SYSTEM`, tamaño 1.0, fuente `ORIGINAL`, interlineado `NORMAL`, márgenes `NORMAL`, alineación `START`. Tamaño en [0.75, 2.5] con pasos de 0.1.
- `scroll = true` siempre. La CSP de `HtmlSanitizer` no cambia sin aprobación de `seguridad`. `servedAssets` sigue siendo `lector/.*`. Scripts propios con datos en JSON, sin `innerHTML`, sin `addJavascriptInterface`.
- Contraste AA de página y tarjeta en los 4 temas; 48 dp; estado elegido con ícono + texto + `stateDescription`; textos en `strings.xml` (es-419); comentarios en español.
- `minSdk` 26; `:books` sin APIs > 26.
- Gradle: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew …`.
- Teléfono (Pixel 7, `2A261FDH200L5R`): solo `installFdroidDebug installFdroidDebugAndroidTest`, `am force-stop`, `am instrument -w -e class …`, `run-as … cat`, con `export MSYS_NO_PATHCONV=1`. Nunca desinstalar, `pm clear`, `connectedAndroidTest`, `adb input`, cambiar ajustes del sistema, ni instalar una versión con base v1. Si **nuestra** app está al frente, Juan puede estar leyendo: no instalar ni cerrar, preguntar al controlador. Solo libros inventados.
- Reglas de CLAUDE.md 8 y 9: `seguridad` (archivos servidos, scripts) y `diseno` (pantallas).

## Review Focus

1. **Cambiar un ajuste a mitad de capítulo:** la posición se mantiene (progresión ± 0,02) y las tarjetas abiertas siguen, solo cambian de color → Tarea 3 (`cambiarAjusteMantieneLaPosicionYLasTarjetas`, en el Pixel).
2. **Tema "Como el teléfono" y el modo del sistema cambia con el Lector abierto:** la página y la tarjeta cambian sin reabrir → Tarea 3 (`temaDelSistemaSigueAlModoOscuro`, JVM sobre la regla + efecto que observa la configuración).
3. **Valor guardado corrupto o de una versión futura** (`"ultra"`, número fuera de rango, tipo equivocado): se usa el de fábrica sin cerrar la app → Tarea 2 (`ajustesCorruptosVuelvenAFabrica`).
4. **Borrar traducciones mientras se traduce o pretraduce:** nada en curso vuelve a guardar después del borrado → Tarea 6 (`borrarConTraduccionEnCursoNoDejaFilas`).
5. **Libro con estilos propios fuertes y "Original del libro" + fábrica:** se respeta el libro (`publisherStyles = true`); cualquier otro ajuste lo sobrescribe y "Restablecer" lo devuelve → Tarea 2 (`fabricaRespetaElLibro`, `restablecerVuelveAFabrica`).

---

### Task 1: Comprobación en el Pixel (desechable; agente `estructura`)

**Objetivo:** responder C1–C4 de la spec §3 con evidencia, en la rama local `spike/3c1-ajustes` (se borra al final). Solo vuelve a `feat/ajustes-lectura` la spec §11.

- [ ] **Step 1: C1 · fuentes propias.** Copiar Literata regular e Inter a `assets/lector/fuentes/` en la rama spike. Buscar en Readium 3.4.0 (jar en la caché de Gradle; `javap`) la API pública para declarar familias propias (`FontFamilyDeclaration`, `fontFamilyDeclarations`, `buildFontFamilyDeclaration`, `addFontFace`/`addSource` o equivalente) y si acepta una ruta de `servedAssets`. Abrir el libro de `ReaderTestBook` con `EpubPreferences(fontFamily = FontFamily("Literata"))` y medir con `evaluateJavascript` `getComputedStyle(p).fontFamily` y el ancho de un texto de prueba (debe cambiar respecto a la fuente original). Confirmar `git diff` vacío de `HtmlSanitizer.kt`.
- [ ] **Step 2: C2 · negro puro.** `submitPreferences(EpubPreferences(scroll = true, theme = Theme.DARK, backgroundColor = Color(0xFF000000.toInt()), textColor = Color(…)))`: `getComputedStyle(document.documentElement).backgroundColor` = `rgb(0, 0, 0)` sin recargar (un `window.__marca` puesto antes sigue definido).
- [ ] **Step 3: C3 · al instante.** A mitad de capítulo cambiar `fontSize` 1.0 → 1.5 y `theme` LIGHT → SEPIA: medir `currentLocator.progression` antes y después (± 0,02) y que `window.__marca` siga (no recarga).
- [ ] **Step 4: C4 · tema de la tarjeta.** Con una hoja de prueba con `html[data-lector-tema="oscuro"] { --x: … }`, poner el atributo por script y comprobar la variable en una tarjeta insertada con `ParagraphScripts.insert`.
- [ ] **Step 5: Resultado.** En `feat/ajustes-lectura`, escribir la spec §11 (tabla C1–C4 con ✅/❌ y evidencia numérica, API exacta de Readium para fuentes, cómo se pasan) y commit `docs(3c-1): resultado de la comprobación en el Pixel`. Borrar la rama spike. Si C1 exige cambiar la CSP, **parar** y avisar al controlador.

---

### Task 2: `ReadingSettings`, guardado y `ReadingRules` (agente `estructura`)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/ReadingSettings.kt`
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/AppSettings.kt`
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReadingRules.kt`
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/data/ReadingSettingsTest.kt`, `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReadingRulesTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class PageTheme(val wire: String) { SYSTEM("system"), LIGHT("light"), SEPIA("sepia"), DARK("dark"), BLACK("black") }
enum class ReadingFont(val wire: String) { ORIGINAL("original"), LITERATA("literata"), INTER("inter"), ATKINSON("atkinson") }
enum class LineHeightLevel(val wire: String) { COMPACT("compact"), NORMAL("normal"), WIDE("wide") }
enum class MarginLevel(val wire: String) { NARROW("narrow"), NORMAL("normal"), WIDE("wide") }
enum class TextAlignChoice(val wire: String) { START("start"), JUSTIFY("justify") }

data class ReadingSettings(
    val theme: PageTheme = PageTheme.SYSTEM,
    val fontScale: Double = 1.0,
    val font: ReadingFont = ReadingFont.ORIGINAL,
    val lineHeight: LineHeightLevel = LineHeightLevel.NORMAL,
    val margins: MarginLevel = MarginLevel.NORMAL,
    val align: TextAlignChoice = TextAlignChoice.START,
) {
    val isFactory: Boolean get() = this == ReadingSettings()
    companion object { const val MIN_SCALE = 0.75; const val MAX_SCALE = 2.5; const val STEP = 0.1 }
}
```

  - `AppSettings`: `val readingSettingsFlow: StateFlow<ReadingSettings>`, `suspend fun loadReadingSettings(io: CoroutineDispatcher = Dispatchers.IO)`, `var readingSettings: ReadingSettings` (setter guarda y publica), mismo patrón que `enginePreference` (claves `reading_theme`, `reading_scale`, `reading_font`, `reading_line_height`, `reading_margins`, `reading_align`; `fontScale` guardado como `Float`, redondeado a pasos de 0.1 y acotado).
  - `object ReadingRules`:
    - `fun preferences(s: ReadingSettings, systemDark: Boolean): EpubPreferences`
    - `fun cardTheme(s: ReadingSettings, systemDark: Boolean): String` → `"claro" | "sepia" | "oscuro" | "negro"`
    - `fun step(scale: Double, up: Boolean): Double` (± 0.1, acotado, redondeado a 1 decimal)
    - `fun percent(scale: Double): Int` (100 · scale redondeado)
    - Constantes por nivel: `LINE_HEIGHT = mapOf(COMPACT to 1.3, NORMAL to 1.5, WIDE to 1.8)`, `MARGINS = mapOf(NARROW to 0.5, NORMAL to 1.0, WIDE to 1.6)` (unidades de `pageMargins` de Readium; ajustar en la Tarea 1 si su escala es otra y anotarlo).
    - Colores del negro: fondo `#000000`, texto `#D6D6D6` (comprobar AA ≥ 12:1).

- [ ] **Step 1: Pruebas (fallan).**

```kotlin
class ReadingRulesTest {
    private val f = ReadingSettings()

    @Test fun `fabrica respeta el libro`() {
        val p = ReadingRules.preferences(f, systemDark = false)
        assertEquals(true, p.scroll); assertEquals(true, p.publisherStyles); assertNull(p.fontFamily)
        assertEquals(Theme.LIGHT, p.theme)
    }
    @Test fun `tema del sistema sigue al modo oscuro`() {
        assertEquals(Theme.DARK, ReadingRules.preferences(f, systemDark = true).theme)
        assertEquals("oscuro", ReadingRules.cardTheme(f, systemDark = true))
        assertEquals("claro", ReadingRules.cardTheme(f, systemDark = false))
    }
    @Test fun `negro es oscuro con fondo negro`() {
        val p = ReadingRules.preferences(f.copy(theme = PageTheme.BLACK), systemDark = false)
        assertEquals(Theme.DARK, p.theme); assertEquals(0xFF000000.toInt(), p.backgroundColor!!.int)
        assertEquals("negro", ReadingRules.cardTheme(f.copy(theme = PageTheme.BLACK), systemDark = false))
    }
    @Test fun `sepia y claro elegidos ignoran el sistema`() {
        assertEquals(Theme.SEPIA, ReadingRules.preferences(f.copy(theme = PageTheme.SEPIA), true).theme)
        assertEquals(Theme.LIGHT, ReadingRules.preferences(f.copy(theme = PageTheme.LIGHT), true).theme)
    }
    @Test fun `cualquier ajuste distinto de fabrica sobrescribe el estilo del libro`() {
        assertEquals(false, ReadingRules.preferences(f.copy(fontScale = 1.2), false).publisherStyles)
        assertEquals(false, ReadingRules.preferences(f.copy(font = ReadingFont.ATKINSON), false).publisherStyles)
    }
    @Test fun `el tamaño se acota y va en pasos de 10`() {
        assertEquals(2.5, ReadingRules.step(2.5, up = true)); assertEquals(0.75, ReadingRules.step(0.8, up = false))
        assertEquals(1.1, ReadingRules.step(1.0, up = true)); assertEquals(110, ReadingRules.percent(1.1))
    }
    @Test fun `justificado lleva guiones`() {
        val p = ReadingRules.preferences(f.copy(align = TextAlignChoice.JUSTIFY), false)
        assertEquals(TextAlign.JUSTIFY, p.textAlign); assertEquals(true, p.hyphens)
    }
    @Test fun `interlineado y margenes por nivel`() {
        assertEquals(1.8, ReadingRules.preferences(f.copy(lineHeight = LineHeightLevel.WIDE), false).lineHeight)
        assertEquals(0.5, ReadingRules.preferences(f.copy(margins = MarginLevel.NARROW), false).pageMargins)
    }
    @Test fun `restablecer vuelve a fabrica`() = assertTrue(ReadingSettings().isFactory)
}
```

  `ReadingSettingsTest` (con un `SharedPreferences` falso en memoria como el de `AppSettingsTest`): guardar y leer cada campo; `ajustesCorruptosVuelvenAFabrica` (tema `"ultra"`, escala `9.0f`, tipo equivocado → fábrica en ese campo, los demás intactos); `loadReadingSettings` publica en el flow.
- [ ] **Step 2: Correr** `:app:testFdroidDebugUnitTest --tests '*Reading*'`: no compila.
- [ ] **Step 3: Implementar.** `ReadingRules.preferences`: `EpubPreferences(scroll = true, theme = …, backgroundColor = (solo BLACK), textColor = (solo BLACK), fontFamily = (null si ORIGINAL; si no, la familia declarada en la Tarea 3 con el nombre exacto de §11), fontSize = fontScale, lineHeight = LINE_HEIGHT[…], pageMargins = MARGINS[…], textAlign = (JUSTIFY → TextAlign.JUSTIFY, START → null), hyphens = (JUSTIFY → true, START → null), publisherStyles = s.isFactory)`. Usar los tipos reales de Readium (`Theme`, `Color`, `FontFamily`, `TextAlign`) según §11.
- [ ] **Step 4: Correr**: OK. Más `:app:testFdroidDebugUnitTest :app:lintFdroidDebug`.
- [ ] **Step 5: Commit** `feat(3c-1): ajustes de lectura guardados y reglas para Readium`.

---

### Task 3: Fuentes, paletas de la tarjeta y ajustes aplicados al Lector (agente `estructura`)

**Files:**
- Create: `app/src/main/assets/lector/fuentes/` (Literata regular/itálica/semibold, Inter, Atkinson Hyperlegible regular/itálica/negrita, cada familia con su `OFL.txt`)
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReaderActivity.kt` (declaración de fuentes en la configuración, preferencias iniciales con `ReadingRules.preferences`)
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReaderScreen.kt` (efecto: ajustes + modo del sistema → `submitPreferences`; `setTheme` al quedar lista la página y al cambiar)
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ParagraphScripts.kt` y `ParagraphBridge.kt` (`setTheme`)
- Modify: `app/src/main/assets/lector/tarjeta.css` (variables y 4 paletas)
- Modify: `core/ui/src/main/res/font/` + `core/ui/.../theme/Type.kt` (familia Compose `AtkinsonFamily` para la fila de la hoja)
- Test: `app/src/test/.../ui/reader/ParagraphScriptsTest.kt`, `app/src/androidTest/.../ReadingSettingsOnDeviceTest.kt`

**Interfaces:**
- Consumes: `ReadingSettings`, `ReadingRules.preferences/cardTheme` (Tarea 2), `AppSettings.readingSettingsFlow`.
- Produces: `ParagraphScripts.setTheme(theme: String): String` (lanza `IllegalArgumentException` si el tema no está en `setOf("claro","sepia","oscuro","negro")`), `ParagraphBridge.setTheme(theme: String)`; familias de Readium con los nombres de §11; `AtkinsonFamily` en Compose.

- [ ] **Step 1: Pruebas JVM (fallan):** `setTheme` usa JSON y `setAttribute('data-lector-tema'`, no usa `innerHTML`; rechaza `"<x>"` y `""`.
- [ ] **Step 2: Implementar** `setTheme`, la hoja con variables (`--lector-fondo`, `--lector-linea`, `--lector-texto`, `--lector-enlace`, `--lector-error-fondo`, `--lector-error-linea`, `--lector-error-enlace`, `--lector-esqueleto`; claro = los valores aprobados de la 3b; sepia/oscuro/negro provisionales con AA, que `diseno` fija en la Tarea 4) y las fuentes en Readium (API de §11). En `ReaderScreen`: `LaunchedEffect(settings, systemDark, navigator) { navigator?.submitPreferences(ReadingRules.preferences(settings, systemDark)); bridge.setTheme(ReadingRules.cardTheme(settings, systemDark)) }`, y `setTheme` también en el punto donde la página queda lista (antes de reinsertar tarjetas). `systemDark` = `isSystemInDarkTheme()`.
- [ ] **Step 3: Prueba en el Pixel (`ReadingSettingsOnDeviceTest`)** con `ReaderTestBook`:
  - `cambiarAjusteMantieneLaPosicionYLasTarjetas`: a mitad del capítulo 2, abrir una tarjeta (bridge.show), cambiar tamaño 1.0 → 1.5 y tema → SEPIA vía `app.settings.readingSettings = …`: progresión ± 0,02, la tarjeta sigue, `getComputedStyle(card).getPropertyValue('--lector-fondo')` es el de sepia, `window.__marca` sigue (no recargó).
  - `fuenteAtkinsonSeAplica`: `getComputedStyle(p).fontFamily` contiene el nombre declarado.
  - `negroEsNegro`: fondo `rgb(0, 0, 0)`.
  - Restaurar `ReadingSettings()` en `@After` (los ajustes de Juan no deben quedar cambiados: guardar los suyos antes y devolverlos).
- [ ] **Step 4: Correr** JVM + Pixel (también `TapTranslateOnDeviceTest`, `ReaderOnDeviceTest`): OK. Tamaño del APK release antes/después (anotar).
- [ ] **Step 5: Commit** `feat(3c-1): fuentes propias, paletas de la tarjeta y ajustes aplicados al Lector`.

---

### Task 4: Hoja "Ajustes de lectura" (agente `diseno`)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReadingSettingsSheet.kt`
- Modify: `ReaderScreen.kt` (botón "Aa" en `ReaderTopBar` y la hoja), `ReaderViewModel.kt` (`readingSettings: StateFlow<ReadingSettings>`, `setReadingSettings(ReadingSettings)`, `resetReadingSettings()`), `ReaderPreviews.kt`, `strings.xml`, `tarjeta.css` (paletas finales)
- Test: `app/src/test/.../ui/reader/ReaderViewModelTest.kt`, `app/src/androidTest/.../ReadingSettingsSheetOnDeviceTest.kt`

- [ ] **Step 1: Boceto en texto** (`docs/agentes/04-diseno.md` §6) de la barra con "Aa" y de la hoja (orden de §4 de la spec, textos exactos en es-419, muestras de tema con nombre, filas de fuente escritas en su fuente, A−/A+ con %), y las **paletas finales de la tarjeta en sepia, oscuro y negro con sus contrastes calculados**. El controlador lo enseña a Juan y espera su visto bueno.
- [ ] **Step 2: Pruebas JVM (fallan):** el VM guarda en `AppSettings` y publica; `resetReadingSettings` → fábrica; A+ en 2.5 no sube.
- [ ] **Step 3: Implementar** la hoja (Material 3 `ModalBottomSheet`, como `DirectionSheet.kt`; cada control de 48 dp; elegido con ícono + negrita + `stateDescription`), el botón "Aa" (descripción "Ajustes de lectura") entre el título y "EN → ES", Previews (claro/oscuro, 360/840, letra 1 y 2) y las paletas finales en `tarjeta.css`.
- [ ] **Step 4: Prueba en el Pixel (`ReadingSettingsSheetOnDeviceTest`):** abrir la hoja, elegir Sepia y A+ dos veces → `app.settings.readingSettings` = SEPIA/1.2; "Restablecer" → fábrica; con letra del sistema al 200 % simulada en Compose, todos los controles visibles y ≥ 48 dp. Restaurar los ajustes de Juan en `@After`.
- [ ] **Step 5: Correr** JVM + Pixel: OK. **Commit** `feat(3c-1): hoja de ajustes de lectura`.

---

### Task 5: Botones "Capítulo anterior / siguiente" (agente `estructura`)

**Files:**
- Modify: `ReaderScreen.kt` (`ReaderBottomBar` con dos `IconButton`; reutilizar `turnChapter` de la línea ~508), `ReaderRules.kt` (regla pura `chapterButtons(readingOrder: List<String>, currentHref: String?): Pair<Boolean, Boolean>`), `strings.xml` ("Capítulo anterior", "Capítulo siguiente")
- Test: `app/src/test/.../ui/reader/ReaderRulesTest.kt`, `app/src/androidTest/.../ChapterSwipeOnDeviceTest.kt` (o uno nuevo)

- [ ] **Step 1: Pruebas JVM (fallan):** `chapterButtons` apaga "anterior" en el primero y "siguiente" en el último; href con `#fragmento`; href desconocido → ambos apagados.
- [ ] **Step 2: Implementar.** Tras el paso, `announce(título del capítulo nuevo)` (del Índice; si no tiene, "Sección sin título") con el ayudante de anuncios de la 3b. Los botones usan el mismo guardia `turningTo` del gesto (PR #18) para no saltar dos.
- [ ] **Step 3: Prueba en el Pixel:** toque real (MotionEvent inyectado) en "Capítulo siguiente" desde el capítulo 2 → capítulo 3 al inicio; en el último capítulo el botón está apagado (`isEnabled` false por semántica).
- [ ] **Step 4: Correr** JVM + Pixel (`ChapterSwipeOnDeviceTest`, `ReaderOnDeviceTest`): OK. **Commit** `feat(3c-1): botones de capítulo anterior y siguiente`.

---

### Task 6: Borrar traducciones guardadas (agente `estructura`)

**Files:**
- Modify: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/db/TranslationDao.kt` (`count()`, `approxBytes()`, `deleteAll()`), `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/TranslationService.kt` (`clearCache()`, `cacheSize()`), `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/languages/` (fila al final + `ConfirmDialog` + snackbar), `strings.xml`
- Test: `app/src/test/.../data/TranslationServiceTest.kt`, `TranslationFakes.kt`, `app/src/test/.../ui/languages/…`, `app/src/androidTest/.../TranslationCacheOnDeviceTest.kt`

**Interfaces:**
- `@Query("SELECT COUNT(*) FROM translations") suspend fun count(): Int`
- `@Query("SELECT COALESCE(SUM(LENGTH(`key`) + LENGTH(translation)), 0) * 2 FROM translations") suspend fun approxBytes(): Long`
- `@Query("DELETE FROM translations") suspend fun deleteAll()`
- `TranslationService.clearCache()` = `release()` y después `deleteAll()` (en el hilo del servicio o tras esperar a que la fila quede vacía); `suspend fun cacheBytes(): Long`.

- [ ] **Step 1: Pruebas JVM (fallan):** `borrarConTraduccionEnCursoNoDejaFilas` (motor con gate, traducción en curso + pretraducción en fila; `clearCache`; soltar el gate → la tabla queda vacía y el `translate` en curso recibe cancelación); `clearCache` vacío no falla; la fila de Idiomas muestra "aprox. 3,2 MB" y se apaga con 0.
- [ ] **Step 2: Implementar.** Textos: "Traducciones guardadas: aprox. %1$s · Borrar", confirmación "¿Borrar las traducciones guardadas? Se volverán a traducir cuando toques los párrafos.", snackbar "Traducciones borradas", error "No se pudieron borrar las traducciones".
- [ ] **Step 3: Prueba en el Pixel (`TranslationCacheOnDeviceTest`):** insertar 3 filas inventadas con claves propias de la prueba, `clearCache()` → `count() == 0`. **Aviso:** esto borra también las traducciones reales de Juan del teléfono; antes de correrla, el controlador debe pedir permiso a Juan, o la prueba usa una base en memoria (`Room.inMemoryDatabaseBuilder`) con el servicio — preferir la base en memoria.
- [ ] **Step 4: Correr** JVM + Pixel: OK. **Commit** `feat(3c-1): borrar las traducciones guardadas desde Idiomas`.

---

### Task 7: Revisiones, correcciones y verificación (agentes `seguridad`, `diseno`, revisor final)

- [ ] **Step 1: `seguridad`** sobre `main...feat/ajustes-lectura`: fuentes servidas por `readium_assets` (solo assets de la app; `servedAssets` intacto), `setTheme` (lista fija, JSON), CSP intacta, `deleteAll` solo en `translations`, licencias OFL presentes, nada en logs.
- [ ] **Step 2: `diseno`:** capturas (Previews + Pixel con libro inventado) de la hoja y del libro en los 4 temas × 4 fuentes (muestra), tarjeta en los 4 temas, botones de capítulo, fila de Idiomas; claro/oscuro de la interfaz, 360/840, letra 200 %; auditoría `material-3` de `ui/reader` y `ui/languages` (≥ 80/100); contraste AA.
- [ ] **Step 3: Correcciones** con TDD, un commit `fix(3c-1): …` por hallazgo alto o medio; bajos a la descripción del PR.
- [ ] **Step 4: Revisión de código de toda la rama** (`superpowers:requesting-code-review`, modelo más capaz).
- [ ] **Step 5: Verificación completa:** `./gradlew lint test assembleFdroidDebug assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest` y todas las instrumentadas en el Pixel (las 13 de la 3b + `ReadingSettingsOnDeviceTest`, `ReadingSettingsSheetOnDeviceTest`, `ChapterSwipeOnDeviceTest`, `TranslationCacheOnDeviceTest`). Tamaño del APK release.

---

### Task 8: Puerta 3c-1 con Juan (sesión principal)

- [ ] **Step 1:** Permiso de Juan para push de `feat/ajustes-lectura`; él abre el PR en borrador. CI con `curl …/actions/runs?head_sha=…`. Antes de cada push posterior, comprobar que el PR no está fusionado.
- [ ] **Step 2:** `installFdroidDebug`; Juan hace la puerta de la spec §1 (puntos 1–6) con un libro real.
- [ ] **Step 3:** Juan aprueba las capturas.
- [ ] **Step 4:** Memoria (`arranque-fase-3`: 3c-1 hecha; sigue 3c-2) y línea "Fase actual" de `CLAUDE.md` en la siguiente rama.
- [ ] **Step 5:** Con CI en verde y la puerta pasada, Juan fusiona.
