# Biblioteca y lector EPUB (Fase 3a) · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Añadir un EPUB desde el selector del sistema, verlo en una Biblioteca y leerlo sin conexión en un lector Readium con desplazamiento continuo, Índice y posición recordada. Todavía sin traducir.

**Architecture:**
- **`:books` (biblioteca Android nueva, sin pantallas):**
  - validación del ZIP (`EpubArchiveCheck`);
  - archivos privados (`BookFiles`);
  - Room (`LectorDatabase`, `BookDao`);
  - Readium (`ReadiumBooks`: abrir, leer metadatos y portada, con `HtmlSanitizer` y `OfflineHttpClient`);
  - `BookImporter` y `BookRepository`.
- **`:app`:**
  - la Biblioteca reemplaza al Inicio (ruta `Route.Library`);
  - el Lector es una actividad propia `ReaderActivity`, porque Readium exige su `FragmentFactory` antes de `super.onCreate()`;
  - el libro abierto pasa de la Biblioteca al Lector por `OpenBooks`, una caché en memoria.

**Tech Stack:**
- Kotlin 2.4.20 · AGP 9.4.1 · Compose BOM 2026.09.00 · Material 3 · Navigation 3.
- Readium Kotlin Toolkit 3.4.0 (BSD-3).
- Room + KSP (Apache-2.0).
- AndroidX Fragment + `fragment-compose` (Apache-2.0).
- jsoup (MIT).

**Spec:** `docs/superpowers/specs/2026-10-05-lector-epub-3a-design.md`

**Ejecución sugerida:** subagent-driven.
- Tarea 1 → `estructura` (spike).
- Tarea 2 → `infraestructura`.
- Tareas 3 a 7 → `estructura`.
- Tareas 8 y 9 → `diseno` (con `estructura` para ViewModels).
- Tarea 10 → `seguridad` + `diseno`, solo lectura.
- Tarea 11 → sesión principal con Juan.

## Global Constraints

- **Paquetes:**
  - base `io.github.diegobr4nd.lectorbilingue`;
  - módulo nuevo `:books`, paquete `io.github.diegobr4nd.lectorbilingue.books`.
- **Rama y PR:**
  - rama `feat/lector-epub`; nunca `main`;
  - nunca push sin permiso de Juan;
  - PR **en borrador** hasta cerrar la puerta.
- **Límites de importación** (spec §6.1):
  - archivo ≤ **100 MB** (`100L * 1024 * 1024`);
  - suma descomprimida ≤ **500 MB**, declarada y real;
  - ≤ **10 000** entradas;
  - nombres sin `..`, sin `/` inicial, sin `\`, sin `\u0000` y sin letra de unidad (`C:`).
- **Motivos de error** (`ImportError`): `NOT_EPUB`, `TOO_BIG`, `UNSAFE_ARCHIVE`, `DRM`, `DAMAGED`, `NO_SPACE`. `UNSAFE_ARCHIVE` se muestra con el mismo texto que `NOT_EPUB`.
- **DRM:** `META-INF/license.lcpl`, `META-INF/rights.xml`, o `META-INF/encryption.xml` con un `Algorithm` distinto de `http://www.idpf.org/2008/embedding` y `http://ns.adobe.com/pdf/enc#RC`.
- **Archivos:**
  - `files/books/<id>.epub`;
  - `files/books/<id>.cover.png`;
  - temporales en `files/books/tmp/`, que se borran al arrancar.
  - El `id` es un UUID.
- **Room:**
  - base `lector.db`, versión 1, `exportSchema = true`, esquemas en `books/schemas/`;
  - tabla `books` con `id`, `title`, `author`, `coverPath`, `addedAt`, `lastOpenedAt`, `progress`, `locator`;
  - orden: `lastOpenedAt DESC, addedAt DESC`, con los `null` al final.
- **Lector:**
  - `EpubPreferences(scroll = true)`;
  - la posición se guarda con 1 s de retraso y al salir;
  - las barras se ocultan cuando `totalProgression` sube y aparecen cuando baja;
  - con TalkBack activo, siempre visibles.
- **Red:**
  - Readium recibe `OfflineHttpClient` (siempre falla);
  - el HTML, XHTML y SVG del libro pasan por `HtmlSanitizer` (quita scripts e inserta una CSP);
  - lista de permisos **sin cambios**: el CI la comprueba.
- **Privacidad:**
  - nunca registrar (*log*) texto, título ni autor del libro; solo el id y el motivo;
  - `allowBackup="false"` y `data_extraction_rules.xml` ya excluyen todo, y no se tocan.
- **Textos:**
  - español latino, todo en `strings.xml`;
  - íconos Material Symbols Rounded en `core/ui/src/main/res/drawable/`;
  - cero emojis;
  - áreas táctiles ≥ 48 dp.
- **Gradle y teléfono:**
  - Gradle: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew …`.
  - Teléfono: `export MSYS_NO_PATHCONV=1`.
  - **Nunca** desinstalar, `pm clear` ni `connectedAndroidTest` (borran los modelos y los textos privados del Pixel).
  - Las pruebas instrumentadas van en `app/src/androidTest` y se corren así:
    ```bash
    ./gradlew installFdroidDebug installFdroidDebugAndroidTest
    adb shell am force-stop io.github.diegobr4nd.lectorbilingue
    adb shell am instrument -w -e class <Clase> io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner
    ```
- **Commits:** Conventional Commits en español, terminando en `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **EPUB normal con fuentes ofuscadas** (muy común en libros de tienda sin DRM): debe importarse, no rechazarse como DRM. Tarea 3, prueba `fuentes ofuscadas no son DRM`.
2. **Persona con TalkBack en el Lector:** no puede "deslizar hacia arriba" para que aparezcan las barras. Con TalkBack las barras siempre se ven. Tarea 9, prueba de `ReaderRules.barsVisible` con `touchExploration = true`.
3. **Android cerró la app con el Lector en segundo plano:** al volver, `ReaderActivity` no tiene el libro en `OpenBooks`. Debe cerrarse y mostrar la Biblioteca, nunca fallar. Tarea 9, prueba de `ReaderStart.decide`.
4. **App cerrada a mitad de una importación:** no debe quedar ni una fila fantasma ni un archivo huérfano. La fila se crea **después** de mover el EPUB, y `tmp/` se limpia al arrancar. Tarea 6, pruebas `fallo al guardar la fila no deja archivos` y `cleanTmp borra restos`.
5. **Cancelar el selector de archivos** (`uri == null`) o un libro con título vacío o solo espacios: no debe aparecer ningún error, y el título cae al nombre del archivo. Tareas 6 (prueba `título en blanco usa el nombre del archivo`) y 8 (`LibraryViewModel.import(null)` no hace nada).

---

### Task 1: Spike desechable de la 3b (agente `estructura`)

**Objetivo:** responder con evidencia cinco preguntas antes de construir nada. **El código no se fusiona:** vive en la rama local `spike/readium-3b` (creada desde `feat/lector-epub`) y nunca se sube.

**Files (solo en `spike/readium-3b`):**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`: Readium 3.4.0, `fragment-compose` y jsoup. Usar `--dependency-verification lenient` en el spike.
- Create: `app/src/debug/kotlin/.../spike/SpikeReaderActivity.kt`, `app/src/debug/AndroidManifest.xml` (actividad no exportada)
- Create: `app/src/debug/assets/spike/prueba.epub`, generado con el script del Paso 1.

- [ ] **Step 1: Crear el EPUB de prueba**

Script Python en el scratchpad (no en el repo) que genera `prueba.epub`:
- `mimetype` sin comprimir;
- `container.xml` y `content.opf`;
- 3 capítulos XHTML de 30 párrafos `<p>` cada uno, con texto inventado ("Párrafo N del capítulo M…").

El capítulo 2 incluye además:
```html
<script>document.body.insertAdjacentHTML('afterbegin','<p id="js">JS_DEL_LIBRO_EJECUTADO</p>')</script>
<p onclick="document.title='ONCLICK'">Párrafo con onclick</p>
<img src="https://127.0.0.1:8765/espia.png"/>
<img src="http://127.0.0.1:8765/espia-http.png"/>
<link rel="stylesheet" href="https://127.0.0.1:8765/espia.css"/>
```

- [ ] **Step 2: Actividad mínima**

`SpikeReaderActivity : FragmentActivity`:
- copia el asset a `cacheDir`;
- abre el libro con `AssetRetriever` + `PublicationOpener`, usando el `onCreatePublication` que aplica `HtmlSanitizer` (copiar el código de la Tarea 5);
- instala `EpubNavigatorFactory(publication).createFragmentFactory(initialLocator = null, initialPreferences = EpubPreferences(scroll = true))` **antes** de `super.onCreate()`. Abrir el libro con `runBlocking` está permitido solo en el spike;
- muestra el fragmento con `AndroidFragment<EpubNavigatorFragment>`.

- [ ] **Step 3: P1 · tocar un párrafo y leer su texto**

`navigator.addInputListener(object : InputListener { override fun onTap(event: TapEvent): Boolean { … } })`. Dentro:
```kotlin
lifecycleScope.launch {
    val js = """(function(){var e=document.elementFromPoint(${event.point.x / density},${event.point.y / density});
      var p=e&&e.closest('p,li,blockquote,h1,h2,h3,h4,h5,h6');return p?p.textContent.length:-1})()"""
    Log.d("Spike", "P1 largo=" + navigator.evaluateJavascript(js))
}
```
Se registra solo el **largo** del texto, nunca el texto (regla 5). Éxito: al tocar párrafos distintos salen largos distintos y positivos.

- [ ] **Step 4: P2 · insertar un bloque debajo del párrafo sin salto**

Mismo toque. Script que inserta `<div class="lb-tr" style="background:#eef">TRADUCCION_DE_PRUEBA</div>` después del párrafo. Si el párrafo queda por encima de la vista, compensa con `window.scrollBy(0, alturaDelDiv)`.

Éxito, con capturas `adb exec-out screencap -p > p2-antes.png` y `p2-despues.png`:
- el bloque aparece bajo el párrafo tocado;
- el párrafo tocado no se mueve en pantalla.

- [ ] **Step 5: P3 · JavaScript del libro**

Abrir el capítulo 2 con `HtmlSanitizer` activo. Éxito:
- no aparece `JS_DEL_LIBRO_EJECUTADO`;
- tocar "Párrafo con onclick" no cambia el título (`evaluateJavascript("document.title")`);
- P1 y P2 siguen funcionando, es decir, la CSP no rompió los scripts de Readium.

- [ ] **Step 6: P4 · red**

En el PC:
```bash
adb reverse tcp:8765 tcp:8765
python -m http.server 8765
```
Abrir el capítulo 2 en el teléfono y esperar 10 s. Éxito: el servidor **no registra ninguna petición**. Repetir **sin** `HtmlSanitizer` para saber si Readium solo ya bloqueaba la red, y anotarlo.

- [ ] **Step 7: P5 · desplazamiento continuo y localizador**

Registrar `navigator.currentLocator` mientras se desliza: solo `locations.totalProgression` y `title != null`. Éxito: `totalProgression` sube al bajar y baja al subir, con actualizaciones durante el desplazamiento y no solo al soltar.

- [ ] **Step 8: Anotar resultados y decidir**

Añadir a la spec la sección `## 11. Resultado del spike`, con una tabla P1–P5 (✅/❌ + una línea de evidencia). Commit **en `feat/lector-epub`** (solo la spec):
```bash
git switch feat/lector-epub
git add docs/superpowers/specs/2026-10-05-lector-epub-3a-design.md
git commit -m "docs(3a): resultado del spike de Readium"
```
- **Si P1 o P2 fallan:** PARAR y avisar a Juan (spec §3).
- **Si P3 o P4 fallan:** seguir, pero ajustar la CSP o el sanitizador en la Tarea 5 y avisar a `seguridad`.
- **Si P5 falla:** cambiar la regla de las barras de la Tarea 9 a `onScrollChanged` del WebView y avisar.

Borrar la rama del spike: `git branch -D spike/readium-3b`.

---

### Task 2: Módulo `:books`, dependencias y verificación (agente `infraestructura`)

**Files:**
- Modify: `gradle/libs.versions.toml`, `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/verification-metadata.xml`, `build.gradle.kts` (raíz: plugins KSP y Room con `apply false`)
- Create: `books/build.gradle.kts`, `books/consumer-rules.pro`, `books/src/main/AndroidManifest.xml`, `books/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/books/ModuleSmokeTest.kt`
- Modify: `docs/agentes/03-seguridad.md` (línea de respaldos), `docs/build.md` (módulo nuevo)

**Interfaces:**
- Produces: módulo `:books` compilable, con acceso a Readium, Room, jsoup y coroutines; `:app` depende de `:books`, `fragment-compose` y `readium-navigator`.

- [ ] **Step 1: Fijar versiones**

Consultar la última **estable** de cada artefacto en `https://dl.google.com/dl/android/maven2/<grupo con />/<artefacto>/maven-metadata.xml` (Google) o `https://repo1.maven.org/maven2/...` (Central):
- `androidx.room:room-runtime`;
- `com.google.devtools.ksp` compatible con Kotlin 2.4.20;
- `androidx.fragment:fragment-compose`;
- `org.jsoup:jsoup`.

Readium queda fijado en `3.4.0`. Añadir al catálogo:
```toml
[versions]
readium = "3.4.0"
room = "<estable>"
ksp = "<compatible con 2.4.20>"
fragment = "<estable>"
jsoup = "<estable>"

[libraries]
readium-shared = { group = "org.readium.kotlin-toolkit", name = "readium-shared", version.ref = "readium" }
readium-streamer = { group = "org.readium.kotlin-toolkit", name = "readium-streamer", version.ref = "readium" }
readium-navigator = { group = "org.readium.kotlin-toolkit", name = "readium-navigator", version.ref = "readium" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
androidx-fragment-compose = { group = "androidx.fragment", name = "fragment-compose", version.ref = "fragment" }
jsoup = { group = "org.jsoup", name = "jsoup", version.ref = "jsoup" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```

- [ ] **Step 2: Módulo `:books`**

`settings.gradle.kts`: `include(":books")`. En `build.gradle.kts` raíz: `alias(libs.plugins.ksp) apply false` y `alias(libs.plugins.room) apply false`.

`books/build.gradle.kts`:
```kotlin
plugins {
    id("lectorbilingue.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue.books"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    testOptions { unitTests.isReturnDefaultValues = false }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    api(libs.readium.shared)
    api(libs.readium.streamer)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```
`books/src/main/AndroidManifest.xml`: `<manifest />` vacío. `books/consumer-rules.pro`: vacío, con el comentario `# Readium y Room traen sus propias reglas.`

`app/build.gradle.kts`, en `dependencies`:
```kotlin
implementation(project(":books"))
implementation(libs.readium.navigator)
implementation(libs.androidx.fragment.compose)
androidTestImplementation(libs.androidx.room.testing)
```

- [ ] **Step 3: Prueba de humo**

```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleSmokeTest {
    @Test fun `el modulo compila y corre pruebas`() = assertEquals(4, 2 + 2)
}
```
Run: `./gradlew :books:test`. Expected: falla por dependencias sin verificar (`Dependency verification failed`).

