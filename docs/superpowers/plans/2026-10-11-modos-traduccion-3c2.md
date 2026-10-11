# 3c-2 · Modos de traducción · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dos modos de lectura nuevos — Intercalado (cada párrafo visible con su traducción debajo, sin tocar) y Solo traducción (la traducción en lugar del original; tocar alterna) — elegidos en la hoja "Aa", iguales para todos los libros.

**Architecture:** se reutilizan las tarjetas de la 3b. Un "guía de modo" en el `ReaderViewModel` pide a la página los párrafos visibles (+10) cuando queda quieta y los pide al `TranslationService` con prioridad; los scripts propios insertan tarjetas como texto y un atributo `data-lector-modo` hace que la hoja CSS oculte el original en Solo traducción.

**Tech Stack:** Kotlin, Compose M3, Readium 3.4.0 (`evaluateJavascript`, `currentLocator`), Room (caché de la 3b), corrutinas.

**Spec:** `docs/superpowers/specs/2026-10-11-modos-traduccion-3c2-design.md` (leerla antes de cada tarea).

## Global Constraints

- Rama `feat/modos-traduccion`; commits `feat(3c-2): …` / `fix(3c-2): …` / `test(3c-2): …` terminados en `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nunca push sin permiso de Juan; antes de cada push comprobar que el PR no está fusionado.
- Nunca registrar ni enviar por red texto de libros, traducciones ni huellas (la guardia `NoLoggingGuardTest` lo vigila).
- Hacia la página solo `textContent`; datos de scripts en JSON; listas fijas para atributos (`setMode`: `tocar | intercalado | solo`); sin `innerHTML` ni `addJavascriptInterface`; CSP sin cambios.
- Modo por defecto `TAP`, igual para todos los libros, guardado en `AppSettings`; valor dañado → `TAP`.
- Modos nuevos: se traduce lo visible + 10 párrafos; como máximo 15 pedidos del modo en la fila a la vez; solo se insertan tarjetas en párrafos visibles o más abajo (sin saltos).
- Motor: beam 1, ≤ 4 hilos; regla de memoria `EngineMemory.canLoad` (3c-1).
- Contraste AA en los 4 temas; 48 dp; estado elegido con ícono + texto + `stateDescription`; textos en `strings.xml` (es-419); comentarios en español; `minSdk` 26.
- Gradle: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon …` (la PC tiene poca memoria; parar daemons al terminar).
- Teléfono (Pixel 7, `2A261FDH200L5R`): solo `installFdroidDebug installFdroidDebugAndroidTest`, `am force-stop`, `am instrument -w -e class …`, `run-as … cat`, con `export MSYS_NO_PATHCONV=1`. Nunca desinstalar, `pm clear`, `connectedAndroidTest`, `adb input`, cambiar ajustes del sistema. Si **nuestra** app está al frente, Juan puede estar leyendo: no instalar ni cerrar, preguntar al controlador. Solo libros inventados; las pruebas devuelven los ajustes de Juan y nunca tocan su caché real.
- Reglas 8 y 9 de CLAUDE.md: `seguridad` (scripts) y `diseno` (pantalla).

## Review Focus

1. **Desplazar rápido varias pantallas:** la fila no se llena de pedidos viejos; solo lo que se ve ahora (y 10 más) se traduce primero → Tarea 4 (`desplazarRapidoSoloPideLoUltimoVisible`).
2. **Volver hacia arriba en Intercalado:** las tarjetas que se llenan por encima no mueven el párrafo que se está leyendo → Tarea 5 (`volverArribaNoSaltaElParrafoLeido`, en el Pixel).
3. **Cambiar de modo con traducciones a medias:** nada se queda en esqueleto para siempre; las tarjetas se adaptan al nuevo modo → Tarea 4 (`cambiarDeModoAMitadNoDejaEsqueletos`).
4. **Motor que falla o falta el modelo en los modos nuevos:** un solo aviso arriba y "Reintentar" vuelve a pedir lo visible; no hay un error por párrafo → Tarea 4 (`fallaElMotorUnSoloAviso`).
5. **Libro hostil en Solo traducción** (un `<p>` que intenta imitar la clase `lector-oculto` o `data-lector-modo`): el saneado lo quita y nada se esconde de más → Tarea 3 (prueba en `HtmlSanitizerTest`) y Tarea 6 (malicioso en modos).

