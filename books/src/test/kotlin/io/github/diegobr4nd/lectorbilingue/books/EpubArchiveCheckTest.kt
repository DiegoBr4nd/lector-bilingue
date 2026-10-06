package io.github.diegobr4nd.lectorbilingue.books

import org.junit.Rule
import java.io.File
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

    @Test fun `encryption xml enorme es DRM`() {
        val big = String(encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc")).replace("<enc:EncryptedData>", " ".repeat(1_150_000) + "<enc:EncryptedData>")
        assertEquals(ImportError.DRM, EpubArchiveCheck.check(TestEpub.build(tmp.root) { entry("META-INF/encryption.xml", big.toByteArray()) }))
    }
}
