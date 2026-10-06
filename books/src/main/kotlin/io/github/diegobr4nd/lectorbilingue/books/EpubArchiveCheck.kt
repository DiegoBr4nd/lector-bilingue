package io.github.diegobr4nd.lectorbilingue.books

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Revisa un EPUB (un ZIP) antes de dárselo a Readium: zip slip, bomba ZIP, demasiadas entradas, estructura y DRM.
 * Lee el contenido real contando bytes: el tamaño declarado en el ZIP puede mentir.
 *
 * Dos topes de tamaño descomprimido: el total ([ImportLimits.MAX_UNCOMPRESSED_BYTES]) y uno POR ENTRADA
 * ([ImportLimits.MAX_ENTRY_BYTES], 32 MB). El de entrada existe porque Readium lee cada recurso entero en
 * memoria antes de sanearlo: un capítulo de 350 MB cabe en el total pero cierra la app al abrirlo.
 * Vale para TODAS las entradas, no solo las de marcado: el tipo con que se sirve un recurso lo decide el
 * manifiesto del libro, no la extensión, así que un "c1.jpg" puede servirse (y leerse entero) como XHTML.
 * 32 MB está muy por encima de cualquier capítulo, imagen o fuente reales (suelen pesar menos de 2 MB).
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
        if (entries.any { it.size > ImportLimits.MAX_ENTRY_BYTES }) return ImportError.UNSAFE_ARCHIVE

        var total = 0L
        val buffer = ByteArray(64 * 1024)
        for (entry in entries) {
            if (entry.isDirectory) continue
            var entryBytes = 0L
            zip.getInputStream(entry).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    entryBytes += n
                    if (total > ImportLimits.MAX_UNCOMPRESSED_BYTES) return ImportError.UNSAFE_ARCHIVE
                    if (entryBytes > ImportLimits.MAX_ENTRY_BYTES) return ImportError.UNSAFE_ARCHIVE
                }
            }
        }

        val mimetype = zip.getEntry("mimetype") ?: return ImportError.NOT_EPUB
        val mimeText = zip.getInputStream(mimetype).use { readUpTo(it, 64) }.toString(Charsets.US_ASCII).trim()
        if (mimeText != MIMETYPE) return ImportError.NOT_EPUB
        if (zip.getEntry("META-INF/container.xml") == null) return ImportError.NOT_EPUB

        if (zip.getEntry("META-INF/license.lcpl") != null || zip.getEntry("META-INF/rights.xml") != null) return ImportError.DRM
        zip.getEntry("META-INF/encryption.xml")?.let { enc ->
            // Si es enorme no se revisa un pedazo: se trata como DRM.
            if (enc.size > ENCRYPTION_MAX_BYTES) return ImportError.DRM
            val bytes = zip.getInputStream(enc).use { readUpTo(it, ENCRYPTION_MAX_BYTES.toInt() + 1) }
            if (bytes.size > ENCRYPTION_MAX_BYTES) return ImportError.DRM
            val text = bytes.toString(Charsets.UTF_8)
            if (ALGORITHM.findAll(text).any { it.groupValues[1].trim() !in FONT_OBFUSCATION }) return ImportError.DRM
        }
        return null
    }

    /** Lee hasta [max] bytes. Sustituye a readNBytes, que en Android solo existe desde la API 33. */
    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var left = max
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    internal fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || '\u0000' in name || '\\' in name) return false
        if (name.startsWith("/")) return false
        if (name.length >= 2 && name[1] == ':') return false
        return name.split('/').none { it == ".." }
    }
}
