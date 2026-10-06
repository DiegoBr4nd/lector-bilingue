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
