package io.github.diegobr4nd.lectorbilingue

import android.content.pm.PackageManager
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.ImportError
import io.github.diegobr4nd.lectorbilingue.books.ImportResult
import io.github.diegobr4nd.lectorbilingue.books.readium.HtmlSanitizer
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EPUBs maliciosos en el teléfono, por el camino real: importar (copia con tope, revisión del ZIP, Readium) y
 * abrir en el Lector (Readium + WebView). Especificación: revisión de seguridad de la Tarea 10 (Fase 3a).
 *
 * - Los EPUBs se arman aquí mismo (texto inventado), los grandes en trozos para no llenar la memoria.
 * - El "espía" es un ServerSocket en 127.0.0.1 dentro de la propia prueba: nunca se contacta un servidor de fuera.
 * - Nunca se pulsa "Abrir" en el diálogo de enlaces externos (no se lanza el navegador).
 * - Todo lo que se crea se borra en [cleanUp].
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class MaliciousEpubOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val dir = File(app.cacheDir, "malicious_test").apply { deleteRecursively(); mkdirs() }
    private val booksDir = File(app.filesDir, "books")
    private val created = mutableListOf<String>()
    private val opened = mutableListOf<Publication>()
    private val secret = File(app.filesDir, "xxe-secreto.txt")
    private var spy: Spy? = null

    @After fun cleanUp() = runBlocking<Unit> {
        for (p in opened) runCatching { p.close() }
        for (id in created) {
            app.openBooks.close(id)
            app.books.delete(id)
        }
        dir.deleteRecursively()
        secret.delete()
        spy?.close()
    }

    // ---------- Importación (I) ----------

    @Test fun i0ControlValidoAbreEnElLector() = runBlocking<Unit> {
        val id = importOk(write("00.epub", basic("<p>Hola mundo.</p>")))
        reader(id) { s -> assertEquals("C1", js(s, "document.title")) }
    }

    @Test fun i1BombaDeUnGigaSeRechazaRapido() = runBlocking<Unit> {
        val f = File(dir, "01.epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putStored("mimetype", MIME)
            zip.putDeflated("META-INF/container.xml", CONTAINER.toByteArray())
            for ((n, d) in basic("<p>bomba</p>")) zip.putDeflated(n, d)
            zip.putNextEntry(ZipEntry("OEBPS/relleno.bin"))
            val chunk = ByteArray(1 shl 20)
            repeat(1024) { zip.write(chunk) }
            zip.closeEntry()
        }
        assertImportFails(f, ImportError.UNSAFE_ARCHIVE, maxMillis = 10_000)
    }

    @Test fun i2BombaConTamanoMentirosoSeRechaza() = runBlocking<Unit> {
        val f = lyingBomb(File(dir, "01b.epub"), realBytes = 1L shl 30, declared = 1000)
        // Resultado de la primera ejecución en el Pixel 7 (Android 16): se fija para detectar regresiones.
        assertImportFails(f, ImportError.UNSAFE_ARCHIVE, maxMillis = 10_000)
    }

    @Test fun i3ZipSlipNoCreaNada() = runBlocking<Unit> {
        val names = listOf("../../evil.txt", "OEBPS/../../evil.txt", "/data/data/${app.packageName}/files/evil", "..\\..\\evil.txt", "C:evil.txt")
        for ((i, evil) in names.withIndex()) {
            val entries = listOf("mimetype" to MIME, "META-INF/container.xml" to CONTAINER.toByteArray()) +
                basic("<p>slip</p>") + (evil to "pwned".toByteArray())
            val f = rawZip(File(dir, "02$i.epub"), entries)
            val r = importChecked(f)
            assertTrue(r is ImportResult.Error && r.reason in setOf(ImportError.UNSAFE_ARCHIVE, ImportError.NOT_EPUB), "$evil: $r")
        }
        for (f in listOf(File(app.filesDir.parentFile, "evil.txt"), File(app.filesDir, "evil"), File(app.cacheDir, "evil.txt"), File(app.filesDir, "evil.txt"))) {
            assertFalse(f.exists(), f.name)
        }
    }

    @Test fun i4DrmSeRechazaI5OfuscacionPasa() = runBlocking<Unit> {
        val lcpl = """{"id":"x","encryption":{"profile":"http://readium.org/lcp/basic-profile"},"links":[],"signature":{}}"""
        val encLcp = """<?xml version="1.0"?><encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
<enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes256-cbc"/><enc:CipherData><enc:CipherReference URI="OEBPS/c1.xhtml"/></enc:CipherData></enc:EncryptedData></encryption>"""
        val drm = mapOf(
            "07a" to listOf("META-INF/license.lcpl" to lcpl, "META-INF/encryption.xml" to encLcp),
            "07b" to listOf("META-INF/encryption.xml" to encLcp),
            "07c" to listOf("META-INF/rights.xml" to "<rights/>"),
            "07e" to listOf("META-INF/encryption.xml" to encLcp.replace("Algorithm=\"http://www.w3.org/2001/04/xmlenc#aes256-cbc\"", "Algorithm = 'http://www.w3.org/2001/04/xmlenc#aes256-cbc'")),
            "07g" to listOf("META-INF/encryption.xml" to encLcp.replace("Algorithm=", "enc:Algorithm=")),
        )
        for ((name, extra) in drm) {
            assertImportFails(write("$name.epub", basic("<p>x</p>") + extra.map { it.first to it.second.toByteArray() }), ImportError.DRM)
        }
        val encFont = encLcp.replace("http://www.w3.org/2001/04/xmlenc#aes256-cbc", "http://www.idpf.org/2008/embedding")
        importOk(write("07d.epub", basic("<p>fuentes</p>") + ("META-INF/encryption.xml" to encFont.toByteArray())))
    }

    @Test fun i6DemasiadasEntradasI7NoEsEpub() = runBlocking<Unit> {
        val many = write("08.epub", basic("<p>x</p>") + (0..10_000).map { "OEBPS/f$it.txt" to ByteArray(0) })
        assertImportFails(many, ImportError.UNSAFE_ARCHIVE)
        assertImportFails(write("09a.epub", basic("<p>x</p>"), mimetype = "application/zip"), ImportError.NOT_EPUB)
        assertImportFails(File(dir, "09b.epub").apply { writeText("%PDF-1.4 esto no es un zip") }, ImportError.NOT_EPUB)
    }

    @Test fun i8CapituloDe350MegasSeRechaza() = runBlocking<Unit> {
        val f = File(dir, "13.epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putStored("mimetype", MIME)
            zip.putDeflated("META-INF/container.xml", CONTAINER.toByteArray())
            for ((n, d) in basic("<p>x</p>").take(2)) zip.putDeflated(n, d)
            zip.putNextEntry(ZipEntry("OEBPS/c1.xhtml"))
            val (head, tail) = xhtml("@@").split("@@")
            zip.write(head.toByteArray())
            val line = ("<p>" + "a".repeat(100) + "</p>\n").repeat(1000).toByteArray()
            repeat((350 shl 20) / line.size) { zip.write(line) }
            zip.write(tail.toByteArray())
            zip.closeEntry()
        }
        assertImportFails(f, ImportError.UNSAFE_ARCHIVE, maxMillis = 10_000)
    }

    // Seguridad (bajo, ronda 3): el peor caso para jsoup justo debajo del tope de 8 MB (una etiqueta cada 8 bytes)
    // se sanea en el teléfono sin quedarse sin memoria; por encima del tope se sirve el aviso.
    @Test fun i9CapituloDeEtiquetasDiminutasNoAgotaLaMemoria() = runBlocking<Unit> {
        val MAX_MARKUP = 8 * 1024 * 1024 // ResourceSanitizing.MAX_MARKUP_BYTES (interno de :books)
        val OVERSIZE = "Este capítulo es demasiado grande para mostrarlo."
        fun tiny(name: String, bytes: Int): File {
            val (head, tail) = xhtml("@@").split("@@")
            val body = "<p>a</p>".repeat((bytes - head.length - tail.length) / 8)
            return write(name, basic(null, c1Raw = head + body + tail))
        }
        val under = importOk(tiny("14a.epub", MAX_MARKUP - 1024))
        val start = System.nanoTime()
        val html = served(open(under), "OEBPS/c1.xhtml")!!
        assertTrue((System.nanoTime() - start) / 1_000_000 < 30_000, "sanear tardó más de 30 s")
        assertTrue(html.length > 1_000_000 && !html.contains(OVERSIZE))
        val over = importOk(tiny("14b.epub", MAX_MARKUP + 1024 * 1024))
        assertTrue(served(open(over), "OEBPS/c1.xhtml")!!.contains(OVERSIZE))
    }

    // ---------- XML: XXE y entidades (X) ----------

    @Test fun x1XxeNoLeeArchivosDelTelefono() = runBlocking<Unit> {
        secret.writeText("SECRETO_XXE")
        val url = "file://${secret.absolutePath}"
        val inContainer = """<?xml version="1.0"?>
<!DOCTYPE container [ <!ENTITY xxe SYSTEM "$url"> ]>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>&xxe;</rootfiles>
</container>"""
        val inPath = """<?xml version="1.0"?>
<!DOCTYPE container [ <!ENTITY rp SYSTEM "$url"> ]>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="&rp;" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
        val opfXxe = opf(listOf(Triple("c1", "c1.xhtml", "application/xhtml+xml")), title = "&xxe;").replace(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + """<!DOCTYPE package [ <!ENTITY xxe SYSTEM "$url"> ]>""",
        )
        val cases = mapOf(
            "03a" to write("03a.epub", basic("<p>xxe</p>"), container = inContainer),
            "03b" to write("03b.epub", basic("<p>xxe</p>"), container = inPath),
            "03d" to write("03d.epub", listOf("OEBPS/content.opf" to opfXxe.toByteArray(), "OEBPS/nav.xhtml" to NAV.toByteArray(), "OEBPS/c1.xhtml" to xhtml("<p>x</p>").toByteArray())),
        )
        val outcome = mutableMapOf<String, String>()
        for ((name, f) in cases) {
            when (val r = importChecked(f)) {
                is ImportResult.Error -> {
                    assertTrue(r.reason == ImportError.DAMAGED || r.reason == ImportError.NOT_EPUB, "$name: $r")
                    outcome[name] = r.reason.name
                }
                is ImportResult.Ok -> {
                    created += r.bookId
                    val book = app.books.get(r.bookId)!!
                    assertFalse(book.title.contains("SECRETO_XXE"), name); assertFalse(book.author.orEmpty().contains("SECRETO_XXE"), name)
                    val pub = open(r.bookId)
                    for (href in listOf("OEBPS/c1.xhtml", "OEBPS/nav.xhtml")) assertFalse(served(pub, href).orEmpty().contains("SECRETO_XXE"), "$name $href")
                    outcome[name] = "OK"
                }
            }
        }
        // Resultado de la primera ejecución en el Pixel 7 (Readium 3.4.0): se fija para detectar regresiones.
        // 03a y 03d se importan, pero la entidad externa no se resuelve (comprobado arriba); 03b no encuentra el OPF.
        assertEquals(mapOf("03a" to "OK", "03b" to "DAMAGED", "03d" to "OK"), outcome)
    }

    @Test fun x2BillionLaughsEnContainerTerminaRapido() = runBlocking<Unit> {
        val laughs = """<?xml version="1.0"?>
<!DOCTYPE container [ <!ENTITY lol0 "lol"> $LOL ]>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles><x>&lol9;</x>
</container>"""
        val start = System.currentTimeMillis()
        val r = importChecked(write("03c.epub", basic("<p>lol</p>"), container = laughs))
        assertTrue(System.currentTimeMillis() - start < 10_000, "tardó demasiado")
        if (r is ImportResult.Ok) created += r.bookId
        // Resultado de la primera ejecución en el Pixel 7 (Readium 3.4.0): se importa sin colgarse ni agotar la
        // memoria (el analizador no expande lol9 o lo corta). Se fija para detectar regresiones.
        assertTrue(r is ImportResult.Ok, "$r")
    }

    @Test fun x3EntidadesEnElCapituloNoSeExpanden() = runBlocking<Unit> {
        secret.writeText("SECRETO_XXE")
        val c1 = xhtml("<p>&xxe;</p><p>&lol9;</p>").replace(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" +
                """<!DOCTYPE html [ <!ENTITY xxe SYSTEM "file://${secret.absolutePath}"> <!ENTITY lol0 "lol"> $LOL ]>""",
        )
        val id = importOk(write("03e.epub", basic(null, c1Raw = c1)))
        val html = served(open(id), "OEBPS/c1.xhtml")!!
        assertFalse(html.contains("<!DOCTYPE", ignoreCase = true)); assertFalse(html.contains("SECRETO_XXE"))
        assertFalse(Regex("(lol){342,}").containsMatchIn(html), "tramo de lol de más de 1 KB")
        reader(id) { s -> assertTrue(js(s, "document.body.innerText.length")!!.toInt() < 1000) }
    }

    // Seguridad (bajo, ronda 3): billion laughs en el OPF y en el NCX, que Readium lee al importar y al abrir con el
    // analizador XML de Android. Vale un error limpio o importar sin el contenido expandido, en menos de 10 s.
    // El mismo OPF y NCX, servidos al WebView, se prueban en la JVM (ResourceSanitizingTest).

    @Test fun x5BillionLaughsEnElOpfTerminaRapido() = runBlocking<Unit> {
        val opfLaughs = opf(listOf(Triple("c1", "c1.xhtml", "application/xhtml+xml")), title = "&lol9;").replace(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + """<!DOCTYPE package [ <!ENTITY lol0 "lol"> $LOL ]>""",
        )
        val f = write("03f.epub", listOf("OEBPS/content.opf" to opfLaughs.toByteArray(), "OEBPS/nav.xhtml" to NAV.toByteArray(), "OEBPS/c1.xhtml" to xhtml("<p>x</p>").toByteArray()))
        val start = System.currentTimeMillis()
        val r = importChecked(f)
        assertTrue(System.currentTimeMillis() - start < 10_000, "tardó demasiado")
        when (r) {
            is ImportResult.Error -> assertTrue(r.reason == ImportError.DAMAGED || r.reason == ImportError.NOT_EPUB, "$r")
            is ImportResult.Ok -> {
                created += r.bookId
                val book = app.books.get(r.bookId)!!
                assertTrue(book.title.length < 1000, "título de ${book.title.length} caracteres")
                assertFalse(Regex("(lol){342,}").containsMatchIn(book.title), "título con lol expandido")
            }
        }
    }

    @Test fun x6BillionLaughsEnElNcxTerminaRapido() = runBlocking<Unit> {
        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx [ <!ENTITY lol0 "lol"> $LOL ]>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>&lol9;</text></docTitle>
<navMap><navPoint id="n1" playOrder="1"><navLabel><text>&lol9;</text></navLabel><content src="c1.xhtml"/></navPoint></navMap>
</ncx>"""
        val files = basic("<p>x</p>", itemsExtra = listOf(Triple("ncx", "toc.ncx", "application/x-dtbncx+xml")), filesExtra = listOf("OEBPS/toc.ncx" to ncx))
            .map { (n, d) -> if (n == "OEBPS/content.opf") n to d.toString(Charsets.UTF_8).replace("<spine>", """<spine toc="ncx">""").toByteArray() else n to d }
        val start = System.currentTimeMillis()
        val r = importChecked(write("03g.epub", files))
        assertTrue(System.currentTimeMillis() - start < 10_000, "tardó demasiado al importar")
        if (r is ImportResult.Error) {
            assertTrue(r.reason == ImportError.DAMAGED || r.reason == ImportError.NOT_EPUB, "$r")
            return@runBlocking
        }
        r as ImportResult.Ok
        created += r.bookId
        val openStart = System.currentTimeMillis()
        val pub = open(r.bookId)
        assertTrue(System.currentTimeMillis() - openStart < 10_000, "tardó demasiado al abrir")
        // Readium lee el NCX como índice de respaldo: ningún título del índice trae el lol expandido.
        fun titles(links: List<Link>): List<String> = links.flatMap { listOfNotNull(it.title) + titles(it.children) }
        for (t in titles(pub.tableOfContents)) {
            assertTrue(t.length < 1000, "título del índice de ${t.length} caracteres")
            assertFalse(Regex("(lol){342,}").containsMatchIn(t), "índice con lol expandido")
        }
        assertFalse(Regex("(lol){342,}").containsMatchIn(served(pub, "OEBPS/toc.ncx").orEmpty()), "NCX servido con lol expandido")
    }

    @Test fun x4CharsetAjenoNoSeSirve() = runBlocking<Unit> {
        val utf7 = importOk(write("10a.epub", basic("<p>+ADw-script+AD4-document.title='UTF7'+ADw-/script+AD4-</p>", c1Type = "application/xhtml+xml; charset=utf-7")))
        assertNull(served(open(utf7), "OEBPS/c1.xhtml"))
        // Con ISO-2022-JP en el manifiesto Readium no llega a leer el libro: se rechaza al importar (primera
        // ejecución en el Pixel 7; se fija para detectar regresiones). Rechazarlo también es seguro.
        assertImportFails(write("10b.epub", basic("<p>x</p>", c1Type = "application/xhtml+xml; charset=\"ISO-2022-JP\"")), ImportError.DAMAGED)
        reader(utf7, waitReadium = false) { s ->
            Thread.sleep(2_000)
            assertTrue(js(s, "document.title") != "UTF7")
        }
    }

    // ---------- Scripts (S) ----------

    @Test fun s1ScriptsDelLibroNoCorren() = runBlocking<Unit> {
        spy = Spy()
        val id = importOk(scriptsEpub())
        val pub = open(id)
        for (href in listOf("OEBPS/c1.xhtml", "OEBPS/evil.js")) assertSanitized(served(pub, href)!!, csp = href.endsWith("xhtml"))
        reader(id) { s ->
            val title = js(s, "document.title")
            assertEquals("C1", title)
            assertEquals("0,0", js(s, DOM_CHECK))
            assertEquals("C1", js(s, "document.getElementById('p1').click(); document.title"))
            assertEquals(HtmlSanitizer.CSP, js(s, "document.querySelector('meta[http-equiv=\"Content-Security-Policy\"]').content"))
        }
    }

    @Test fun s1bLaCspFrenaUnScriptEnLineaQueSeCuele() = runBlocking<Unit> {
        // Si algún día el saneador deja pasar un <script> en línea, la CSP debe frenarlo (segunda barrera).
        val id = importOk(write("00.epub", basic("<p>Hola mundo.</p>")))
        reader(id) { s ->
            js(s, "var x=document.createElement('script');x.textContent=\"document.title='EN_LINEA'\";document.head.appendChild(x);1")
            Thread.sleep(500)
            assertEquals("C1", js(s, "document.title"))
            // Readium sigue vivo: su JS responde.
            assertEquals("object", js(s, "typeof window.readium"))
        }
    }

    @Test fun s2BodyOnloadNoCorre() = runBlocking<Unit> {
        val id = importOk(write("04b.epub", basic(null, c1Raw = xhtml("<p>x</p>").replace("<body>", "<body onload=\"document.title='ONLOAD'\">"))))
        reader(id) { s -> assertEquals("C1", js(s, "document.title")) }
    }

    @Test fun s3TextHtmlConMxssNoCorre() = runBlocking<Unit> {
        val id = importOk(write("11.epub", basic(null, c1Type = "text/html", c1Name = "c1.html", c1Raw = MXSS)))
        // En modo HTML el texto de <title> o <textarea> puede contener "onerror=" ya escapado (inerte):
        // los atributos se comprueban en el DOM que arma el navegador (DOM_CHECK), que es lo que importa.
        assertSanitized(served(open(id), "OEBPS/c1.html")!!, csp = true, attributesAsText = false)
        reader(id) { s ->
            Thread.sleep(2_000)
            val title = js(s, "document.title")
            assertFalse(title.orEmpty().matches(Regex("MX\\d+")), "título $title")
            assertEquals("0,0", js(s, DOM_CHECK))
        }
    }

    @Test fun s4RecursosSueltosYSvg() = runBlocking<Unit> {
        spy = Spy()
        val id = importOk(svgEpub())
        val pub = open(id)
        assertSanitized(served(pub, "OEBPS/suelto")!!, csp = true)
        assertSanitized(served(pub, "OEBPS/i.svg")!!, csp = false)
        reader(id) { s ->
            // i.svg no está en el orden de lectura: Readium puede negarse a ir (false). Si va, el SVG no ejecuta nada.
            val nav = s.navigator()
            runBlocking(Dispatchers.Main) { nav.go(Locator(Url("OEBPS/i.svg")!!, MediaType.SVG), false) }
            Thread.sleep(2_000)
            val title = js(s, "document.title").orEmpty()
            assertFalse(title == "SVG_ONLOAD" || title == "SVG_DOC_SCRIPT", title)
        }
        assertEquals(0, spy!!.count(), "conexiones al espía")
    }

    @Test fun s5EntradasDuplicadasSaneadas() = runBlocking<Unit> {
        val f = File(dir, "14.epub")
        // ZipOutputStream no permite nombres repetidos: la copia se arma a mano.
        val entries = listOf("mimetype" to MIME, "META-INF/container.xml" to CONTAINER.toByteArray()) + basic("<p>inocente</p>") +
            ("OEBPS/c1.xhtml" to xhtml("<script>document.title='DUP'</script><p onclick='x()'>dup</p>").toByteArray())
        val r = importChecked(rawZip(f, entries))
        if (r is ImportResult.Error) return@runBlocking // Rechazarlo también es seguro.
        val id = (r as ImportResult.Ok).bookId.also { created += it }
        assertSanitized(served(open(id), "OEBPS/c1.xhtml")!!, csp = true)
        reader(id) { s -> assertTrue(js(s, "document.title") != "DUP") }
    }

    @Test fun s6CspPropiaYXslt() = runBlocking<Unit> {
        val c1 = xhtml("<p>x</p>", head = """<meta http-equiv="Content-Security-Policy" content="default-src *; script-src * 'unsafe-inline' 'unsafe-eval'; connect-src *"/>""")
            .replace("""<?xml version="1.0" encoding="UTF-8"?>""", """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + """<?xml-stylesheet type="text/xsl" href="x.xsl"?>""")
        val xsl = """<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"><xsl:template match="/"><html><body><script>document.title="XSLT"</script></body></html></xsl:template></xsl:stylesheet>"""
        val id = importOk(write("15.epub", basic(null, c1Raw = c1, itemsExtra = listOf(Triple("xsl", "x.xsl", "application/xslt+xml")), filesExtra = listOf("OEBPS/x.xsl" to xsl))))
        val html = served(open(id), "OEBPS/c1.xhtml")!!
        assertSanitized(html, csp = true)
        assertFalse(html.contains("default-src *"))
        reader(id) { s -> assertTrue(js(s, "document.title") != "XSLT") }
    }

    // ---------- Red (R) y archivos locales (W) ----------

    @Test fun rNingunaConexionDeRed() = runBlocking<Unit> {
        val spy = Spy().also { this@MaliciousEpubOnDeviceTest.spy = it }
        val net = importOk(networkEpub(spy))
        val html = served(open(net), "OEBPS/c1.xhtml")!!
        assertFalse(Regex("""rel="(preconnect|dns-prefetch|prefetch|prerender|preload|modulepreload)"""", RegexOption.IGNORE_CASE).containsMatchIn(html), "pistas de red")
        assertFalse(html.contains("ping=", ignoreCase = true))
        reader(net) { s ->
            js(s, "window.scrollTo(0, document.body.scrollHeight); 1")
            Thread.sleep(3_000)
        }
        for (epub in listOf(svgEpub(), scriptsEpub())) {
            val id = importOk(epub)
            reader(id) { s ->
                js(s, "window.scrollTo(0, document.body.scrollHeight); 1")
                Thread.sleep(3_000)
            }
        }
        Thread.sleep(4_000)
        assertEquals(0, spy.count(), "conexiones al espía")
        // Control: el espía funciona (una conexión de la propia prueba sí se cuenta).
        Socket(InetAddress.getByName("127.0.0.1"), spy.port).close()
        val deadline = System.currentTimeMillis() + 5_000
        while (spy.count() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(1, spy.count(), "control del espía")
    }

    @Test fun wSinArchivosLocalesNiIframes() = runBlocking<Unit> {
        spy = Spy()
        val id = importOk(networkEpub(spy!!))
        reader(id) { s ->
            Thread.sleep(2_000)
            assertEquals("0", js(s, "document.querySelector('img[alt=f]').naturalWidth"))
            assertEquals("0", js(s, "document.querySelector('img[alt=g]').naturalWidth"))
            assertEquals("0", js(s, "document.querySelectorAll('iframe').length"))
        }
    }

    // ---------- Enlaces (E) ----------

    @Test fun e1EnlacesExternosPreguntanAntes() = runBlocking<Unit> {
        val id = importOk(write("06.epub", basic(LINKS)))
        val title = app.getString(R.string.reader_external_title)
        val cancel = app.getString(io.github.diegobr4nd.lectorbilingue.core.ui.R.string.action_cancel)
        val expected = mapOf(
            "ext" to "https://example.org/ruta?x=1",
            "exth" to "http://example.org/",
            "blank" to "https://example.org/blank",
            "userinfo" to "https://example.org@127.0.0.1:8765/enganio",
        )
        reader(id) { s ->
            for ((anchor, url) in expected) {
                js(s, "document.getElementById('$anchor').click(); 1")
                rule.waitUntil("diálogo de $anchor", 5_000) { rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithText(url, substring = true).assertExists()
                rule.onNodeWithText(cancel).performClick() // Nunca "Abrir".
                rule.waitUntil("diálogo cerrado", 5_000) { rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty() }
                assertEquals(Lifecycle.State.RESUMED, s.state)
            }
        }
    }

    @Test fun e2EnlacesPeligrososNoHacenNada() = runBlocking<Unit> {
        val id = importOk(write("06.epub", basic(LINKS)))
        val title = app.getString(R.string.reader_external_title)
        reader(id) { s ->
            // mailto: Readium 3.4.0 no lo pasa a onExternalLinkActivated (primera ejecución en el Pixel 7): no hace
            // nada, que también es seguro. Si algún día lo pasa, ReaderRules lo deja pasar con el diálogo.
            for (anchor in listOf("js", "jsws", "intent", "file", "content", "tel", "market", "mail")) {
                js(s, "document.getElementById('$anchor').click(); 1")
                Thread.sleep(2_000)
                assertTrue(rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty(), "diálogo con $anchor")
                assertEquals(Lifecycle.State.RESUMED, s.state, anchor)
                val t = js(s, "document.title")
                assertFalse(t == "LINK_JS" || t == "LINK_JS2", "$anchor: $t")
            }
        }
    }

    // ---------- Lector con datos malos (P), registros (L) y WebView sin telemetría ----------

    @Test fun pLectorConExtrasMalosTerminaSinFallar() {
        val bad = listOf("../../databases/lector", "123E4567-E89B-12D3-A456-426614174000", "", "123e4567-e89b-12d3-a456-426614174000")
        for (extra in bad) {
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, extra)).use { s ->
                assertEquals(Lifecycle.State.DESTROYED, s.state, extra)
            }
        }
        val noExtra = android.content.Intent(app, ReaderActivity::class.java)
        ActivityScenario.launch<ReaderActivity>(noExtra).use { s -> assertEquals(Lifecycle.State.DESTROYED, s.state) }
    }

    @Test fun lNiElTituloNiElTextoLleganAlRegistro() = runBlocking<Unit> {
        val id = importOk(write("00.epub", basic("<p>FRASE_UNICA_8c41 inventada.</p>", title = "Titulo unico L7q2")))
        reader(id) { s -> assertEquals("C1", js(s, "document.title")) }
        val s1 = importOk(scriptsEpub(title = "Titulo unico L7q2"))
        reader(s1) { Thread.sleep(1_000) }
        val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "brief", "--pid", android.os.Process.myPid().toString()))
            .inputStream.bufferedReader().use { it.readText() }
        assertFalse(log.contains("Titulo unico L7q2"), "título en el registro")
        assertFalse(log.contains("FRASE_UNICA_8c41"), "texto en el registro")
    }

    @Test fun webViewSinMetricasNiSafeBrowsing() {
        val meta = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        assertNotNull(meta, "sin meta-data")
        assertTrue(meta.containsKey("android.webkit.WebView.MetricsOptOut")); assertTrue(meta.getBoolean("android.webkit.WebView.MetricsOptOut"))
        assertTrue(meta.containsKey("android.webkit.WebView.EnableSafeBrowsing")); assertFalse(meta.getBoolean("android.webkit.WebView.EnableSafeBrowsing", true))
    }

    // ---------- Ayudas ----------

    /** Importa por el camino real y comprueba que no quedan restos (temporal vacío; si falla, ni archivos ni filas nuevas). */
    private suspend fun importChecked(file: File): ImportResult {
        val before = booksDir.list()?.toSet().orEmpty()
        val rows = app.books.books.first().size
        val r = app.books.import({ file.inputStream() }, file.name)
        assertTrue(File(booksDir, "tmp").list().isNullOrEmpty(), "quedó un temporal")
        if (r is ImportResult.Error) {
            assertEquals(before, booksDir.list()?.toSet().orEmpty(), "quedaron archivos")
            assertEquals(rows, app.books.books.first().size, "quedó una fila")
        }
        return r
    }

    private suspend fun importOk(file: File): String {
        val r = importChecked(file)
        assertTrue(r is ImportResult.Ok, "${file.name}: $r")
        return r.bookId.also { created += it }
    }

    private suspend fun assertImportFails(file: File, reason: ImportError, maxMillis: Long = 30_000) {
        val start = System.currentTimeMillis()
        val r = importChecked(file)
        val took = System.currentTimeMillis() - start
        if (r is ImportResult.Ok) created += r.bookId
        assertEquals(ImportResult.Error(reason), r, file.name)
        assertTrue(took < maxMillis, "${file.name} tardó $took ms")
    }

    private suspend fun open(id: String): Publication =
        app.readium.open(app.books.epubFile(id)).getOrNull()!!.also { opened += it }

    /** El recurso tal como Readium se lo da al WebView (ya saneado). null si Readium no lo sirve. */
    private suspend fun served(pub: Publication, href: String): String? =
        pub.get(Url(href)!!)?.read()?.getOrNull()?.toString(Charsets.UTF_8)

    private fun assertSanitized(html: String, csp: Boolean, attributesAsText: Boolean = true) {
        assertFalse(html.contains("<script", true)); assertFalse(html.contains(":script", true)); assertFalse(html.contains("javascript:", true))
        if (attributesAsText) assertFalse(Regex("""(?i)[\s"'](?:\w+:)?on[a-z]+\s*=""").containsMatchIn(html), "atributo on…")
        for (bad in listOf("<iframe", "<object", "<embed", "<form", "<base", "<noscript", "srcdoc", "xml-stylesheet", "<!ENTITY")) {
            assertFalse(html.contains(bad, true), bad)
        }
        if (csp) {
            assertEquals(1, Regex("Content-Security-Policy", RegexOption.IGNORE_CASE).findAll(html).count())
            assertTrue(html.contains(HtmlSanitizer.CSP.replace("'", "&#39;")) || html.contains(HtmlSanitizer.CSP), "CSP distinta")
        }
    }

    /** Abre el libro en el Lector de verdad y espera (si se pide) a que el JavaScript de Readium esté listo. */
    private suspend fun reader(id: String, waitReadium: Boolean = true, block: (ActivityScenario<ReaderActivity>) -> Unit) {
        val pub = app.readium.open(app.books.epubFile(id)).getOrNull()!!
        app.openBooks.put(id, pub, null)
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            if (waitReadium) rule.waitUntil("Readium listo", 10_000) { js(s, "typeof window.readium") == "object" }
            block(s)
        }
    }

    private fun ActivityScenario<ReaderActivity>.navigator(): EpubNavigatorFragment {
        var nav: EpubNavigatorFragment? = null
        onActivity { a -> nav = a.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull() }
        return nav!!
    }

    /** Ejecuta JavaScript en el capítulo actual. Devuelve el valor ya sin comillas JSON (o null). */
    private fun js(s: ActivityScenario<ReaderActivity>, script: String): String? {
        val nav = runCatching { s.navigator() }.getOrNull() ?: return null
        val raw = runBlocking(Dispatchers.Main) { nav.evaluateJavascript(script) } ?: return null
        return JSONArray("[$raw]").opt(0)?.takeIf { it != org.json.JSONObject.NULL }?.toString()
    }

    /** Espía de red: cuenta cada conexión TCP que llega a 127.0.0.1:[port]. */
    private class Spy : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val hits = AtomicInteger()
        val port: Int = server.localPort
        val https = "https://127.0.0.1:$port"
        val http = "http://127.0.0.1:$port"

        init {
            thread(isDaemon = true, name = "espia") {
                while (!server.isClosed) {
                    runCatching { server.accept().use { hits.incrementAndGet() } }
                }
            }
        }

        fun count(): Int = hits.get()
        override fun close() = server.close()
    }

    // ---------- EPUBs ----------

    private fun scriptsEpub(title: String = "Prueba"): File {
        val spy = spy ?: Spy().also { spy = it }
        val body = SCRIPTS.replace("SPY", spy.https)
        return write(
            "04a-$title.epub".replace(' ', '_'),
            basic(
                body,
                head = """<script src="evil.js"></script><meta http-equiv="refresh" content="0;url=${spy.https}/refresh"/>""",
                itemsExtra = listOf(Triple("js", "evil.js", "application/javascript")),
                filesExtra = listOf("OEBPS/evil.js" to "document.title='EXT_JS';fetch('${spy.https}/evil-js')"),
                title = title,
            ),
        )
    }

    private fun svgEpub(): File {
        val spy = spy ?: Spy().also { spy = it }
        val svg = """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" onload="document.title='SVG_ONLOAD'">
<script>document.title='SVG_DOC_SCRIPT'</script><a xlink:href="javascript:alert(1)"><rect width="9" height="9"/></a>
<foreignObject><body xmlns="http://www.w3.org/1999/xhtml"><iframe src="${spy.https}/svg-iframe"/></body></foreignObject></svg>"""
        return write(
            "12.epub",
            basic(
                """<p><a href="suelto">suelto</a> <a href="i.svg">svg</a> <img src="i.svg" alt="s"/></p>""",
                itemsExtra = listOf(Triple("svg", "i.svg", "image/svg+xml")),
                filesExtra = listOf(
                    "OEBPS/i.svg" to svg,
                    "OEBPS/suelto" to "<html><body><script>document.title='SUELTO'</script><img src=x onerror=alert(1)></body></html>",
                ),
            ),
        )
    }

    private fun networkEpub(spy: Spy): File {
        fun spyIn(s: String) = s.replace("SPYH", spy.http).replace("SPY", spy.https)
        val css = spyIn(
            """@import url('SPY/import.css');
@font-face { font-family: Spy; src: url('SPY/font.woff2'); unicode-range: U+0061; }
body { font-family: Spy, serif; background-image: url('SPY/bg.png'); }
p::after { content: url('SPY/after.png'); }
""",
        )
        val head = spyIn("""<link rel="stylesheet" type="text/css" href="s.css"/><link rel="preconnect" href="SPY"/><link rel="dns-prefetch" href="//spy-dns-prefetch.invalid"/><style>@import "SPY/style-import.css";</style>""")
        val body = spyIn(NETWORK).replace("PKG", app.packageName)
        return write("05.epub", basic(body, head = head, itemsExtra = listOf(Triple("css", "s.css", "text/css")), filesExtra = listOf("OEBPS/s.css" to css)))
    }

    private fun basic(
        c1Body: String?,
        head: String = "",
        itemsExtra: List<Triple<String, String, String>> = emptyList(),
        filesExtra: List<Pair<String, String>> = emptyList(),
        c1Type: String = "application/xhtml+xml",
        c1Name: String = "c1.xhtml",
        c1Raw: String? = null,
        title: String = "Prueba",
    ): List<Pair<String, ByteArray>> {
        val items = listOf(Triple("c1", c1Name, c1Type)) + itemsExtra
        return listOf(
            "OEBPS/content.opf" to opf(items, title).toByteArray(),
            "OEBPS/nav.xhtml" to NAV.toByteArray(),
            "OEBPS/$c1Name" to (c1Raw ?: xhtml(c1Body.orEmpty(), head)).toByteArray(),
        ) + filesExtra.map { it.first to it.second.toByteArray() }
    }

    private fun write(name: String, files: List<Pair<String, ByteArray>>, container: String = CONTAINER, mimetype: String = "application/epub+zip"): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putStored("mimetype", mimetype.toByteArray())
            zip.putDeflated("META-INF/container.xml", container.toByteArray())
            for ((n, d) in files) zip.putDeflated(n, d)
        }
        return f
    }

    /** ZIP con una entrada comprimida que declara [declared] bytes pero se descomprime en [realBytes] (bomba que miente). */
    private fun lyingBomb(file: File, realBytes: Long, declared: Long): File {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var count = 0
        fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
        fun le32(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) o.write((v shr (8 * i)).toInt() and 0xff) }
        fun add(name: String, data: ByteArray, method: Int, size: Long, crc: Long) {
            val n = name.toByteArray()
            val offset = out.size().toLong()
            le32(out, 0x04034b50); le16(out, 20); le16(out, 0); le16(out, method); le16(out, 0); le16(out, 0x21)
            le32(out, crc); le32(out, data.size.toLong()); le32(out, size); le16(out, n.size); le16(out, 0)
            out.write(n); out.write(data)
            le32(central, 0x02014b50); le16(central, 20); le16(central, 20); le16(central, 0); le16(central, method); le16(central, 0); le16(central, 0x21)
            le32(central, crc); le32(central, data.size.toLong()); le32(central, size); le16(central, n.size)
            le16(central, 0); le16(central, 0); le16(central, 0); le16(central, 0); le32(central, 0); le32(central, offset)
            central.write(n)
            count++
        }
        fun crcOf(b: ByteArray) = CRC32().apply { update(b) }.value
        fun deflate(b: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            DeflaterOutputStream(o, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(b) }
            return o.toByteArray()
        }
        add("mimetype", MIME, 0, MIME.size.toLong(), crcOf(MIME))
        for ((n, d) in listOf("META-INF/container.xml" to CONTAINER.toByteArray()) + basic("<p>bomba</p>")) add(n, deflate(d), 8, d.size.toLong(), crcOf(d))
        val bomb = ByteArrayOutputStream()
        val crc = CRC32()
        DeflaterOutputStream(bomb, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { z ->
            val chunk = ByteArray(1 shl 20)
            repeat((realBytes shr 20).toInt()) { z.write(chunk); crc.update(chunk) }
        }
        add("OEBPS/relleno.bin", bomb.toByteArray(), 8, declared, crc.value)
        val cdOffset = out.size().toLong()
        out.write(central.toByteArray())
        le32(out, 0x06054b50); le16(out, 0); le16(out, 0); le16(out, count); le16(out, count)
        le32(out, central.size().toLong()); le32(out, cdOffset); le16(out, 0)
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun ZipOutputStream.putDeflated(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
    }

    private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; this.crc = crc.value })
        write(bytes); closeEntry()
    }

    private companion object {
        val MIME = "application/epub+zip".toByteArray()

        const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

        const val NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Indice</title></head>
<body><nav epub:type="toc"><ol><li><a href="c1.xhtml">Uno</a></li></ol></nav></body></html>"""

        /** Entidades encadenadas: lol9 = 10^9 veces "lol". */
        val LOL = (1..9).joinToString("") { i -> """<!ENTITY lol$i "${"&lol${i - 1};".repeat(10)}">""" }

        /** Cuenta los `<script>` que no son de Readium y los atributos `on…` en todo el DOM: "scripts,on". */
        const val DOM_CHECK = """(function(){var b=document.querySelectorAll('script:not([src*=readium_assets])').length;var o=0;
document.querySelectorAll('*').forEach(function(e){for(var i=0;i<e.attributes.length;i++){if(e.attributes[i].localName.toLowerCase().indexOf('on')===0)o++;}});
return b+','+o;})()"""

        fun opf(items: List<Triple<String, String, String>>, title: String = "Prueba"): String {
            val man = items.joinToString("\n") { (i, h, t) -> """<item id="$i" href="$h" media-type="$t"/>""" }
            return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">urn:uuid:00000000-0000-4000-8000-000000000000</dc:identifier>
<dc:title>$title</dc:title><dc:language>es</dc:language>
<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
$man
</manifest>
<spine><itemref idref="c1"/></spine>
</package>"""
        }

        fun xhtml(body: String, head: String = "") = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xmlns:svg="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
