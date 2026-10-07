# Spec · Fase 3a · Biblioteca y lector EPUB (abrir y leer)

- **Fecha:** 2026-10-05
- **Rama:** `feat/lector-epub`
- **Estado:** diseño aprobado por Juan en conversación (partes 1 a 4). Falta que revise esta spec.
- **Fase 3 partida en:** **3a abrir y leer** → 3b tocar y traducir (caché Room, pretraducción, hilo único del motor) → 3c modos de traducción y ajustes de lectura. Cada subfase tiene su spec, su plan y su PR.
- **Agentes:**
  - `estructura`: módulo `:books`, Room, ViewModels y navegación.
  - `diseno`: Biblioteca y Lector, Previews, accesibilidad y auditoría material-3.
  - `infraestructura`: Readium, KSP, `verification-metadata.xml` y CI.
  - `seguridad`: dependencias nuevas, WebView, importación de EPUB y EPUBs maliciosos.

## 1. Objetivo

Abrir un EPUB real en el Pixel y leerlo **sin conexión**, con el diseño original del libro. Todavía sin traducir: eso es la 3b.

**Puerta 3a** (con evidencia en el Pixel 7):
1. Añadir un EPUB real → aparece en la Biblioteca con portada, título y autor.
2. Abrirlo y deslizar → desplazamiento continuo. Las barras se ocultan al bajar y aparecen al subir.
3. Índice → saltar a un capítulo funciona.
4. Cerrar la app y volver a abrir el libro → sigue en la misma posición.
5. Borrar el libro → desaparece de la Biblioteca y el archivo original sigue en el teléfono.
6. En modo avión todo funciona igual.
7. Con TalkBack se puede añadir y abrir un libro sin mirar. Con letra del sistema al 200 % no se corta nada.
8. Capturas de Biblioteca y Lector aprobadas por Juan (4 temas, teléfono y tableta, letra grande).
9. CI en verde. Revisiones de `seguridad` y `diseno` sin hallazgos altos abiertos. PR **en borrador** hasta cerrar la puerta.

### Fuera de alcance
- Tocar y traducir, caché de traducciones y pretraducción: 3b.
- Modos Intercalado y Solo traducción, tema de página, fuente, tamaño de letra y márgenes: 3c.
- Modo páginas (deslizar a los lados): quizá en la 3c como ajuste.
- Marcadores, buscar texto, ordenar, colecciones o estantes: más adelante, si hacen falta.
- PDF y DOCX: Fase 5.
- Diseño de tableta a dos columnas: sigue pendiente; aquí solo se comprueba que nada se rompe en tableta.

## 2. Decisiones tomadas (con Juan)

| # | Decisión | Elegido | Por qué |
|---|---|---|---|
| 1 | División de la Fase 3 | 3a / 3b / 3c | Un PR por subfase, cada una con su prueba en el Pixel |
| 2 | Cómo se muestra el libro | WebView de Readium | Juan quiere que los libros se vean como en cualquier lector (imágenes, tablas, estilos) |
| 3 | Biblioteca | Mínima: portada, título, autor, % leído, añadir y borrar | Lo justo para la puerta |
| 4 | Dónde se guarda el libro | Copia privada dentro de la app | Sigue abriendo aunque se borre el original; se valida una sola vez |
| 5 | Pantalla de inicio | La Biblioteca reemplaza al Inicio | El estado de idiomas pasa a un aviso y al menú ⋮ |
| 6 | Avance por el libro | Desplazamiento continuo | En la 3b la traducción se inserta debajo del párrafo sin reacomodar páginas |
| 7 | Controles del lector | Básicos: volver, título, Índice, % leído, recordar posición | Lo necesario para leer 10 minutos |
| 8 | Barras del lector | Se ocultan al deslizar hacia abajo y aparecen al deslizar hacia arriba | Tocar el texto queda libre para traducir en la 3b |
| 9 | Organización del código | Módulo nuevo `:books` + Room desde ya | En la 3b la tabla de traducciones se suma a la misma base, sin migrar desde JSON |
| 10 | Respaldos de Android | Libros y Biblioteca **fuera** del respaldo | Restaurar la lista sin los EPUB dejaría libros que no abren; los libros no caben en el tope de 25 MB del respaldo en la nube |

## 3. Primera tarea: spike de la 3b (desechable)

