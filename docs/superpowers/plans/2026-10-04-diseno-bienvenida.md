# Sistema de diseño, Bienvenida, Inicio e Idiomas (Fase 2d) · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** La app pasa de una pantalla de prueba a un recorrido real: Bienvenida en 3 pasos → Inicio provisional → Idiomas. Todo se apoya en el sistema de diseño Tinta y papel de `:core:ui`, y la pantalla de prueba queda solo en la versión debug.

**Architecture:**
- **`:core:ui` (biblioteca Android nueva):** colores, temas de página, tipografía (Inter y Literata), formas, íconos Material Symbols y componentes. No depende de `:models` ni de los motores.
- **En `:app`:**
  - `ModelHub` reúne la lógica de modelos que hoy vive en la pantalla de prueba (catálogo, descargas, borrar, importar), para que la usen las tres pantallas nuevas.
  - `AppSettings` guarda dos ajustes en `SharedPreferences`.
  - Navigation 3 navega entre `Welcome`, `Home`, `Languages` y `Developer`.
- **Pantalla de prueba:** pasa a `src/debug/`, junto con sus pruebas.

**Tech Stack:**
- Kotlin · AGP 9.4.1 · Compose BOM 2026.09.00 · Material 3.
- Navigation 3 (`androidx.navigation3:navigation3-runtime` y `navigation3-ui`, Apache-2.0; última estable, fijada en la Tarea 1).
- Fuentes Inter y Literata (OFL) · Material Symbols Rounded (Apache-2.0).

**Spec:** `docs/superpowers/specs/2026-10-04-diseno-bienvenida-design.md`

**Ejecución:** subagent-driven.
- Tareas 1 y 5 → `infraestructura` (la 5, junto con `estructura`).
- Tareas 2 y 3 → `diseno`.
- Tareas 4 y 6 → `estructura`.
- Tareas 7, 8 y 9 → `diseno`.
- Tarea 10 → `seguridad` + `diseno`, solo lectura, con auditoría Material 3.
- Tarea 11 → sesión principal con Juan.

## Global Constraints

- **Paquetes:** base `io.github.diegobr4nd.lectorbilingue`; módulo nuevo `:core:ui`, paquete `io.github.diegobr4nd.lectorbilingue.core.ui`.
- **Rama y PR:** `feat/diseno-bienvenida`; nunca `main`; nunca push sin permiso de Juan; el PR se crea **como borrador** y se fusiona solo con el CI en verde y la puerta cerrada.
- **Paleta Tinta y papel:**
  - claro: primario `#2E4A7D`, fondo `#FBF8F1`, texto `#1C1B1F`;
  - oscuro: primario `#A9C1F0`, fondo `#121418`, texto `#E6E3DD`.
  - Sin color dinámico de Material You.
- **Temas de página (`ReaderTheme`):**

  | Tema | Fondo | Texto | Traducción |
  |---|---|---|---|
  | Claro | `#FBF8F1` | `#1C1B1F` | `#2E4A7D` |
  | Sepia | `#F4ECD8` | `#3B2F1E` | `#6B4A1F` |
  | Oscuro | `#121418` | `#E6E3DD` | `#A9C1F0` |
  | Negro | `#000000` | `#D9D9D9` | `#9DB4E0` |

- **Contraste:** texto normal ≥ 4,5:1; elementos grandes e íconos ≥ 3:1. Lo comprueba una prueba automática.
- **Fuentes:** Inter (interfaz) y Literata (lectura y títulos grandes de la Bienvenida), con licencia OFL, en `res/font/`, con su `OFL.txt`. Todo en `sp`.
- **Íconos:** Material Symbols **Rounded**, copiados como dibujos vectoriales en `core/ui/src/main/res/drawable/ic_*.xml`, con `LICENSE` Apache-2.0. **Cero emojis en la interfaz.** Los íconos con significado llevan descripción en español; los decorativos, `null`.
- **Medidas:** esquinas de 12 dp (tarjetas), 20 dp (botones) y 28 dp (diálogos); espaciados en múltiplos de 4 dp; áreas táctiles ≥ 48 dp.
- **Textos:** solo español latino; todo en `strings.xml` (nada escrito dentro del código). Nombres de los motores para el usuario: **Calidad** = OPUS (ícono `workspace_premium`) y **Rápido** = Firefox (ícono `bolt`). "OPUS" y "Firefox" solo en la pantalla de desarrollador.
- **Ajustes:** `SharedPreferences` `app_settings` con `welcome_done: Boolean` y `engine_choice: String` ∈ {`auto`, `opus`, `firefox`}, por defecto `auto`.
- **Comportamiento:**
  - borrar y volver a descargar **siempre** piden confirmación;
  - el refresco del catálogo iniciado desde la interfaz tiene un tope de **5 s**;
  - el motor nunca cambia solo.
