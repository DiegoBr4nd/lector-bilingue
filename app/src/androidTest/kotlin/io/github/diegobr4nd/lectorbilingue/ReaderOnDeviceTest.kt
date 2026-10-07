package io.github.diegobr4nd.lectorbilingue

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.ImportResult
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El Lector de verdad (Readium + WebView) en el teléfono, con un libro inventado de 3 capítulos.
 * Con `-e shots true` guarda capturas en la caché de la app (para revisarlas a mano; se borran después).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val dir = File(app.cacheDir, "reader_test").apply { deleteRecursively(); mkdirs() }
    private val created = mutableListOf<String>()

    @After fun cleanUp() = runBlocking<Unit> {
        for (id in created) {
            app.openBooks.close(id)
            app.books.delete(id)
        }
        dir.deleteRecursively()
    }

    @Test fun sinLibroAbiertoSeCierraSinFallar() {
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, "123e4567-e89b-12d3-a456-426614174000")).use { s ->
            assertEquals(Lifecycle.State.DESTROYED, s.state)
        }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, "../x")).use { s ->
            assertEquals(Lifecycle.State.DESTROYED, s.state)
        }
    }

    @Test fun abreMuestraElTextoYGuardaLaPosicion() = runBlocking<Unit> {
        val id = importAndOpen()
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("posición guardada") { runBlocking { app.books.get(id)?.locator } != null }
            rule.onNodeWithText("Capítulo uno", substring = true).assertExists() // Barra inferior.
            rule.onNodeWithContentDescription("Índice").assertHeightIsAtLeast(48.dp)
            rule.onNodeWithContentDescription("Volver").assertHeightIsAtLeast(48.dp)
            // El capítulo saneado se dibuja de verdad en el WebView de Readium: hay "tinta" en el centro.
            val shot = screenshot("lector-abierto")
            val ink = inkRatio(shot)
            assertTrue(ink > 0.005, "sin texto en la página (tinta=$ink)")
            s.moveToState(Lifecycle.State.CREATED) // onStop → guardado inmediato.
        }
        assertNotNull(app.books.get(id)!!.locator)
    }

    @Test fun indiceSaltaAlCapituloYSeVuelveAlMismoPunto() = runBlocking<Unit> {
        val id = importAndOpen()
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use {
            waitForText("Capítulo uno")
            rule.onNodeWithContentDescription("Índice").performClick()
            // El capítulo actual se marca con texto para TalkBack (no solo color).
            rule.onNode(hasText("Capítulo uno") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Capítulo actual"))
                .assertExists()
            screenshot("indice")
            // TalkBack dice "doble toque para ir al capítulo", no solo "para activar".
            assertEquals("ir al capítulo", rule.onNodeWithText("Capítulo tres").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            rule.onNodeWithText("Capítulo tres").assertHeightIsAtLeast(48.dp).performClick()
            waitForText("Capítulo tres")
            waitFor("posición del capítulo 3 guardada") {
                runBlocking { app.books.get(id)?.locator }?.contains("c3.xhtml") == true
            }
            screenshot("capitulo-tres")
        } // Al cerrar, la actividad suelta el libro de la memoria.
        assertEquals(null, app.openBooks.get(id))

        // Volver a abrir como lo hace la Biblioteca: con la posición guardada.
        val saved = Locator.fromJSON(JSONObject(app.books.get(id)!!.locator!!))
        app.openBooks.put(id, app.readium.open(app.books.epubFile(id)).getOrNull()!!, saved)
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use {
            waitForText("Capítulo tres")
            screenshot("reabierto")
        }
    }

    @Test fun deslizarParaLeerOcultaLasBarrasYVolverLasMuestra() = runBlocking<Unit> {
        val id = importAndOpen()
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            val m = app.resources.displayMetrics
            val focused = { var f = false; s.onActivity { f = it.hasWindowFocus() }; f }
            val x = m.widthPixels / 2
            // Dedo hacia arriba: el texto avanza. Solo si el Lector está al frente: el gesto es real.
            assumeTrue("el Lector no está al frente", focused())
            swipe(x, m.heightPixels * 7 / 10, x, m.heightPixels * 3 / 10)
            rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Índice")).fetchSemanticsNodes().isEmpty() }
            screenshot("barras-ocultas")
            // Dedo hacia abajo: se vuelve atrás y las barras aparecen.
            assumeTrue("el Lector no está al frente", focused())
            swipe(x, m.heightPixels * 4 / 10, x, m.heightPixels * 6 / 10)
            rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Índice")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private suspend fun importAndOpen(): String {
        val epub = TestEpub.build(dir, "lector.epub") {
            title = "Libro largo de prueba"
            replace("OEBPS/content.opf", opf().toByteArray())
            replace("OEBPS/nav.xhtml", nav().toByteArray())
            replace("OEBPS/c1.xhtml", chapter("Capítulo uno").toByteArray())
            entry("OEBPS/c2.xhtml", chapter("Capítulo dos").toByteArray())
            entry("OEBPS/c3.xhtml", chapter("Capítulo tres").toByteArray())
        }
        val id = (app.books.import({ epub.inputStream() }, "lector.epub") as ImportResult.Ok).bookId
        created += id
        app.openBooks.put(id, app.readium.open(app.books.epubFile(id)).getOrNull()!!, null)
        return id
    }

    private fun waitForText(text: String) =
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Con la regla de Compose (no con Thread.sleep): así avanza también el reloj de los efectos de la pantalla. */
    private fun waitFor(what: String, done: () -> Boolean) = rule.waitUntil(what, 10_000) { done() }

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        instrumentation.uiAutomation.executeShellCommand("input swipe $x1 $y1 $x2 $y2 600").close()
        Thread.sleep(900)
    }

    private fun screenshot(name: String): Bitmap {
        rule.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (InstrumentationRegistry.getArguments().getString("shots") == "true") {
            val out = File(app.cacheDir, "reader_shots").apply { mkdirs() }
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    /** Parte de píxeles oscuros en la franja central (página blanca de Readium con texto negro). */
    private fun inkRatio(b: Bitmap): Double {
        var dark = 0
        var total = 0
        for (y in b.height * 3 / 10 until b.height * 7 / 10 step 2) {
            for (x in 0 until b.width step 2) {
                val c = b.getPixel(x, y)
                if ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < 100) dark++
                total++
            }
        }
        return dark.toDouble() / total
    }

    private fun opf() = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">urn:uuid:00000000-0000-0000-0000-000000000009</dc:identifier>
    <dc:title>Libro largo de prueba</dc:title>
    <dc:language>es</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="c3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
</package>"""

    private fun nav() = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Índice</title></head><body><nav epub:type="toc"><ol>""" +
        """<li><a href="c1.xhtml">Capítulo uno</a></li><li><a href="c2.xhtml">Capítulo dos</a></li><li><a href="c3.xhtml">Capítulo tres</a></li>""" +
        """</ol></nav></body></html>"""

    private fun chapter(title: String) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>""")
        for (i in 1..70) append("<p>Párrafo $i inventado para probar el lector. La lluvia caía sobre el puerto y nadie miraba el mar.</p>")
        append("</body></html>")
    }
}