Antes de construir la 3a, un experimento rápido confirma que el WebView de Readium permite lo que necesita la 3b:
1. Detectar qué párrafo tocó el usuario y obtener su texto.
2. Insertar un bloque de texto debajo de ese párrafo, en desplazamiento continuo, sin que el texto "salte".
3. Ejecutar solo el JavaScript de Readium y no el del libro (o, si no se puede, quitar los `<script>` del libro antes de mostrarlo).

El resultado se anota al final de esta spec, en una sección nueva "11. Resultado del spike", y el código se borra. Si (1) o (2) fallan, se para y se replantea con Juan la decisión 2 antes de seguir.

## 4. Módulo `:books` (sin pantallas)

### 4.1 `BookImporter`
Recibe el `Uri` que entrega el selector del sistema (*Storage Access Framework*, sin permisos de almacenamiento).
1. Copia el archivo a `files/books/tmp/<uuid>.epub` contando bytes. Si pasa de **100 MB**, se corta y falla.
2. Valida el archivo (§6.1).
3. Abre la publicación con Readium y lee título, autor y portada. Sin título, se usa el nombre del archivo sin extensión.
4. Guarda la portada reducida en `files/books/<id>.cover.png` (si existe).
5. Mueve el EPUB a `files/books/<id>.epub` y crea la fila en `books`.
6. Si algo falla en cualquier paso, borra lo temporal y lo parcial (`finally`). Al arrancar la app se borra lo que quede en `files/books/tmp/`.

Resultado: `ImportResult.Ok(bookId)` o `ImportResult.Error(reason)`, con `reason` ∈ {`NOT_EPUB`, `TOO_BIG`, `UNSAFE_ARCHIVE`, `DRM`, `DAMAGED`, `NO_SPACE`}.

### 4.2 `LibraryDb` (Room)
Tabla `books`:

| Campo | Tipo | Nota |
|---|---|---|
| `id` | String (UUID) | Clave |
| `title` | String | |
| `author` | String? | |
| `coverPath` | String? | Relativa a `files/books/` |
| `addedAt` | Long | Milisegundos |
| `lastOpenedAt` | Long? | Para ordenar |
| `progress` | Float | 0..1, `totalProgression` de Readium |
| `locator` | String? | *Locator* de Readium en JSON: la posición exacta |

- Orden de la Biblioteca: `lastOpenedAt` descendente y después `addedAt` descendente.
- La base se llama `lector.db` y empieza en la versión 1. La 3b añade la tabla de traducciones con una migración a la versión 2.
- `BookRepository` expone `Flow<List<Book>>`, `import`, `delete` (borra la fila, el EPUB y la portada) y `savePosition`.

### 4.3 `BookOpener`
Abre `files/books/<id>.epub` con Readium (`AssetRetriever` + `PublicationOpener`) y devuelve la `Publication` o un error (`DAMAGED`, `MISSING`).

## 5. Pantallas (en `:app`)

### 5.1 Biblioteca (reemplaza a Inicio)
- **Barra superior:** "Biblioteca" y menú ⋮ (Idiomas, y Desarrollador solo en debug).
- **Aviso de idiomas**, solo si hace falta: falta un modelo ("Falta el modelo inglés→español · Descargar") o hay una descarga en curso ("Descargando… 45 %"). Al tocarlo se abre Idiomas. Reutiliza las reglas de `HomeRules` y también cubre el pendiente "aviso cuando falló la última descarga".
- **Lista de libros:** portada (o recuadro con la inicial), título, autor y barra de % leído con su texto ("42 %").
- **Mantener presionado** un libro abre "Borrar libro", con `ConfirmDialog`. Borra la copia y la posición, nunca el original.
- **Botón flotante** "＋ Añadir libro" abre el selector del sistema (`OpenDocument` con `application/epub+zip`).
- **Vacía:** ilustración sencilla, el texto "Añade tu primer libro EPUB" y un botón grande "Añadir libro".
- **Importando:** fila provisional con "Añadiendo…".
- **Errores** (snackbar con mensaje humano, uno por `reason`):
  - "Este archivo no es un EPUB válido"
  - "El libro es demasiado grande (máx. 100 MB)"
  - "Este libro tiene protección DRM y no se puede abrir"
  - "El archivo parece dañado"
  - "No hay espacio suficiente en el teléfono"
  - `UNSAFE_ARCHIVE` usa el mismo texto que "no es un EPUB válido": no se dan detalles del ataque.

