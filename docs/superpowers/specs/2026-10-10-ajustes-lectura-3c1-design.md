# 3c-1 · Ajustes de lectura · Diseño

- **Fecha:** 2026-10-10. **Rama:** `feat/ajustes-lectura`. **Base:** `main` ef0771f (3a, 3b y el arreglo del cambio de capítulo fusionados: PR #15–#18).
- **3c partida en** (decisión de Juan): **3c-1 ajustes de lectura** → 3c-2 modos de traducción (Intercalado, Solo traducción) → 3c-3 guía de uso al entrar (o al inicio de la Fase 6). Cada parte con su spec, plan y PR.
- Decisiones tomadas con Juan en el brainstorming del 2026-10-10 (§2). Después de la 3c viene la Fase 6 (publicación); ver la memoria `plan-lanzamiento`.

## 1. Objetivo

Que cada persona ajuste cómo se ve el libro (tema, fuente, tamaño, interlineado, márgenes, alineación) desde una hoja en el Lector, con cambios al instante e iguales para todos los libros. Además: botones de capítulo anterior/siguiente accesibles y un botón para borrar las traducciones guardadas.

**Puerta 3c-1** (Juan, en el Pixel 7, con un libro real):
1. Cambiar tema, fuente, tamaño, interlineado, márgenes y alineación → el libro cambia al instante, sin recargar ni perder la posición.
2. Cerrar y reabrir la app → los ajustes siguen; otro libro se ve igual.
3. "Como el teléfono" sigue el modo oscuro del sistema.
4. La tarjeta de traducción se lee bien en los 4 temas (contraste AA).
5. Botones "Capítulo anterior/siguiente" funcionan; TalkBack anuncia el título del capítulo nuevo.
6. "Borrar traducciones guardadas" vacía el caché; después todo se vuelve a traducir al tocar.
7. Capturas aprobadas por Juan. CI en verde. `seguridad` y `diseno` sin hallazgos altos abiertos. PR en borrador hasta cerrar la puerta.

### Fuera de alcance
- Modos Intercalado y Solo traducción: 3c-2.
- Guía de uso al entrar: 3c-3.
- Ajustes por libro (decisión 1).
- Más pares de idiomas (después del lanzamiento).
- Diseño de tableta a dos columnas.

## 2. Decisiones tomadas (con Juan)

| # | Tema | Decisión | Por qué |
|---|---|---|---|
| 1 | Alcance de los ajustes | **Iguales para todos los libros** | Lo más simple; lo que hacen la mayoría de lectores |
| 2 | Fuentes | **Original del libro · Literata · Inter · Atkinson Hyperlegible** (todas OFL) | Lectura serif, sans limpia y una pensada para baja visión |
| 3 | Tema por defecto | **Como el teléfono** (claro ↔ oscuro según el sistema) hasta que se elija uno | Termina la página blanca con la interfaz oscura (pendiente de la 3a) |
| 4 | Borrar traducciones | En **Idiomas**, con lo que ocupan y confirmación | Ahí se gestiona el espacio; el caché no sabe de qué libro es cada párrafo |
| 5 | Arquitectura | **Enfoque A: lo que ya trae Readium** (`EpubPreferences`, temas, fuentes propias declaradas), sin tocar el saneado ni la CSP | B (CSS propio al sanear) repite Readium, choca con estilos del libro y obliga a recargar |
| 6 | Cambio de capítulo | Botones en la barra inferior, además del gesto del borde (PR #18) | Con TalkBack los gestos son del lector de pantalla; hoy solo servía el Índice |

## 3. Primera tarea: comprobación en el Pixel (desechable)

En una rama local que se borra al final:
- **C1 · Fuentes propias:** Literata, Inter y Atkinson servidas desde `assets/lector/fuentes/` por `readium_assets` (como `tarjeta.css`, ruta `servedAssets`) y declaradas con la API pública de Readium 3.4.0 para familias propias (`fontFamilyDeclarations` / `FontFamilyDeclaration` en la configuración de `EpubNavigatorFactory` o del fragmento). Con `EpubPreferences(fontFamily = …)` el texto usa la fuente (`getComputedStyle(p).fontFamily` y una medida de ancho que cambie). La CSP de `HtmlSanitizer` no cambia (la `font-src` ya admite `https:`).
- **C2 · Negro puro:** `theme = DARK` con `backgroundColor = #000000` y `textColor` claro aplicado con `submitPreferences` sin recargar; `getComputedStyle(document.documentElement).backgroundColor` es negro.
- **C3 · Al instante:** cambiar tamaño/tema con `submitPreferences` mantiene la posición (`currentLocator.progression` ± 0,02) y no recarga el recurso.
- **C4 · Tema de la tarjeta:** un atributo `data-lector-tema` en `<html>` puesto por script propio cambia las variables de `tarjeta.css`.
Resultado en §11. Si C1 exige cambiar la CSP, se decide con `seguridad` antes de seguir.

## 4. Qué ve el usuario

- **Barra superior:** `[<] Título… [Aa] [EN → ES] [☰]`. "Aa" (48 dp, descripción "Ajustes de lectura") va entre el título y el botón de idioma; con poco espacio cede el título.
- **Hoja "Ajustes de lectura"** (inferior; el libro se ve detrás; cada cambio se aplica al instante):
  1. **Tema:** "Como el teléfono" (marcado por defecto) y cuatro muestras con nombre: **Claro · Sepia · Oscuro · Negro**. La elegida: ✓ + negrita + `stateDescription`, nunca solo color.
  2. **Tamaño:** **A− / A+** con el valor en medio ("100 %"). De 75 % a 250 %, pasos de 10 %. Se suma al tamaño de letra del sistema.
  3. **Fuente:** cuatro filas, cada una escrita en su fuente: Original del libro · Literata · Inter · Atkinson Hyperlegible.
  4. **Interlineado:** Compacto · Normal · Amplio.
  5. **Márgenes:** Estrechos · Normales · Anchos.
  6. **Alineación:** Izquierda · Justificado (justificado con guiones).
  7. **Restablecer** (vuelve a los valores de fábrica).
- **Tarjeta de traducción:** colores por tema, contraste AA en los cuatro, línea izquierda siempre.
- **Barra inferior:** `[◀] Capítulo 3 · 42 % [▶]`. "Capítulo anterior/siguiente", 48 dp, apagados en los extremos. TalkBack anuncia el título del capítulo nuevo.
- **Idiomas, al final:** "Traducciones guardadas: 3,2 MB · Borrar". Confirmación: "¿Borrar las traducciones guardadas? Se volverán a traducir cuando toques los párrafos." Apagado si no hay nada.

## 5. Piezas

### 5.1 `ReadingSettings` y `ReadingRules` (`app`, `data/` y `ui/reader/`)
- `data class ReadingSettings(theme: PageTheme, fontScale: Double, font: ReadingFont, lineHeight: LineHeightLevel, margins: MarginLevel, align: TextAlignChoice)`; enums `PageTheme { SYSTEM, LIGHT, SEPIA, DARK, BLACK }`, `ReadingFont { ORIGINAL, LITERATA, INTER, ATKINSON }`, `LineHeightLevel { COMPACT, NORMAL, WIDE }`, `MarginLevel { NARROW, NORMAL, WIDE }`, `TextAlignChoice { START, JUSTIFY }`. Valores de fábrica: SYSTEM, 1.0, ORIGINAL, NORMAL, NORMAL, START.
- Guardado en `AppSettings` (SharedPreferences, igual que el motor elegido): una clave por campo con su `wire`; un valor desconocido o corrupto vuelve al de fábrica. Lectura fuera del hilo principal, como `loadEnginePreference`. Expone `StateFlow<ReadingSettings>`.
- `ReadingRules.preferences(settings, systemDark: Boolean): EpubPreferences` (puro, probado en la JVM): tema SYSTEM → LIGHT/DARK según `systemDark`; BLACK → DARK + `backgroundColor #000000` + `textColor` claro; tamaño acotado a [0,75; 2,5]; ORIGINAL → `fontFamily = null`; `publisherStyles = false` solo con interlineado distinto de NORMAL o JUSTIFY (Ruling K, según §11: tema, tamaño, fuente y márgenes funcionan con `true` y así el libro conserva su interlineado); interlineado y márgenes a valores fijos por nivel; JUSTIFY → `textAlign = JUSTIFY` + `hyphens = true`. `ReadingRules.cardTheme(settings, systemDark): String` → `claro | sepia | oscuro | negro`.
- `scroll = true` siempre (el modo desplazamiento de la 3a).

### 5.2 Fuentes (`app/src/main/assets/lector/fuentes/`)
- Literata e Inter (ya en `core/ui/res/font`) se copian a assets; se añade **Atkinson Hyperlegible** (OFL 1.1, regular, itálica y negrita), con sus archivos `OFL.txt` al lado. Servidas por `readium_assets` (`servedAssets = listOf("lector/.*")`, ya existe) y declaradas en Readium según §11 (C1).
- Las filas de la hoja muestran cada fuente con la misma familia en Compose.

### 5.3 Colores de la tarjeta por tema (`tarjeta.css`)
- Variables CSS (`--lector-fondo`, `--lector-linea`, `--lector-texto`, `--lector-enlace`, `--lector-error-fondo`, `--lector-error-linea`, `--lector-error-enlace`, `--lector-esqueleto`) con una paleta por `html[data-lector-tema="…"]`; claro = la paleta actual aprobada en la 3b.
- `ParagraphScripts.setTheme(tema)` (JSON + `document.documentElement.setAttribute('data-lector-tema', …)`, solo de una lista fija de valores) al quedar lista cada página (mismo punto que la reinserción de la 3b) y al cambiar el tema.
- Paletas con contraste AA sobre cada fondo, calculadas y anotadas por `diseno`.

### 5.4 `ReadingSettingsSheet` (`ui/reader/ReadingSettingsSheet.kt`)
- Hoja Material 3 como `DirectionSheet.kt`. Lee `vm.readingSettings` y llama `vm.setReadingSettings(…)`; el Lector aplica `navigator.submitPreferences(ReadingRules.preferences(…))` en un efecto que observa ajustes y modo del sistema.
- Cambios de ajuste no cierran tarjetas abiertas (solo cambian de color).

### 5.5 Botones de capítulo
- En `ReaderBottomBar`: `IconButton` anterior/siguiente (48 dp, `contentDescription` "Capítulo anterior"/"Capítulo siguiente"), apagados en el primer/último recurso. Llaman la misma función de paso de capítulo que el gesto del borde (PR #18). Tras el paso, `announce(título del capítulo nuevo)`.
- Con TalkBack la barra inferior ya está siempre visible (regla de la 3a).

### 5.6 Borrar traducciones
- `TranslationDao.count(): Int`, `TranslationDao.approxBytes(): Long` = `SELECT COALESCE(SUM(LENGTH(`key`) + LENGTH(translation)), 0)` × 2 (caracteres → bytes UTF-16 aproximados; se muestra con "aprox." redondeado a 0,1 MB) y `deleteAll()`.
- `TranslationService.clearCache()`: `release()` (vacía la fila y suelta el motor) y después `deleteAll()`, para que nada en curso vuelva a guardar justo después.
- Idiomas: fila al final con el tamaño y "Borrar" + `ConfirmDialog` existente; al terminar, un snackbar con el texto fijo "Traducciones borradas".

## 6. Errores
- Ajuste guardado corrupto → valor de fábrica, sin cierre.
- Fuente que no carga → Readium usa la del libro; la hoja sigue funcionando.
- Borrar falla → mensaje fijo "No se pudieron borrar las traducciones"; nada se registra.

## 7. Seguridad y privacidad (revisa `seguridad`, regla 8)
- Las fuentes se sirven por `readium_assets` desde assets de la app (no desde el libro ni `filesDir`); `servedAssets` sigue siendo `lector/.*`.
- `setTheme` solo acepta valores de una lista fija; datos en JSON; sin `innerHTML`; CSP sin cambios (o cambio aprobado por `seguridad` si C1 lo exige).
- `deleteAll()` borra solo la tabla `translations`.
- Nada nuevo en logs, red, permisos ni manifiesto. Licencias OFL de las fuentes en assets (pantalla de licencias en la Fase 6).

## 8. Accesibilidad y diseño (revisa `diseno`, regla 9)
- Boceto en texto de la hoja para aprobación de Juan antes de la pantalla.
- Contraste AA de página y tarjeta en los 4 temas.
- Áreas táctiles de 48 dp; estado elegido con ícono + texto + `stateDescription`.
- Tamaño de letra del sistema respetado y sumado al de la hoja; la hoja usable al 200 %.
- "Reducir movimiento": sin animaciones nuevas.
- Auditoría `material-3` de `ui/reader` y `ui/languages` (meta ≥ 80/100).

## 9. Pruebas
- **JVM:** `ReadingRules` (5 temas, SYSTEM con y sin oscuro, BLACK, límites de tamaño, ORIGINAL ↔ publisherStyles, niveles, justificado); `AppSettings` (guardar, leer, corrupto → fábrica); `clearCache` (suelta la fila antes de borrar); botones de capítulo (apagados en extremos) en las reglas del Lector; `setTheme` (JSON, lista fija).
- **Pixel:** cambiar tema y fuente con `submitPreferences` y comprobar con `getComputedStyle` fondo, fuente y variables de la tarjeta; la posición se mantiene; botones de capítulo con toque real; borrar traducciones vacía la tabla (con texto inventado); letra del sistema al 200 % (por semántica).

## 10. Riesgos
| Riesgo | Plan |
|---|---|
| Readium no sirve fuentes propias sin cambiar la CSP | C1 primero; decisión con `seguridad` |
| `publisherStyles = false` rompe libros con maquetación especial | Solo cuando la persona cambia un ajuste; "Original del libro" + valores de fábrica respeta el libro; "Restablecer" siempre vuelve |
| Cambiar ajustes pierde la posición | C3 lo mide; si pasa, guardar y restaurar el localizador alrededor de `submitPreferences` |
| Peso del APK por las fuentes | Solo los pesos necesarios (regular, itálica, negrita); medir antes/después |
| Paleta de la tarjeta poco legible en sepia/negro | `diseno` calcula contrastes; capturas en los 4 temas |

## 11. Resultado de la comprobación

Pixel 7 (`2A261FDH200L5R`), 2026-10-10, Readium 3.4.0, libro inventado de `ReaderTestBook` (70 párrafos por capítulo), rama local `spike/3c1-ajustes` (borrada). Prueba `SpikeAjustesOnDeviceTest` con `am instrument`: 6 pruebas, todas en verde; los números salen de `getComputedStyle`/`getBoundingClientRect` por `evaluateJavascript`. `HtmlSanitizer.kt` sin cambios (`git diff` vacío): **la CSP no cambia**.

| # | Resultado | Evidencia |
|---|---|---|
| C1 · Fuentes propias | ✅ | Original: `fontFamily` = `"Iowan Old Style", "Sitka Text", Palatino, "Book Antiqua", serif`, ancho de la frase de prueba 248,05 px. Con `fontFamily = FontFamily("Literata")`: `Literata`, 245,73 px, `FontFace` en estado `loaded`, `document.fonts.check('16px Literata')` = true. `Inter`: 237,61 px. Igual con `publisherStyles = false` y como `initialPreferences` al abrir (245,73 px). Readium mete los `@font-face` en un `<style>` en línea (la CSP ya permite `'unsafe-inline'` en `style-src` y `https:` en `font-src`). |
| C2 · Negro puro | ✅ | `theme = DARK` + `backgroundColor = #000000` + `textColor = #E6E6E6` → `:root` `rgb(0, 0, 0)`, `p` `rgb(230, 230, 230)`, `window.__marca` sigue (sin recarga). **Ojo:** `Theme.DARK` solo ya da `#000000`/`#FEFEFE`; SEPIA = `#FAF4E8`/`#121212`; LIGHT = `#FFFFFF`/`#121212`. |
| C3 · Al instante | ⚠️ ✅ con arreglo | Sin recarga en todos los casos (`window.__marca` sigue). Cambiar el tema no mueve la página (primer párrafo visible 17 → 17). **Cambiar el tamaño sí pierde la posición:** Readium deja el `scrollY` en píxeles (2749 px; alto 6284 → 11596) y el primer párrafo visible pasa de 31 a 17 (proporción 0,437 → 0,237); además `currentLocator` **no se actualiza** (sigue en 0,437 hasta el próximo desplazamiento), así que medirlo solo engaña. **Arreglo comprobado:** guardar `navigator.currentLocator.value` antes de `submitPreferences` y llamar `navigator.go(guardado, animated = false)` justo después (incluso en la misma corrutina del hilo principal, sin esperar): primer párrafo visible 31 → 31 en 1,0 → 1,5 → 1,0 y con 2,0 + interlineado + márgenes a la vez; progresión 0,43744 → 0,43743. |
| C4 · Tema de la tarjeta | ✅ con `!important` | `data-lector-tema="oscuro"` puesto por script en `<html>` → la tarjeta recibe la variable (`--lector-prueba` = `7px`) y su fondo cambia; el atributo **sigue** tras cada `submitPreferences`. Pero ReadiumCSS-after pisa colores con `!important`: en DARK `:root[style*=readium-night-on] :not(a){color:inherit;background-color:transparent;border-color:currentColor}` (fondo de la tarjeta → `rgba(0, 0, 0, 0)`), en SEPIA lo mismo sin el borde, y con `backgroundColor`/`textColor` `:root[style*="--USER__backgroundColor"] *` y `:root[style*="--USER__textColor"] :not(h1)…:not(pre)` (especificidad 0,2,7). Con `html[data-lector-tema] aside.lector-tarjeta.lector-tarjeta { background-color/color/border-left-color: var(--…) !important }` (especificidad 0,3,2) la tarjeta queda `rgb(30, 42, 74)` / `rgb(230, 230, 230)` / línea `rgb(142, 162, 255)` en DARK, SEPIA, DARK + negro y LIGHT + `publisherStyles = false`. |

### API exacta de Readium 3.4.0 (para las tareas siguientes)

- **Declarar fuentes** en `EpubNavigatorFragment.Configuration` (la que ya se pasa a `createFragmentFactory(configuration = …)`; `EpubNavigatorFactory.Configuration` solo tiene `defaults`). `addFontFamilyDeclaration` es `@ExperimentalReadiumApi` (pide `@OptIn(ExperimentalReadiumApi::class)`):
  ```kotlin
  EpubNavigatorFragment.Configuration(servedAssets = listOf("lector/.*")).apply {
      disablePageTurnsWhileScrolling = true
      addFontFamilyDeclaration(FontFamily("Literata")) {          // org.readium.r2.navigator.preferences.FontFamily
          addFontFace {                                           // MutableFontFaceDeclaration
              addSource("lector/fuentes/literata_regular.ttf")   // relativa a assets/ → https://readium_assets/lector/fuentes/…
              setFontStyle(FontStyle.NORMAL)                      // org.readium.r2.navigator.epub.css.FontStyle { NORMAL, ITALIC }
              setFontWeight(FontWeight.NORMAL)                    // org.readium.r2.navigator.epub.css.FontWeight (THIN…BLACK) o un rango 100..900
          }
      }
  }
  ```
  Firma: `addFontFamilyDeclaration(fontFamily: FontFamily, alternates: List<FontFamily> = emptyList(), builder: MutableFontFamilyDeclaration.() -> Unit)`. `addSource(path: String, preload: Boolean = false)` (también acepta `Url`); la ruta se resuelve contra `https://readium_assets/`, así que la sirve `servedAssets = listOf("lector/.*")` sin cambios. Una `addFontFace` por archivo (regular, itálica, negrita); para una fuente variable, `setFontWeight(100..900)`. Nombres de familia probados: `"Literata"`, `"Inter"` (Atkinson: `"Atkinson Hyperlegible"`, misma forma). Readium ya declara por su cuenta AccessibleDfA, IA Writer Duospace y OpenDyslexic (no se descargan si no se eligen).
- **`EpubPreferences`** (`org.readium.r2.navigator.epub`), todos nulables: `fontFamily: FontFamily?`, `theme: Theme?`, `backgroundColor: Color?`, `textColor: Color?`, `fontSize: Double?` (1,0 = 100 %; el editor de Readium admite 0,1–5,0; 1,5 → 24 px), `lineHeight: Double?` (1,0–2,0), `pageMargins: Double?` (0,0–4,0; **multiplica** `--RS__pageGutter`, que en el Pixel 7 vale 20 px: 0,5 → 10 px, 1,5 → 30 px, 2,0 → 40 px de relleno a cada lado; la línea sigue limitada a 40rem = 640 px), `textAlign: TextAlign?`, `hyphens: Boolean?`, `publisherStyles: Boolean?`, `scroll: Boolean?`. Los valores del plan (`MARGINS` 0,5/1,0/1,6, `LINE_HEIGHT` 1,3/1,5/1,8) caben en estas escalas.
- **Qué pide `publisherStyles = false`:** `lineHeight`, `textAlign` y `hyphens` (sin él: interlineado 24,21 px y `text-align: start`, o sea, ignorados; con él: 28,8 px, `justify`, `hyphens: auto`). `fontFamily`, `fontSize`, `pageMargins` (1,5 → 30 px), `theme` y los colores funcionan también con `publisherStyles = true`.
- **Tipos:** `Color` = `org.readium.r2.navigator.preferences.Color` (clase de valor, `Color(0xFF000000.toInt())`, se lee con `.int`); `Theme` = `org.readium.r2.navigator.preferences.Theme { LIGHT, DARK, SEPIA }`; `TextAlign` = `org.readium.r2.navigator.preferences.TextAlign { CENTER, JUSTIFY, START, END, LEFT, RIGHT }` (no confundir con `org.readium.r2.navigator.epub.css.TextAlign` ni con `Color` de Compose); `FontFamily` = `org.readium.r2.navigator.preferences.FontFamily` (clase de valor, `FontFamily("Literata")`).
- **Aplicar:** `EpubNavigatorFragment.submitPreferences(preferences: EpubPreferences)` (hilo principal). **Reemplaza** todas las preferencias (no las suma): mandar siempre `scroll = true`. `go(locator: Locator, animated: Boolean): Boolean` no es `suspend` en 3.4.0. `initialPreferences` de `createFragmentFactory` acepta las mismas preferencias (fuentes propias incluidas).

### Consecuencias para el plan
1. **Oscuro ≠ Negro:** como `Theme.DARK` ya es `#000000`, "Oscuro" necesita su propio `backgroundColor` (un gris muy oscuro) y `textColor`, que fija `diseno`; "Negro" = DARK + `#000000`.
2. **Posición al cambiar ajustes:** el efecto que llama `submitPreferences` guarda antes `currentLocator.value` y llama `go(guardado, animated = false)` después (C3). Abrir el Lector con `initialPreferences = ReadingRules.preferences(…)` evita un reajuste al entrar.
3. **Hoja de la tarjeta:** en los temas, los colores (`background-color`, `color`, `border-left-color` y el color de `.lector-reintentar`) van con `!important` bajo `html[data-lector-tema] aside.lector-tarjeta.lector-tarjeta` (o más específico) para ganar a ReadiumCSS. Falta comprobar en la tarea de la hoja el esqueleto (`::after`; `*` no toca pseudoelementos) y los estados de error.
4. **CSP sin cambios:** C1 no la toca; no hace falta decisión de `seguridad` por esto (sí su revisión normal de los archivos servidos).
