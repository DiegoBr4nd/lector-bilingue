# 3c-2 · Modos de traducción · Diseño

- **Fecha:** 2026-10-11. **Rama:** `feat/modos-traduccion`. **Base:** `main` 3b3bb4a (3a, 3b y 3c-1 fusionadas: PR #15–#19).
- **3c partida en:** 3c-1 ajustes de lectura (hecha) → **3c-2 modos de traducción** → 3c-3 guía de uso. Después, la Fase 6 (publicación).
- Decisiones tomadas con Juan en el brainstorming del 2026-10-11 (§2).

## 1. Objetivo

Además del modo actual (Tocar), dos modos de lectura: **Intercalado** (cada párrafo con su traducción debajo, sin tocar) y **Solo traducción** (se lee la traducción como si el libro estuviera en tu idioma; tocar muestra el original). Todo en el teléfono, con el mismo motor, caché y dirección por libro de la 3b.

**Puerta 3c-2** (Juan, en el Pixel 7, con un libro real):
1. Intercalado: las traducciones aparecen solas mientras lee, sin saltos de la página.
2. Solo traducción: se lee como un libro en su idioma; tocar un párrafo muestra el original y otro toque vuelve.
3. Cambiar de modo es instantáneo (sin recargar ni perder el sitio) y se recuerda al reabrir la app y en otro libro.
4. Cambiar la dirección ("EN → ES") vuelve a traducir lo visible.
5. 10 minutos de lectura en Solo traducción: cómo se siente.
6. Capturas aprobadas por Juan. CI en verde. `seguridad` y `diseno` sin hallazgos altos abiertos. PR en borrador hasta cerrar la puerta.

### Fuera de alcance
- Traducir el capítulo o el libro entero en segundo plano (Fase 4).
- Modo por libro (decisión 1).
- Más pares de idiomas (después del lanzamiento).
- Guía de uso al entrar (3c-3).

## 2. Decisiones tomadas (con Juan)

| # | Tema | Decisión | Por qué |
|---|---|---|---|
| 1 | Dónde y alcance | En la hoja **"Aa"**, arriba de todo, igual para todos los libros | Coherente con la 3c-1; la barra ya tiene 4 botones |
| 2 | Cuánto se traduce | **Lo visible + ~10 párrafos adelante**, avanzando al leer; lo de atrás queda en caché | Rápido para empezar y gasta solo lo que se lee |
| 3 | Ver el original en Solo traducción | **Tocar el párrafo alterna** traducción ↔ original | Tan fácil como el modo Tocar y funciona con TalkBack; mantener presionado choca con la selección de texto |
| 4 | Arquitectura | **Enfoque A: reutilizar las tarjetas de la 3b** (scripts propios, solo `textContent`) | Mantiene la seguridad, la fila, el caché y la reinserción; B (traducir al sanear) exige el capítulo entero antes de mostrarlo y toca el saneado; C (vista propia) pierde Readium |
| 5 | Toque en Intercalado | Oculta/muestra la traducción de ese párrafo (decisión del controlador, delegada por Juan) | Los tres modos responden igual al toque |
| 6 | Solo traducción sin traducir aún | Original en gris claro + "Traduciendo…" (decisión del controlador, delegada) | Nunca una página vacía |

## 3. Primera tarea: comprobación en el Pixel (desechable)

En una rama local que se borra al final:
- **M1 · Párrafos visibles:** un script `visible(following)` devuelve los índices de los párrafos hoja (lista de la 3b, sin tarjetas) que intersectan el viewport más los N siguientes; se llama al quedar quieta la página (`currentLocator` nuevo) y responde en < 50 ms en un capítulo de 300 párrafos.
- **M2 · Sin saltos:** insertar tarjetas en párrafos visibles y más abajo no mueve el primer párrafo visible (± 1 px). Insertar por encima (al volver hacia arriba) sí lo mueve; medir si guardar el localizador y `go(guardado)` (truco de la 3c-1) lo corrige sin parpadeo, o si conviene compensar `scrollBy` desde el script (resultado: `scrollBy`; `go(guardado)` descartado).
- **M3 · Ocultar sin saltos:** en Solo traducción, ocultar el original (cualquier hoja, `lector-oculto`) y mostrar la tarjeta con el estilo del texto del libro no mueve el primer párrafo visible más que la diferencia de alto entre original y traducción; medirlo y anotar.
- **M4 · Libros con estructura:** citas (`blockquote`), listas (`li`) y encabezados ocultos/reemplazados se ven bien (capturas).
Resultado en §11 (hecha el 2026-10-11). **Decidido allí:** no se usa `go(guardado)`; cada script de tarjeta (`insert`, `remove`, `showOriginal`) compensa con `scrollBy` anclado en el primer bloque que empieza en pantalla (técnica b); `lector-oculto` vale para toda hoja; en Solo traducción la tarjeta copia una lista fija de propiedades calculadas del original.

## 4. Qué ve el usuario

- **Hoja "Aa"**, arriba de todo: "Modo de lectura" con tres botones segmentados **Tocar · Intercalado · Solo traducción** (elegido con ✓ + negrita + `stateDescription`) y una línea de ayuda que cambia: "Toca un párrafo para ver su traducción" / "Cada párrafo con su traducción debajo" / "Toca un párrafo para ver el original". Cambiar de modo se aplica al instante.
- **Intercalado:** los párrafos visibles reciben su tarjeta (esqueleto "Traduciendo…") y se llenan en orden de arriba abajo; al desplazar se traducen los ~10 siguientes; lo ya traducido sale al instante. Tocar un párrafo oculta/muestra su traducción.
- **Solo traducción:** el texto traducido con la letra, tamaño y colores del libro (la tarjeta pierde fondo y línea). Párrafo aún sin traducir: original en gris claro + "Traduciendo…". Tocar muestra el original con una marca pequeña "Original"; otro toque vuelve a la traducción.
- **Avisos en los modos nuevos:** una sola tarjeta arriba del capítulo (no una por párrafo): "Falta el idioma … · Descargar" o "No se pudo preparar el traductor · Reintentar". Párrafo demasiado largo: su propia tarjeta (como la 3b).
- **Botón "EN → ES":** cambiar la dirección vuelve a traducir lo visible.
- **TalkBack:** Solo traducción lee la traducción; el original con toque doble (y se anuncia "Original"). Intercalado lee cada párrafo y después "Traducción: …". No se anuncia cada tarjeta que se llena sola (solo las pedidas con toque).

## 5. Piezas

### 5.1 `ReadingMode` (`data/ReadingSettings.kt`, `AppSettings`)
- `enum class ReadingMode(val wire: String) { TAP("tap"), INTERLEAVED("interleaved"), TRANSLATION_ONLY("translation_only") }`, campo `mode` en `ReadingSettings` (fábrica `TAP`), clave `reading_mode` en `AppSettings`; valor dañado → `TAP`. El modo no afecta a `isFactory` de los estilos del libro (`publisherStyles` sigue la regla K de la 3c-1).

### 5.2 Scripts (`ui/reader/ParagraphScripts.kt`, `ParagraphBridge.kt`)
- `visible(following: Int = 10)` → JSON `[{i, t, cut}]` de los párrafos hoja que intersectan el viewport y los `following` siguientes (mismo recorte y marca `cut` de la 3b).
- `setMode(mode)` → `data-lector-modo` en `<html>` desde una lista fija (`tocar | intercalado | solo`), como `setTheme`.
- `insert` gana el estado "solo": antes de ocultar el original, la tarjeta copia de él una **lista fija** de propiedades calculadas con `style.setProperty(…, 'important')` (`display, font-family, font-size, font-weight, font-style, line-height, text-indent, text-align, letter-spacing, word-spacing, text-transform, font-variant, margin-*, padding-*, list-style-type, list-style-position`; §11 M4; las tres de espaciado/versalitas se añadieron en la revisión de la Tarea 3), y el original recibe la clase `lector-oculto` (cualquier hoja: `p, li, blockquote, h1–h6, dd`). `showOriginal(i, on)` alterna. La tarjeta Solo y `showOriginal` no hacen nada fuera del modo "solo". Al salir de "solo" se quita el estilo copiado. Siempre `textContent`, JSON, sin `innerHTML`; el `setProperty` pasa por `seguridad` (regla 8).
- **Sin saltos (técnica b, §11 M2):** `insert`, `remove` y `showOriginal` (y `setMode`) miden, dentro del mismo script, el `top` del ancla, hacen el cambio y corrigen con `window.scrollBy(0, Δ)` antes de que se pinte. No se usa `go(guardado)`.
  - Política de ancla (`CardAnchor`, Ruling F): `self` fija el propio párrafo cambiado (TAP: la tarjeta sale justo debajo del tocado, como en la 3b); `screen` (Intercalado y Solo) fija el primer bloque hoja que empieza dentro de la pantalla (`0 ≤ top < innerHeight`, alto > 0); si no hay (un párrafo más alto que la pantalla), el que cruza el borde de arriba (`top < 0 < bottom`), por su `top`; si tampoco, no se corrige.
- `visible()`: un original oculto se mide con el rectángulo de su tarjeta.
- `tarjeta.css`: reglas por `data-lector-modo` (en `solo`, la tarjeta sin fondo ni borde y con `color: inherit`; el resto del estilo lo copia el script; `.lector-oculto { display:none !important }` para toda hoja, solo en `solo`); marca "Original"; gris claro para el original en espera (contraste AA).

### 5.3 Guía de modo (`ReaderViewModel`)
- En INTERLEAVED y TRANSLATION_ONLY: al quedar lista la página y en cada `currentLocator` nuevo (página quieta), pide `visible(10)`; los visibles van con prioridad TAP en orden de arriba abajo; los siguientes, como PREFETCH. Tope: como máximo **15 pedidos** del modo en la fila a la vez; los que sobran se piden en la siguiente vuelta.
- Estado por párrafo: `Translating | Shown(text) | Hidden | ShowingOriginal | TooLong`; aviso único de capítulo `MissingModel | EngineFailed`.
- Cambiar de capítulo: cancela lo pendiente del anterior (`cancelPrefetchExcept`, ya existe) y empieza de nuevo. Cambiar de modo: `setMode`, cierra o adapta las tarjetas y vuelve a pedir lo visible. Cambiar dirección: como en la 3b (cierra y retraduce).
- Sin texto del libro en logs ni en `SavedStateHandle`.

### 5.4 Pantalla (`ReaderScreen`, `ReadingSettingsSheet`)
- Sección "Modo de lectura" en la hoja.
- El toque: en TAP como la 3b; en INTERLEAVED oculta/muestra; en TRANSLATION_ONLY alterna original.
- Sin saltos: solo se insertan tarjetas en párrafos visibles o más abajo (límite de trabajo); la corrección es la de los scripts (técnica b, `scrollBy` anclado en el primer bloque que empieza en pantalla), también al volver hacia arriba. La pantalla no llama a `go(guardado)` por las tarjetas.

## 6. Errores
- Motor que no carga o falta modelo → aviso único arriba; los párrafos quedan en original (Solo traducción) o sin tarjeta (Intercalado); "Reintentar" vuelve a pedir lo visible.
- Párrafo que falla → su tarjeta de error de la 3b, sin bloquear a los demás.
- Página recargada antes de insertar → se descarta; la reinserción la recupera.

## 7. Seguridad y privacidad (revisa `seguridad`)
- Mismas reglas que la 3b: de la página solo `textContent`; hacia la página solo texto con `textContent`; scripts con datos en JSON; `setMode` con lista fija; sin `addJavascriptInterface`; CSP sin cambios.
- `MaliciousEpubOnDeviceTest`: los casos t1–t6 de la 3b también en Intercalado y Solo traducción (nada se ejecuta, nada en logs, ninguna conexión).

## 8. Accesibilidad y diseño (revisa `diseno`)
- Boceto en texto de la sección de modo, del aspecto de Solo traducción (incluido el original en espera y la marca "Original") y del aviso único, para aprobación de Juan antes de la pantalla.
- Contraste AA en los 4 temas (también el gris del original en espera).
- 48 dp; estado elegido con ícono + texto + `stateDescription`; letra 200 %; reducir movimiento (esqueleto quieto).

## 9. Pruebas
- **JVM:** guía de modo (qué pide, orden, prioridad, tope de 15, cancelar al cambiar de capítulo, cambio de modo y de dirección), estados por párrafo, toque por modo, `ReadingMode` guardado y dañado → TAP, scripts (`visible`, `setMode` con lista fija, JSON).
- **Pixel (motor real, libro inventado en inglés):** Intercalado sin saltos (primer párrafo visible ± 1 px al llenarse); Solo traducción alterna original; cambiar de modo no recarga (`window.__marca` sigue); malicioso t1–t6 en los modos nuevos; tiempo hasta la primera pantalla traducida en Solo traducción.

## 10. Riesgos
| Riesgo | Plan |
|---|---|
| Saltos al insertar por encima de lo visible | Medido en M2 (§11): regla "solo visibles y más abajo" + técnica b (`scrollBy` en el mismo script, anclado en el primer bloque que empieza en pantalla); el *scroll anchoring* de Chrome ayuda arriba, pero depende de la versión del WebView; se confirma en la puerta con un libro real |
| Libros con maquetación especial en Solo traducción | M4 con citas y listas; "Tocar" siempre disponible |
| Batería y calor | Solo lo visible + 10; tope de 15 en la fila |
| Mucho tráfico de scripts al desplazar | Se pide solo con la página quieta (`currentLocator` ya tiene antirrebote) |
| Memoria del motor | La regla `EngineMemory` de la 3c-1; aviso único si falla |

## 11. Resultado de la comprobación

Pixel 7, 2026-10-11, build de depuración (base e8dc241), tema claro, letra 100 %. Libro inventado: un capítulo de **361 hojas** (h1 + 300 `p` + 12 secciones con `h2`, `blockquote` y `ul` de 3 `li`); viewport 786 px CSS. Rama `spike/3c2-modos` (borrada). Tarjetas con texto inventado (sin motor). "A" = primer párrafo visible (su borde de abajo ya pasó el tope); "B" = primer párrafo que **empieza** en pantalla (lo que se está leyendo). Los saltos se midieron cuadro a cuadro con `requestAnimationFrame` ("cuadros movidos" = cuadros con más de 1 px de diferencia).

| # | Qué | Resultado | ¿Pasa? |
|---|---|---|---|
| M1 | `visible(10)` (selector y filtro de la 3b, `getBoundingClientRect` vs `innerHeight`, mismo recorte y `cut`) | JS **0,8–8,4 ms** (1.ª llamada 7,3 ms); ida y vuelta Kotlin↔página **5,6–18,6 ms**. Peor caso, con una tarjeta bajo cada una de las 361 hojas: JS **9,9–13,7 ms**, ida y vuelta **21–45 ms**. Devuelve 15–19 párrafos (~3 KB). | Sí: la meta de 50 ms es la **ida y vuelta** (lo que espera la app); el peor caso, 45 ms, deja poco margen |
| M2a | Tarjetas en visibles y más abajo (19 tarjetas, esqueleto → texto, una por llamada) | A: **0 px**. Pero B baja **166 px** (153 de 155 cuadros): la tarjeta del párrafo A, cortado arriba, empuja todo lo que se lee. | No, sin corrección |
| M2a + (b) | Igual, con `scrollBy` en el mismo script anclado en **B** | B: **0,2 px**, 0 cuadros movidos (A sale por arriba, como se espera). Compensa 94,9 px en la 1.ª inserción; las demás, 0. | Sí |
| M2b | 6 tarjetas **por encima** de la pantalla, sin corregir | A y B: **0,3 px**, 0 cuadros movidos. El WebView ya trae el *scroll anchoring* de Chrome (`overflow-anchor: auto` en `html` y `body`): corrige solo lo que cambia arriba (`scrollY` +761 px). | Sí |
| M2b + (a) | Guardar `currentLocator` + `go(guardado, animated = false)` (truco de la 3c-1) | **Empeora:** salto final de **381 px** (capítulo de 361 bloques) y 94 cuadros movidos. El localizador en modo continuo solo trae `progression` (claves `progression`, `position`, `totalProgression`, sin texto), y la progresión se recalcula sobre un capítulo ahora más alto. | **No** |
| M2b + (b) | `scrollBy` en el mismo script | 0,7 px, 0 cuadros movidos; compensa ±0,2 px (el *anchoring* ya lo hizo; no se suman). | Sí |
| M3 | Solo traducción: ocultar el original (`lector-oculto`) y poner la tarjeta en su lugar, en el mismo script | El reemplazo del primer párrafo queda en su sitio (A: 0 px) y B baja exactamente la diferencia de alto entre original y traducción (**96,8 px**; 48 → 145 px en ese párrafo), como preveía §3. Con la compensación (b) anclada en B: **0,1 px**, 0 cuadros movidos. | Sí, con (b) |
| M4 | Citas, listas y encabezados en Solo traducción (capturas) | Con solo CSS heredado (`font-size: inherit`…) **se pierde la estructura**: el `h2` sale como párrafo, la cita sin sangría, los `li` sin viñeta. Copiando a la tarjeta el estilo calculado del original (lista fija, abajo) se ven como el libro: título grande, cita con sangría, viñetas; también en oscuro (`color: inherit`). | Sí, copiando estilo |

**Técnica elegida para insertar sin saltos: (b) `scrollBy` dentro del mismo script, anclado en el primer párrafo que empieza en pantalla (B).**
- Por qué: el script mide el `top` de B, inserta (o cambia) la tarjeta y corrige con `window.scrollBy(0, Δ)` antes de que el WebView pinte: ningún cuadro sale movido. (a) depende de la `progression` del localizador, que cambia al crecer el capítulo: salta 381 px y además se ven cuadros fuera de sitio entre la inserción y el `go`.
- Lo que cambia **por encima** de la pantalla ya lo corrige el *scroll anchoring* de Chrome; (b) no lo duplica (Δ ≈ 0). La regla "solo visibles y más abajo" se mantiene como límite de trabajo (gasto), ya no por los saltos.
- El caso que de verdad salta es la tarjeta del párrafo cortado arriba (A): (b) lo corrige. Lo mismo al ocultar/mostrar en Intercalado y al alternar el original en Solo traducción.
- `go(guardado)` (truco de la 3c-1) **no** se usa para las tarjetas.

**Cambios a la §5.2 que salen de la comprobación:**
- `insert`, `remove` y `showOriginal` llevan la compensación (b) dentro del mismo script (anclada en B: el primer párrafo hoja con `top ≥ 0` y alto > 0; si no hay, sin compensar).
- En "solo", antes de ocultar el original, la tarjeta copia de él una **lista fija** de propiedades con `style.setProperty(…, 'important')` (CSSOM: la CSP no lo frena y no es marcado): `display, font-family, font-size, font-weight, font-style, line-height, text-indent, text-align, letter-spacing, margin-*, padding-*, list-style-type, list-style-position`. Fondo, borde y color salen de tarjeta.css (`transparent`, `0`, `inherit`). Al volver a otro modo se quita ese estilo.
- `lector-oculto` vale para **toda hoja** (`p, li, blockquote, h1–h6, dd`), no solo `p`.
- `visible()`: un original oculto se mide con el rectángulo de su tarjeta (oculto da 0×0).

**Alcance / límites:**
- Un solo libro inventado.
- Un solo aparato y una sola versión del WebView.
- Tema claro y letra al 100 % (M4 también se miró en oscuro).
- Sin motor.
- Las traducciones inventadas de `h2` y `li` son más largas que el original, así que los 96,8 px de M3 están exagerados.
- El *scroll anchoring* de Chrome para lo que cambia arriba se vio en una sola corrida (6 tarjetas) y depende de la versión del WebView: por eso (b) se queda como red de seguridad, y la prueba con un libro real en la puerta lo confirma.
- La meta de M1 (50 ms) es de ida y vuelta: el peor caso, con 361 tarjetas, dio 45 ms (poco margen).
- El `setProperty` en los scripts pasa por `seguridad` (regla 8).

Capturas (no van al repo), en `C:\Users\JUAN\AppData\Local\Temp\claude\C--Users-JUAN-Documents-lector-bilingue\7825bb96-c19c-4248-8529-7df99ae37d55\scratchpad\spike-3c2\`: `m4-s4-1-tocar.png`, `m4-s4-2-intercalado.png`, `m4-s4-3-solo-css.png` (estructura perdida), `m4-s4-3-solo-copia.png`, `m4-s4-4-solo-copia-oscuro.png`; números en `results-m1.txt`, `results-m2.txt`, `results-m3.txt`.
