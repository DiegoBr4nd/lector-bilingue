package io.github.diegobr4nd.lectorbilingue

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
    /** Cambia el contenido de un archivo base ("OEBPS/content.opf", "OEBPS/nav.xhtml" u "OEBPS/c1.xhtml") sin duplicar la entrada. */
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
                replaced["OEBPS/nav.xhtml"] ?: """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Índice</title></head><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">Capítulo uno</a></li></ol></nav></body></html>""".toByteArray(),
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