<head><title>C1</title>$head</head>
<body>$body</body></html>"""

        const val SCRIPTS = """
<script>document.title='JS_DEL_LIBRO_EJECUTADO';fetch('SPY/fetch')</script>
<p id="p1" onclick="document.title='ONCLICK'">parrafo</p>
<img src="no-existe.png" onerror="document.title='ONERROR'" alt="x"/>
<svg:svg width="10" height="10"><svg:script>document.title='SVG_SCRIPT'</svg:script>
  <svg:a xlink:href="javascript:document.title='SVG_JS'"><svg:text y="9">a</svg:text></svg:a>
  <svg:a href="#"><svg:set attributeName="href" to="javascript:document.title='SET_JS'"/><svg:text y="9">b</svg:text></svg:a>
  <svg:animate attributeName="xlink:href" values="javascript:alert(1)"/>
  <svg:foreignObject width="10" height="10"><div xmlns="http://www.w3.org/1999/xhtml"><script>document.title='FO_SCRIPT'</script></div></svg:foreignObject>
  <svg:handler type="application/ecmascript">document.title='HANDLER'</svg:handler>
</svg:svg>
<h:script xmlns:h="http://www.w3.org/1999/xhtml">document.title='H_SCRIPT'</h:script>
<noscript><p title="&lt;/noscript&gt;&lt;img src=x onerror=alert(1)&gt;">n</p></noscript>
<math xmlns="http://www.w3.org/1998/Math/MathML" href="javascript:alert(1)"><mi>x</mi></math>
<p><a href="data:text/html,&lt;script&gt;alert(1)&lt;/script&gt;">data html</a></p>
<object data="SPY/object"></object><embed src="SPY/embed"/>
"""

        const val NETWORK = """
