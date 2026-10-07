# 3b · Tocar y traducir · Diseño

- **Fecha:** 2026-10-07. **Rama:** `feat/tocar-traducir`. **Base:** `main` f543a5c (3a fusionada, PR #15 y #16).
- **Fase 3 partida en:** 3a abrir y leer (hecha) → **3b tocar y traducir** → 3c modos de traducción y ajustes de lectura.
- Decisiones tomadas con Juan en el brainstorming del 2026-10-07 (§2).

## 1. Objetivo

Tocar un párrafo de un EPUB en el Lector y ver su traducción **debajo**, en menos de 2 s, todo en el teléfono. Lo ya traducido se guarda y vuelve al instante. Sin modos Intercalado ni Solo traducción (3c).

**Puerta 3b** (Juan, en el Pixel 7, con un libro real):
1. Tocar un párrafo → la traducción aparece en < 2 s. Los párrafos siguientes aparecen casi al instante (pretraducidos).
2. Tocar otra vez la oculta. Al reabrir el libro la página está limpia y lo ya traducido aparece al instante.
3. Cambiar la dirección del idioma de un libro → se recuerda al reabrirlo.
4. Sin el modelo del par, la tarjeta lleva a Idiomas; al volver ya se puede traducir.
5. En modo avión todo funciona igual.
6. Con TalkBack se traduce un párrafo sin mirar.
7. Juan lee 10 minutos un libro real y cuenta cómo se siente (plan maestro, Fase 3).
8. Capturas aprobadas por Juan. CI en verde. Revisiones de `seguridad` y `diseno` sin hallazgos altos abiertos. PR en borrador hasta cerrar la puerta.

### Fuera de alcance
- Modos Intercalado y Solo traducción, tema de página, fuente, tamaño, márgenes: 3c.
- "Borrar traducciones guardadas" (ajuste): 3c.
- Recordar qué tarjetas estaban abiertas entre sesiones (decisión 3).
- Traducir el libro completo en segundo plano: Fase 4.
- Numerar párrafos al sanear el capítulo (enfoque B): solo si la 3c lo necesita.

## 2. Decisiones tomadas (con Juan)

| # | Tema | Decisión | Por qué |
|---|---|---|---|
| 1 | Dirección | Automática según el idioma del libro (`dc:language`): inglés → español, español → inglés. Botón "EN → ES" en la barra superior para cambiarla **solo para ese libro**; se guarda | Funciona sin preguntar y corrige libros que declaran mal su idioma |
| 2 | Pretraducción | Solo **después del primer toque**: se traducen los ~5 párrafos siguientes y la fila avanza con cada toque | Si no se toca nada, el motor ni se carga (batería y ~330 MB de RAM) |
| 3 | Al reabrir | La página vuelve limpia; lo traducido queda en el caché y vuelve al instante al tocar | Más simple; el libro se ve como el original |
| 4 | Aspecto | Tarjeta suave: fondo tenue, esquinas redondeadas, letra del libro un poco más pequeña y **línea a la izquierda** | Se distingue de un vistazo y no depende solo del color |
| 5 | Arquitectura | **Enfoque A:** la app pregunta a la página con scripts propios (`evaluateJavascript`) y la página solo inserta texto. Caché por huella del texto | El spike de la 3a lo probó (P1, P2). B (numerar al sanear) toca la pieza más delicada; C (dibujar encima) no sigue al desplazar |

## 3. Primera tarea: comprobación en el Pixel (desechable)

Antes de construir, un experimento en una rama local confirma:
- **T1 · TalkBack:** con TalkBack, el toque doble sobre un párrafo enfocado llega a `InputListener.onTap` con un punto dentro del párrafo. Si además se puede ofrecer una acción con nombre ("Traducir") en el contenido web, se anota.
- **T2 · Hoja de estilos:** una hoja CSS de la app, servida como recurso de Readium (`readium_assets` o equivalente), aplica estilo a la tarjeta sin cambiar la CSP y sin estilos en línea.
- **T3 · Reinsertar:** al cambiar de capítulo y volver, la página se recarga; se mide cómo detectar la carga (`currentLocator`/recurso visible) para reinsertar tarjetas.

Si T1 falla, se busca otra vía accesible (por ejemplo, un botón "Traducir" en la barra inferior que traduce el párrafo con el foco de TalkBack) y se consulta con Juan. Si T2 falla, se decide con `seguridad` la alternativa (por ejemplo, un `<style>` con *nonce*).

## 4. Qué ve el usuario

- **Tocar un párrafo:** aparece al instante un **esqueleto** (tarjeta con rayas grises animadas; quietas con "reducir movimiento"). En ~1 s cambia por la traducción. El párrafo no se mueve (P2 del spike).
- **Tocar otra vez** el párrafo o su tarjeta la oculta. Puede haber **varias tarjetas abiertas**.
- **Cuenta como párrafo:** `p`, `li`, `blockquote`, `h1`–`h6`, `dd`. Un toque fuera de ellos, o sobre uno sin texto (imagen, adorno), no hace nada.
- **Estados en la tarjeta:**
  - Falta el modelo: "Falta el idioma inglés → español (230 MB) · Descargar" → abre Idiomas.
  - Primer toque, motor cargando: esqueleto con "Preparando el traductor…".
  - Error del motor: "No se pudo traducir este párrafo · Reintentar". Al preparar el motor: "No se pudo preparar el traductor · Reintentar".
- **Botón de dirección** en la barra superior ("EN → ES"): abre una lista con las dos direcciones; la elegida se guarda para ese libro. Libro sin idioma declarado: inglés → español.
- **TalkBack:** el toque doble sobre el párrafo enfocado lo traduce (a confirmar en T1). Se anuncia "Traduciendo…" y la tarjeta se lee justo después del párrafo como "Traducción: …".
- Las barras siguen ocultándose al desplazar; tocar el texto no las muestra.

## 5. Piezas

### 5.1 `ParagraphBridge` (`app`, `ui/reader`)
Habla con la página con scripts propios vía `EpubNavigatorFragment.evaluateJavascript` (solo el recurso visible):
- `find(x, y)` → el párrafo bajo el punto (`elementFromPoint(point / density)`, como en el spike) y los ~5 siguientes: índice local, `textContent` normalizado (espacios colapsados).
- `insert(index, card)` → crea o reemplaza la tarjeta bajo el párrafo: esqueleto, traducción, falta modelo o error.
- `remove(index)`.
- Reglas: los datos van **codificados en JSON** dentro del script; el texto se pone con `textContent`, nunca `innerHTML`; **sin** `addJavascriptInterface` (la página no puede llamar a la app). Las tarjetas llevan clases de la hoja CSS de la app, sin estilos en línea.
- El índice local identifica el párrafo **dentro del recurso visible**; el caché no depende de él.

### 5.2 `TranslationService` (`app`, `data/`; uno para toda la app)
- `suspend fun translate(request): Result` con prioridad: **toque** antes que **pretraducción**.
- Primero el caché; si falta, a la fila. La pretraducción de un recurso se cancela al cambiar de recurso (capítulo).
- Un **solo hilo** dedicado (`Executors.newSingleThreadExecutor().asCoroutineDispatcher()`): nunca dos traducciones a la vez; los motores se crean con ese mismo hilo como despachador, así su `synchronized` ya no bloquea un hilo de `Dispatchers.Default` (pendiente de la 2c) y queda como protección barata.
- Carga el motor con el primer pedido (`EngineSelector` con los motores instalados del par). Lo descarga al salir del Lector o tras **2 min** sin pedidos. Nunca cambia de motor en silencio.
- Antes de cargar mira la memoria libre (`ActivityManager.MemoryInfo`); si no alcanza, error claro.
- Parte en oraciones con `SentenceSplitter`, traduce y vuelve a unir con espacio. Párrafos de más de ~4000 caracteres: por tandas de oraciones.
- Motor: beam 1, máximo 4 hilos.

### 5.3 Caché en Room (`books`, base `lector.db` v1 → **v2**)
- Tabla `translations`: `key` (TEXT, PK) = SHA-256 hex de `modelo + "\n" + par + "\n" + texto normalizado`; `translation` (TEXT); `createdAt` (INTEGER).
- `modelo` = id y versión del modelo instalado del catálogo (p. ej. `opus-en-es-v1`): cambiar de modelo no mezcla traducciones.
- Solo se guarda un párrafo **completo**: si falla una oración, nada.
- Las traducciones no se borran al borrar un libro (sirven si se vuelve a añadir). La base ya está fuera de las copias de seguridad.
- Migración `1 → 2` explícita (sin `fallbackToDestructiveMigration`), probada con `room-testing` contra `schemas/.../1.json`.

### 5.4 Dirección por libro (`books`)
- Columna nueva `direction` (TEXT, null) en `books`, en la misma migración `1 → 2`. Null = automática.
- Automática: primer idioma de `publication.metadata.languages`: `en*` → `en-es`, `es*` → `es-en`; otro o ninguno → `en-es`.

### 5.5 `ReaderViewModel` (ampliado)
- Estado de las tarjetas del recurso visible: índice → `Skeleton | Text | MissingModel | Error`.
- Al tocar: abre o cierra; si abre, pide la traducción y la pretraducción de los siguientes.
- Al recargarse el recurso (cambio de capítulo y vuelta), reinserta las tarjetas abiertas de ese recurso.
- Sin texto del libro en `SavedStateHandle` ni en logs.

## 6. Errores
- Motor no carga → error de preparación con "Reintentar". Falla una oración → error del párrafo, nada en caché.
- Cambio de capítulo durante una traducción: termina y se guarda, pero no se inserta. Pretraducción del capítulo anterior cancelada.
- Salir del Lector: se cancela la fila y se descarga el motor.
- Página recargada antes de insertar: se descarta la inserción; la reinserción la recupera si la tarjeta sigue abierta.

## 7. Seguridad y privacidad (revisa `seguridad`, regla 8)
- De la página solo se lee `textContent`; hacia la página solo texto, siempre con `textContent`.
- Scripts con datos en JSON (comillas, `</script>`, ` ` no rompen el script).
- Sin `addJavascriptInterface`. La CSP no cambia (T2).
- Nunca se registra texto, traducción ni huella; una prueba lo vigila como `MaliciousEpubOnDeviceTest.lNiElTituloNiElTextoLleganAlRegistro`.
- EPUBs maliciosos nuevos: párrafos con comillas, `</script>`, `<img onerror>` como texto, ` `, texto enorme. Se insertan como texto y nada se ejecuta.

## 8. Accesibilidad y diseño (revisa `diseno`, regla 9)
- Contraste AA de la tarjeta en claro y oscuro de la interfaz (la página sigue blanca hasta la 3c: AA sobre blanco).
- La línea izquierda distingue la tarjeta sin depender del color.
- "Reducir movimiento": esqueleto sin animación.
- Letra del sistema al 200 %: la tarjeta crece con el texto del libro.
- Botón de dirección: 48 dp, `contentDescription` "Idioma de traducción: inglés a español".
- Bocetos en Compose Previews del botón y de los estados; capturas reales de la tarjeta en el Pixel.

## 9. Pruebas
- **JVM:** fila (prioridad, cancelación por capítulo, nunca dos a la vez), huellas y regla de no guardar a medias, dirección automática, estados de tarjetas (abrir, cerrar, error, reintentar, reinsertar), descarga del motor a los 2 min. Con motor falso.
- **Pixel (`am instrument`):** migración 1 → 2 con datos; tocar y traducir con el motor real y un libro inventado (la tarjeta aparece debajo y el párrafo no se mueve); EPUBs maliciosos de §7; TalkBack (toque doble); medición de tiempos (§10).

## 10. Rendimiento
Meta del proyecto: ≥ 15 palabras/s y < 2 s por párrafo con beam 1. Se mide en el Pixel, con un libro inventado: toque → traducción con motor frío, con motor cargado y con párrafo pretraducido. Se revisa que el desplazamiento siga fluido mientras el motor trabaja.

## 11. Riesgos
| Riesgo | Plan |
|---|---|
| TalkBack no genera el toque en la página | T1 lo descubre primero; alternativa: botón "Traducir" para el párrafo con el foco, consultado con Juan |
| La hoja CSS de la tarjeta exige cambiar la CSP | T2; decisión con `seguridad` |
| Readium recarga la página y se pierden tarjetas | T3 y reinserción desde el ViewModel |
| Poca memoria al cargar el motor junto al WebView | Revisión de memoria libre antes de cargar; descarga al salir y por inactividad |
| `evaluateJavascript` solo ve el recurso visible | Índices locales por recurso; el caché no depende de ellos |