- [ ] **Step 4: Regenerar `verification-metadata.xml`** (comando de `docs/build.md`):
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks resolveAapt2AllPlatforms lint test assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest
```
Expected: BUILD SUCCESSFUL. En el diff de `verification-metadata.xml` solo aparecen artefactos nuevos.

- [ ] **Step 5: Árbol de dependencias para `seguridad`**

```bash
./gradlew :app:dependencies --configuration fdroidReleaseRuntimeClasspath > "$SCRATCH/deps-3a.txt"
```
Buscar `firebase|gms|play-services|analytics|crashlytics|okhttp|mupdf`. Expected: ninguna coincidencia. Si aparece alguna, PARAR y avisar. Anotar el tamaño del APK release antes y después (`ls -l app/build/outputs/apk/fdroid/release/`).

- [ ] **Step 6: Documentos**

- `docs/agentes/03-seguridad.md` §4: cambiar `respaldar ajustes y biblioteca; **no** la caché ni los modelos` por `sin respaldos de Android (`allowBackup="false"`): ni ajustes, ni biblioteca, ni libros, ni caché, ni modelos. Restaurar la biblioteca sin los EPUB dejaría libros que no abren`.
- `docs/build.md`: añadir `:books` a la lista de módulos, con una línea: "Biblioteca: importar y validar EPUB, Room y Readium (sin pantallas)".

- [ ] **Step 7: Verificar y commit**

Run: `./gradlew :books:test lint assembleFdroidDebug`. Expected: PASS y BUILD SUCCESSFUL. Además, el paso del CI "El manifest fusionado solo pide los permisos permitidos" se reproduce localmente:
```bash
grep -h "uses-permission" $(find app/build/intermediates -path '*fdroidRelease*' -name AndroidManifest.xml) | sort -u
```
Expected: la misma lista de 7 permisos de siempre.
```bash
git add gradle settings.gradle.kts build.gradle.kts app/build.gradle.kts books docs/agentes/03-seguridad.md docs/build.md
git commit -m "build(3a): módulo :books con Readium 3.4.0, Room, KSP y jsoup"
```

---

### Task 3: `EpubArchiveCheck` y `BookFiles` (agente `estructura`)

**Files:**
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/ImportError.kt`
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/EpubArchiveCheck.kt`
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/BookFiles.kt`
- Create: `books/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/books/TestEpub.kt` (constructor de EPUBs de prueba)
- Test: `books/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/books/EpubArchiveCheckTest.kt`, `BookFilesTest.kt`
- Delete: `books/src/test/.../ModuleSmokeTest.kt`

**Interfaces:**
- Produces:
  - `enum class ImportError { NOT_EPUB, TOO_BIG, UNSAFE_ARCHIVE, DRM, DAMAGED, NO_SPACE }`
  - `object ImportLimits { const val MAX_FILE_BYTES: Long; const val MAX_UNCOMPRESSED_BYTES: Long; const val MAX_ENTRIES: Int }`
  - `object EpubArchiveCheck { fun check(file: java.io.File): ImportError? }`: devuelve `null` si el archivo está bien.
  - `class BookFiles(root: File)`, donde `root` es `context.filesDir`:
    - `val booksDir: File`, `val tmpDir: File`;
    - `fun epub(id: String): File`, `fun cover(id: String): File`, `fun newTmp(): File`;
    - `fun cleanTmp()`, `fun deleteBook(id: String)`.
  - En pruebas: `TestEpub.build(dir: File, name: String = "libro.epub", block: TestEpub.() -> Unit = {}): File`, con las opciones `title`, `author`, `entry(name, bytes, stored = false)`, `replace(name, bytes)`, `withoutMimetype()`, `withoutContainer()`, `mimetypeText`; y `rawZip(file, entries)`.

- [ ] **Step 1: Constructor de EPUBs de prueba**

```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Arma EPUBs pequeños en las pruebas. Nada con derechos de autor: texto inventado. */
class TestEpub private constructor() {
    var title: String? = "Libro de prueba"
    var author: String? = "Autora Inventada"
    var mimetypeText = "application/epub+zip"
    private var mimetype = true
    private var container = true
    private val extra = mutableListOf<Triple<String, ByteArray, Boolean>>()
    private val replaced = mutableMapOf<String, ByteArray>()

    fun withoutMimetype() { mimetype = false }
    /** Cambia el contenido de un archivo base (p. ej. "OEBPS/content.opf" u "OEBPS/c1.xhtml") sin duplicar la entrada. */
    fun replace(name: String, bytes: ByteArray) { replaced[name] = bytes }
    fun withoutContainer() { container = false }
    fun entry(name: String, bytes: ByteArray, stored: Boolean = false) { extra += Triple(name, bytes, stored) }

    private fun opf(): String = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">urn:uuid:00000000-0000-0000-0000-000000000001</dc:identifier>
    ${title?.let { "<dc:title>$it</dc:title>" } ?: ""}
    ${author?.let { "<dc:creator>$it</dc:creator>" } ?: ""}
    <dc:language>es</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>"""

    private fun write(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            if (mimetype) zip.putStored("mimetype", mimetypeText.toByteArray())
            if (container) zip.putDeflated(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            zip.putDeflated("OEBPS/content.opf", replaced["OEBPS/content.opf"] ?: opf().toByteArray())
            zip.putDeflated(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Índice</title></head><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">Capítulo uno</a></li></ol></nav></body></html>""".toByteArray(),
            )
            zip.putDeflated(
                "OEBPS/c1.xhtml",
                replaced["OEBPS/c1.xhtml"] ?: """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Capítulo uno</title></head><body><p>Párrafo uno inventado.</p><p>Párrafo dos inventado.</p></body></html>""".toByteArray(),
            )
            for ((name, bytes, stored) in extra) if (stored) zip.putStored(name, bytes) else zip.putDeflated(name, bytes)
        }
    }

    companion object {
        fun build(dir: File, name: String = "libro.epub", block: TestEpub.() -> Unit = {}): File =
            File(dir, name).also { TestEpub().apply(block).write(it) }
    }
}

private fun ZipOutputStream.putDeflated(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
}

private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
    val crc = CRC32().apply { update(bytes) }
    putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; this.crc = crc.value })
    write(bytes); closeEntry()
}
```
`ZipOutputStream` rechaza nombres con `..`, así que las pruebas de *zip slip* escriben el ZIP **a mano**. Añadir a `TestEpub.kt`:
```kotlin
/** ZIP mínimo escrito byte a byte, para nombres que ZipOutputStream no permite (p. ej. "../x"). */
fun rawZip(file: File, entries: List<Pair<String, ByteArray>>): File {
    val out = java.io.ByteArrayOutputStream()
    val central = java.io.ByteArrayOutputStream()
    fun le16(o: java.io.OutputStream, v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
    fun le32(o: java.io.OutputStream, v: Long) { for (i in 0 until 4) o.write((v shr (8 * i)).toInt() and 0xff) }
    for ((name, data) in entries) {
        val offset = out.size().toLong()
        val n = name.toByteArray()
        val crc = CRC32().apply { update(data) }.value
        le32(out, 0x04034b50); le16(out, 20); le16(out, 0); le16(out, 0); le16(out, 0); le16(out, 0)
        le32(out, crc); le32(out, data.size.toLong()); le32(out, data.size.toLong()); le16(out, n.size); le16(out, 0)
        out.write(n); out.write(data)
        le32(central, 0x02014b50); le16(central, 20); le16(central, 20); le16(central, 0); le16(central, 0); le16(central, 0); le16(central, 0)
        le32(central, crc); le32(central, data.size.toLong()); le32(central, data.size.toLong()); le16(central, n.size)
        le16(central, 0); le16(central, 0); le16(central, 0); le16(central, 0); le32(central, 0); le32(central, offset)
        central.write(n)
    }
    val cdOffset = out.size().toLong()
    out.write(central.toByteArray())
    le32(out, 0x06054b50); le16(out, 0); le16(out, 0); le16(out, entries.size); le16(out, entries.size)
    le32(out, central.size().toLong()); le32(out, cdOffset); le16(out, 0)
    file.writeBytes(out.toByteArray())
    return file
}
```