<p>red</p>
<img src="SPY/img.png" alt="a"/><img src="SPYH/img-http.png" alt="b"/>
<img srcset="SPY/srcset.png 2x" src="x.png" alt="c"/>
<picture><source srcset="SPY/picture.png"/><img src="x.png" alt="d"/></picture>
<video src="SPY/video.mp4" poster="SPY/poster.png"></video>
<audio src="SPY/audio.mp3"></audio>
<video><source src="SPY/source.mp4"/><track src="SPY/track.vtt"/></video>
<svg:svg width="10" height="10"><svg:image xlink:href="SPY/svgimage.png" width="10" height="10"/><svg:use href="SPY/use.svg#a"/></svg:svg>
<div style="background:url('SPY/inline-bg.png')">bg</div>
<p><a href="SPY/ping-target" ping="SPY/ping">ping</a></p>
<link rel="preconnect" href="SPY"/>
<link rel="dns-prefetch" href="//spy-dns-prefetch.invalid"/>
<link rel="prefetch" href="SPY/prefetch"/><link rel="preload" as="image" href="SPY/preload.png"/>
<link rel="prerender" href="SPY/prerender"/><link rel="modulepreload" href="SPY/mod.js"/>
<link rel="stylesheet" href="SPY/remote.css"/><link rel="icon" href="SPY/favicon.ico"/>
<form action="SPY/form" method="post"><input name="q" value="texto"/></form>
<iframe src="file:///data/data/PKG/databases/lector.db"></iframe>
<iframe src="content://PKG.androidx-startup/x"></iframe>
<img src="file:///data/data/PKG/files/books/" alt="f"/>
<img src="content://media/external/images/media/1" alt="g"/>
<base href="SPY/base/"/>
"""

        const val LINKS = """