- **Heredadas de las fases anteriores:**
  - nunca registrar ni mostrar texto de libros ni traducciones;
  - mensajes fijos, sin rutas;
  - lista de permisos sin cambios (`INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `<paquete>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`);
  - lista blanca de red sin cambios.
- **Gradle y teléfono:**
  - Gradle: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew …`.
  - Teléfono: `export MSYS_NO_PATHCONV=1`, rutas locales como `C:/…`.
  - Nunca desinstalar, `pm clear` ni `connectedAndroidTest`. Instrumentadas con `installFdroidDebug installFdroidDebugAndroidTest` y `am instrument`.
- **Commits:** Conventional Commits en español, terminando en `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **App cerrada en el paso 2 de la Bienvenida:** al reabrir empieza en el paso 1 y no salta al Inicio sin haber elegido. Tarea 7: prueba de `WelcomeRules` y de que `welcome_done` solo se escribe al salir del paso 3.
2. **Sin catálogo y sin red en el paso 3:** mensaje claro, sin botón Descargar, con Importar disponible; la pantalla responde en ≤ 5 s. Tareas 4 (tope del refresco) y 7 (regla de qué botones mostrar).
3. **Borrar el modelo en uso desde Idiomas:** el diálogo lo confirma y el Inicio pasa a "Aún no tienes idiomas" o al otro motor. Nunca falla en silencio. Tareas 4 (`ModelHub.delete`) y 8 (prueba de reglas de Idiomas).
4. **Letra del sistema al 200 % y tableta:** los botones de la Bienvenida y las filas de Idiomas no se cortan. Tareas 3, 7 y 8: Preview `fontScale = 2f` y ancho de tableta; revisión de `diseno`.
5. **Versión release:** la pantalla de desarrollador y "Mostrar Bienvenida otra vez" no existen. Tarea 5: comprobación del CI sobre el APK release.

---

### Task 1: Módulo `:core:ui`, dependencias, fuentes e íconos (agente `infraestructura`)

**Files:**
- Modify: `settings.gradle.kts` (`include(":core:ui")`), `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`, `app/build.gradle.kts`
- Create:
  - `core/ui/build.gradle.kts`, `core/ui/src/main/AndroidManifest.xml`, `core/ui/consumer-rules.pro`;
  - `core/ui/src/main/res/font/inter_*.ttf` (o la variable) y `core/ui/src/main/res/font/literata_*.ttf`;
  - `core/ui/src/main/res/font/OFL-Inter.txt`, `core/ui/src/main/res/font/OFL-Literata.txt`;
  - `core/ui/src/main/res/drawable/ic_*.xml` (íconos de la spec §3.4) y `core/ui/ICONOS-LICENSE.txt` (Apache-2.0);
  - `core/ui/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/core/ui/package-info.kt`.

**Interfaces:**
- Produces:
  - **Módulo `:core:ui`:** plugins `lectorbilingue.android.library` y el de Compose; namespace `io.github.diegobr4nd.lectorbilingue.core.ui`.
  - **Dependencias:** `api` de Compose material3, ui y tooling-preview. `:app` depende de `:core:ui`.
  - **Catálogo de versiones:** `androidx-navigation3-runtime` y `androidx-navigation3-ui` (y, si la doc oficial lo exige para ViewModels por pantalla, `androidx-lifecycle-viewmodel-navigation3`), agregados a `:app`.
  - **Recursos de fuente:** `R.font.inter_regular`, `inter_semibold`, `inter_bold`, `literata_regular`, `literata_semibold`, `literata_italic` (si se usa la variable, documentar los nombres reales en `core/ui/README.md`).
  - **Íconos:** `R.drawable.ic_menu_book`, `ic_lock`, `ic_translate`, `ic_check_circle`, `ic_check_box`, `ic_check_box_outline_blank`, `ic_download`, `ic_folder_open`, `ic_wifi`, `ic_workspace_premium`, `ic_bolt`, `ic_tune`, `ic_delete`, `ic_library_books`, `ic_more_vert`, `ic_close`, `ic_arrow_back`.

- [ ] **Step 1:** Versión estable más reciente de Navigation 3, según la skill `navigation-3` o la doc oficial. Agregarla a `libs.versions.toml`.
- [ ] **Step 2:** Crear el módulo `:core:ui` copiando la convención de `:models` (biblioteca Android) y aplicando el plugin de Compose (ver `build-logic/convention/.../AndroidComposeConventionPlugin.kt` y cómo lo usa `:app`).
- [ ] **Step 3:** Bajar Inter y Literata **desde sus repos oficiales**:
  - `rsms/inter`, release fijo;
  - `googlefonts/literata`, release fijo.

  Copiar solo los pesos necesarios (o la variable, si pesa menos) a `res/font/` con nombres en minúsculas y `_`, más sus `OFL.txt`. Anotar en `core/ui/README.md` la URL, la versión y el SHA-256 de cada archivo.
- [ ] **Step 4:** Íconos:
  - descargar los SVG de Material Symbols **Rounded** (peso 400, relleno 0, tamaño óptico 24) desde `google/material-design-icons`, en un commit fijo;
  - convertirlos a vectores de Android (`<vector>` 24 dp, `android:tint="?attr/colorControlNormal"`; a mano o con la conversión estándar de SVG a vector);
  - guardarlos como `ic_*.xml` y anotar el origen en el README.
- [ ] **Step 5:** `./gradlew --write-verification-metadata sha256 help` según `docs/build.md`. Listar en el reporte los componentes nuevos: deben ser solo `androidx.navigation3:*` y sus transitivas. Si aparece algo inesperado, BLOCKED.
- [ ] **Step 6:** `./gradlew lint test assembleFdroidDebug assembleFdroidRelease` → BUILD SUCCESSFUL. Reportar el tamaño del APK release antes y después.
- [ ] **Step 7: Commit** `build(ui): módulo :core:ui, Navigation 3, fuentes e íconos`.

---

### Task 2: Colores, temas de página, tipografía y formas (agente `diseno`)

**Files:**
- Create in `core/ui/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/core/ui/theme/`: `Color.kt`, `ReaderTheme.kt`, `Type.kt`, `Shape.kt`, `Spacing.kt`, `Theme.kt`, `Contrast.kt`
- Test: `core/ui/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/core/ui/theme/ContrastTest.kt`
- Modify:
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/theme/Theme.kt`: se borra; `MainActivity` usa `LectorTheme` de `:core:ui`;
  - `MainActivity.kt` (solo el cambio de tema).

**Interfaces:**
- Produces:
  - `object Contrast { fun ratio(a: Long, b: Long): Double }`: colores `0xFFRRGGBB`; luminancia relativa WCAG 2.x.
  - `val TintaLight: ColorScheme`, `val TintaDark: ColorScheme`.
  - `enum class ReaderTheme(val background: Color, val text: Color, val translation: Color) { LIGHT, SEPIA, DARK, BLACK }`, con los valores de Global Constraints.
  - `val LectorTypography: Typography` (Inter) y `val ReadingFontFamily: FontFamily` (Literata).
  - `object Spacing { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp }`.
  - `val LectorShapes: Shapes` (12 / 20 / 28 dp).
  - `@Composable fun LectorTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`.

- [ ] **Step 1: Prueba que falla**:
```kotlin
package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContrastTest {
    @Test fun blancoSobreNegroEs21() = assertEquals(21.0, Contrast.ratio(0xFFFFFFFF, 0xFF000000), 0.01)
    @Test fun mismoColorEs1() = assertEquals(1.0, Contrast.ratio(0xFF2E4A7D, 0xFF2E4A7D), 0.001)

    @Test fun temasDePaginaCumplenAA() {
        for (t in ReaderTheme.entries) {
            assertTrue(Contrast.ratio(t.text.argbLong(), t.background.argbLong()) >= 4.5, "${t.name} texto")
            assertTrue(Contrast.ratio(t.translation.argbLong(), t.background.argbLong()) >= 4.5, "${t.name} traducción")
        }
    }

    @Test fun esquemasDeInterfazCumplenAA() {
        for ((name, s) in listOf("claro" to TintaLight, "oscuro" to TintaDark)) {
            val pares = listOf(
                "onBackground" to (s.onBackground to s.background),
                "onSurface" to (s.onSurface to s.surface),
                "onSurfaceVariant" to (s.onSurfaceVariant to s.surface),
                "onPrimary" to (s.onPrimary to s.primary),
                "primary/fondo" to (s.primary to s.background),
                "onPrimaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "onSecondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                "onError" to (s.onError to s.error),
                "error/fondo" to (s.error to s.background),
            )
            for ((label, p) in pares) {
                assertTrue(Contrast.ratio(p.first.argbLong(), p.second.argbLong()) >= 4.5, "$name $label")
            }
            // Bordes e íconos: elementos gráficos, mínimo 3:1.
            assertTrue(Contrast.ratio(s.outline.argbLong(), s.surface.argbLong()) >= 3.0, "$name outline")
        }
    }
}
```
  `argbLong()` es una extensión `internal fun Color.argbLong(): Long = this.toArgb().toLong() and 0xFFFFFFFF` en `Contrast.kt`.
- [ ] **Step 2:** `./gradlew :core:ui:testDebugUnitTest` → FALLA (no compila).
- [ ] **Step 3: Implementación.**
  - **`Contrast.ratio`:** fórmula WCAG 2.x: canal `c/255`, `≤ 0,03928 → c/12,92`, si no `((c+0,055)/1,055)^2,4`; `L = 0,2126 R + 0,7152 G + 0,0722 B`; `(L1+0,05)/(L2+0,05)` con L1 la mayor.
  - **`TintaLight` / `TintaDark`:** `lightColorScheme(...)` y `darkColorScheme(...)` con:
    - primario, fondo y texto de Global Constraints;
    - superficie clara `#FFFFFF`, oscura `#1E222A`;
    - `outline` claro `#8A8579`, oscuro `#8D93A0`.

    El resto de roles los fija `diseno` y los valida la prueba (ajustarlos hasta que pase, sin bajar el umbral).
  - **Tipografía:** `Typography` de Material 3 con `FontFamily(Inter)` en todos los estilos. `ReadingFontFamily` = Literata (regular, semibold, italic).
- [ ] **Step 4:** `./gradlew :core:ui:testDebugUnitTest` → PASA. Previews en `ThemePreviews.kt`: una muestra de cada `ReaderTheme` con un párrafo de ejemplo propio (no de los textos privados) y su traducción en cursiva.
- [ ] **Step 5:** `MainActivity` usa `LectorTheme`; se borra el `ui/theme/Theme.kt` viejo. `./gradlew lint test assembleFdroidDebug` → verde.
- [ ] **Step 6: Commit** `feat(ui): colores Tinta y papel, temas de página y tipografía`.

---

### Task 3: Componentes de `:core:ui` (agente `diseno`)

**Files:**
- Create in `core/ui/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/core/ui/components/`: `PrivacyBadge.kt`, `ModelRow.kt`, `ConfirmDialog.kt`, `DownloadProgress.kt`, `Icons.kt`, `ComponentPreviews.kt`
- Create: `core/ui/src/main/res/values/strings.xml` (los textos de los componentes)
- Test: `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/ComponentsOnDeviceTest.kt` (semántica, con `createComposeRule`; si `:app` no tiene `ui-test-junit4` en `androidTestImplementation`, agregarlo en esta tarea)

**Interfaces:**
- Produces:
  - `enum class EngineKind { QUALITY, FAST }`: `QUALITY` = OPUS, `FAST` = Firefox. `:core:ui` no conoce `EngineId`; la app los traduce.
  - `sealed interface ModelRowState { data object NotInstalled; data object Installed; data object InUse; data class Downloading(val fraction: Float?) }`.
  - `@Composable fun ModelRow(kind: EngineKind, sizeMb: Long, state: ModelRowState, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier)`.
    - Muestra el ícono y el nombre ("Calidad" o "Rápido"), "N MB", el estado en texto y un solo botón según el estado: Descargar, Cancelar o Borrar.
    - Con `Downloading`, también muestra `DownloadProgress`.
  - `@Composable fun DownloadProgress(fraction: Float?, modifier: Modifier = Modifier)`.
    - Barra determinada si `fraction != null`, indeterminada si no, y el texto "Descargando… N %".
    - `liveRegion = Polite` y `contentDescription` en español.
  - `@Composable fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit)`.
  - `@Composable fun PrivacyBadge(modifier: Modifier = Modifier)`: ícono `ic_lock` decorativo y el texto "Traducido en tu teléfono".
  - `object LectorIcons`: accesos `@DrawableRes` a cada `ic_*`.

- [ ] **Step 1: Previews** de cada componente en claro y oscuro, con `fontScale = 2f` y a ancho de teléfono (360 dp) y de tableta (840 dp). `ModelRow` en sus 4 estados.
- [ ] **Step 2: Prueba instrumentada** (`ComponentsOnDeviceTest`):
  - `ModelRow` en `Installed` expone un botón con texto "Borrar" y el texto de estado "Instalado"; en `Downloading(0.4f)`, el texto "Descargando… 40 %" y un botón "Cancelar";
  - el botón mide ≥ 48 dp (`assertHeightIsAtLeast(48.dp)`);
  - `ConfirmDialog` llama a `onConfirm` al tocar el botón de confirmar y a `onDismiss` al tocar "Cancelar".
- [ ] **Step 3:** Implementar hasta que compile: `./gradlew lint assembleFdroidDebug compileFdroidDebugAndroidTestKotlin`. El controlador la corre en el Pixel.
- [ ] **Step 4: Commit** `feat(ui): componentes de modelo, progreso, confirmación y privacidad`.

---

### Task 4: `AppSettings` y `ModelHub` (agente `estructura`)

**Files:**
- Create:
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/AppSettings.kt`;
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/ModelHub.kt`;
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/HubRules.kt`.
- Move: `ui/enginetest/ModelActions.kt` → `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/ModelActions.kt`, con su prueba. Se actualizan los imports de la pantalla de prueba; su comportamiento no cambia.
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/data/AppSettingsTest.kt`, `HubRulesTest.kt`, y `ModelActionsTest.kt` movida.

**Interfaces:**
- Consumes:
  - `:models`: `Models.catalogRepository(ctx).current()` y `.refresh()`, `Models.enqueueDownload`, `downloadInfo`, `cancelDownload`, `deleteModel(ctx, engine, pair)`, `importModel`, `installedDir`, `store(ctx).installed()`.
  - `:engine:api`: `EngineId`, `EngineSelector`, `EngineChoice`, `Reason`.
  - `ModelActions` (reglas puras existentes).
- Produces:
  - `enum class EnginePreference { AUTO, QUALITY, FAST }` (en `AppSettings.kt`), con `fun toForced(): EngineId? = when (this) { AUTO -> null; QUALITY -> EngineId.OPUS; FAST -> EngineId.FIREFOX }`. No se llama `EngineChoice` para no chocar con el `EngineChoice` de `:engine:api`.
  - `class AppSettings(private val prefs: SharedPreferences)`, con:
    - `var welcomeDone: Boolean`;
    - `var enginePreference: EnginePreference` (lee y escribe `engine_choice`: `auto`, `opus` o `firefox`; un valor desconocido se lee como `AUTO`);
    - `companion object { fun of(context: Context) = AppSettings(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)) }`.
  - `data class PairStatus(val pair: String, val rows: List<RowStatus>)` y `data class RowStatus(val modelId: String, val engine: EngineId, val sizeBytes: Long, val installed: Boolean, val download: DownloadState?)`.
  - `class ModelHub(private val app: Application, private val io: CoroutineDispatcher = Dispatchers.IO, private val refreshTimeoutMs: Long = 5_000)`:
    - `val pairs: StateFlow<List<PairStatus>>`: pares del catálogo con sus filas; se actualiza con `refresh()` y con el estado de las descargas;
    - `suspend fun refresh(): ModelMessage?`: refresco del catálogo con tope de `refreshTimeoutMs` (`withTimeoutOrNull`); al vencer, usa `current()`; devuelve el mensaje fijo para la interfaz o `null`;
    - `fun download(modelId: String)`, `fun cancel(modelId: String)`;
    - `suspend fun delete(engine: EngineId, pair: String): ModelMessage?`;
    - `suspend fun import(uri: Uri): ModelMessage`;
    - `fun totalRamBytes(): Long`.
  - `object HubRules`:
    - `fun pairStatuses(catalog: Catalog?, installed: Set<Pair<String, String>>, downloads: Map<String, DownloadState?>): List<PairStatus>`;
    - `fun recommended(status: PairStatus, totalRamBytes: Long): RowStatus?`: usa `EngineSelector` con todos los motores del catálogo como "posibles" y devuelve la fila que convendría descargar;
    - `fun inUse(status: PairStatus, totalRamBytes: Long, pref: EnginePreference): RowStatus?`: el motor que se usaría, solo entre los instalados;
    - `fun needsConfirmToDownload(row: RowStatus): Boolean = row.installed`: volver a descargar pide confirmación.

- [ ] **Step 1: Pruebas que fallan.**
  - **`AppSettingsTest`** (con un `SharedPreferences` falso en memoria, que implementa la interfaz):
    - por defecto `welcomeDone == false` y `enginePreference == AUTO`;
    - escribir y leer;
    - un valor desconocido se lee como `AUTO`.
  - **`HubRulesTest`:**
    - con el catálogo real de 4 modelos (copiar la lectura de `models/src/test/resources/catalog-prod/` vía `CatalogParser` o construir los objetos a mano), dos `PairStatus` en orden `en-es` y `es-en`, cada uno con filas `OPUS` y `FIREFOX`;
    - `recommended` con 8 GiB → OPUS, con 3 GiB → FIREFOX;
    - `inUse` con solo Firefox instalado y `AUTO` → Firefox; con `QUALITY` y OPUS no instalado → `null`;
    - `needsConfirmToDownload` es verdadero para una fila instalada;
    - sin catálogo → lista vacía.
- [ ] **Step 2:** `./gradlew :app:testFdroidDebugUnitTest` → FALLAN.
- [ ] **Step 3: Implementación.**
  - **`ModelHub`:**
    - reúne lo que hoy hace `EngineTestViewModel` para el catálogo, las descargas, borrar e importar (leerlo; **no** se modifica la pantalla de prueba salvo los imports);
    - un colector por modelo descargándose, con `Models.downloadInfo`, que actualiza `pairs`;
    - ninguna lectura de disco en el hilo principal.
- [ ] **Step 4:** Pruebas verdes; `./gradlew lint test assembleFdroidDebug` → verde.
- [ ] **Step 5: Commit** `feat(app): ajustes guardados y ModelHub compartido`.

---

### Task 5: Pantalla de prueba a debug, menú Desarrollador y comprobación del release (agentes `estructura` + `infraestructura`)

**Files:**
- Move: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/enginetest/{EngineTestScreen,EngineTestViewModel,EnginePicker,ScreenRules}.kt` → `app/src/debug/kotlin/io/github/diegobr4nd/lectorbilingue/ui/enginetest/`.
- Move: sus pruebas JVM → `app/src/testDebug/kotlin/...`.
- Move: los strings que solo usa la pantalla de prueba → `app/src/debug/res/values/strings.xml`.
- Create:
  - `app/src/debug/kotlin/io/github/diegobr4nd/lectorbilingue/ui/developer/DeveloperScreen.kt`;
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/DeveloperEntry.kt` (interfaz);
  - `app/src/debug/kotlin/.../ui/DeveloperEntryImpl.kt`;
  - `app/src/release/kotlin/.../ui/DeveloperEntryImpl.kt`.
- Modify: `.github/workflows/ci.yml` (paso nuevo).

**Interfaces:**
- Consumes: `AppSettings` (Tarea 4).
- Produces:
  - `interface DeveloperEntry { val available: Boolean; @Composable fun Screen(onBack: () -> Unit) }` y `object DeveloperEntries { val current: DeveloperEntry }`:
    - en debug, `available = true`; `Screen` muestra `DeveloperScreen`;
    - en release, `available = false`; `Screen` no muestra nada.
  - `DeveloperScreen` muestra un botón "Prueba del motor", que abre la `EngineTestScreen` actual, y un interruptor "Mostrar Bienvenida otra vez", que pone `welcomeDone = false`.

- [ ] **Step 1:** Mover los archivos con `git mv` (para que git siga su historia). Ajustar los paquetes si hace falta. `./gradlew assembleFdroidDebug assembleFdroidRelease testFdroidDebugUnitTest` → verde. `BenchmarkRunner` y `BenchText` se quedan en `main` (las pruebas instrumentadas los usan).
- [ ] **Step 2:** `DeveloperEntry` con sus dos implementaciones. Mientras la Tarea 6 no exista, `MainActivity` sigue abriendo la pantalla de prueba en debug **a través de `DeveloperEntries.current.Screen`**; en release muestra un `Text` provisional "Lector bilingüe", que la Tarea 6 reemplaza.
- [ ] **Step 3: CI.** Después de construir el release:
```yaml
      - name: El release no incluye la pantalla de desarrollador
        run: |
          apk=app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
          if unzip -p "$apk" 'classes*.dex' | grep -a -q -e 'ui/enginetest' -e 'ui/developer' -e 'EngineTestScreen'; then
            echo "::error::La versión release incluye código de la pantalla de desarrollador"; exit 1
          fi
```
  Comprobar en local que el comando no encuentra nada en el release y **sí** encuentra algo en el debug (`app-fdroid-debug.apk`).
- [ ] **Step 4: Pendiente de la 2c (spec §6).** En `EngineTestViewModel.selectPair`, los pasos previos (`checkSpanishBenchTexts()` y `refreshModels()`) van dentro del mismo `try` que `reloadEngine()`, con `finally` que nunca deja `LOADING` puesto si algo lanza. Agregar a `ScreenRulesTest` (o a una prueba del ViewModel con un hub falso) el caso "un paso previo lanza → el estado termina en ERROR, no en LOADING".
- [ ] **Step 5:** `./gradlew lint test assembleFdroidDebug assembleFdroidRelease compileFdroidDebugAndroidTestKotlin` → verde. **Commit** `refactor(app): la pantalla de prueba pasa a debug con menú Desarrollador`.

---

### Task 6: Navegación con Navigation 3 y arranque (agente `estructura`)

**Files:**
- Create:
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/nav/Routes.kt`;
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/nav/AppNav.kt`;
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/nav/StartRules.kt`.
- Modify: `MainActivity.kt`.
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/nav/StartRulesTest.kt`.

**Interfaces:**
- Consumes: `AppSettings`, `DeveloperEntries`.
- Produces:
  - `sealed interface Route { data class Welcome(val step: Int) : Route; data object Home : Route; data object Languages : Route; data object Developer : Route }`, serializable si la versión de Navigation 3 lo exige para guardar el historial;
  - `object StartRules { fun start(welcomeDone: Boolean): Route = if (welcomeDone) Route.Home else Route.Welcome(1); fun back(current: Route): Route? }`:
    - `back` devuelve el paso anterior en la Bienvenida;
    - en `Welcome(1)` devuelve `null`, y la app se cierra;
  - `@Composable fun AppNav(settings: AppSettings, hub: ModelHub)`:
    - historial `mutableStateListOf<Route>` con el inicio de `StartRules`;
    - `NavDisplay` con una entrada por ruta;
    - `Developer` solo si `DeveloperEntries.current.available`;
    - mientras las Tareas 7, 8 y 9 no existan, cada pantalla es un marcador simple con el nombre de la ruta y botones para navegar.

- [ ] **Step 1: Prueba que falla** (`StartRulesTest`):
  - `start(false) == Welcome(1)`;
  - `start(true) == Home`;
  - `back(Welcome(3)) == Welcome(2)`;
  - `back(Welcome(1)) == null`;
  - `back(Languages) == null`, porque lo saca la pila de `NavDisplay`, no la regla.
- [ ] **Step 2:** Implementar según la skill `navigation-3`. `ModelHub` vive en un `ViewModel` de nivel de actividad (o en la `Application`) para no recrearlo al girar la pantalla.
- [ ] **Step 3:** `./gradlew lint test assembleFdroidDebug assembleFdroidRelease` → verde. **Commit** `feat(app): navegación con Navigation 3 y arranque según la Bienvenida`.

---

### Task 7: Bienvenida en 3 pasos (agente `diseno`)

**Files:**
- Create:
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/welcome/WelcomeScreen.kt`;
  - `WelcomeViewModel.kt`;
  - `WelcomeRules.kt`.
- Modify: `AppNav.kt` (entrada real) y `app/src/main/res/values/strings.xml`.
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/welcome/WelcomeRulesTest.kt`; `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/WelcomeOnDeviceTest.kt`.

**Interfaces:**
- Consumes: `ModelHub`, `HubRules`, `AppSettings`, componentes de `:core:ui`, `Route`.
- Produces:
  - `object WelcomeRules`:
    - `fun step3(pairs: List<PairStatus>, catalogMessage: ModelMessage?, totalRam: Long): Step3`;
    - `data class Step3(val options: List<PairOption>, val canDownload: Boolean, val message: ModelMessage?)`;
    - `data class PairOption(val pair: String, val recommended: RowStatus, val selectedByDefault: Boolean)`: `en-es` preseleccionado y `es-en` no;
    - `fun downloadBytes(selected: List<PairOption>): Long`.

- [ ] **Step 1: Pruebas que fallan** (`WelcomeRulesTest`):
  - con el catálogo de 4 modelos y 8 GiB, las opciones son `en-es` (Calidad, preseleccionado) y `es-en` (Calidad); con 3 GiB, son Rápido;
  - `downloadBytes` suma solo las elegidas;
  - sin catálogo (`message = NO_CATALOG`) → `canDownload = false` y `options` vacías (Importar sigue visible: lo decide la pantalla, siempre);
  - si el recomendado ya está instalado, no se suma a la descarga y se muestra como "Ya instalado".
- [ ] **Step 2:** Implementar las pantallas según la spec §4.1 y el boceto aprobado (íconos `ic_menu_book`, `ic_lock`; títulos en Literata; indicador de paso con descripción "Paso N de 3").
  - **Descargar:** pide el permiso de notificaciones (como la pantalla de prueba), llama a `hub.download` para cada elegido, marca `welcomeDone = true` y navega a `Home` reemplazando el historial.
  - **Importar:** usa el selector de documentos, `hub.import`; si sale bien, marca `welcomeDone = true` y va a `Home`.
  - **Más tarde:** marca `welcomeDone = true` y va a `Home`.
  - **"Reducir movimiento":** la transición entre pasos no anima.
- [ ] **Step 3:** `WelcomeOnDeviceTest` (Compose, con un `ModelHub` falso, extrayendo una interfaz si hace falta):
  - recorrer 1 → 2 → 3 con "Siguiente";
  - Atrás vuelve de 3 a 2;
  - "Más tarde" llama a la acción de terminar;
  - el indicador anuncia "Paso 2 de 3".
- [ ] **Step 4:** Previews en claro y oscuro, teléfono y tableta, `fontScale` 2. `./gradlew lint test assembleFdroidDebug compileFdroidDebugAndroidTestKotlin` → verde. **Commit** `feat(app): Bienvenida en 3 pasos`.

---

### Task 8: Inicio provisional e Idiomas (agente `diseno`)

**Files:**
- Create:
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/home/HomeScreen.kt`, `HomeViewModel.kt`, `HomeRules.kt`;
  - `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/languages/LanguagesScreen.kt`, `LanguagesViewModel.kt`, `LanguagesRules.kt`.
- Modify: `AppNav.kt` y `strings.xml`.
- Test: `app/src/test/kotlin/.../ui/home/HomeRulesTest.kt`, `app/src/test/kotlin/.../ui/languages/LanguagesRulesTest.kt`, `app/src/androidTest/kotlin/.../LanguagesOnDeviceTest.kt`.

**Interfaces:**
- Consumes: `ModelHub`, `HubRules`, `AppSettings`, `EnginePreference`, `EngineSelector`, componentes de `:core:ui`.
- Produces:
  - `object HomeRules { fun cards(pairs: List<PairStatus>, ram: Long, pref: EnginePreference): HomeState }`:
    - `HomeState(val pairCards: List<PairCard>, val showNoLanguages: Boolean)`;
    - `PairCard(val pair: String, val engine: EngineKind?, val download: DownloadState?)`.
  - `object LanguagesRules`:
    - `fun rows(pairs: List<PairStatus>, ram: Long, pref: EnginePreference): List<LanguageCard>`, donde `LanguageCard(pair, rows: List<UiRow>)` y `UiRow(modelId, kind: EngineKind, sizeMb, state: ModelRowState, engine: EngineId)`;
    - `fun autoLine(ram: Long): AutoLine`, con `AutoLine(val kind: EngineKind, val ramGb: String)` ("Automático usa Calidad: tu teléfono tiene 8 GB");
    - `fun confirmFor(action: RowAction, row: UiRow): Confirm?`, donde `RowAction { DOWNLOAD, DELETE }`. Borrar siempre pide confirmación; descargar, solo si ya está instalado.

- [ ] **Step 1: Pruebas que fallan.**
  - **`HomeRulesTest`:**
    - sin modelos → `showNoLanguages`;
    - `en-es` OPUS instalado con `AUTO` y 8 GiB → una tarjeta Calidad;
    - una descarga activa → la tarjeta lleva `download`.
  - **`LanguagesRulesTest`:**
    - la fila en uso es `InUse` y la otra instalada es `Installed`;
    - `Downloading(fraction)` cuando hay descarga;
    - `confirmFor(DELETE)` nunca es `null`;
    - `confirmFor(DOWNLOAD)` es `null` si no está instalado;
    - `autoLine` con 3,6 GiB dice Rápido y "3,6 GB" (reusar el redondeo de `EnginePicker.ramText`, movido a `main` si hace falta).
- [ ] **Step 2: Implementar** según la spec §4.2 y §4.3 y el boceto aprobado:
  - el Inicio con el menú de tres puntos (`ic_more_vert`), que en debug incluye "Desarrollador";
  - el selector de motor (Automático / Calidad / Rápido) con rol de opción (radio) en TalkBack, que guarda en `AppSettings`;
  - `ConfirmDialog` para borrar ("¿Borrar Calidad (inglés → español)?", "Liberarás N MB…") y para volver a descargar;
  - errores con los mensajes fijos de `ModelActions`, redactados en lenguaje sencillo;
  - "Buscando…" mientras corre `hub.refresh()`, con el tope de 5 s.
- [ ] **Step 3:** `LanguagesOnDeviceTest` (Compose, `ModelHub` falso):
  - tocar Borrar abre el diálogo; Cancelar no llama a `delete` y Borrar sí;
  - el selector de motor expone `Role.RadioButton` y queda marcado tras tocarlo.
- [ ] **Step 4:** Previews (claro y oscuro, teléfono y tableta, `fontScale` 2) y `./gradlew lint test assembleFdroidDebug compileFdroidDebugAndroidTestKotlin` → verde. **Commit** `feat(app): Inicio provisional e Idiomas`.

---

### Task 9: Capturas de las Previews para Juan (agente `diseno`)

- [ ] **Step 1:** Generar imágenes de las Previews de Bienvenida (3 pasos), Inicio (con idioma y sin idiomas), Idiomas (con descarga en curso) y el diálogo de borrar, en claro y oscuro, teléfono y tableta, y letra al 200 %.
  - Usar la tarea de capturas de Compose Previews si está disponible en el AGP del proyecto; si no, capturas reales del Pixel con `adb exec-out screencap` navegando con `adb shell input`.
  - Guardarlas en el scratchpad del controlador, nunca en el repo.
- [ ] **Step 2:** Lista de rutas de las capturas en el reporte. El controlador se las muestra a Juan; los cambios que pida Juan vuelven a la Tarea 7 u 8 como ronda de arreglos.

---

### Task 10: Revisiones y auditoría (agentes `seguridad` y `diseno`, solo lectura)

- [ ] **Step 1: `seguridad`.** Revisa:
  - la dependencia Navigation 3 (licencia, mantenimiento, huellas en `verification-metadata.xml`);
  - fuentes e íconos (origen y licencias);
  - `AppSettings` (nada sensible guardado; `MODE_PRIVATE`; excluido de los respaldos por `data_extraction_rules.xml`);
  - el release sin código de desarrollador;
  - que ninguna pantalla nueva registre texto;
  - permisos y red sin cambios.
- [ ] **Step 2: `diseno`.** Revisión de las pantallas y **auditoría con la skill `material-3`** (modo auditoría), con la puntuación anotada. Checklist de accesibilidad de `04-diseno.md` §5. Comprobar que **ningún** `strings.xml` (main, debug y `:core:ui`) contiene emojis: buscar caracteres de los rangos Unicode de emoji y `✅`/`❌`/`⚡`/`📦`/`🔒`. La regla es cero emojis en la interfaz.
- [ ] **Step 3:**
  - Críticos y altos → los arregla el dueño, con re-revisión.
  - Medios baratos → un lote.
  - El resto → lista para Juan y memoria de pendientes.

---

### Task 11: Puerta 2d con Juan (sesión principal)

- [ ] **Step 1 (Pixel):**
  - `installFdroidDebug`, sin desinstalar;
  - en Desarrollador, "Mostrar Bienvenida otra vez" → recorrer la Bienvenida completa → Inicio;
  - capturas.
- [ ] **Step 2:** Idiomas en el Pixel:
  - borrar con confirmación (luego volver a descargar el mismo modelo);
  - volver a descargar con confirmación *(no alcanzable en la 2d: ver la nota de cierre en §4.3 de la spec; pasa a la Fase 3)*;
  - importar un `.zip` (con permiso de Juan para cada paso que borre o descargue);
  - cambiar el motor, cerrar la app (`am force-stop`), abrir y comprobar que se recordó.
- [ ] **Step 3:**
  - **TalkBack:** se activa con `adb shell settings put secure enabled_accessibility_services …` o Juan lo activa a mano. Recorrer la Bienvenida y empezar una descarga.
  - **Letra al 200 %:** `adb shell settings put system font_scale 2.0` → capturas → volver a `1.0`.
  - Anotar el resultado.
- [ ] **Step 4:** Pruebas instrumentadas en el Pixel: `ComponentsOnDeviceTest`, `WelcomeOnDeviceTest`, `LanguagesOnDeviceTest`, más las de motores (`FirefoxOnDeviceTest`, `OpusOnDeviceTest`, `SlimtCorruptModelOnDeviceTest`, `SlimtConfigOnDeviceTest`).
- [ ] **Step 5:**
  - revisión final de la rama, con el modelo más capaz, y una ronda de arreglos;
  - push con permiso de Juan;
  - PR **como borrador** con el texto preparado;
  - CI en verde;
  - Juan lo saca de borrador y lo fusiona.