- [ ] **Step 2: Pruebas que fallan** (`EpubArchiveCheckTest.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpubArchiveCheckTest {
    @get:Rule val tmp = TemporaryFolder()
    private val mime = "application/epub+zip".toByteArray()
    private val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="a.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray()

    private fun encryption(vararg algorithms: String) = """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
${algorithms.joinToString("\n") { "<enc:EncryptedData><enc:EncryptionMethod Algorithm=\"$it\"/></enc:EncryptedData>" }}
</encryption>""".toByteArray()

    @Test fun `EPUB valido pasa`() = assertNull(EpubArchiveCheck.check(TestEpub.build(tmp.root)))

    @Test fun `no es ZIP`() {
        val f = tmp.newFile("x.epub").apply { writeText("hola, no soy un zip") }
        assertEquals(ImportError.NOT_EPUB, EpubArchiveCheck.check(f))
    }

    @Test fun `archivo vacio`() = assertEquals(ImportError.NOT_EPUB, EpubArchiveCheck.check(tmp.newFile("v.epub")))

    @Test fun `sin mimetype`() =
        assertEquals(ImportError.NOT_EPUB, EpubArchiveCheck.check(TestEpub.build(tmp.root) { withoutMimetype() }))

    @Test fun `mimetype equivocado`() =
        assertEquals(ImportError.NOT_EPUB, EpubArchiveCheck.check(TestEpub.build(tmp.root) { mimetypeText = "application/zip" }))

    @Test fun `sin container`() =
        assertEquals(ImportError.NOT_EPUB, EpubArchiveCheck.check(TestEpub.build(tmp.root) { withoutContainer() }))

    @Test fun `zip slip con puntos`() {
        val f = rawZip(tmp.newFile("s.epub"), listOf("mimetype" to mime, "META-INF/container.xml" to container, "../../evil.txt" to "x".toByteArray()))
        assertEquals(ImportError.UNSAFE_ARCHIVE, EpubArchiveCheck.check(f))
    }

    @Test fun `nombres peligrosos`() {
        for (bad in listOf("/abs.txt", "a\\b.txt", "C:/x.txt", "a\u0000b", "OEBPS/../../x")) {
            val f = rawZip(tmp.newFile(), listOf("mimetype" to mime, "META-INF/container.xml" to container, bad to "x".toByteArray()))
            assertEquals(ImportError.UNSAFE_ARCHIVE, EpubArchiveCheck.check(f), bad)
        }
    }

    @Test fun `demasiadas entradas`() {
        val f = TestEpub.build(tmp.root) { repeat(ImportLimits.MAX_ENTRIES) { entry("OEBPS/r$it.txt", byteArrayOf(1)) } }
        assertEquals(ImportError.UNSAFE_ARCHIVE, EpubArchiveCheck.check(f))
    }

    @Test fun `bomba ZIP por tamano real`() {
        // 600 MB de ceros comprimen a menos de 1 MB: se escribe en trozos para no llenar la memoria.
        val f = File(tmp.root, "bomba.epub")
        java.util.zip.ZipOutputStream(f.outputStream()).use { zip ->
            val crc = java.util.zip.CRC32()
            zip.putNextEntry(java.util.zip.ZipEntry("mimetype").apply { method = java.util.zip.ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; this.crc = crc.apply { update(mime) }.value })
            zip.write(mime); zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml")); zip.write(container); zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/ceros.bin"))
            val chunk = ByteArray(1024 * 1024)
            repeat(600) { zip.write(chunk) }
            zip.closeEntry()
        }
        assertEquals(ImportError.UNSAFE_ARCHIVE, EpubArchiveCheck.check(f))
    }

    @Test fun `LCP es DRM`() =
        assertEquals(ImportError.DRM, EpubArchiveCheck.check(TestEpub.build(tmp.root) { entry("META-INF/license.lcpl", "{}".toByteArray()) }))

    @Test fun `Adobe rights es DRM`() =
        assertEquals(ImportError.DRM, EpubArchiveCheck.check(TestEpub.build(tmp.root) { entry("META-INF/rights.xml", "<rights/>".toByteArray()) }))

    @Test fun `cifrado AES es DRM`() = assertEquals(
        ImportError.DRM,
        EpubArchiveCheck.check(TestEpub.build(tmp.root) { entry("META-INF/encryption.xml", encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc")) }),
    )

    @Test fun `fuentes ofuscadas no son DRM`() = assertNull(
        EpubArchiveCheck.check(
            TestEpub.build(tmp.root) {
                entry("META-INF/encryption.xml", encryption("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC"))
            },
        ),
    )

    @Test fun `fuentes ofuscadas mas un recurso cifrado es DRM`() = assertEquals(
        ImportError.DRM,
        EpubArchiveCheck.check(
            TestEpub.build(tmp.root) {
                entry("META-INF/encryption.xml", encryption("http://www.idpf.org/2008/embedding", "http://www.w3.org/2001/04/xmlenc#aes256-cbc"))
            },
        ),
    )
}
```
(Añadir `import java.io.File` arriba.)

Run: `./gradlew :books:test --tests "*EpubArchiveCheckTest"`. Expected: FAIL, compilación: `Unresolved reference: EpubArchiveCheck`.

- [ ] **Step 3: Implementación**

`ImportError.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books

/** Por qué no se pudo añadir un libro. La interfaz lo convierte en un mensaje humano. */
enum class ImportError { NOT_EPUB, TOO_BIG, UNSAFE_ARCHIVE, DRM, DAMAGED, NO_SPACE }

/** Topes de la importación (docs/agentes/03-seguridad.md, amenaza A3). */
object ImportLimits {
    const val MAX_FILE_BYTES: Long = 100L * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES: Long = 500L * 1024 * 1024
    const val MAX_ENTRIES: Int = 10_000
}
```

`EpubArchiveCheck.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Revisa un EPUB (un ZIP) antes de dárselo a Readium: zip slip, bomba ZIP, demasiadas entradas, estructura y DRM.
 * Lee el contenido real contando bytes: el tamaño declarado en el ZIP puede mentir.
 */
object EpubArchiveCheck {
    private const val MIMETYPE = "application/epub+zip"
    private const val ENCRYPTION_MAX_BYTES = 1L * 1024 * 1024

    /** La ofuscación de fuentes no es DRM: la usan muchos libros normales. */
    private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
    private val ALGORITHM = Regex("""Algorithm\s*=\s*["']([^"']*)["']""")

    fun check(file: File): ImportError? = try {
        ZipFile(file).use { zip -> checkZip(zip) }
    } catch (_: ZipException) {
        ImportError.NOT_EPUB
    } catch (_: IOException) {
        ImportError.DAMAGED
    }

    private fun checkZip(zip: ZipFile): ImportError? {
        val entries = zip.entries().toList()
        if (entries.size > ImportLimits.MAX_ENTRIES) return ImportError.UNSAFE_ARCHIVE
        if (entries.any { !isSafeName(it.name) }) return ImportError.UNSAFE_ARCHIVE
        if (entries.sumOf { it.size.coerceAtLeast(0) } > ImportLimits.MAX_UNCOMPRESSED_BYTES) return ImportError.UNSAFE_ARCHIVE

        var total = 0L
        val buffer = ByteArray(64 * 1024)
        for (entry in entries) {
            if (entry.isDirectory) continue
            zip.getInputStream(entry).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > ImportLimits.MAX_UNCOMPRESSED_BYTES) return ImportError.UNSAFE_ARCHIVE
                }
            }
        }

        val mimetype = zip.getEntry("mimetype") ?: return ImportError.NOT_EPUB
        val mimeText = zip.getInputStream(mimetype).use { it.readNBytes(64) }.toString(Charsets.US_ASCII).trim()
        if (mimeText != MIMETYPE) return ImportError.NOT_EPUB
        if (zip.getEntry("META-INF/container.xml") == null) return ImportError.NOT_EPUB

        if (zip.getEntry("META-INF/license.lcpl") != null || zip.getEntry("META-INF/rights.xml") != null) return ImportError.DRM
        zip.getEntry("META-INF/encryption.xml")?.let { enc ->
            val text = zip.getInputStream(enc).use { it.readNBytes(ENCRYPTION_MAX_BYTES.toInt()) }.toString(Charsets.UTF_8)
            if (ALGORITHM.findAll(text).any { it.groupValues[1].trim() !in FONT_OBFUSCATION }) return ImportError.DRM
        }
        return null
    }

    internal fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || '\u0000' in name || '\\' in name) return false
        if (name.startsWith("/")) return false
        if (name.length >= 2 && name[1] == ':') return false
        return name.split('/').none { it == ".." }
    }
}
```

- [ ] **Step 4: Las pruebas pasan**

Run: `./gradlew :books:test --tests "*EpubArchiveCheckTest"`. Expected: PASS (15 pruebas). Si `ZipFile` de Java rechaza alguno de los nombres hechos a mano antes que nosotros, con una `ZipException`, el resultado sería `NOT_EPUB`. En ese caso, en `check()`, antes de devolver `NOT_EPUB`, reabrir con `java.util.zip.ZipInputStream` y devolver `UNSAFE_ARCHIVE` si algún nombre falla `isSafeName`. La prueba exige `UNSAFE_ARCHIVE`.

- [ ] **Step 5: `BookFiles` con prueba primero**

`BookFilesTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `rutas dentro de files-books`() {
        val f = BookFiles(tmp.root)
        val id = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(java.io.File(tmp.root, "books/$id.epub"), f.epub(id))
        assertEquals(java.io.File(tmp.root, "books/$id.cover.png"), f.cover(id))
        assertTrue(f.newTmp().parentFile == f.tmpDir)
    }

    @Test fun `id que no es UUID se rechaza`() {
        val f = BookFiles(tmp.root)
        assertFailsWith<IllegalArgumentException> { f.epub("../../x") }
    }

    @Test fun `cleanTmp borra restos`() {
        val f = BookFiles(tmp.root)
        f.newTmp().writeText("resto")
        f.cleanTmp()
        assertEquals(0, f.tmpDir.listFiles()?.size ?: 0)
    }

    @Test fun `deleteBook borra epub y portada`() {
        val f = BookFiles(tmp.root)
        val id = "123e4567-e89b-12d3-a456-426614174000"
        f.booksDir.mkdirs(); f.epub(id).writeText("e"); f.cover(id).writeText("c")
        f.deleteBook(id)
        assertFalse(f.epub(id).exists()); assertFalse(f.cover(id).exists())
    }
}
```
Run: `./gradlew :books:test --tests "*BookFilesTest"`. Expected: FAIL (`Unresolved reference: BookFiles`).

`BookFiles.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import java.io.File
import java.util.UUID

/** Dónde viven los libros: carpeta privada de la app, que ninguna otra app puede leer. */
class BookFiles(root: File) {
    val booksDir = File(root, "books")
    val tmpDir = File(booksDir, "tmp")

    fun epub(id: String) = File(booksDir, "${checkId(id)}.epub")
    fun cover(id: String) = File(booksDir, "${checkId(id)}.cover.png")

    fun newTmp(): File {
        tmpDir.mkdirs()
        return File(tmpDir, "${UUID.randomUUID()}.epub")
    }

    /** Borra lo que dejó una importación cortada (la app se cerró a mitad). */
    fun cleanTmp() {
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    fun deleteBook(id: String) {
        epub(id).delete()
        cover(id).delete()
    }

    companion object {
        /** Solo UUID: un id raro nunca se convierte en una ruta fuera de books/. */
        fun checkId(id: String): String {
            require(isValidId(id)) { "id de libro inválido" }
            return id
        }

        fun isValidId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
    }
}
```
Run: `./gradlew :books:test`. Expected: PASS.

- [ ] **Step 6: Commit**
```bash
git rm books/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/books/ModuleSmokeTest.kt
git add books
git commit -m "feat(books): validación del EPUB (zip slip, bomba ZIP, DRM) y carpeta privada de libros"
```

---

### Task 4: Room · tabla `books` (agente `estructura`)

**Files:**
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/db/BookEntity.kt`, `BookDao.kt`, `LectorDatabase.kt`
- Create: `books/schemas/` (lo genera Room al compilar; se sube al repo)
- Test: `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/BookDaoOnDeviceTest.kt`
- Test (JVM): `books/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/books/FakeBookDao.kt` (para las Tareas 6 y 8)

**Interfaces:**
- Produces:
  ```kotlin
  @Entity(tableName = "books") data class BookEntity(
      @PrimaryKey val id: String, val title: String, val author: String?, val coverPath: String?,
      val addedAt: Long, val lastOpenedAt: Long?, val progress: Float, val locator: String?)
  @Dao interface BookDao {
      fun observeAll(): Flow<List<BookEntity>>
      suspend fun get(id: String): BookEntity?
      suspend fun insert(book: BookEntity)
      suspend fun delete(id: String)
      suspend fun savePosition(id: String, locator: String, progress: Float)
      suspend fun markOpened(id: String, at: Long)
  }
  abstract class LectorDatabase : RoomDatabase { abstract fun books(): BookDao; companion object { fun open(context: Context): LectorDatabase } }
  class FakeBookDao : BookDao   // solo pruebas JVM, en memoria
  ```

- [ ] **Step 1: Prueba instrumentada que falla**

```kotlin
package io.github.diegobr4nd.lectorbilingue

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Room en memoria: no toca la base real de la app en el Pixel. */
@RunWith(AndroidJUnit4::class)
class BookDaoOnDeviceTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, LectorDatabase::class.java,
    ).build()
    private val dao = db.books()

    @After fun close() = db.close()

    private fun book(id: String, added: Long, opened: Long? = null) =
        BookEntity(id, "T$id", null, null, added, opened, 0f, null)

    @Test fun ordenPorUltimoAbiertoYLuegoPorAnadido() = runBlocking {
        dao.insert(book("a", added = 1))
        dao.insert(book("b", added = 2))
        dao.insert(book("c", added = 3, opened = 10))
        assertEquals(listOf("c", "b", "a"), dao.observeAll().first().map { it.id })
    }

    @Test fun guardarPosicionYMarcarAbierto() = runBlocking {
        dao.insert(book("a", added = 1))
        dao.savePosition("a", "{\"href\":\"c1.xhtml\"}", 0.42f)
        dao.markOpened("a", 99)
        val a = dao.get("a")!!
        assertEquals(0.42f, a.progress); assertEquals("{\"href\":\"c1.xhtml\"}", a.locator); assertEquals(99L, a.lastOpenedAt)
    }

    @Test fun borrar() = runBlocking {
        dao.insert(book("a", added = 1))
        dao.delete("a")
        assertNull(dao.get("a"))
    }
}
```
Run: `./gradlew :app:compileFdroidDebugAndroidTestKotlin`. Expected: FAIL (`Unresolved reference: LectorDatabase`).

- [ ] **Step 2: Implementación**

`BookEntity.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Una fila de la Biblioteca. [locator] es la posición exacta de Readium en JSON; [progress] va de 0 a 1. */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val progress: Float,
    val locator: String?,
)
```
`BookDao.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    /** Último abierto primero; los nunca abiertos al final, del más nuevo al más viejo. */
    @Query("SELECT * FROM books ORDER BY lastOpenedAt IS NULL, lastOpenedAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE books SET locator = :locator, progress = :progress WHERE id = :id")
    suspend fun savePosition(id: String, locator: String, progress: Float)

    @Query("UPDATE books SET lastOpenedAt = :at WHERE id = :id")
    suspend fun markOpened(id: String, at: Long)
}
```
`LectorDatabase.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Base de datos de la app. La 3b añade la tabla de traducciones con una migración a la versión 2. */
@Database(entities = [BookEntity::class], version = 1, exportSchema = true)
abstract class LectorDatabase : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        const val NAME = "lector.db"
        fun open(context: Context): LectorDatabase =
            Room.databaseBuilder(context.applicationContext, LectorDatabase::class.java, NAME).build()
    }
}
```

- [ ] **Step 3: `FakeBookDao` para pruebas JVM**

```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import io.github.diegobr4nd.lectorbilingue.books.db.BookDao
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** BookDao en memoria con el mismo orden que la consulta real. */
class FakeBookDao : BookDao {
    val rows = MutableStateFlow<Map<String, BookEntity>>(emptyMap())
    var failInsert = false

    override fun observeAll(): Flow<List<BookEntity>> = rows.map { m ->
        m.values.sortedWith(compareBy<BookEntity> { it.lastOpenedAt == null }.thenByDescending { it.lastOpenedAt ?: 0 }.thenByDescending { it.addedAt })
    }
    override suspend fun get(id: String) = rows.value[id]
    override suspend fun insert(book: BookEntity) {
        if (failInsert) throw IllegalStateException("falla de prueba")
        check(book.id !in rows.value); rows.value = rows.value + (book.id to book)
    }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
    override suspend fun savePosition(id: String, locator: String, progress: Float) {
        rows.value[id]?.let { rows.value = rows.value + (id to it.copy(locator = locator, progress = progress)) }
    }
    override suspend fun markOpened(id: String, at: Long) {
        rows.value[id]?.let { rows.value = rows.value + (id to it.copy(lastOpenedAt = at)) }
    }
}
```
`BookImporter` trata cualquier excepción de `insert` igual que un error de Room (`DAMAGED`).

- [ ] **Step 4: Pasa en el Pixel**

```bash
./gradlew assembleFdroidDebug installFdroidDebug installFdroidDebugAndroidTest
export MSYS_NO_PATHCONV=1
adb shell am force-stop io.github.diegobr4nd.lectorbilingue
adb shell am instrument -w -e class io.github.diegobr4nd.lectorbilingue.BookDaoOnDeviceTest io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: `OK (3 tests)`. Comprobar que existe `books/schemas/io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase/1.json`.

- [ ] **Step 5: Commit**
```bash
git add books app/src/androidTest
git commit -m "feat(books): base de datos Room con la tabla books"
```

---

### Task 5: Readium · abrir, metadatos, portada, `HtmlSanitizer` y `OfflineHttpClient` (agente `estructura`)

**Files:**
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/readium/HtmlSanitizer.kt`
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/readium/OfflineHttpClient.kt`
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/readium/ReadiumBooks.kt`
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/BookMetadata.kt`
- Test: `books/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/books/readium/HtmlSanitizerTest.kt`
- Test: `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/ReadiumBooksOnDeviceTest.kt`
- Create: `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/TestEpub.kt`: copia de `TestEpub` y `rawZip` de la Tarea 3, con el paquete `io.github.diegobr4nd.lectorbilingue`. Es la única duplicación permitida: las pruebas de `:books` y las instrumentadas de `:app` no comparten fuentes.

**Interfaces:**
- Consumes: `ImportError` (Tarea 3).
- Produces:
  - `object HtmlSanitizer { const val CSP: String; fun sanitize(markup: String, isSvg: Boolean = false): String }`
  - `class OfflineHttpClient : org.readium.r2.shared.util.http.HttpClient`
  - `data class BookMetadata(val title: String?, val author: String?, val cover: android.graphics.Bitmap?)`
  - `class ReadiumBooks(context: Context)`:
    - `suspend fun open(file: File): Try<Publication, ImportError>`, donde `Try` es `org.readium.r2.shared.util.Try`;
    - `suspend fun metadata(file: File): Try<BookMetadata, ImportError>`, que abre, lee y cierra.

- [ ] **Step 1: Pruebas del sanitizador que fallan**

```kotlin
package io.github.diegobr4nd.lectorbilingue.books.readium

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlSanitizerTest {
    private fun xhtml(body: String, head: String = "<title>t</title>") =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head>$head</head><body>$body</body></html>"""

    @Test fun `quita script`() {
        val out = HtmlSanitizer.sanitize(xhtml("<p>hola</p><script>alert(1)</script><SCRIPT src='x.js'></SCRIPT>"))
        assertFalse(out.contains("script", ignoreCase = true)); assertTrue(out.contains("<p>hola</p>"))
    }

    @Test fun `quita atributos on`() {
        val out = HtmlSanitizer.sanitize(xhtml("""<p onclick="x()" ONLOAD="y()" class="c">a</p><img src="i.png" onerror="z()"/>"""))
        assertFalse(out.contains("onclick", true)); assertFalse(out.contains("onload", true)); assertFalse(out.contains("onerror", true))
        assertTrue(out.contains("class=\"c\""))
    }

    @Test fun `quita enlaces javascript aunque lleven espacios o mayusculas`() {
        val out = HtmlSanitizer.sanitize(xhtml("""<a href=" JavaScript:alert(1)">a</a><a href="java&#x09;script:x">b</a><a href="c2.xhtml">c</a>"""))
        assertFalse(out.contains("javascript", true)); assertFalse(out.contains("java\tscript", true))
        assertTrue(out.contains("href=\"c2.xhtml\""))
    }

    @Test fun `quita iframe object embed form base y meta refresh`() {
        val out = HtmlSanitizer.sanitize(
            xhtml(
                """<iframe src="https://x"/><object data="x"/><embed src="x"/><form action="https://x"><input/></form>""",
                head = """<title>t</title><base href="https://x/"/><meta http-equiv="refresh" content="0;url=https://x"/>""",
            ),
        )
        for (t in listOf("<iframe", "<object", "<embed", "<form", "<base", "refresh")) assertFalse(out.contains(t, true), t)
    }

    @Test fun `inserta la CSP en head`() {
        val out = HtmlSanitizer.sanitize(xhtml("<p>a</p>"))
        assertTrue(out.contains("Content-Security-Policy")); assertTrue(out.contains("connect-src 'none'"))
    }

    @Test fun `crea head si no hay`() {
        val out = HtmlSanitizer.sanitize("""<html xmlns="http://www.w3.org/1999/xhtml"><body><p>a</p></body></html>""")
        assertTrue(out.contains("Content-Security-Policy"))
    }

    @Test fun `SVG sin script ni on`() {
        val out = HtmlSanitizer.sanitize("""<svg xmlns="http://www.w3.org/2000/svg"><script>x()</script><rect onload="y()"/></svg>""", isSvg = true)
        assertFalse(out.contains("script", true)); assertFalse(out.contains("onload", true))
    }

    @Test fun `conserva texto con acentos y entidades basicas`() {
        val out = HtmlSanitizer.sanitize(xhtml("<p>Canción &amp; señal</p>"))
        assertTrue(out.contains("Canción &amp; señal"))
    }
}
```
Run: `./gradlew :books:test --tests "*HtmlSanitizerTest"`. Expected: FAIL (`Unresolved reference: HtmlSanitizer`).

