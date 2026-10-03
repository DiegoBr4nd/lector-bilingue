package io.github.diegobr4nd.lectorbilingue.models

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Un archivo descargado o instalado no coincide con el catálogo. El mensaje nombra el archivo, nunca hashes ni rutas. */
class IntegrityException(message: String) : Exception(message)

/**
 * Error de archivos del almacén de modelos (disco lleno, permisos, carpeta rara…). Siempre con un
 * mensaje fijo y sin causa: los de java.io/nio llevan rutas internas. Es una [IOException] para que
 * quien llame la trate como tal, pero se distingue de los errores de red (que sí se reintentan).
 */
class ModelFileException(message: String) : IOException(message)

/** Utilidades compartidas por [ModelDownloader] y [ModelInstaller] (rutas seguras, SHA-256, borrado). */
internal object ModelFiles {
    /** Carpeta de descargas en curso dentro de `modelsDir`. */
    const val TMP_DIR = ".tmp"
    const val PART_SUFFIX = ".part"
    const val INSTALLED_JSON = ".installed.json"
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * `dir/name`, comprobando que la ruta canónica queda justo dentro de [dir] (segunda defensa:
     * el parser ya rechaza `..`, `/` y nombres raros). Si no → [IllegalArgumentException].
     */
    fun child(dir: File, name: String): File {
        requireSimpleName(name)
        val f = File(dir, name)
        require(f.canonicalFile.parentFile == dir.canonicalFile) { "nombre no válido" }
        return f
    }

    private val FILE_NAME = Regex("^[A-Za-z0-9._-]{1,128}$")

    /**
     * Regla única para nombres de archivo de modelo (catálogo, `.installed.json`, entradas de zip):
     * `^[A-Za-z0-9._-]{1,128}$`, sin `..` y sin `.` inicial (evita `.`, `..` y `.installed.json`).
     */
    fun isValidFileName(name: String): Boolean =
        FILE_NAME.matches(name) && !name.contains("..") && !name.startsWith(".")

    /** Un solo componente de ruta: sin separadores, sin NUL, y distinto de `.` y `..`. */
    fun requireSimpleName(name: String) {
        require(
            name.isNotEmpty() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name && '\u0000' !in name,
        ) { "nombre no válido" }
    }

    /**
     * Deja [dir] como carpeta real: si es un archivo o un enlace simbólico, borra ese archivo/enlace
     * (nunca el destino del enlace) y crea la carpeta; si no existe, la crea. El padre debe existir.
     */
    fun ensureRealDir(dir: File) {
        val p = dir.toPath()
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return
        if (existsNoFollow(p)) Files.delete(p)
        Files.createDirectory(p)
    }

    /** Nombres únicos y sin choques con los `.part` de otro archivo del mismo modelo. */
    fun checkNames(model: CatalogModel) {
        val names = model.files.map { it.name }
        require(names.toSet().size == names.size) { "nombres de archivo repetidos" }
        require(names.none { it + PART_SUFFIX in names }) { "un nombre de archivo choca con otro .part" }
        require(names.none { it == INSTALLED_JSON }) { "nombre de archivo reservado" }
    }

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /**
     * Pasa todo el contenido de [file] por [digest]. Cualquier fallo de lectura del disco sale como
     * [ModelFileException] (mensaje fijo, sin causa ni ruta) para que se trate como error de archivos
     * y no como error de red que se reintenta. [open] existe solo para poder simular un disco que falla en las pruebas.
     */
    fun hashInto(file: File, digest: MessageDigest, open: (File) -> InputStream = { FileInputStream(it) }) {
        try {
            open(file).use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
        } catch (e: IOException) {
            throw ModelFileException("error de archivos al leer el modelo")
        }
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** ¿Es [file] un archivo normal (no enlace) con el tamaño y el SHA-256 del catálogo? */
    fun matches(file: File, expected: ModelFile): Boolean {
        val p = file.toPath()
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.size(p) != expected.size) return false
        val d = newDigest()
        hashInto(file, d)
        return hex(d.digest()) == expected.sha256
    }

    fun isRegularFileNoFollow(p: Path) = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)

    fun existsNoFollow(p: Path) = Files.exists(p, LinkOption.NOFOLLOW_LINKS)

    /** Borra [p] y, si es carpeta, su contenido. Nunca sigue enlaces simbólicos: un enlace se borra a sí mismo. */
    fun deleteTree(p: Path) {
        if (!existsNoFollow(p)) return
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(p).use { children -> for (c in children) deleteTree(c) }
        }
        Files.delete(p)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
