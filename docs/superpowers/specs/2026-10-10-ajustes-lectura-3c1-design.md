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
- `ReadingRules.preferences(settings, systemDark: Boolean): EpubPreferences` (puro, probado en la JVM): tema SYSTEM → LIGHT/DARK según `systemDark`; BLACK → DARK + `backgroundColor #000000` + `textColor` claro; tamaño acotado a [0,75; 2,5]; ORIGINAL → `fontFamily = null` y `publisherStyles = true`; cualquier otro ajuste distinto de fábrica → `publisherStyles = false`; interlineado y márgenes a valores fijos por nivel; JUSTIFY → `textAlign = JUSTIFY` + `hyphens = true`. `ReadingRules.cardTheme(settings, systemDark): String` → `claro | sepia | oscuro | negro`.
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
(Se completa en la Tarea 1 del plan.)