### 5.2 Lector
- **Actividad propia `ReaderActivity`** (`FragmentActivity`, no exportada) con interfaz Compose que aloja el `EpubNavigatorFragment` de Readium (con `AndroidFragment`). Motivo: Readium exige instalar su `FragmentFactory` **antes** de `super.onCreate()`, con el libro ya abierto; eso no encaja en una ruta de Navigation 3. `MainActivity` no cambia.
- El libro se abre **en la Biblioteca** (indicador "Abriendo…") y se deja en una caché en memoria (`OpenBooks`). `ReaderActivity` lo toma de ahí. Si la caché está vacía (Android cerró la app en segundo plano), la actividad se cierra y se vuelve a la Biblioteca, sin fallar.
- **Desplazamiento continuo** (`EpubPreferences(scroll = true)`). Tema y letra quedan con los valores por defecto hasta la 3c.
- **Barra superior:** ← volver, título del libro y botón "Índice".
- **Barra inferior:** título del capítulo actual y % leído ("La tormenta · 42 %"). Si el capítulo no tiene título, solo "42 %". Se usa el título y no un número porque muchos EPUB cuentan la portada y el índice como capítulos.
- Las dos barras se ocultan al deslizar hacia abajo y aparecen al deslizar hacia arriba. Con "reducir movimiento" activado aparecen y desaparecen sin animación.
- **Índice:** hoja inferior con la tabla de contenidos. El capítulo actual va marcado con ícono y texto, no solo con color. Tocar un capítulo salta a él.
- **Posición:** se guarda cuando cambia (con un retraso corto para no escribir en cada píxel) y al salir. Al abrir se vuelve al `locator` guardado y se actualiza `lastOpenedAt`.
- **Estados:** abriendo (en la fila de la Biblioteca); dañado o ausente → diálogo en la Biblioteca "No se pudo abrir este libro" con "Cerrar" y "Quitar de la biblioteca".

### 5.3 Navegación
- Ruta nueva: `Route.Library` (sustituye a `Route.Home`), `encode` = `"library"`.
- El Lector no es una ruta: se abre con un `Intent` explícito a `ReaderActivity` con el id del libro. Un id que no es UUID cierra la actividad.
- Un `"home"` guardado de la versión anterior se decodifica como `Library`.

## 6. Seguridad y privacidad

### 6.1 Validación al importar
- **Tamaño:** archivo ≤ 100 MB. Suma de tamaños descomprimidos declarados ≤ 500 MB, y al leer se cuentan los bytes reales con el mismo tope (defensa contra **bomba ZIP**).
- **Cantidad:** ≤ 10 000 entradas en el ZIP.
- **Nombres:** se rechaza toda entrada con `..`, ruta absoluta, `\` o carácter nulo (**zip slip**). Readium no extrae a disco, pero se valida igual.
- **Estructura:** debe tener `mimetype` = `application/epub+zip` y `META-INF/container.xml`. Si no, `NOT_EPUB`.
- **XML:** sin entidades externas ni DTD (**XXE**). Se verifica el analizador que usa Readium y se cubre con una prueba.
- **DRM:** `META-INF/license.lcpl` o `META-INF/rights.xml`, o `META-INF/encryption.xml` con algún algoritmo que **no** sea de ofuscación de fuentes → `DRM`. La ofuscación de fuentes (`http://www.idpf.org/2008/embedding` y `http://ns.adobe.com/pdf/enc#RC`) es común en libros sin DRM y se acepta. No se incluye `readium-lcp`.

### 6.2 WebView del lector
- **Limpieza del HTML del libro** (`HtmlSanitizer`, con jsoup, al servir cada recurso HTML, XHTML o SVG, antes de que Readium añada sus scripts):
  - se quitan `<script>`, `<iframe>`, `<object>`, `<embed>`, `<form>`, `<base>` y `<meta http-equiv="refresh">`;
  - se quitan los atributos `on…` (`onclick`, `onload`…) y los enlaces `javascript:`;
  - se inserta una **Content-Security-Policy** (regla que el WebView obedece) que solo permite recursos del propio libro: sin red, sin `connect`, sin `object`.
- **Sin red:** lo garantizan la CSP y que Readium no navega a direcciones remotas. El spike (§3) y `seguridad` lo comprueban con un servidor de prueba en el PC.
- **Sin JavaScript del libro:** solo corre el de Readium.
- **Enlaces externos:** no se abren solos. Diálogo "¿Abrir en el navegador?" con la dirección visible.
- Sin acceso a archivos ni a `content://` desde el WebView. `setWebContentsDebuggingEnabled` solo en debug.

### 6.3 Privacidad y respaldos
- Nunca se registra el texto, el título ni el autor del libro. En los registros solo van el id y el `reason`.
- `dataExtractionRules` y `fullBackupContent` excluyen `files/books/` y `lector.db`.
- `docs/agentes/03-seguridad.md` §4 se corrige: "respaldar ajustes; **no** la biblioteca, los libros, la caché ni los modelos".

