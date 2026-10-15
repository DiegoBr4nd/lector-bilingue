# 3c-3 · Guía de uso — diseño

Fecha: 2026-10-15 · Rama: `feat/guia-uso` · Fase 3 (última subfase antes de la Fase 6, publicación solo en Google Play al inicio).

## 1. Objetivo

Que una persona que nunca vio la app consiga, sin ayuda, añadir un libro, abrirlo y traducir un párrafo, y que descubra los modos, los capítulos y la dirección de traducción.

Lo pidió Juan: "cuando el usuario entre a la app, se le explique cómo funciona la app, y que todo quede muy claro".

Éxito:
- Recorrido de usuario nuevo en el Pixel: biblioteca vacía → libro → primer toque → traducción, guiado por los consejos.
- Ningún consejo tapa el párrafo que se lee; con letra al 200 % y con TalkBack todo se entiende.
- Lo explicado se puede releer en "Cómo se usa".

## 2. Decisiones de Juan (brainstorming)

| # | Pregunta | Decisión |
|---|---|---|
| 1 | Forma de la guía | **A** · consejos en el momento + página "Cómo se usa" (no más pasos en la Bienvenida, no libro de ejemplo) |
| 2 | Lista de consejos | Los 5 de §3, en orden, uno a la vez, una sola vez cada uno |
| 3 | Aspecto en el lector | **A** · tarjeta abajo con "Entendido"; se va sola al hacer lo que dice |
| 4 | Arquitectura | Marcas "ya visto" en AppSettings; reglas puras; un componente de tarjeta; ruta nueva "Cómo se usa" |
| 5 | Comportamiento y textos | §4 y §5 |
| 6 | Pruebas y revisiones | §8 |

Fuera de alcance: cambiar los 3 pasos de la Bienvenida; imágenes o animaciones de tutorial; libro de ejemplo; traducción de la interfaz al inglés (Fase 6).

## 3. Los consejos

| Id | Dónde | Cuándo sale | Texto | Se marca visto al… |
|---|---|---|---|---|
| `LIBRARY` | Biblioteca vacía (texto dentro del estado vacío, sin tarjeta) | Siempre que la biblioteca está vacía | "Toca «Añadir libro» para elegir un archivo EPUB del teléfono." + "¿No tienes libros? Hay EPUB gratis y legales en [Project Gutenberg] y [Standard Ebooks]." | No se marca: es parte del estado vacío |
| `TAP` | Lector | Al abrir un libro en modo **Tocar** si nunca se ha visto | "Toca un párrafo para ver su traducción." | Tocar un párrafo, o "Entendido" |
| `HIDE_AND_MODES` | Lector | Justo después de la primera traducción mostrada por un toque (modo Tocar) | "Toca otra vez para ocultarla. En Aa puedes leer todo traducido o intercalado." | Ocultar una tarjeta, abrir la hoja Aa, o "Entendido" |
| `CHAPTER_EDGE` | Lector | La primera vez que la lectura llega al final de un capítulo que no es el último | "Sigue deslizando para pasar al capítulo siguiente. También puedes usar el Índice o los botones de abajo." | Cambiar de capítulo (por cualquier vía), o "Entendido" |
| `DIRECTION` | Lector | Al abrir un libro cuya dirección efectiva es español → inglés, si nunca se ha visto | "Este libro se traduce al inglés. Puedes cambiarlo en ES → EN, arriba." (con la dirección real del libro) | Abrir la hoja de dirección, o "Entendido" |

Reglas:
- Uno a la vez. Si dos están pendientes, gana el de menor orden: `DIRECTION` < `TAP` < `HIDE_AND_MODES` < `CHAPTER_EDGE` (la dirección se explica antes de tocar, porque cambia lo que se verá). El otro espera a que el actual se cierre.
- `TAP` y `HIDE_AND_MODES` solo en modo Tocar (en Intercalado y Solo la traducción llega sola; quien eligió un modo ya pasó por Aa). Si el modo no es Tocar, siguen pendientes para cuando lo sea.
- Salir del lector (atrás, cerrar la app) sin cerrar el consejo **no** lo marca visto.
- Un consejo visto nunca vuelve a salir, en ningún libro, salvo "Volver a ver los consejos".
- Los textos con nombres de botones usan los mismos strings que esos botones ("Aa", "Índice", la etiqueta de dirección) para no desalinearse.

## 4. Comportamiento de la tarjeta (`TipCard`)

- Posición: sobre la barra inferior del lector, ancho completo con márgenes del tema; flota sobre el texto como las barras: puede tapar las últimas líneas de la pantalla mientras se ve (es breve, se cierra con «Entendido» o al hacer lo que dice), nunca el párrafo del medio. El consejo del borde se ve aunque las barras estén ocultas (ruling D de la ejecución). Si las barras se esconden al desplazarse, la tarjeta se esconde con ellas y vuelve con ellas.
- Contenido: ícono de bombilla (decorativo), texto (`bodyMedium`, sin límite de líneas), botón de texto "Entendido" (≥ 48 dp). Con letra ≥ 150 % el botón baja debajo del texto (misma idea que la banda del aviso de 3c-2).
- Colores: `secondaryContainer` / `onSecondaryContainer` del tema de la app (no los de la página), borde `outlineVariant`; contraste AA en claro y oscuro.
- TalkBack: el texto es una región viva Polite (se anuncia una vez al aparecer); "Entendido" es un botón aparte. Nada se anuncia dos veces.
- Movimiento: entra con un fundido corto; con "reducir movimiento" aparece sin animación.
- Coexistencia: si está la banda del aviso de capítulo (3c-2), la tarjeta espera a que la banda se vaya. Si hay una hoja abierta (Aa, Índice, dirección), la tarjeta no se muestra mientras esté abierta.