- [ ] **Step 2: `HtmlSanitizer`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.books.readium

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser

/**
 * Limpia el HTML de un libro antes de mostrarlo: el libro no puede ejecutar código ni usar la red.
 * Corre ANTES de que Readium añada sus propios scripts, así que no los toca.
 */
object HtmlSanitizer {
    /** Solo recursos del propio libro (Readium los sirve desde su mismo origen); nada de red. */
    const val CSP = "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' data:; " +
        "img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' data: blob:; connect-src 'none'; " +
        "object-src 'none'; frame-src 'self'; form-action 'none'; base-uri 'none'"

    private const val REMOVE = "script, iframe, object, embed, form, base, meta[http-equiv~=(?i)refresh]"
    private val LINK_ATTRS = setOf("href", "src", "xlink:href", "action", "formaction", "data", "poster")

    fun sanitize(markup: String, isSvg: Boolean = false): String {
        val doc = Jsoup.parse(markup, "", Parser.xmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false).escapeMode(Entities.EscapeMode.xhtml).charset("UTF-8")
        doc.select(REMOVE).remove()
        for (el in doc.allElements) cleanAttributes(el)
        if (!isSvg) insertCsp(doc)
        return doc.outerHtml()
    }

    private fun cleanAttributes(el: Element) {
        val toRemove = el.attributes().asList().filter { attr ->
            val key = attr.key.lowercase()
            key.startsWith("on") || (key in LINK_ATTRS && isJavascriptUrl(attr.value))
        }
        for (attr in toRemove) el.removeAttr(attr.key)
    }

    /** "java\tscript:", " JavaScript:"… el navegador ignora espacios y controles: aquí también. */
    private fun isJavascriptUrl(value: String): Boolean =
        value.filterNot { it.isWhitespace() || it.isISOControl() }.lowercase().let { it.startsWith("javascript:") || it.startsWith("vbscript:") }

    private fun insertCsp(doc: Document) {
        val html = doc.selectFirst("html") ?: return
        val head = html.selectFirst("head") ?: html.prependElement("head")
        head.prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", CSP)
    }
}
```
jsoup convierte `&#x09;` en un tabulador al leer, e `isJavascriptUrl` quita espacios y controles antes de comparar: `java&#x09;script:` también se elimina. Run: `./gradlew :books:test --tests "*HtmlSanitizerTest"`. Expected: PASS (8 pruebas).

- [ ] **Step 3: `OfflineHttpClient`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.books.readium

import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.ThrowableError
import java.io.IOException

/** Readium pide un cliente HTTP; este nunca conecta. Leer un libro no usa la red (CLAUDE.md, regla 5). */
class OfflineHttpClient : HttpClient {
    override suspend fun stream(request: HttpRequest): Try<HttpStreamResponse, HttpError> =
        Try.failure(HttpError.IO(ThrowableError(IOException("sin red en el lector"))))
}
```
Si en 3.4.0 `HttpError.IO` tiene otro nombre o firma, mirar `readium/shared/src/main/java/org/readium/r2/shared/util/http/HttpError.kt` en la etiqueta `3.4.0` del repositorio de GitHub y usar la variante de error de E/S o "inalcanzable". El comportamiento exigido es siempre fallar sin abrir conexión.

- [ ] **Step 4: Prueba instrumentada que falla** (`ReadiumBooksOnDeviceTest.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.ImportError
import io.github.diegobr4nd.lectorbilingue.books.readium.ReadiumBooks
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReadiumBooksOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "readium-test").apply { deleteRecursively(); mkdirs() }
    private val books = ReadiumBooks(context)

    @Test fun leeTituloYAutor() = runBlocking {
        val m = books.metadata(TestEpub.build(dir)).getOrNull()!!
        assertEquals("Libro de prueba", m.title); assertEquals("Autora Inventada", m.author); assertNull(m.cover)
    }

    @Test fun sinTituloDevuelveNull() = runBlocking {
        assertNull(books.metadata(TestEpub.build(dir) { title = null }).getOrNull()!!.title)
    }

    @Test fun opfRotoEsDamaged() = runBlocking {
        val f = TestEpub.build(dir) { replace("OEBPS/content.opf", "<<no es xml".toByteArray()) }
        assertEquals(ImportError.DAMAGED, books.metadata(f).failureOrNull())
    }

    @Test fun xxeNoLeeArchivosDelTelefono() = runBlocking {
        val secreto = File(context.filesDir, "xxe-secreto.txt").apply { writeText("SECRETO_XXE") }
        try {
            // OPF con una entidad externa que apunta a un archivo privado de la app.
            val opf = """<?xml version="1.0"?><!DOCTYPE package [<!ENTITY xxe SYSTEM "file://${secreto.absolutePath}">]>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">x</dc:identifier><dc:title>&xxe;</dc:title><dc:language>es</dc:language></metadata>
<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>"""
            val g = TestEpub.build(dir, "xxe.epub") { replace("OEBPS/content.opf", opf.toByteArray()) }
            val title = books.metadata(g).getOrNull()?.title.orEmpty()
            assertFalse(title.contains("SECRETO_XXE"))
        } finally { secreto.delete() }
    }

    @Test fun elHtmlServidoNoTraeScripts() = runBlocking {
        val f = TestEpub.build(dir, "js.epub") {
            replace(
                "OEBPS/c1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title></head><body><p onclick="x()">a</p><script>y()</script></body></html>""".toByteArray(),
            )
        }
        val pub: Publication = books.open(f).getOrNull()!!
        try {
            val html = pub.get(Url("OEBPS/c1.xhtml")!!)!!.read().getOrNull()!!.toString(Charsets.UTF_8)
            assertFalse(html.contains("<script", true)); assertFalse(html.contains("onclick", true))
            assertTrue(html.contains("Content-Security-Policy"))
        } finally { pub.close() }
    }
}
```
Run (compilar): `./gradlew :app:compileFdroidDebugAndroidTestKotlin`. Expected: FAIL (`Unresolved reference: ReadiumBooks`).

- [ ] **Step 5: `ReadiumBooks` y `BookMetadata`**

`BookMetadata.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import android.graphics.Bitmap

/** Lo que la Biblioteca necesita de un libro. Nunca se registra en el log. */
data class BookMetadata(val title: String?, val author: String?, val cover: Bitmap?)
```
`ReadiumBooks.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books.readium

import android.content.Context
import io.github.diegobr4nd.lectorbilingue.books.BookMetadata
import io.github.diegobr4nd.lectorbilingue.books.ImportError
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.TransformingResource
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/** Abre EPUBs guardados con Readium, sin red y con el HTML limpio (HtmlSanitizer). */
class ReadiumBooks(context: Context) {
    private val http = OfflineHttpClient()
    private val assets = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(context, http, assets, pdfFactory = null),
        contentProtections = emptyList(),
        onCreatePublication = { container = sanitizing(container) },
    )

    suspend fun open(file: File): Try<Publication, ImportError> {
        val asset = assets.retrieve(file.toUrl()).getOrElse { return Try.failure(ImportError.NOT_EPUB) }
        return opener.open(asset, allowUserInteraction = false)
            .fold({ Try.success(it) }, { Try.failure(ImportError.DAMAGED) })
    }

    suspend fun metadata(file: File): Try<BookMetadata, ImportError> {
        val pub = open(file).getOrElse { return Try.failure(it) }
        return try {
            val m = pub.metadata
            Try.success(
                BookMetadata(
                    title = m.title?.takeIf { it.isNotBlank() },
                    author = m.authors.mapNotNull { it.name.takeIf(String::isNotBlank) }.joinToString(", ").ifBlank { null },
                    cover = pub.cover(),
                ),
            )
        } finally { pub.close() }
    }

    private fun sanitizing(container: Container<Resource>): Container<Resource> =
        TransformingContainer(container) { url, resource ->
            val path = url.path?.lowercase().orEmpty()
            when {
                path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm") -> resource.mapText { HtmlSanitizer.sanitize(it) }
                path.endsWith(".svg") -> resource.mapText { HtmlSanitizer.sanitize(it, isSvg = true) }
                else -> resource
            }
        }

    private fun Resource.mapText(transform: (String) -> String): Resource =
        TransformingResource(this) { bytes -> Try.success(transform(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)) }
}
```
Si los nombres de `TransformingContainer` o `TransformingResource`, la firma de `onCreatePublication` o el parámetro `pdfFactory` difieren en 3.4.0, buscarlos en el código de la etiqueta `3.4.0` (`readium/shared/src/main/java/org/readium/r2/shared/util/resource/` y `readium/streamer/src/main/java/org/readium/r2/streamer/PublicationOpener.kt`) y adaptar. El comportamiento es fijo: todo HTML, XHTML y SVG pasa por `HtmlSanitizer` antes de llegar al navegador. El spike (Tarea 1) ya habrá usado este código.

Run:
```bash
./gradlew installFdroidDebug installFdroidDebugAndroidTest
adb shell am force-stop io.github.diegobr4nd.lectorbilingue
adb shell am instrument -w -e class io.github.diegobr4nd.lectorbilingue.ReadiumBooksOnDeviceTest io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: `OK (5 tests)`. Si `xxeNoLeeArchivosDelTelefono` falla, PARAR y avisar a `seguridad`: el analizador XML de Readium resuelve entidades externas.

- [ ] **Step 6: Commit**
```bash
git add books app/src/androidTest
git commit -m "feat(books): abrir EPUB con Readium sin red y con HTML limpio (sin scripts, con CSP)"
```

---

### Task 6: `BookImporter` y `BookRepository` (agente `estructura`)

**Files:**
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/BookImporter.kt`, `BookRepository.kt`, `Book.kt`
- Test: `books/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/books/BookImporterTest.kt`, `BookRepositoryTest.kt`

**Interfaces:**
- Consumes: `BookFiles`, `EpubArchiveCheck`, `ImportError`, `ImportLimits` (Tarea 3); `BookDao`, `BookEntity`, `FakeBookDao` (Tarea 4); `BookMetadata` (Tarea 5).
- Produces:
  ```kotlin
  data class Book(val id: String, val title: String, val author: String?, val coverFile: File?, val progress: Float, val locator: String?)
  sealed interface ImportResult { data class Ok(val bookId: String) : ImportResult; data class Error(val reason: ImportError) : ImportResult }
  sealed interface MetadataRead { data class Ok(val metadata: BookMetadata) : MetadataRead; data class Failed(val reason: ImportError) : MetadataRead }
  class BookImporter(
      files: BookFiles, dao: BookDao,
      readMetadata: suspend (File) -> MetadataRead,       // en :app se conecta a ReadiumBooks.metadata; en la JVM, un falso
      saveCover: (android.graphics.Bitmap, File) -> Unit,
      clock: () -> Long = System::currentTimeMillis,
      newId: () -> String = { java.util.UUID.randomUUID().toString() },
  ) { suspend fun import(open: () -> InputStream?, fileName: String?): ImportResult; companion object { const val UNTITLED: String } }
  class BookRepository(dao: BookDao, files: BookFiles, importer: BookImporter, clock: () -> Long = System::currentTimeMillis) {
      val books: Flow<List<Book>>
      suspend fun import(open: () -> InputStream?, fileName: String?): ImportResult
      suspend fun get(id: String): Book?
      suspend fun delete(id: String)
      suspend fun savePosition(id: String, locator: String, progress: Float)
      suspend fun markOpened(id: String)
      fun epubFile(id: String): File
  }
  ```

- [ ] **Step 1: Pruebas que fallan** (`BookImporterTest.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BookImporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private lateinit var files: BookFiles
    private val dao = FakeBookDao()

    private fun importer(meta: MetadataRead = MetadataRead.Ok(BookMetadata("Título", "Autora", null))) =
        BookImporter(files, dao, readMetadata = { meta }, saveCover = { _, f -> f.writeText("png") }, clock = { 1000L }, newId = { id })
            .also { files.booksDir.mkdirs() }

    private fun source(file: File): () -> InputStream? = { file.inputStream() }

    private fun leftovers(): List<String> =
        (files.booksDir.listFiles().orEmpty().toList() + files.tmpDir.listFiles().orEmpty().toList()).filter { it.isFile }.map { it.name }

    @org.junit.Before fun setUp() { files = BookFiles(tmp.newFolder("files")) }

    @Test fun `importa un EPUB valido`() = runTest {
        val epub = TestEpub.build(tmp.root)
        val r = importer().import(source(epub), "mi-libro.epub")
        assertEquals(ImportResult.Ok(id), r)
        assertTrue(files.epub(id).exists())
        val row = dao.get(id)!!
        assertEquals("Título", row.title); assertEquals("Autora", row.author); assertEquals(1000L, row.addedAt); assertEquals(0f, row.progress)
        assertEquals(listOf("$id.epub"), leftovers())
    }

    @Test fun `titulo en blanco usa el nombre del archivo`() = runTest {
        val r = importer(MetadataRead.Ok(BookMetadata("   ", null, null))).import(source(TestEpub.build(tmp.root)), "Mi libro favorito.epub")
        assertIs<ImportResult.Ok>(r)
        assertEquals("Mi libro favorito", dao.get(id)!!.title)
    }

    @Test fun `sin titulo ni nombre usa Libro sin titulo`() = runTest {
        importer(MetadataRead.Ok(BookMetadata(null, null, null))).import(source(TestEpub.build(tmp.root)), null)
        assertEquals(BookImporter.UNTITLED, dao.get(id)!!.title)
    }

    @Test fun `demasiado grande corta la copia`() = runTest {
        val huge: () -> InputStream = {
            object : InputStream() {
                var left = ImportLimits.MAX_FILE_BYTES + 1
                override fun read(): Int = if (left-- > 0) 0 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    val n = minOf(len.toLong(), left).toInt(); left -= n; return n
                }
            }
        }
        assertEquals(ImportResult.Error(ImportError.TOO_BIG), importer().import(huge, "x.epub"))
        assertEquals(emptyList(), leftovers())
    }

    @Test fun `archivo que no es EPUB`() = runTest {
        val r = importer().import({ ByteArrayInputStream("hola".toByteArray()) }, "x.epub")
        assertEquals(ImportResult.Error(ImportError.NOT_EPUB), r)
        assertEquals(emptyList(), leftovers())
    }

    @Test fun `DRM se rechaza`() = runTest {
        val epub = TestEpub.build(tmp.root) { entry("META-INF/license.lcpl", "{}".toByteArray()) }
        assertEquals(ImportResult.Error(ImportError.DRM), importer().import(source(epub), "x.epub"))
        assertEquals(emptyList(), leftovers())
    }

    @Test fun `fallo de Readium no deja nada`() = runTest {
        val r = importer(MetadataRead.Failed(ImportError.DAMAGED)).import(source(TestEpub.build(tmp.root)), "x.epub")
        assertEquals(ImportResult.Error(ImportError.DAMAGED), r)
        assertEquals(emptyList(), leftovers())
    }

    @Test fun `fallo al guardar la fila no deja archivos`() = runTest {
        dao.failInsert = true
        val r = importer().import(source(TestEpub.build(tmp.root)), "x.epub")
        assertEquals(ImportResult.Error(ImportError.DAMAGED), r)
        assertEquals(emptyList(), leftovers())
        assertEquals(null, dao.get(id))
    }

    @Test fun `no se puede abrir el origen`() = runTest {
        assertEquals(ImportResult.Error(ImportError.DAMAGED), importer().import({ null }, "x.epub"))
    }
}
```
La portada no se prueba aquí porque `Bitmap` no existe en la JVM. Se comprueba en el Pixel en la Tarea 8 (Paso 6) y en la puerta.

Run: `./gradlew :books:test --tests "*BookImporterTest"`. Expected: FAIL (`Unresolved reference: BookImporter`).

- [ ] **Step 2: Implementación**

`Book.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import java.io.File