---

### Task 1: Comprobación en el Pixel (desechable; agente `estructura`)

- [ ] **Step 1: M1 · visibles.** En la rama local `spike/3c2-modos`, script que devuelva los índices de los párrafos hoja (selector y filtro de la 3b) que intersectan el viewport (`getBoundingClientRect` vs `innerHeight`) + N siguientes; medir tiempo en un capítulo inventado de 300 párrafos (< 50 ms).
- [ ] **Step 2: M2 · sin saltos.** Insertar tarjetas (con `ParagraphScripts.insert`) en los visibles y más abajo: el `top` del primer párrafo visible no cambia (± 1 px). Insertar por encima (tras desplazar hacia abajo y volver): medir el salto; probar (a) guardar `currentLocator` + `go(guardado, animated = false)` y (b) compensar con `window.scrollBy(0, altoInsertado)` dentro del mismo script; elegir la que no parpadee.
- [ ] **Step 3: M3 · ocultar.** En "solo", ocultar el `<p>` con una clase y mostrar la tarjeta con estilo heredado; medir el salto del primer visible y anotar.
- [ ] **Step 4: M4 · estructura.** Capturas de un capítulo con `blockquote`, `ul/li` y encabezados en "solo".
- [ ] **Step 5: Resultado** en la spec §11 (tabla M1–M4 con números, técnica elegida para M2), commit `docs(3c-2): resultado de la comprobación en el Pixel`, borrar la rama spike.

---

### Task 2: `ReadingMode` en los ajustes (agente `estructura`)

**Files:** Modify `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/ReadingSettings.kt`, `data/AppSettings.kt`; Test `app/src/test/.../data/ReadingSettingsTest.kt`, `ui/reader/ReadingRulesTest.kt`.

**Interfaces — Produces:**
```kotlin
enum class ReadingMode(val wire: String) { TAP("tap"), INTERLEAVED("interleaved"), TRANSLATION_ONLY("translation_only") }
// ReadingSettings gana: val mode: ReadingMode = ReadingMode.TAP
```
- Clave `reading_mode`; dañado o desconocido → `TAP`. `ReadingRules.preferences` ignora el modo (no cambia `publisherStyles`); `isFactory` de estilos no debe depender del modo: si hace falta, separar `isStyleFactory`.

- [ ] **Step 1: Pruebas (fallan):** guardar/leer cada modo; `"ultra"` → TAP; cambiar solo el modo no cambia `ReadingRules.preferences(...)`; "Restablecer" vuelve también el modo a TAP.
- [ ] **Step 2: Implementar** y correr `:app:testFdroidDebugUnitTest :app:lintFdroidDebug`.
- [ ] **Step 3: Commit** `feat(3c-2): modo de lectura guardado en los ajustes`.

---

### Task 3: Scripts `visible`, `setMode`, `showOriginal` y CSS de los modos (agente `estructura`)

**Files:** Modify `ui/reader/ParagraphScripts.kt`, `ui/reader/ParagraphBridge.kt`, `app/src/main/assets/lector/tarjeta.css`, `books/.../readium/HtmlSanitizer.kt` (quitar del libro la clase `lector-oculto` y el atributo `data-lector-modo`); Test `ParagraphScriptsTest.kt`, `HtmlSanitizerTest.kt`, `app/src/androidTest/.../ParagraphBridgeOnDeviceTest.kt`.