<p><a id="js" href="javascript:document.title='LINK_JS'">javascript</a></p>
<p><a id="jsws" href=" java&#x09;script:document.title='LINK_JS2'">javascript con tab</a></p>
<p><a id="ext" href="https://example.org/ruta?x=1">externo https</a></p>
<p><a id="exth" href="http://example.org/">externo http</a></p>
<p><a id="mail" href="mailto:alguien@example.org?subject=hola">mailto</a></p>
<p><a id="intent" href="intent://scan/#Intent;scheme=zxing;package=com.example;end">intent</a></p>
<p><a id="file" href="file:///data/data/io.github.diegobr4nd.lectorbilingue/databases/lector.db">file</a></p>
<p><a id="content" href="content://io.github.diegobr4nd.lectorbilingue.androidx-startup/x">content</a></p>
<p><a id="tel" href="tel:+34600000000">tel</a></p>
<p><a id="market" href="market://details?id=com.example">market</a></p>
<p><a id="userinfo" href="https://example.org@127.0.0.1:8765/enganio">userinfo enganoso</a></p>
<p><a id="blank" href="https://example.org/blank" target="_blank">target blank</a></p>
"""

        const val MXSS = """<!DOCTYPE html><html><head><title>t</title></head><body>
<p>html</p>
<svg><style><img src=x onerror="document.title='MX1'"></style></svg>
<math><mtext><table><mglyph><style><img src=x onerror="document.title='MX2'"></style></mglyph></table></mtext></math>
<noscript><p title="</noscript><img src=x onerror=document.title='MX3'>"></p></noscript>
<form><math><mtext></form><form><mglyph><style></math><img src=x onerror="document.title='MX4'">
<textarea><script>document.title='MX5'</script></textarea>
<title><img src=x onerror="document.title='MX6'"></title>
<!--><img src=x onerror="document.title='MX7'">-->
<template><script>document.title='MX8'</script></template>
<iframe srcdoc="&lt;script&gt;parent.document.title='MX9'&lt;/script&gt;"></iframe>
<select><style></select><img src=x onerror="document.title='MX10'"></style></select>
</body></html>"""
    }
}