### 6.4 Dependencias nuevas
- Readium Kotlin Toolkit **3.4.0** (BSD-3-Clause): `readium-shared`, `readium-streamer` y `readium-navigator`.
- Room y KSP (Apache-2.0). AndroidX Fragment y `fragment-compose` (Apache-2.0). jsoup (MIT), para `HtmlSanitizer`.
- Readium recibe un `HttpClient` propio que siempre falla (`OfflineHttpClient`): ni el lector ni la importación pueden usar la red.
- Se fijan las versiones, se añaden los hashes a `verification-metadata.xml` y `seguridad` revisa qué arrastra cada una (nada con red, analítica ni Play Services).

## 7. Accesibilidad
- Áreas táctiles de 48 dp como mínimo. Contraste AA en los 4 temas de la interfaz.
- `contentDescription` en español en el FAB, el menú y el Índice. Las portadas son decorativas (sin descripción y sin leer la inicial del recuadro): cada libro se anuncia una sola vez como "<título>, <autor>, N % leído", con la acción "abrir el libro".
- El % leído se anuncia como texto. El capítulo actual se marca sin depender del color.
- Borrar también se ofrece en las acciones de TalkBack (`customActions`), no solo con mantener presionado.
- Letra del sistema al 200 %: los títulos largos se cortan con "…" y nunca tapan los botones.

## 8. Pruebas
- **`BookImporter`** (pruebas instrumentadas o Robolectric, según lo que pida Readium): EPUB válido; sin título; sin portada; > 100 MB; no es ZIP; zip slip; bomba ZIP; demasiadas entradas; XXE; DRM. En cada error se comprueba que no queda ningún archivo.
- **`LibraryDb`:** Room en memoria; insertar, ordenar, borrar, guardar posición.
- **Reglas de pantalla:** aviso de idiomas, texto por cada `reason`, etiqueta "capítulo · P %" y cuándo se ven las barras.
- **`HtmlSanitizer`:** pruebas en la JVM con HTML malicioso.
- **Rutas:** `encode`/`decode` de `Library`, `"home"` → `Library`; id de libro inválido en `ReaderActivity` → se cierra.
- **EPUBs de prueba** generados por nosotros en `src/test` (o `src/androidTest`), sin contenido con derechos de autor.
- **Evidencia antes de decir "listo":** `./gradlew assembleFdroidDebug`, `./gradlew test`, `./gradlew lint` y CI del PR en verde.

## 9. Riesgos
| Riesgo | Plan |
|---|---|
| Readium no deja insertar texto bajo un párrafo o detectar el toque | El spike (§3) lo descubre el primer día. Si falla, se replantea la decisión 2 con Juan |
| La CSP o la limpieza del HTML rompen los scripts de Readium | El spike (§3) lo prueba con la limpieza activa; si falla, se ajusta la CSP (nunca se quita la limpieza) |
| `EpubNavigatorFragment` dentro de Compose da problemas de ciclo de vida | `ReaderActivity` propia con `AndroidFragment`. Si falla, `FragmentContainerView` en un `AndroidView` |
| Readium arrastra dependencias no deseadas | Revisión de `seguridad` del árbol de dependencias antes de añadirlo |
| Tamaño del APK | Medir antes y después y anotarlo en el PR |

## 10. Lo que hace Juan
1. Revisar y aprobar esta spec, y después el plan.
2. Aprobar las capturas de Biblioteca y Lector.
3. Hacer la prueba de la puerta (§1) en el Pixel con un EPUB real suyo.
4. Aprobar la fusión del PR.

## 11. Resultado del spike

Rama local `spike/readium-3b` (borrada), Readium 3.4.0, Pixel 7 (Android 17), EPUB inventado de 3 capítulos. En el log solo hubo largos, booleanos y números.