/** Un libro de la Biblioteca, listo para la interfaz. */
data class Book(val id: String, val title: String, val author: String?, val coverFile: File?, val progress: Float, val locator: String?)

sealed interface ImportResult {
    data class Ok(val bookId: String) : ImportResult
    data class Error(val reason: ImportError) : ImportResult
}

sealed interface MetadataRead {
    data class Ok(val metadata: BookMetadata) : MetadataRead
    data class Failed(val reason: ImportError) : MetadataRead
}
```
`BookImporter.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import android.graphics.Bitmap
import io.github.diegobr4nd.lectorbilingue.books.db.BookDao
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Añade un libro: copia con tope de tamaño → valida → lee metadatos → guarda portada → mueve → crea la fila.
 * Si algo falla, no queda ni archivo ni fila. La fila se crea al final: una app cerrada a mitad no deja libros fantasma.
 */
class BookImporter(
    private val files: BookFiles,
    private val dao: BookDao,
    private val readMetadata: suspend (File) -> MetadataRead,
    private val saveCover: (Bitmap, File) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun import(open: () -> InputStream?, fileName: String?): ImportResult = withContext(Dispatchers.IO) {
        val tmp = files.newTmp()
        val id = newId()
        var result: ImportResult = ImportResult.Error(ImportError.DAMAGED)
        try {
            result = steps(open, fileName, tmp, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            result = ImportResult.Error(if (files.booksDir.usableSpace in 0 until MIN_FREE_BYTES) ImportError.NO_SPACE else ImportError.DAMAGED)
        } catch (e: Exception) {
            result = ImportResult.Error(ImportError.DAMAGED)
        } finally {
            // Pase lo que pase: sin temporal; y si no terminó bien, sin EPUB ni portada.
            tmp.delete()
            if (result !is ImportResult.Ok) {
                files.epub(id).delete()
                files.cover(id).delete()
            }
        }
        result
    }

    private suspend fun steps(open: () -> InputStream?, fileName: String?, tmp: File, id: String): ImportResult {
        copyLimited(open, tmp)?.let { return ImportResult.Error(it) }
        EpubArchiveCheck.check(tmp)?.let { return ImportResult.Error(it) }
        val meta = when (val read = readMetadata(tmp)) {
            is MetadataRead.Failed -> return ImportResult.Error(read.reason)
            is MetadataRead.Ok -> read.metadata
        }
        files.booksDir.mkdirs()
        val coverFile = meta.cover?.let { bmp -> files.cover(id).also { saveCover(bmp, it) } }
        if (!tmp.renameTo(files.epub(id))) return ImportResult.Error(ImportError.DAMAGED)
        dao.insert(
            BookEntity(
                id = id,
                title = titleFor(meta.title, fileName),
                author = meta.author,
                coverPath = coverFile?.name,
                addedAt = clock(),
                lastOpenedAt = null,
                progress = 0f,
                locator = null,
            ),
        )
        return ImportResult.Ok(id)
    }

    /** Copia contando bytes: el tope se aplica aunque el origen no diga su tamaño. */
    private fun copyLimited(open: () -> InputStream?, target: File): ImportError? {
        val input = open() ?: return ImportError.DAMAGED
        input.use { src ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > ImportLimits.MAX_FILE_BYTES) return ImportError.TOO_BIG
                    out.write(buf, 0, n)
                }
            }
        }
        return null
    }

    private fun titleFor(meta: String?, fileName: String?): String =
        meta?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileName?.substringAfterLast('/')?.removeSuffix(".epub")?.removeSuffix(".EPUB")?.trim()?.takeIf { it.isNotEmpty() }
            ?: UNTITLED

    companion object {
        /** La interfaz lo muestra con su propio texto traducible; en la base queda esta marca. */
        const val UNTITLED = "\u0000untitled"
        private const val MIN_FREE_BYTES = 5L * 1024 * 1024
    }
}
```
Import extra: `kotlinx.coroutines.CancellationException`. Borrar `epub(id)` o `cover(id)` cuando todavía no existen no hace nada.

Run: `./gradlew :books:test --tests "*BookImporterTest"`. Expected: PASS (9 pruebas).

- [ ] **Step 3: `BookRepository` con prueba primero**

`BookRepositoryTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BookRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun `borrar quita fila, epub y portada`() = runTest {
        val files = BookFiles(tmp.root).apply { booksDir.mkdirs(); epub(id).writeText("e"); cover(id).writeText("c") }
        val dao = FakeBookDao().apply { insert(BookEntity(id, "T", null, "$id.cover.png", 1, null, 0f, null)) }
        val repo = BookRepository(dao, files, BookImporter(files, dao, { error("no") }, { _, _ -> }))
        repo.delete(id)
        assertNull(dao.get(id)); assertFalse(files.epub(id).exists()); assertFalse(files.cover(id).exists())
    }

    @Test fun `libros con portada como archivo y progreso`() = runTest {
        val files = BookFiles(tmp.root)
        val dao = FakeBookDao().apply { insert(BookEntity(id, "T", "A", "$id.cover.png", 1, null, 0.5f, null)) }
        val repo = BookRepository(dao, files, BookImporter(files, dao, { error("no") }, { _, _ -> }))
        assertEquals(listOf(Book(id, "T", "A", files.cover(id), 0.5f, null)), repo.books.first())
    }

    @Test fun `abrir marca la hora`() = runTest {
        val files = BookFiles(tmp.root)
        val dao = FakeBookDao().apply { insert(BookEntity(id, "T", null, null, 1, null, 0f, null)) }
        val repo = BookRepository(dao, files, BookImporter(files, dao, { error("no") }, { _, _ -> }), clock = { 77L })
        repo.markOpened(id)
        assertEquals(77L, dao.get(id)!!.lastOpenedAt)
    }

    @Test fun `progreso fuera de rango se recorta`() = runTest {
        val files = BookFiles(tmp.root)
        val dao = FakeBookDao().apply { insert(BookEntity(id, "T", null, null, 1, null, 0f, null)) }
        val repo = BookRepository(dao, files, BookImporter(files, dao, { error("no") }, { _, _ -> }))
        repo.savePosition(id, "{}", 1.7f)
        assertEquals(1f, dao.get(id)!!.progress)
    }
}
```
Run: `./gradlew :books:test --tests "*BookRepositoryTest"`. Expected: FAIL.

`BookRepository.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.books

import io.github.diegobr4nd.lectorbilingue.books.db.BookDao
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Punto único para la Biblioteca y el Lector: libros, importar, borrar y posición. */
class BookRepository(
    private val dao: BookDao,
    private val files: BookFiles,
    private val importer: BookImporter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val books: Flow<List<Book>> = dao.observeAll().map { rows -> rows.map { it.toBook() } }

    suspend fun import(open: () -> InputStream?, fileName: String?): ImportResult = importer.import(open, fileName)

    suspend fun get(id: String): Book? = dao.get(id)?.toBook()

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        files.deleteBook(id)
    }

    suspend fun savePosition(id: String, locator: String, progress: Float) =
        dao.savePosition(id, locator, progress.coerceIn(0f, 1f))

    suspend fun markOpened(id: String) = dao.markOpened(id, clock())

    fun epubFile(id: String): File = files.epub(id)

    private fun BookEntity.toBook() =
        Book(id, title, author, coverPath?.let { File(files.booksDir, it) }, progress, locator)
}
```
Run: `./gradlew :books:test`. Expected: PASS.

- [ ] **Step 4: Commit**
```bash
git add books
git commit -m "feat(books): importar libros sin dejar restos y repositorio de la Biblioteca"
```

---

### Task 7: Conexión en `:app`, ruta `Library` y reglas de la Biblioteca (agente `estructura`)

**Files:**
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/LectorApp.kt`
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/OpenBooks.kt`
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/nav/Routes.kt`, `StartRules.kt`, `AppNav.kt` (`Route.Home` → `Route.Library`)
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/library/LibraryRules.kt`
- Move: `ui/home/HomeRules.kt` → `ui/library/HomeRules.kt` (cambiar el paquete), y su prueba a `app/src/test/.../ui/library/HomeRulesTest.kt`
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/library/LibraryRulesTest.kt`; modify `ui/nav/RoutesTest.kt` y `StartRulesTest.kt`

**Interfaces:**
- Consumes: `BookRepository`, `BookFiles`, `BookImporter`, `MetadataRead` (Tarea 6); `ReadiumBooks` (Tarea 5); `LectorDatabase` (Tarea 4); `HomeRules`, `HomeState`, `PairStatus` (existentes).
- Produces:
  - `LectorApp.books: BookRepository`, `LectorApp.readium: ReadiumBooks`, `LectorApp.openBooks: OpenBooks`
  - `class OpenBooks { fun put(id: String, pub: Publication, initial: Locator?); fun get(id: String): Publication?; fun initialLocator(id: String): Locator?; fun close(id: String) }`
  - `Route.Library` (encode `"library"`); `decodeRoute("home") == Route.Library`
  - `AppNav(settings, hub, onClose = {}, library: @Composable (onLanguages: () -> Unit, onDeveloper: (() -> Unit)?) -> Unit = { _, _ -> })`: la Biblioteca llega como "ranura" (*slot*: un hueco que llena quien llama), así las pruebas de la 2d que llaman `AppNav(settings, hub)` siguen compilando.
  - ```kotlin
    sealed interface LanguageNotice {
        data class Downloading(val pair: String, val percent: Int?) : LanguageNotice
        data class Failed(val pair: String) : LanguageNotice
        data class Missing(val pair: String, val kind: EngineKind) : LanguageNotice
        data object NoLanguages : LanguageNotice
    }
    enum class LibraryMessage { NOT_EPUB, TOO_BIG, DRM, DAMAGED, NO_SPACE }
    object LibraryRules {
        fun notice(pairs: List<PairStatus>, home: HomeState): LanguageNotice?
        fun message(reason: ImportError): LibraryMessage
        fun percent(progress: Float): Int
        fun initial(title: String): String
    }
    ```

