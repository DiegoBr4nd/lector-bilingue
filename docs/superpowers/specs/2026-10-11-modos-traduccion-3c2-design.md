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
- **M2 · Sin saltos:** insertar tarjetas en párrafos visibles y más abajo no mueve el primer párrafo visible (± 1 px). Insertar por encima (al volver hacia arriba) sí lo mueve; medir si guardar el localizador y `go(guardado)` (truco de la 3c-1) lo corrige sin parpadeo, o si conviene compensar `scrollBy` desde el script.
- **M3 · Ocultar sin saltos:** en Solo traducción, ocultar el `<p>` original (`lector-oculto`) y mostrar la tarjeta con el estilo del texto del libro no mueve el primer párrafo visible más que la diferencia de alto entre original y traducción; medirlo y anotar.
- **M4 · Libros con estructura:** citas (`blockquote`), listas (`li`) y encabezados ocultos/reemplazados se ven bien (capturas).
Resultado en §11.

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
- `insert` gana el estado "solo" (tarjeta con estilo de texto) y la clase `lector-oculto` para el párrafo original; `showOriginal(i, on)` alterna. Siempre `textContent`, JSON, sin `innerHTML`.
- `tarjeta.css`: reglas por `data-lector-modo` (en `solo`, la tarjeta hereda fuente, tamaño y color del párrafo; `p.lector-oculto { display:none !important }` solo en `solo`); marca "Original"; gris claro para el original en espera (contraste AA).

### 5.3 Guía de modo (`ReaderViewModel`)
- En INTERLEAVED y TRANSLATION_ONLY: al quedar lista la página y en cada `currentLocator` nuevo (página quieta), pide `visible(10)`; los visibles van con prioridad TAP en orden de arriba abajo; los siguientes, como PREFETCH. Tope: como máximo **15 pedidos** del modo en la fila a la vez; los que sobran se piden en la siguiente vuelta.
- Estado por párrafo: `Translating | Shown(text) | Hidden | ShowingOriginal | TooLong`; aviso único de capítulo `MissingModel | EngineFailed`.
- Cambiar de capítulo: cancela lo pendiente del anterior (`cancelPrefetchExcept`, ya existe) y empieza de nuevo. Cambiar de modo: `setMode`, cierra o adapta las tarjetas y vuelve a pedir lo visible. Cambiar dirección: como en la 3b (cierra y retraduce).
- Sin texto del libro en logs ni en `SavedStateHandle`.

### 5.4 Pantalla (`ReaderScreen`, `ReadingSettingsSheet`)
- Sección "Modo de lectura" en la hoja.
- El toque: en TAP como la 3b; en INTERLEAVED oculta/muestra; en TRANSLATION_ONLY alterna original.
- Sin saltos: solo se insertan tarjetas en párrafos visibles o más abajo; al volver hacia arriba se aplica la corrección medida en M2.

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
| Saltos al insertar por encima de lo visible | M2 primero; regla "solo visibles y más abajo" + corrección medida |
| Libros con maquetación especial en Solo traducción | M4 con citas y listas; "Tocar" siempre disponible |
| Batería y calor | Solo lo visible + 10; tope de 15 en la fila |
| Mucho tráfico de scripts al desplazar | Se pide solo con la página quieta (`currentLocator` ya tiene antirrebote) |
| Memoria del motor | La regla `EngineMemory` de la 3c-1; aviso único si falla |

## 11. Resultado de la comprobación
(Se completa en la Tarea 1 del plan.)