| # | Pregunta | Resultado | Evidencia |
|---|---|---|---|
| P1 | Tocar un párrafo y leer su texto | ✅ | `InputListener.onTap` + `elementFromPoint(point / density)` devuelve el párrafo correcto: largos 155, 69, 198 y 19 en párrafos distintos, también con la página desplazada |
| P2 | Insertar un bloque bajo el párrafo sin salto | ✅ | `top` del párrafo tocado igual antes, justo después y 800 ms después (249,6 → 249,6; con scrollY 349: 402,75 → 402,75 y −57,2 → −57,2); `scrollY` no cambia; capturas: el bloque aparece debajo y el párrafo no se mueve |
| P3 | Solo corre el JavaScript de Readium | ✅ (con cambio de CSP) | Con `HtmlSanitizer`: `JS_DEL_LIBRO_EJECUTADO` ausente, el `onclick` no cambia el título, `window.readium` existe y P1/P2 funcionan. Sin sanitizador el script del libro **sí** corre. Con la CSP del plan, Readium se rompía (ver abajo) |
| P4 | Red | ✅ | Servidor espía (cuenta cada conexión TCP) por `adb reverse`: 0 conexiones en 12 s, con y sin `HtmlSanitizer`; control desde el teléfono sí llega. Readium solo ya bloquea: su `shouldInterceptRequest` atiende **todas** las peticiones del WebView desde el libro |
| P5 | Localizador durante el desplazamiento | ❌ (parcial) | `progression` sube al bajar y baja al subir, pero `currentLocator` se emite ~180 ms **después de soltar** (antirrebote de 100 ms en Readium), no durante. `totalProgression` solo cambia por "posición" (aquí, por capítulo: 0,333 → 0,667). `InputListener.onDrag` sí llega durante el gesto (Start/Move/End, `offset.y` < 0 al bajar) |

**Decisión:** P1 y P2 pasan, se sigue con la decisión 2 (WebView de Readium).
- **Tarea 5 (CSP):** Readium carga sus scripts y CSS desde `https://readium_assets`, otro origen que el libro (`https://readium_package`). Con `script-src 'self'` Readium no arranca (`window.readium` indefinido). Chromium **rechaza** `https://readium_assets` en la CSP ("invalid source": el `_` no es válido en un host). La CSP que funcionó permite el esquema `https:` en `default-src`, `script-src`, `style-src`, `img-src` y `font-src` (con `connect-src 'none'` y el resto igual). Es seguro porque Readium intercepta toda petición y nunca sale a la red (P4). Revisa `seguridad`.
- **Tarea 9 (barras):** usar `InputListener.onDrag` (signo de `offset.y`) para ocultar o mostrar; el `currentLocator` sirve para el % y para guardar la posición, no para el gesto.

**APIs de Readium 3.4.0 que difieren del plan:**
- `PublicationOpener(onCreatePublication = …)` del **constructor nunca se llama** (en `open()` el parámetro homónimo lo tapa y se llama dos veces). Pasar el `onCreatePublication` a `opener.open(asset, allowUserInteraction = false, onCreatePublication = { … })`. Se aplica dos veces: el sanitizador debe ser idempotente o evitar insertar dos `<meta>` CSP.
- `getOrElse` de `Try` es una extensión: `import org.readium.r2.shared.util.getOrElse`.
- `File.toUrl()` no existe sin argumentos (`toUrl(isDirectory: Boolean)`); más simple: `assetRetriever.retrieve(file: File)`, que devuelve `Try<Asset, AssetRetriever.RetrieveError>`.
- Iguales al plan: `HttpError.IO(cause: Error)` (también `HttpError.IO(exception)` y `HttpError.Unreachable`), `TransformingContainer(container) { url, resource -> }`, `TransformingResource(resource) { bytes -> Try<ByteArray, ReadError> }`, `DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null)`, `AssetRetriever(contentResolver, httpClient)`, `InputListener.onTap(TapEvent)` (`point` en píxeles de la vista; `TapEvent.targetElement` es experimental y llegó `null`), `EpubNavigatorFragment.evaluateJavascript(script): String?` (suspend; solo el recurso visible), `EpubNavigatorFactory(pub).createFragmentFactory(initialLocator, initialPreferences = EpubPreferences(scroll = true))` y `AndroidFragment<EpubNavigatorFragment>` de `fragment-compose` 1.9.1.
- `Publication.container` lleva `@InternalReadiumApi`.

**Para la Tarea 2 (dependencias):**
- `readium-streamer` y `readium-navigator` **exigen core library desugaring** en `:app` (`isCoreLibraryDesugaringEnabled = true` + `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")`); sin él falla `checkFdroidDebugAarMetadata`.
- El navegador arrastra `media3` (exoplayer, session) y con él `guava`, además de `timber`, `com.mcxiaoke.koi:core`, `appcompat`, `constraintlayout`, `browser`, `kotlinx-serialization` y `kotlinx-datetime`. Revisa `seguridad`. APK debug del spike: 29,4 MB.
- El modo continuo pasa de un capítulo al siguiente al deslizar (se llegó del capítulo 2 al 3).