- [ ] **Step 1: Pruebas que fallan** (`LibraryRulesTest.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.library

import io.github.diegobr4nd.lectorbilingue.books.ImportError
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.RowStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryRulesTest {
    private fun row(installed: Boolean, dl: DownloadState? = null, engine: EngineId = EngineId.OPUS) =
        RowStatus("m-${engine.name}", engine, 100, installed, dl)
    private fun home(vararg cards: PairCard) = HomeState(cards.toList(), showNoLanguages = cards.isEmpty())

    @Test fun `todo listo no muestra aviso`() {
        val pairs = listOf(PairStatus("en-es", listOf(row(true))))
        assertNull(LibraryRules.notice(pairs, home(PairCard("en-es", EngineKind.QUALITY))))
    }

    @Test fun `descarga en curso con porcentaje`() {
        val dl = DownloadState(DownloadState.Status.RUNNING, 45, 100, null)
        val pairs = listOf(PairStatus("en-es", listOf(row(false, dl))))
        val card = PairCard("en-es", null, listOf(PairDownload("m-OPUS", EngineKind.QUALITY, dl)))
        assertEquals(LanguageNotice.Downloading("en-es", 45), LibraryRules.notice(pairs, home(card)))
    }

    @Test fun `descarga sin total no inventa porcentaje`() {
        val dl = DownloadState(DownloadState.Status.QUEUED, 0, 0, null)
        val card = PairCard("en-es", null, listOf(PairDownload("m-OPUS", EngineKind.QUALITY, dl)))
        assertEquals(LanguageNotice.Downloading("en-es", null), LibraryRules.notice(emptyList(), home(card)))
    }

    @Test fun `ultima descarga fallida sin modelo instalado`() {
        val dl = DownloadState(DownloadState.Status.FAILED, 10, 100, "red")
        val pairs = listOf(PairStatus("en-es", listOf(row(false, dl))))
        assertEquals(LanguageNotice.Failed("en-es"), LibraryRules.notice(pairs, home()))
    }

    @Test fun `motor elegido ausente`() {
        val pairs = listOf(PairStatus("en-es", listOf(row(true))))
        assertEquals(
            LanguageNotice.Missing("en-es", EngineKind.FAST),
            LibraryRules.notice(pairs, home(PairCard("en-es", null, missing = EngineKind.FAST))),
        )
    }

    @Test fun `sin idiomas`() = assertEquals(LanguageNotice.NoLanguages, LibraryRules.notice(emptyList(), home()))

    @Test fun `descarga gana a fallo`() {
        val running = DownloadState(DownloadState.Status.RUNNING, 1, 2, null)
        val failed = DownloadState(DownloadState.Status.FAILED, 0, 2, "x")
        val pairs = listOf(PairStatus("es-en", listOf(row(false, failed))), PairStatus("en-es", listOf(row(false, running))))
        val card = PairCard("en-es", null, listOf(PairDownload("m-OPUS", EngineKind.QUALITY, running)))
        assertEquals(LanguageNotice.Downloading("en-es", 50), LibraryRules.notice(pairs, home(card)))
    }

    @Test fun `mensajes por motivo y UNSAFE se disfraza de NOT_EPUB`() {
        assertEquals(LibraryMessage.NOT_EPUB, LibraryRules.message(ImportError.UNSAFE_ARCHIVE))
        for (r in ImportError.entries.filter { it != ImportError.UNSAFE_ARCHIVE }) assertEquals(r.name, LibraryRules.message(r).name)
    }

    @Test fun `porcentaje redondea hacia abajo y se recorta`() {
        assertEquals(0, LibraryRules.percent(0f)); assertEquals(42, LibraryRules.percent(0.429f))
        assertEquals(100, LibraryRules.percent(1f)); assertEquals(100, LibraryRules.percent(3f)); assertEquals(0, LibraryRules.percent(-1f))
        assertEquals(0, LibraryRules.percent(Float.NaN))
    }

    @Test fun `inicial para libros sin portada`() {
        assertEquals("E", LibraryRules.initial("el principito")); assertEquals("Á", LibraryRules.initial("  ábaco"))
        assertEquals("?", LibraryRules.initial("")); assertEquals("1", LibraryRules.initial("1984"))
    }
}
```
Modificar `RoutesTest`: en la lista `routes`, `Route.Home` → `Route.Library`, y añadir:
```kotlin
@Test fun `el Inicio guardado de la version anterior abre la Biblioteca`() = assertEquals(Route.Library, decodeRoute("home"))
```
Modificar `StartRulesTest`: `Route.Home` → `Route.Library` en las aserciones.

Run: `./gradlew :app:testFdroidDebugUnitTest`. Expected: FAIL (`Unresolved reference: Library`, `LibraryRules`).

- [ ] **Step 2: Implementación**

Mover `HomeRules.kt` con `git mv app/src/main/kotlin/.../ui/home/HomeRules.kt app/src/main/kotlin/.../ui/library/HomeRules.kt` y su prueba igual. Cambiar `package … .ui.home` por `… .ui.library` y actualizar los `import` en `HomeScreen.kt`, `HomeViewModel.kt` y `HomePreviews.kt`. Esos tres archivos se borran en la Tarea 8.

`Routes.kt`: `data object Home` → `data object Library`; `encode`: `Route.Library -> "library"`; `decode`: `text == "library" || text == "home" -> Route.Library`, con el comentario `// "home" = pila guardada por la versión 2d`. `StartRules.start`: `Route.Library`. `AppNav.kt`: reemplazar `Route.Home` por `Route.Library` (en `onFinish` y en `entryProvider`) y añadir el parámetro `library` de arriba. En esta tarea `Route.Library` sigue mostrando `HomeScreen`; la Tarea 8 la cambia por `library(onLanguages, onDeveloper)`.

`LibraryRules.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.library

import io.github.diegobr4nd.lectorbilingue.books.ImportError
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.models.DownloadState

/** Aviso de idiomas arriba de la Biblioteca: solo cuando hay algo que hacer o que esperar. */
sealed interface LanguageNotice {
    data class Downloading(val pair: String, val percent: Int?) : LanguageNotice
    data class Failed(val pair: String) : LanguageNotice
    data class Missing(val pair: String, val kind: EngineKind) : LanguageNotice
    data object NoLanguages : LanguageNotice
}

/** Mensaje de error de importación. UNSAFE_ARCHIVE no tiene propio: no se dan pistas del ataque. */
enum class LibraryMessage { NOT_EPUB, TOO_BIG, DRM, DAMAGED, NO_SPACE }

/** Reglas puras (sin Android) de la Biblioteca: se prueban en la JVM. */
object LibraryRules {
    /** Prioridad: descargando > última descarga fallida > motor elegido ausente > sin idiomas. */
    fun notice(pairs: List<PairStatus>, home: HomeState): LanguageNotice? {
        home.pairCards.firstNotNullOfOrNull { card -> card.downloads.firstOrNull()?.let { card.pair to it.state } }?.let { (pair, s) ->
            return LanguageNotice.Downloading(pair, if (s.total > 0) ((s.bytes * 100) / s.total).toInt().coerceIn(0, 100) else null)
        }
        pairs.firstOrNull { p -> p.rows.none { it.installed } && p.rows.any { it.download?.status == DownloadState.Status.FAILED } }
            ?.let { return LanguageNotice.Failed(it.pair) }
        home.pairCards.firstOrNull { it.missing != null }?.let { return LanguageNotice.Missing(it.pair, it.missing!!) }
        return if (home.showNoLanguages) LanguageNotice.NoLanguages else null
    }

    fun message(reason: ImportError): LibraryMessage = when (reason) {
        ImportError.NOT_EPUB, ImportError.UNSAFE_ARCHIVE -> LibraryMessage.NOT_EPUB
        ImportError.TOO_BIG -> LibraryMessage.TOO_BIG
        ImportError.DRM -> LibraryMessage.DRM
        ImportError.DAMAGED -> LibraryMessage.DAMAGED
        ImportError.NO_SPACE -> LibraryMessage.NO_SPACE
    }

    fun percent(progress: Float): Int = if (progress.isNaN()) 0 else (progress.coerceIn(0f, 1f) * 100).toInt()

    fun initial(title: String): String = title.trim().firstOrNull()?.uppercase() ?: "?"
}
```
En `descarga gana a fallo`, el `Downloading` sale de `home.pairCards` (en-es), aunque el fallo de es-en esté primero en `pairs`. En la prueba `descarga sin total` se pasan `pairs` vacíos: el aviso sale solo de `home`.

`OpenBooks.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.data

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Libros abiertos que la Biblioteca le pasa al Lector (ReaderActivity necesita el libro ya abierto antes de crearse),
 * con la posición guardada. Vive en memoria: si Android cierra la app, se vacía y el Lector vuelve a la Biblioteca.
 */
class OpenBooks {
    private class Entry(val pub: Publication, val initial: Locator?)
    private val open = mutableMapOf<String, Entry>()

    @Synchronized fun put(id: String, pub: Publication, initial: Locator?) {
        open.remove(id)?.takeIf { it.pub !== pub }?.pub?.close()
        open[id] = Entry(pub, initial)
    }

    @Synchronized fun get(id: String): Publication? = open[id]?.pub

    @Synchronized fun initialLocator(id: String): Locator? = open[id]?.initial

    @Synchronized fun close(id: String) { open.remove(id)?.pub?.close() }
}
```
`LectorApp.kt`:
```kotlin
class LectorApp : Application() {
    val hub: ModelHub by lazy { ModelHub(this) }
    val settings: AppSettings by lazy { AppSettings.of(this) }
    val readium: ReadiumBooks by lazy { ReadiumBooks(this) }
    val openBooks = OpenBooks()
    private val bookFiles by lazy { BookFiles(filesDir) }
    val books: BookRepository by lazy {
        val dao = LectorDatabase.open(this).books()
        BookRepository(
            dao, bookFiles,
            BookImporter(
                bookFiles, dao,
                readMetadata = { file ->
                    readium.metadata(file).fold({ MetadataRead.Ok(it) }, { MetadataRead.Failed(it) })
                },
                saveCover = ::saveCover,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Restos de una importación cortada: fuera del hilo principal.
        Thread { bookFiles.cleanTmp() }.start()
    }

    /** Portada reducida a 480 px de alto como máximo: suficiente para la lista y liviana. */
    private fun saveCover(bitmap: Bitmap, target: File) {
        val scale = minOf(1f, 480f / bitmap.height.coerceAtLeast(1))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), 480, true) else bitmap
        target.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
```
(Imports: `android.graphics.Bitmap`, `java.io.File`, `…books.*`, `…books.db.LectorDatabase`, `…books.readium.ReadiumBooks`, `…data.OpenBooks`.)

- [ ] **Step 3: Pruebas pasan**

Run: `./gradlew :app:testFdroidDebugUnitTest assembleFdroidDebug`. Expected: PASS y BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
```bash
git add app
git commit -m "feat(app): ruta Biblioteca, reglas del aviso de idiomas y conexión con :books"
```

---

### Task 8: Pantalla Biblioteca (agente `diseno`, con `estructura` para el ViewModel)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/library/LibraryViewModel.kt`, `LibraryScreen.kt`, `LibraryPreviews.kt`
- Delete: `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`, `ui/home/HomePreviews.kt` y los textos `home_library_*` y `home_title` que ya no se usan
- Modify: `AppNav.kt` (`Route.Library` → `LibraryScreen`), `app/src/main/res/values/strings.xml`
- Create: `core/ui/src/main/res/drawable/ic_add.xml`, `ic_toc.xml`, `ic_error.xml` (Material Symbols Rounded, 24 dp, mismo formato que `ic_download.xml`) y en `LectorIcons`: `Add = R.drawable.ic_add`, `Toc = R.drawable.ic_toc`, `Error = R.drawable.ic_error`
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/library/LibraryViewModelTest.kt`; `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/LibraryOnDeviceTest.kt`

**Interfaces:**
- Consumes: `BookRepository`, `Book`, `ImportResult` (Tarea 6); `ReadiumBooks.open` (Tarea 5); `OpenBooks`, `LibraryRules`, `LanguageNotice`, `LibraryMessage` (Tarea 7); `HomeRules.cards`, `ModelHubApi`, `AppSettings` (existentes).
- Produces:
  ```kotlin
  data class LibraryUiState(val books: List<Book> = emptyList(), val loaded: Boolean = false, val importing: Boolean = false, val openingId: String? = null, val notice: LanguageNotice? = null)
  sealed interface LibraryEvent { data class Message(val message: LibraryMessage) : LibraryEvent; data class Open(val bookId: String) : LibraryEvent; data class OpenFailed(val bookId: String) : LibraryEvent }
  class LibraryViewModel(repo: BookRepository, opener: suspend (String) -> Boolean, notice: Flow<LanguageNotice?>) : ViewModel() {
      val state: StateFlow<LibraryUiState>; val events: Flow<LibraryEvent>
      fun import(source: (() -> InputStream?)?, name: String?)   // source == null: se cerró el selector sin elegir
      fun open(id: String); fun delete(id: String)
  }
  fun languageNotices(hub: ModelHubApi, settings: AppSettings): Flow<LanguageNotice?>
  ```
  - `opener(id)` abre el EPUB con `ReadiumBooks.open`, lee la posición guardada y deja ambos en `OpenBooks`. Devuelve `false` si falló.
  - El aviso llega como `Flow` para poder probar el ViewModel en la JVM sin `AppSettings`, que necesita `SharedPreferences`.

- [ ] **Step 1: Pruebas del ViewModel que fallan**

Copiar `FakeBookDao.kt` (Tarea 4) a `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/library/FakeBookDao.kt`, cambiando solo el paquete. Añadir `testImplementation(libs.kotlinx.coroutines.test)` a `:app` si falta (ya está).

`LibraryViewModelTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.library

import io.github.diegobr4nd.lectorbilingue.books.BookFiles
import io.github.diegobr4nd.lectorbilingue.books.BookImporter
import io.github.diegobr4nd.lectorbilingue.books.BookMetadata
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.books.MetadataRead
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private val dao = FakeBookDao()
    private val events = mutableListOf<LibraryEvent>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.viewModel(opener: suspend (String) -> Boolean = { true }): LibraryViewModel {
        val files = BookFiles(tmp.root)
        val importer = BookImporter(files, dao, readMetadata = { MetadataRead.Ok(BookMetadata("T", null, null)) }, saveCover = { _, _ -> })
        dao.insert(BookEntity(id, "T", null, null, 1, null, 0f, null))
        return LibraryViewModel(BookRepository(dao, files, importer), opener, flowOf(null)).also { vm ->
            backgroundScope.launch(dispatcher) { vm.events.toList(events) }
        }
    }

    @Test fun `cancelar el selector no hace nada`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.import(source = null, name = null)
        advanceUntilIdle()
        assertFalse(vm.state.value.importing)
        assertTrue(events.isEmpty())
    }

    @Test fun `error de importacion emite el mensaje`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.import(source = { "no zip".byteInputStream() }, name = "x.epub")
        advanceUntilIdle()
        assertEquals(listOf<LibraryEvent>(LibraryEvent.Message(LibraryMessage.NOT_EPUB)), events)
        assertFalse(vm.state.value.importing)
    }

    @Test fun `abrir con exito emite Open y marca abierto`() = runTest(dispatcher) {
        val vm = viewModel(opener = { true })
        vm.open(id)
        advanceUntilIdle()
        assertEquals(listOf<LibraryEvent>(LibraryEvent.Open(id)), events)
        assertNotNull(dao.get(id)!!.lastOpenedAt)
        assertNull(vm.state.value.openingId)
    }

    @Test fun `abrir fallido emite OpenFailed`() = runTest(dispatcher) {
        val vm = viewModel(opener = { false })
        vm.open(id)
        advanceUntilIdle()
        assertEquals(listOf<LibraryEvent>(LibraryEvent.OpenFailed(id)), events)
    }

    @Test fun `un segundo toque mientras abre se ignora`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        val vm = viewModel(opener = { gate.await() })
        vm.open(id); advanceUntilIdle(); vm.open(id)
        gate.complete(true); advanceUntilIdle()
        assertEquals(1, events.size)
    }
}
```
Run: `./gradlew :app:testFdroidDebugUnitTest --tests "*LibraryViewModelTest"`. Expected: FAIL (`Unresolved reference: LibraryViewModel`).

- [ ] **Step 2: `LibraryViewModel`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.books.Book
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.books.ImportResult
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream

data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val loaded: Boolean = false,
    val importing: Boolean = false,
    val openingId: String? = null,
    val notice: LanguageNotice? = null,
)

sealed interface LibraryEvent {
    data class Message(val message: LibraryMessage) : LibraryEvent
    data class Open(val bookId: String) : LibraryEvent
    data class OpenFailed(val bookId: String) : LibraryEvent
}

/** Estado de la Biblioteca. No guarda ni registra texto de libros: solo ids y motivos. */
class LibraryViewModel(
    private val repo: BookRepository,
    private val opener: suspend (String) -> Boolean,
    notice: Flow<LanguageNotice?>,
) : ViewModel() {
    private val importing = MutableStateFlow(false)
    private val opening = MutableStateFlow<String?>(null)
    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<LibraryUiState> =
        combine(repo.books, importing, opening, notice) { books, imp, op, n -> LibraryUiState(books, true, imp, op, n) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** [source] es null si la persona cerró el selector sin elegir: no pasa nada. */
    fun import(source: (() -> InputStream?)?, name: String?) {
        if (source == null || importing.value) return
        importing.value = true
        viewModelScope.launch {
            try {
                val r = repo.import(source, name)
                if (r is ImportResult.Error) eventChannel.send(LibraryEvent.Message(LibraryRules.message(r.reason)))
            } finally { importing.value = false }
        }
    }

    fun open(id: String) {
        if (opening.value != null) return
        opening.value = id
        viewModelScope.launch {
            try {
                if (opener(id)) {
                    repo.markOpened(id)
                    eventChannel.send(LibraryEvent.Open(id))
                } else {
                    eventChannel.send(LibraryEvent.OpenFailed(id))
                }
            } finally { opening.value = null }
        }
    }

    fun delete(id: String) { viewModelScope.launch { repo.delete(id) } }
}

/** Aviso de idiomas a partir del gestor de modelos y del motor elegido. Nada hasta tener los dos cargados. */
fun languageNotices(hub: ModelHubApi, settings: AppSettings): Flow<LanguageNotice?> =
    combine(hub.pairs, settings.enginePreferenceFlow, hub.loaded, settings.enginePreferenceLoaded) { pairs, pref, a, b ->
        if (a && b) LibraryRules.notice(pairs, HomeRules.cards(pairs, hub.totalRamBytes(), pref)) else null
    }
```
Run: `./gradlew :app:testFdroidDebugUnitTest --tests "*LibraryViewModelTest"`. Expected: PASS (5 pruebas).

- [ ] **Step 3: Textos** (`app/src/main/res/values/strings.xml`; borrar `home_title`, `home_library_title` y `home_library_body`)