**Interfaces — Produces:**
```kotlin
// ParagraphScripts
fun visible(following: Int = 10): String          // JSON [{i, t, cut}] como find
fun parseVisible(json: String?): List<PageParagraph>
fun setMode(mode: String): String                  // require(mode in MODES); MODES = setOf("tocar","intercalado","solo")
fun showOriginal(index: Int, on: Boolean): String  // alterna lector-oculto en el párrafo y data-lector-original en la tarjeta
// Card gana: data class Solo(val translation: String) : Card  (tarjeta con estilo de texto)
//            data class Waiting(val label: String) : Card     ("Traduciendo…" bajo el original en gris)
// ParagraphBridge: suspend fun visible(): List<PageParagraph>; suspend fun setMode(mode: String); suspend fun showOriginal(index: Int, on: Boolean)
```
- CSS (bajo `html[data-lector-modo="solo"]`, con `!important` y la especificidad de la 3c-1): la tarjeta Solo hereda `font`, `color`, `line-height` del párrafo, sin fondo ni línea; `p.lector-oculto { display:none !important }`; original en espera en gris AA (variable por tema); marca "Original" en el párrafo mostrado. La técnica anti-salto elegida en §11 (M2) va en `insert` si es por `scrollBy`.

- [ ] **Step 1: Pruebas JVM (fallan):** `setMode` rechaza valores fuera de la lista; `visible` usa el mismo selector/filtro que `find` y excluye tarjetas; `parseVisible` falla cerrado; ningún script nuevo usa `innerHTML`/`eval`; el saneado quita `lector-oculto` y `data-lector-modo` del libro (Review Focus 5).
- [ ] **Step 2: Implementar.**
- [ ] **Step 3: Prueba en el Pixel** (`ParagraphBridgeOnDeviceTest`): `visible()` devuelve los párrafos en pantalla; `setMode("solo")` + `show(i, Card.Solo(…))` oculta el original y la tarjeta toma la fuente del párrafo (`getComputedStyle`); `showOriginal(i, true)` lo vuelve a mostrar; texto hostil sigue como texto.
- [ ] **Step 4: Correr** JVM + Pixel. **Commit** `feat(3c-2): scripts y estilos de los modos de traducción`.

---

### Task 4: Guía de modo en el `ReaderViewModel` (agente `estructura`)

**Files:** Modify `ui/reader/ReaderViewModel.kt`, `ui/reader/CardRules.kt`; Test `app/src/test/.../ui/reader/ReaderViewModelTest.kt`, `CardRulesTest.kt`.

**Interfaces:**
- Consumes: `ReadingMode` (T2), `PageParagraph`, `TranslationService` (`translate`, `prefetch`, `cancelPrefetchExcept`, `release`), `TranslateResult`.
- Produces:
```kotlin
fun onVisible(resource: String, paragraphs: List<PageParagraph>)  // la pantalla la llama con bridge.visible() cuando la página queda quieta
val chapterNotice: StateFlow<ChapterNotice?>   // sealed: MissingModel(engine) | EngineFailed ; null = nada
fun retryChapter(resource: String)
// CardState gana: data class Solo(val translation: String), data object Waiting, data object ShowingOriginal (o equivalente)
```
- Reglas: en modos nuevos, `onVisible` toma los primeros visibles (los que tocan el viewport) como TAP en orden y los siguientes como PREFETCH; no repite los que ya tienen estado; tope 15 pedidos activos del modo; lo que sobra espera a la siguiente llamada. Al cambiar de recurso cancela lo del anterior. `MissingModel`/`EngineFailed` en modos nuevos → `chapterNotice` (no tarjeta por párrafo); `retryChapter` limpia el aviso y vuelve a pedir lo visible. El toque (`onTap`) por modo: TAP como 3b; INTERLEAVED oculta/muestra; TRANSLATION_ONLY alterna original. Cambiar de modo: emite las ops para adaptar cada tarjeta (Text ↔ Solo), cancela esqueletos huérfanos y pide lo visible. No anunciar a TalkBack las tarjetas que se llenan solas.

- [ ] **Step 1: Pruebas JVM (fallan)** — cada una completa, con `FakeEngine` (gate) de `TranslationFakes.kt`:
  - `intercaladoPideLoVisibleEnOrdenYLosSiguientesComoPretraduccion`
  - `topeDeQuincePedidos`
  - `desplazarRapidoSoloPideLoUltimoVisible` (Review Focus 1)
  - `cambiarDeCapituloCancelaLoPendiente`
  - `cambiarDeModoAMitadNoDejaEsqueletos` (Review Focus 3)
  - `fallaElMotorUnSoloAviso` y `reintentarCapituloVuelveAPedirLoVisible` (Review Focus 4)
  - `faltaElModeloUnSoloAviso`
  - `tocarEnIntercaladoOcultaYMuestra`, `tocarEnSoloAlternaOriginal`
  - `lasTarjetasAutomaticasNoSeAnuncian`
  - `modoTocarNoPideNadaAlDesplazar`
