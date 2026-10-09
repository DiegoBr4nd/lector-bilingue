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

## 12. Resultado de la comprobación

Rama local `spike/3b-tocar` (borrada), Readium 3.4.0, Pixel 7, libro inventado de 3 capítulos (párrafos con id `p1`…`p30`; `p4` con `role="button"`). En los registros solo hubo números, booleanos e ids de prueba.

| # | Pregunta | Resultado | Evidencia |
|---|---|---|---|
| T1 | Con TalkBack, el toque doble llega a `onTap` dentro del párrafo | ✅ | Con TalkBack encendido por Juan: foco en p3 → toque doble → `onTap` (538, 611) y `elementFromPoint(point / density)` = p3; foco en p6 → `onTap` (538, 1118), bajo el punto = p6, foco = p6. Llega como un `click` real del DOM (`isTrusted` true) en el **centro** del párrafo (clientX 205 = 538 / 2,625). Juan: TalkBack leía los párrafos y mostraba el recuadro verde; tras el toque doble no vio ningún cambio (en el spike no se inserta nada). Del toque doble sobre p4 no quedó registro (ver abajo) |
| T1b | Acción con nombre ("Traducir") en el contenido web | ❌ | Ningún nodo ofrece acciones con etiqueta propia. `role="button"` solo cambia la clase a `android.widget.Button` y añade la acción de clic; Juan no oyó "botón" ni "toca dos veces para activar" en p4. No hace falta: el `<p>` normal ya recibe el toque doble |
| T2 | Hoja CSS de la app sin cambiar la CSP | ✅ | Dos vías. **B (recomendada):** `EpubNavigatorFragment.Configuration(servedAssets = listOf("lector/.*"))` + `<link rel="stylesheet" href="https://readium_assets/lector/tarjeta.css">` tras la CSP → `borderLeftWidth` 4,95 px (5 px ajustado a píxeles del aparato), `window.readium` sigue listo. **A:** recurso en el contenedor **y** en `manifest.resources` (`https://readium_package/lector/tarjeta.css`) → 6,86 px (7 px). Solo en el contenedor **no** basta: Readium sirve su página de error (200, 0 reglas). Una sola `<meta>` CSP; `git diff` de `HtmlSanitizer.kt` vacío |
| T3 | Recarga al cambiar de capítulo y volver | ✅ (se pierde) | Capítulo 1 con tarjeta y `window.__marca` → Índice al 3 → Índice al 1: ni tarjeta ni marca (documento recargado). `evaluateJavascript` ya ve el recurso nuevo con `readyState` `"complete"` y `window.readium` ~60 ms **antes** del primer `currentLocator` con el `href` nuevo (2300 → 2361 ms; 4407 → 4473 ms) |

**Decisión:** se sigue con el enfoque A (§2, decisión 5) sin cambiar la CSP:
- Toque: `InputListener.onTap` sirve igual con y sin TalkBack. No hace falta el botón "Traducir" en la barra.
- Hoja: vía B (assets de la app en `readium_assets`). El libro no puede tapar el archivo, y es el mismo origen que el CSS de Readium. El `<link>` se inyecta tras la CSP sin duplicarse (Readium sanea dos veces).
- Reinserción: al llegar un `currentLocator` con `href` distinto. El script comprueba antes `document.readyState === "complete"` y que `location.pathname` sea el del recurso esperado; si no, reintenta poco después.

**APIs de Readium 3.4.0 que difieren o conviene saber:**
- `WebViewServer.servedUrlToLink` solo sirve en `readium_package` los `href` del manifiesto.
- `servedAssets` se **suma** a `readium/.*` (no lo reemplaza). Se pasa en `createFragmentFactory(configuration = …)`.
- Las reglas de una hoja de `readium_assets` no se leen desde la página (`SecurityError`, otro origen), pero sí se aplican.
- El ViewPager tiene cargado el capítulo vecino: sus nodos de accesibilidad tienen los mismos ids (fuera de pantalla, `isVisibleToUser` false con TalkBack). Los scripts propios solo ven el recurso visible.
- Con TalkBack **apagado**, `performAction(ACTION_CLICK)` desde UiAutomation no hace nada. Con TalkBack encendido genera el clic en el centro del nodo, también en un `<p>` sin acción de clic declarada. Para la prueba automática del plan (§9), TalkBack debe estar encendido.
- Del toque doble de Juan sobre p4 no hubo registro: hubo foco en p4 (30,5 s) y luego en p6 (35,8 s), sin `onTap` entre los dos. Es probable que el toque doble no llegara a hacerse ahí. La parte automática sí dio `onTap` en p4. Se vuelve a mirar en la prueba con TalkBack del plan.

### Tiempos del motor real (Task 7)

Pixel 7, modelo OPUS en-es (beam 1, 4 hilos), compilación debug, `TapTranslateTimingOnDeviceTest`, textos inventados en inglés de ~50 palabras (solo números). "Al frente" = la app visible (el sistema le da los núcleos rápidos); "de fondo" = la prueba corre con el lanzador al frente.

| Medida | Meta | Al frente | De fondo |
|---|---|---|---|
| Toque, motor frío (con carga), 1 muestra | (sin meta) | 2967 ms | 7962 ms |
| Toque, motor cargado, 50 palabras, 5 muestras | < 2 s | 1605 a 1904 ms (mediana 1852) | 6874 a 8135 ms |
| Toque sobre párrafo pretraducido, 5 muestras | < 200 ms | 8 ms (7 a 8) | 7 a 10 ms |
| 20 párrafos de 30 a 70 palabras, seguidos (1020 palabras) | ≥ 15 palabras/s | 26,2 palabras/s (38,97 s) | 7,0 palabras/s (145,8 s) |

- Metas cumplidas con la app al frente, que es el uso real. De fondo no se cumplen: es el efecto ya medido en la puerta 1b (sin los núcleos rápidos), no un cambio de código.
- No se tocaron beam ni hilos.