```xml
<string name="library_title">Biblioteca</string>
<string name="library_add">Añadir libro</string>
<string name="library_empty_title">Añade tu primer libro EPUB</string>
<string name="library_empty_body">Se guarda una copia privada en el teléfono. Nada sale de aquí.</string>
<string name="library_importing">Añadiendo…</string>
<string name="library_opening">Abriendo…</string>
<string name="library_untitled">Libro sin título</string>
<string name="library_cover">Portada de %1$s</string>
<string name="library_progress">%1$d %% leído</string>
<string name="library_delete">Borrar libro</string>
<string name="library_delete_title">¿Borrar «%1$s»?</string>
<string name="library_delete_body">Se borra la copia de la app y la posición de lectura. El archivo original de tu teléfono no se toca.</string>
<string name="library_delete_confirm">Borrar</string>
<string name="library_cancel">Cancelar</string>
<string name="library_open_failed_title">No se pudo abrir este libro</string>
<string name="library_open_failed_body">El archivo de la app parece dañado.</string>
<string name="library_open_failed_remove">Quitar de la biblioteca</string>
<string name="library_close">Cerrar</string>
<string name="library_error_not_epub">Este archivo no es un EPUB válido</string>
<string name="library_error_too_big">El libro es demasiado grande (máx. 100 MB)</string>
<string name="library_error_drm">Este libro tiene protección DRM y no se puede abrir</string>
<string name="library_error_damaged">El archivo parece dañado</string>
<string name="library_error_no_space">No hay espacio suficiente en el teléfono</string>
<string name="library_notice_downloading">Descargando %1$s… %2$d %%</string>
<string name="library_notice_downloading_nopct">Descargando %1$s…</string>
<string name="library_notice_failed">Falló la descarga de %1$s · Reintentar en Idiomas</string>
<string name="library_notice_missing">Falta %1$s para %2$s · Descargar</string>
<string name="library_notice_none">Aún no tienes idiomas · Descargar</string>
```
`%1$s` del par usa `pairName(pair)` (ya existe). `%1$s` de `missing` usa `engineLabel(kind)` y `%2$s` usa `pairName`.

- [ ] **Step 4: `LibraryScreen`** (boceto aprobado, spec §5.1)

Estructura (Compose + Material 3, componentes de `:core:ui`):
- `Scaffold`:
  - `topBar` = `TopAppBar(title = library_title, actions = menú ⋮ con Idiomas y, si existe, Desarrollador)`. Copiar `HomeHeader` de `HomeScreen.kt` antes de borrarlo.
  - `floatingActionButton` = `ExtendedFloatingActionButton(icon = LectorIcons.Add, text = library_add)`, oculto si la lista está vacía (ahí está el botón grande).
  - `snackbarHost` para los `LibraryEvent.Message`.
- Selector: `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.import(uri?.let { u -> { context.contentResolver.openInputStream(u) } }, uri?.let { displayName(context, it) }) }` con `launch(arrayOf("application/epub+zip"))`. `displayName` lee `OpenableColumns.DISPLAY_NAME` con `contentResolver.query`, devuelve `null` si falla y nunca registra el nombre.
- Contenido:
  - si `!loaded`, `LoadingLine`;
  - si `books.isEmpty() && !importing`, estado vacío centrado: ícono `LibraryBooks` de 64 dp, `library_empty_title`, `library_empty_body` y `Button(library_add)` de ancho completo, con alto ≥ 48 dp;
  - si no, `LazyColumn` con:
    - el aviso (si hay): `Card` clicable con ícono (`Download`, `Error` o `Translate`) y texto, `onClick = onLanguages`;
    - una fila provisional "Añadiendo…" con `LinearProgressIndicator` indeterminado si `importing`;
    - un `BookRow` por libro, con `key = book.id`.
- `BookRow(book, opening, onOpen, onDelete)`:
  - `Row` de alto mínimo 88 dp;
  - portada `52×76 dp` con esquinas de 4 dp. `Image` de `BitmapFactory.decodeFile` cargado con `produceState` en `Dispatchers.IO`. Si no hay portada o falla, una `Surface` `secondaryContainer` con `LibraryRules.initial(title)` en `titleLarge`. `contentDescription = library_cover(title)`;
  - columna: título (`titleMedium`, 2 líneas, `Ellipsis`), autor (`bodyMedium`, 1 línea), `LinearProgressIndicator(progress = { book.progress })` y el texto `library_progress(percent)`;
  - si `opening == book.id`: `CircularProgressIndicator` de 24 dp a la derecha y el texto `library_opening` como `stateDescription`;
  - `Modifier.combinedClickable(onClick = onOpen, onLongClick = { showDelete = true }, onLongClickLabel = library_delete)`;
  - `semantics { customActions = listOf(CustomAccessibilityAction(library_delete) { showDelete = true; true }) }`.
- Título mostrado: si `book.title == BookImporter.UNTITLED`, usar `library_untitled`.
- Borrar: `ConfirmDialog` de `:core:ui` con `library_delete_title(título)`, `library_delete_body`, confirmar `library_delete_confirm` y cancelar `library_cancel`.
- Eventos (`LaunchedEffect(Unit) { vm.events.collect { … } }`):
  - `Message` → snackbar con el texto según `LibraryMessage`;
  - `Open(id)` → `onOpenBook(id)`;
  - `OpenFailed(id)` → diálogo `library_open_failed_*` con "Cerrar" y "Quitar de la biblioteca" (`vm.delete(id)`).
- Firma pública:
  ```kotlin
  @Composable fun LibraryScreen(app: LectorApp, onLanguages: () -> Unit, onDeveloper: (() -> Unit)?, onOpenBook: (String) -> Unit, modifier: Modifier = Modifier)
  @Composable fun LibraryContent(state: LibraryUiState, onAdd: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit, onLanguages: () -> Unit, onDeveloper: (() -> Unit)?, snackbar: SnackbarHostState, modifier: Modifier = Modifier)
  ```
  `LibraryScreen` arma el ViewModel así:
  ```kotlin
  LaunchedEffect(Unit) { app.settings.loadEnginePreference() }   // primera lectura fuera del hilo principal
  val vm: LibraryViewModel = viewModel(factory = viewModelFactory {
      initializer { LibraryViewModel(app.books, opener = { id -> openBook(app, id) }, languageNotices(app.hub, app.settings)) }
  })

  private suspend fun openBook(app: LectorApp, id: String): Boolean {
      if (app.openBooks.get(id) != null) return true
      val pub = app.readium.open(app.books.epubFile(id)).getOrNull() ?: return false
      // Posición guardada; un JSON dañado empieza desde el principio en vez de fallar.
      val initial = app.books.get(id)?.locator?.let { json -> runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull() }
      app.openBooks.put(id, pub, initial)
      return true
  }
  ```
- `MainActivity`:
  ```kotlin
  AppNav(
      settings = app.settings, hub = app.hub, onClose = { finish() },
      library = { onLanguages, onDeveloper ->
          LibraryScreen(app, onLanguages, onDeveloper, onOpenBook = { id -> /* Tarea 9: abrir ReaderActivity */ })
      },
  )
  ```
  En `AppNav`, `Route.Library -> NavEntry(key) { library({ backStack.add(Route.Languages) }, if (developer.available) ({ backStack.add(Route.Developer) }) else null) }`.
- Borrar `HomeScreen.kt`, `HomeViewModel.kt` y `HomePreviews.kt`. Sus pruebas instrumentadas, si las hay (`grep -rl HomeScreen app/src/androidTest`), se adaptan a `LibraryContent`.

- [ ] **Step 5: Previews** (`LibraryPreviews.kt`)

`LibraryContent` con datos de muestra (3 libros inventados, uno sin portada y con título de 120 caracteres, uno al 100 %) en estos estados:
- vacía;
- importando;
- con aviso de descarga;
- abriendo un libro;
- en temas claro y oscuro;
- `fontScale = 2f`;
- ancho de tableta (`widthDp = 840`).

Seguir el patrón de `HomePreviews.kt`. Copiarlo antes de borrarlo.

- [ ] **Step 6: Prueba en el Pixel** (`LibraryOnDeviceTest.kt`)

Prueba Compose (`createComposeRule`) sobre `LibraryContent`, con TalkBack simulado por semántica:
```kotlin
@Test fun borrarEstaEnLasAccionesDeAccesibilidad() {
    var deleted: String? = null
    rule.setContent { LectorTheme { LibraryContent(state = sample, onAdd = {}, onOpen = {}, onDelete = { deleted = it }, onLanguages = {}, onDeveloper = null, snackbar = SnackbarHostState()) } }
    rule.onNodeWithText("Libro de muestra").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
}

@Test fun vaciaMuestraElBotonGrande() {
    rule.setContent { LectorTheme { LibraryContent(state = LibraryUiState(loaded = true), onAdd = {}, onOpen = {}, onDelete = {}, onLanguages = {}, onDeveloper = null, snackbar = SnackbarHostState()) } }
    rule.onNodeWithText("Añadir libro").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
}
```
Además, una prueba de punta a punta con el repositorio real en una carpeta temporal:
```kotlin
@Test fun importarEpubRealConPortadaGuardaPng() = runBlocking {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LectorApp
    // EPUB con portada: TestEpub + entry("OEBPS/cover.png", png 10x10 generado con Bitmap) + item properties="cover-image" en el OPF (usar replace).
    val r = app.books.import({ epub.inputStream() }, "con-portada.epub")
    val id = (r as ImportResult.Ok).bookId
    try { assertTrue(app.books.get(id)!!.coverFile!!.exists()) } finally { app.books.delete(id) }
}
```
Esta prueba usa la base real de la app en el Pixel, pero borra lo que crea en el `finally`.

Run:
```bash
./gradlew :app:testFdroidDebugUnitTest assembleFdroidDebug installFdroidDebug installFdroidDebugAndroidTest
adb shell am force-stop io.github.diegobr4nd.lectorbilingue
adb shell am instrument -w -e class io.github.diegobr4nd.lectorbilingue.LibraryOnDeviceTest io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: PASS y `OK (3 tests)`.

- [ ] **Step 7: Commit**
```bash
git add app core/ui
git commit -m "feat(app): Biblioteca con añadir, abrir, borrar y aviso de idiomas (reemplaza al Inicio)"
```

---

### Task 9: Lector · `ReaderActivity` (agente `diseno`, con `estructura` para el ViewModel y la actividad)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReaderActivity.kt`, `ReaderViewModel.kt`, `ReaderRules.kt`, `ReaderScreen.kt`, `TocSheet.kt`, `ReaderPreviews.kt`
- Modify: `app/src/main/AndroidManifest.xml` (actividad no exportada), `MainActivity.kt` (`onOpenBook`), `strings.xml`
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReaderRulesTest.kt`; `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/ReaderOnDeviceTest.kt`

**Interfaces:**
- Consumes: `OpenBooks`, `LectorApp.books` (Tareas 6 y 7); `BookFiles.isValidId` (Tarea 3); Readium `EpubNavigatorFactory`, `EpubNavigatorFragment`, `EpubPreferences`, `Locator`, `Link`.
- Produces:
  ```kotlin
  object ReaderRules {
      fun barsVisible(previous: Double?, current: Double?, wasVisible: Boolean, touchExploration: Boolean): Boolean
      fun label(chapterTitle: String?, totalProgression: Double?): PositionLabel   // PositionLabel(chapter, percent) de la Tarea 7
      fun flattenToc(links: List<TocEntrySource>): List<TocEntry>
  }
  object ReaderStart { sealed interface Decision { data class Show(val id: String) : Decision; data object Finish : Decision }
      fun decide(extraId: String?, isOpen: (String) -> Boolean): Decision }
  data class TocEntry(val title: String, val depth: Int, val href: String)
  data class TocEntrySource(val title: String?, val href: String, val children: List<TocEntrySource>)
  class ReaderActivity : FragmentActivity() { companion object { const val EXTRA_BOOK_ID = "book_id"; fun intent(context: Context, id: String): Intent } }
  ```
  `PositionLabel` se define en `ui/reader/ReaderRules.kt` (no en la Tarea 7): `data class PositionLabel(val chapter: String?, val percent: Int?)`. `percent` vale `null` si Readium aún no da progreso.

- [ ] **Step 1: Pruebas de reglas que fallan** (`ReaderRulesTest.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderRulesTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun `al abrir las barras se ven`() = assertTrue(ReaderRules.barsVisible(null, 0.3, wasVisible = false, touchExploration = false))
    @Test fun `bajar oculta`() = assertFalse(ReaderRules.barsVisible(0.30, 0.31, wasVisible = true, touchExploration = false))
    @Test fun `subir muestra`() = assertTrue(ReaderRules.barsVisible(0.31, 0.30, wasVisible = false, touchExploration = false))
    @Test fun `sin cambio conserva`() {
        assertFalse(ReaderRules.barsVisible(0.3, 0.3, wasVisible = false, touchExploration = false))
        assertTrue(ReaderRules.barsVisible(0.3, 0.3, wasVisible = true, touchExploration = false))
    }
    @Test fun `al final del libro se ven`() = assertTrue(ReaderRules.barsVisible(0.99, 1.0, wasVisible = false, touchExploration = false))
    @Test fun `con TalkBack siempre se ven`() = assertTrue(ReaderRules.barsVisible(0.30, 0.31, wasVisible = true, touchExploration = true))
    @Test fun `progreso nulo conserva`() = assertTrue(ReaderRules.barsVisible(0.3, null, wasVisible = true, touchExploration = false))

    @Test fun `etiqueta con y sin capitulo`() {
        assertEquals(PositionLabel("La tormenta", 42), ReaderRules.label("La tormenta", 0.429))
        assertEquals(PositionLabel(null, 42), ReaderRules.label("   ", 0.429))
        assertEquals(PositionLabel(null, null), ReaderRules.label(null, null))
        assertEquals(PositionLabel(null, 100), ReaderRules.label(null, 1.4))
    }

    @Test fun `indice aplanado con profundidad`() {
        val toc = listOf(
            TocEntrySource("Parte 1", "p1.xhtml", listOf(TocEntrySource("Cap 1", "c1.xhtml", emptyList()), TocEntrySource(null, "c2.xhtml", emptyList()))),
            TocEntrySource("  ", "p2.xhtml", emptyList()),
        )
        assertEquals(
            listOf(TocEntry("Parte 1", 0, "p1.xhtml"), TocEntry("Cap 1", 1, "c1.xhtml"), TocEntry("c2.xhtml", 1, "c2.xhtml"), TocEntry("p2.xhtml", 0, "p2.xhtml")),
            ReaderRules.flattenToc(toc),
        )
    }

    @Test fun `arranque con libro abierto`() = assertEquals(ReaderStart.Decision.Show(id), ReaderStart.decide(id) { true })
    @Test fun `arranque tras cierre de Android se cierra`() = assertEquals(ReaderStart.Decision.Finish, ReaderStart.decide(id) { false })
    @Test fun `id ausente o raro se cierra`() {
        assertEquals(ReaderStart.Decision.Finish, ReaderStart.decide(null) { true })
        assertEquals(ReaderStart.Decision.Finish, ReaderStart.decide("../x") { true })
    }
}
```
Run: `./gradlew :app:testFdroidDebugUnitTest --tests "*ReaderRulesTest"`. Expected: FAIL.

- [ ] **Step 2: `ReaderRules.kt`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.books.BookFiles

/** Texto de la barra inferior: título del capítulo (si tiene) y % leído (si Readium ya lo sabe). */
data class PositionLabel(val chapter: String?, val percent: Int?)

data class TocEntry(val title: String, val depth: Int, val href: String)
data class TocEntrySource(val title: String?, val href: String, val children: List<TocEntrySource>)

/** Reglas puras del Lector (sin Android): se prueban en la JVM. */
object ReaderRules {
    /**
     * Barras: bajar (leer) las oculta, subir las muestra. Al abrir y al final del libro se ven.
     * Con TalkBack siempre se ven: quien no ve la pantalla no puede "deslizar para que aparezcan".
     */
    fun barsVisible(previous: Double?, current: Double?, wasVisible: Boolean, touchExploration: Boolean): Boolean = when {
        touchExploration -> true
        current == null -> wasVisible
        previous == null -> true
        current >= 1.0 -> true
        current > previous -> false
        current < previous -> true
        else -> wasVisible
    }

    fun label(chapterTitle: String?, totalProgression: Double?): PositionLabel = PositionLabel(
        chapterTitle?.trim()?.takeIf { it.isNotEmpty() },
        totalProgression?.takeIf { !it.isNaN() }?.let { (it.coerceIn(0.0, 1.0) * 100).toInt() },
    )

    /** Índice en una lista con sangría. Un capítulo sin título muestra su archivo para no quedar en blanco. */
    fun flattenToc(links: List<TocEntrySource>, depth: Int = 0): List<TocEntry> = links.flatMap { l ->
        listOf(TocEntry(l.title?.trim()?.takeIf { it.isNotEmpty() } ?: l.href, depth, l.href)) + flattenToc(l.children, depth + 1)
    }
}

object ReaderStart {
    sealed interface Decision {
        data class Show(val id: String) : Decision
        data object Finish : Decision
    }

    /** Sin id válido, o sin el libro abierto en memoria (Android cerró la app), el Lector se cierra y vuelve a la Biblioteca. */
    fun decide(extraId: String?, isOpen: (String) -> Boolean): Decision =
        if (extraId != null && BookFiles.isValidId(extraId) && isOpen(extraId)) Decision.Show(extraId) else Decision.Finish
}
```
Run: `./gradlew :app:testFdroidDebugUnitTest --tests "*ReaderRulesTest"`. Expected: PASS (12 pruebas).