- [ ] **Step 2: Implementar.**
- [ ] **Step 3: Correr** `:app:testFdroidDebugUnitTest :app:lintFdroidDebug`. **Commit** `feat(3c-2): guía de modo que traduce lo visible`.

---

### Task 5: Pantalla (agente `diseno`; boceto primero)

- [ ] **Step 1 (5a): Boceto en texto** de la sección "Modo de lectura" en la hoja (copia de la spec §4), del aspecto de Solo traducción (texto, original en espera en gris con "Traduciendo…", marca "Original"), del aviso único arriba del capítulo, y paletas del gris en espera por tema con contraste. El controlador lo enseña a Juan (o lo confirma si es un detalle menor y reversible).
- [ ] **Step 2 (5b): Implementar** en `ReadingSettingsSheet.kt` (sección arriba, segmentado de 3, ayuda que cambia), `ReaderScreen.kt` (llamar `bridge.visible()` → `vm.onVisible` al quedar lista la página y en cada `currentLocator` nuevo, solo en modos nuevos; `setMode` al cambiar y al quedar lista la página antes de reinsertar; aviso único `chapterNotice` como banda arriba con "Descargar"/"Reintentar"; corrección anti-salto de §11), `ReaderPreviews.kt`, `strings.xml`.
- [ ] **Step 3: Pruebas en el Pixel** (motor real, libro inventado en inglés): `intercaladoSinSaltos` (primer visible ± 1 px mientras se llena), `volverArribaNoSaltaElParrafoLeido` (Review Focus 2), `soloTraduccionAlternaOriginal`, `cambiarDeModoNoRecarga` (`window.__marca`), y el modo se guarda. Devolver los ajustes de Juan en `@After`.
- [ ] **Step 4: Correr** JVM + Pixel. **Commit** `feat(3c-2): modos Intercalado y Solo traducción en el Lector`.

---

### Task 6: Revisiones, tiempos y verificación (agentes `seguridad`, `diseno`, revisor final)

- [ ] **Step 1: `seguridad`** sobre `main...feat/modos-traduccion` (scripts nuevos, CSS que oculta, saneado de `lector-oculto`/`data-lector-modo`) y pide los casos t1–t6 de `MaliciousEpubOnDeviceTest` también en Intercalado y Solo traducción.
- [ ] **Step 2: `diseno`:** capturas (Pixel, libro inventado) de los 3 modos × 4 temas, letra 200 %, tableta; auditoría `material-3` (≥ 80/100); contraste del gris en espera.
- [ ] **Step 3: Tiempos** (`TapTranslateTimingOnDeviceTest` o una nueva): primera pantalla traducida en Solo traducción con motor frío y caliente; anotar en §11.
- [ ] **Step 4: Correcciones** (una ronda), **revisión final** de toda la rama (modelo más capaz) y su ronda de arreglos.
- [ ] **Step 5: Verificación completa:** `./gradlew --no-daemon lint test assembleFdroidDebug assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest` y todas las instrumentadas en el Pixel (las 18 de la 3c-1 + las nuevas), en dos tandas para no agotar la memoria de la PC. Tamaño del APK.

---

### Task 7: Puerta 3c-2 con Juan (sesión principal)

- [ ] **Step 1:** Permiso para push; Juan abre el PR en borrador; CI (si falla por descarga de dependencias, relanzar).
- [ ] **Step 2:** Juan hace la puerta de la spec §1 (puntos 1–5) con un libro real.
- [ ] **Step 3:** Capturas aprobadas.
- [ ] **Step 4:** Memoria (3c-2 hecha; sigue 3c-3) y línea "Fase actual" de `CLAUDE.md` en la siguiente rama.
- [ ] **Step 5:** Juan fusiona.