## 5. Pantalla "Cómo se usa"

- Ruta nueva en la navegación de la app. Entradas: menú ⋮ de la Biblioteca ("Cómo se usa") y un enlace al final de la hoja Aa del lector ("¿Cómo se usa la app?").
- Barra superior con atrás y título "Cómo se usa". Contenido desplazable, ancho máximo como la Bienvenida en tabletas.
- Secciones (título con `heading()` para TalkBack + 2–4 frases, con un ícono decorativo):
  1. **Añadir libros** — "Añadir libro" en la Biblioteca; solo EPUB por ahora; se guarda una copia privada; dónde conseguir libros gratis (mismos dos enlaces).
  2. **Tocar y traducir** — tocar un párrafo muestra su traducción debajo; tocar otra vez la oculta; la primera vez el traductor tarda unos segundos en prepararse.
  3. **Modos de lectura (Aa)** — Tocar, Intercalado, Solo traducción; en Solo traducción tocar un párrafo muestra el original. En Aa también: letra, tamaño, interlineado, márgenes y tema de página.
  4. **Capítulos e Índice** — seguir deslizando en el borde cambia de capítulo; botones de capítulo abajo; Índice arriba.
  5. **Idiomas y descargas** — la dirección (EN → ES / ES → EN) se elige por libro arriba; los idiomas se descargan una vez en "Idiomas" (menú ⋮) y después todo funciona sin internet; "Borrar traducciones" está allí.
  6. **Privacidad** — la traducción ocurre en el teléfono; los libros y lo que lees nunca salen de él; internet solo para descargar idiomas.
- Al final: botón "Volver a ver los consejos" → borra las marcas y muestra un mensaje breve "Los consejos volverán a aparecer" (región viva).
- Textos exactos: se escriben en el plan a partir de estas frases; Juan los revisa con el plan.

## 6. Arquitectura

- `data/AppSettings.kt`: `seenTips: Set<TipId>` guardado como un string con los ids separados por comas (clave `tips_seen`); `markTipSeen(id)`, `resetTips()`. Valor ilegible → conjunto vacío (en el peor caso un consejo sale otra vez). Flow como `readingSettingsFlow`.
- `ui/tips/TipRules.kt` (puro, sin Android): `enum class TipId { DIRECTION, TAP, HIDE_AND_MODES, CHAPTER_EDGE }`; `fun next(state: TipState): TipId?` donde `TipState` reúne: vistos, modo de lectura, dirección es→en, hubo traducción por toque en esta sesión, llegó al borde de capítulo no final, hay banda o hoja abierta. Más `fun dismissedBy(tip, event)` para los eventos que cierran cada consejo.
- `ui/tips/TipCard.kt`: el componente de §4 (con previews).
- `ui/reader/ReaderViewModel.kt`: expone `tip: StateFlow<TipId?>` calculado con `TipRules`; recibe eventos (toque con traducción, ocultar, cambio de capítulo, apertura de hojas, borde alcanzado) y "Entendido".
- `ui/reader/ReaderScreen.kt`: dibuja `TipCard` sobre la barra inferior; avisa al VM al llegar al borde usando `bridge.edges()` al asentarse el desplazamiento (ya existe para el cambio de capítulo y para `visible`).
- `ui/howto/HowToScreen.kt` (sin estado propio salvo el botón de reinicio) + ruta `Route.HowTo` en `ui/nav/Routes.kt`/`AppNav.kt` para la Biblioteca. En el lector (otra Activity) se muestra como capa a pantalla completa, igual que hoy "Idiomas" dentro del lector; atrás la cierra y vuelve a la misma posición de lectura.
- `ui/library/LibraryScreen.kt`: estado vacío con las dos frases y los enlaces; ítem "Cómo se usa" en el menú ⋮.
- Enlaces externos: `Intent(ACTION_VIEW, Uri)` con `try/catch ActivityNotFoundException` (sin navegador: no pasa nada y el nombre queda escrito). Rol de enlace para TalkBack. URLs fijas: `https://www.gutenberg.org` y `https://standardebooks.org`. La app no hace ninguna conexión: abre el navegador.
- Sin dependencias nuevas. Nada del libro en logs (NoLoggingGuardTest sigue cubriendo).

## 7. Errores

- Ajustes ilegibles → consejos como no vistos.
- Sin navegador → el enlace no hace nada visible, sin cierre de la app.
- El lector falla al cargar un capítulo → no se muestra ningún consejo de lector hasta que haya página.

## 8. Pruebas y revisiones

- JVM: `TipRulesTest` (prioridad, uno a la vez, solo Tocar para TAP/HIDE, borde solo en capítulo no final, eventos que cierran cada uno, salir sin cerrar no marca); `AppSettings` (guardar, leer, valor roto, reset); ReaderViewModel (secuencia TAP → HIDE_AND_MODES; "Entendido" marca; DIRECTION antes que TAP en libro en español).
- Pixel (`TipsOnDeviceTest`, libros inventados, ajustes de Juan guardados y devueltos, caché en memoria): recorrido nuevo completo; libro en español con DIRECTION; borde de capítulo; la tarjeta no tapa el párrafo tocado; letra 200 % texto entero y "Entendido" ≥ 48 dp; "Cómo se usa" desde Biblioteca y desde Aa; "Volver a ver los consejos".
- `diseno`: capturas de la tarjeta en los 4 temas, claro/oscuro de la app, letra 100/200 %, Biblioteca vacía, "Cómo se usa"; auditoría Material 3 ≥ 80.
- `seguridad`: enlaces externos (intents), nada nuevo de red.
- Puerta con Juan: recorrido de usuario nuevo en su Pixel (con "Volver a ver los consejos" para no borrar datos).