- [ ] **Step 3: `ReaderViewModel`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/** Posición del Lector: la guarda con 1 s de retraso y al salir. No guarda ni registra texto del libro. */
@OptIn(FlowPreview::class)
class ReaderViewModel(private val bookId: String, private val repo: BookRepository) : ViewModel() {
    private val locator = MutableStateFlow<Locator?>(null)
    private val _bars = MutableStateFlow(true)
    val barsVisible: StateFlow<Boolean> = _bars.asStateFlow()
    private val _label = MutableStateFlow(PositionLabel(null, null))
    val label: StateFlow<PositionLabel> = _label.asStateFlow()

    init {
        viewModelScope.launch {
            locator.filterNotNull().debounce(1_000).collect { save(it) }
        }
    }

    fun onLocator(new: Locator, touchExploration: Boolean) {
        val prev = locator.value?.locations?.totalProgression
        val now = new.locations.totalProgression
        _bars.value = ReaderRules.barsVisible(prev, now, _bars.value, touchExploration)
        _label.value = ReaderRules.label(new.title, now)
        locator.value = new
    }

    /** Al salir: guardado inmediato (sin esperar el retraso). */
    fun flush() { locator.value?.let { l -> viewModelScope.launch { save(l) } } }

    private suspend fun save(l: Locator) =
        repo.savePosition(bookId, l.toJSON().toString(), (l.locations.totalProgression ?: 0.0).toFloat())
}
```
(`flush()` se llama en `onStop` de la actividad. Si el proceso muere, se pierde como mucho 1 s de posición.)

- [ ] **Step 4: `ReaderActivity`**

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.github.diegobr4nd.lectorbilingue.LectorApp
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences

/**
 * Lector en su propia actividad: Readium exige instalar su FragmentFactory ANTES de super.onCreate(), con el libro ya abierto.
 * No exportada: solo la abre la Biblioteca.
 */
class ReaderActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as LectorApp
        val decision = ReaderStart.decide(intent.getStringExtra(EXTRA_BOOK_ID)) { app.openBooks.get(it) != null }
        if (decision !is ReaderStart.Decision.Show) {
            super.onCreate(null)
            finish()
            return
        }
        val id = decision.id
        val publication = app.openBooks.get(id)!!
        // La posición guardada la leyó la Biblioteca (Room no se lee en el hilo principal).
        val initial = app.openBooks.initialLocator(id)
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = initial,
            initialPreferences = EpubPreferences(scroll = true),
            listener = linkListener,
        )
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { LectorTheme { ReaderScreen(app, id, publication, onBack = { finish() }) } }
    }

    override fun onDestroy() {
        if (isFinishing) intent.getStringExtra(EXTRA_BOOK_ID)?.let { (application as LectorApp).openBooks.close(it) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        fun intent(context: Context, id: String) = Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, id)
    }
}
```
`linkListener`: un `EpubNavigatorFragment.Listener` cuyo `onExternalLinkActivated(url: AbsoluteUrl)` guarda la URL en un `MutableStateFlow<String?>` de la actividad. `ReaderScreen` muestra un diálogo "¿Abrir en el navegador?" con la URL visible y, al confirmar, `startActivity(Intent(ACTION_VIEW, Uri.parse(url)))` dentro de `try/catch (ActivityNotFoundException)`. Solo se aceptan esquemas `http`, `https` y `mailto`; cualquier otro se ignora. Los demás métodos de la interfaz quedan con su implementación por defecto.

Manifest, dentro de `<application>`:
```xml
<!-- Lector: solo lo abre la Biblioteca (no exportada). -->
<activity
    android:name=".ui.reader.ReaderActivity"
    android:exported="false"
    android:theme="@style/Theme.LectorBilingue" />
```
`MainActivity`: en la ranura `library`, `onOpenBook = { id -> startActivity(ReaderActivity.intent(this, id)) }`.

- [ ] **Step 5: `ReaderScreen` y `TocSheet`** (boceto aprobado, spec §5.2)

- `Box(fillMaxSize)` con:
  - `AndroidFragment<EpubNavigatorFragment>(modifier = Modifier.fillMaxSize()) { nav -> navigator = nav }`, de `androidx.fragment.compose`;
  - encima, `AnimatedVisibility(visible = barsVisible)` con `TopAppBar` arriba (← volver con `contentDescription` "Volver", título del libro en 1 línea con `Ellipsis`, botón `IconButton(LectorIcons.Toc)` con descripción "Índice") y una barra inferior con `label` ("La tormenta · 42 %", "42 %" o nada).
- Con `rememberReduceMotion()`, las transiciones son `EnterTransition.None` y `ExitTransition.None`.
- `touchExploration`: `LocalContext.current.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled`, recordado con un `AccessibilityManager.TouchExplorationStateChangeListener` en `DisposableEffect`.
- `LaunchedEffect(navigator)`: `navigator.currentLocator.collect { vm.onLocator(it, touchExploration) }`.
- `LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }`.
- `TocSheet`: `ModalBottomSheet` con un `LazyColumn` de `ReaderRules.flattenToc(publication.tableOfContents.map { it.toSource() })`. `toSource()` es el `Link` de Readium → `TocEntrySource(title, href.toString(), children.map { it.toSource() })`. Sangría `16.dp * depth`, alto mínimo 48 dp. La entrada cuyo `href` (sin `#fragmento`) coincide con el del localizador actual lleva el ícono `CheckCircle`, `fontWeight = Bold` y `stateDescription = "Capítulo actual"`. Al tocar: `publication.linkWithHref(Url(href)!!)?.let { navigator.go(it) }` y se cierra la hoja. Índice vacío: texto "Este libro no trae índice".
- Textos nuevos: `reader_back` "Volver", `reader_toc` "Índice", `reader_toc_empty` "Este libro no trae índice", `reader_current_chapter` "Capítulo actual", `reader_position` "%1$s · %2$d %%", `reader_position_pct` "%1$d %%", `reader_external_title` "¿Abrir en el navegador?", `reader_external_body` "Este enlace sale del libro: %1$s", `reader_external_open` "Abrir", `library_cancel` (ya existe).
- Ícono `LectorIcons.Toc` (añadido en la Tarea 8).
- `ReaderPreviews.kt`: solo las barras (sin el fragmento: `ReaderBars(title, label, onBack, onToc)` separada para poder previsualizarla) y `TocSheet` con un índice de muestra, en claro, oscuro, `fontScale = 2f` y tableta.
- **Depuración del WebView:** Readium ya llama a `WebView.setWebContentsDebuggingEnabled` según su configuración. Comprobar con `grep -r setWebContentsDebuggingEnabled` en las fuentes de Readium 3.4.0. Si lo activa siempre, llamar `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` en `ReaderActivity.onCreate`, después de `super.onCreate`, y anotarlo para `seguridad`.

- [ ] **Step 6: Prueba en el Pixel** (`ReaderOnDeviceTest.kt`)

```kotlin
@RunWith(AndroidJUnit4::class)
class ReaderOnDeviceTest {
    @Test fun sinLibroAbiertoSeCierraSinFallar() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(ctx, "123e4567-e89b-12d3-a456-426614174000")).use { s ->
            assertEquals(Lifecycle.State.DESTROYED, s.state)
        }
    }

    @Test fun abreUnLibroYGuardaPosicion() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LectorApp
        val epub = TestEpub.build(app.cacheDir, "lector.epub")
        val id = (app.books.import({ epub.inputStream() }, "lector.epub") as ImportResult.Ok).bookId
        try {
            app.openBooks.put(id, app.readium.open(app.books.epubFile(id)).getOrNull()!!, null)
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
                // Esperar a que Readium emita el primer localizador y pase el retraso de 1 s.
                Thread.sleep(3_000)
                s.moveToState(Lifecycle.State.CREATED) // dispara onStop → flush()
                Thread.sleep(500)
            }
            assertNotNull(app.books.get(id)!!.locator)
        } finally { app.books.delete(id); app.openBooks.close(id) }
    }
}
```
Run:
```bash
./gradlew :app:testFdroidDebugUnitTest lint assembleFdroidDebug installFdroidDebug installFdroidDebugAndroidTest
adb shell am force-stop io.github.diegobr4nd.lectorbilingue
adb shell am instrument -w -e class io.github.diegobr4nd.lectorbilingue.ReaderOnDeviceTest io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: `OK (2 tests)`, lint sin errores.

- [ ] **Step 7: Prueba manual corta** (antes de la revisión)

Instalar (`installFdroidDebug`), añadir el `TestEpub` de 2 párrafos copiado a `Download/` con `adb push` y comprobar:
- se abre;
- el índice salta al capítulo;
- se vuelve a la misma posición.

Captura de cada paso en el scratchpad. Si el EPUB de prueba es demasiado corto para deslizar, generar uno de 200 párrafos con el script del spike.

- [ ] **Step 8: Commit**
```bash
git add app core/ui
git commit -m "feat(app): lector EPUB con desplazamiento continuo, Índice, barras que se ocultan y posición recordada"
```

---

### Task 10: Revisiones (agentes `seguridad` y `diseno`, solo lectura; después, correcciones)

- [ ] **Step 1: `seguridad`** (regla 8: red, archivos, WebView, dependencias)

Encargo: revisar el diff de `main...feat/lector-epub` con `docs/agentes/03-seguridad.md` §3 (A1, A3, A6, A8) y §5.1. Incluye:
1. Árbol de dependencias de la Tarea 2 (`deps-3a.txt`): nada con red, analítica ni Play Services. Licencias compatibles con GPL-3.0.
2. EPUBs maliciosos **nuevos** (no los de las pruebas), generados en el scratchpad e importados en el Pixel por la interfaz:
   - bomba ZIP de 1 GB;
   - zip slip;
   - XXE en `container.xml` (no solo en el OPF);
   - `<script>` + `onload` + `<svg><script>`;
   - `<img src="https://…">` con `adb reverse` y el servidor de la Tarea 1 (ninguna petición);
   - `<a href="javascript:…">`;
   - enlace externo (el diálogo aparece);
   - `<iframe src="file:///data/data/…">`;
   - libro con LCP falso.
3. `ReaderActivity` no exportada; `EXTRA_BOOK_ID` validado; nada de `FileProvider`.
4. Ningún `Log` con título, autor o texto: `grep -rn "Log\.\|println" books app/src/main`.
5. `setWebContentsDebuggingEnabled` solo en debug.

Salida: hallazgos con gravedad. Los **altos** se corrigen antes del Paso 3.

- [ ] **Step 2: `diseno`** (regla 9)

- Capturas de las Previews de `LibraryPreviews` y `ReaderPreviews` (claro, oscuro, `fontScale = 2f`, tableta) y capturas reales del Pixel (Biblioteca vacía, con libros, Lector con barras, Índice), en el scratchpad, para Juan.
- Auditoría con la skill `material-3`, modo auditoría, sobre `ui/library` y `ui/reader`: anotar la cifra (meta de publicación ≥ 80/100).
- TalkBack: añadir un libro y abrirlo sin mirar; el capítulo actual del Índice se anuncia.
- Contraste AA del aviso de idiomas y de la barra inferior en los 4 temas.

- [ ] **Step 3: Correcciones**

Cada hallazgo alto o medio aceptado se corrige con TDD (prueba que lo reproduce → corrección) y su propio commit `fix(3a): …`. Los bajos que no se corrigen se anotan en la descripción del PR.

- [ ] **Step 4: Revisión de código de toda la rama** (skill `superpowers:requesting-code-review`)

- [ ] **Step 5: Verificación completa**
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew lint test assembleFdroidDebug assembleFdroidRelease assemblePlayRelease
```
Expected: BUILD SUCCESSFUL. Más todas las instrumentadas nuevas (`BookDaoOnDeviceTest`, `ReadiumBooksOnDeviceTest`, `LibraryOnDeviceTest`, `ReaderOnDeviceTest`) y las existentes de la 2d (`WelcomeOnDeviceTest`, `LanguagesOnDeviceTest`, `ComponentsOnDeviceTest`), con `am instrument`. Pegar la salida en el resumen.

---

### Task 11: Puerta 3a con Juan (sesión principal)

- [ ] **Step 1:** Pedir permiso a Juan para hacer push de `feat/lector-epub`. Él abre el PR **en borrador** desde la web (no hay `gh`). Comprobar el CI con:
  ```bash
  curl -s "https://api.github.com/repos/DiegoBr4nd/lector-bilingue/actions/runs?branch=feat/lector-epub" | grep -m3 '"conclusion"'
  ```
- [ ] **Step 2:** `installFdroidDebug` en el Pixel. Juan hace la puerta de la spec §1 (puntos 1 a 8) con un EPUB real suyo. Anotar el resultado de cada punto. No se registra ni se copia nada del libro.
- [ ] **Step 3:** Juan aprueba las capturas (Tarea 10, Paso 2).
- [ ] **Step 4:** Actualizar la memoria `arranque-fase-3` (3a hecha y lo siguiente: 3b). Proponer a Juan cambiar la línea "Fase actual" de `CLAUDE.md` a "3a hecha".
- [ ] **Step 5:** Con el CI en verde y la puerta pasada, Juan marca el PR como listo y lo fusiona.
