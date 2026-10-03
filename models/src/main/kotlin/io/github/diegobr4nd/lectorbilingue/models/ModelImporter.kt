package io.github.diegobr4nd.lectorbilingue.models

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Importa un modelo desde un `.zip` elegido por el usuario (spec §5).
 *
 * El zip se lee en flujo con [ZipInputStream] (nunca se copia entero). Cada entrada se escribe en
 * `modelsDir/.tmp/import-<aleatorio>/<nombre>` mientras se calcula su SHA-256. Defensas, en orden:
 * - Entradas de carpeta, nombres con `/` o `\`, absolutos, con `..`, con punto inicial o fuera de
 *   la regla del catálogo, y nombres repetidos → [IntegrityException] ("entrada no válida").
 * - Nombre que no está en ningún modelo del catálogo → [CatalogException].
 * - Más entradas que el modelo con más archivos → [IntegrityException].
 * - Tope por entrada = mayor `size` del catálogo para ese nombre; tope global = mayor
 *   [CatalogModel.totalSize]. Se cuentan los bytes realmente descomprimidos (no se confía en los
 *   tamaños declarados del zip) y se aborta ANTES de escribir el bloque que pasaría el tope.
 * Al terminar: candidatos = modelos cuyo conjunto de nombres es exactamente el del zip (ninguno →
 * [CatalogException]); gana el primero que coincide en tamaño y SHA-256 de todos sus archivos
 * (ninguno → [IntegrityException]). La carpeta pasa a `.tmp/<id>` (borrando restos de una descarga
 * anterior del mismo modelo) y la instala [ModelInstaller].
 *
 * Cualquier fallo: se borra lo creado (carpeta de importación y `.tmp/<id>`), nunca queda nada nuevo
 * en `modelsDir/<pair>`. Errores sin rutas ni causa: zip roto/truncado/vacío/cifrado →
 * `IOException("zip inválido")`; archivos → `IOException("error de archivos al importar el modelo")`.
 *
 * En Android 14+ (targetSdk ≥ 34) `ZipPathValidator` rechaza nombres con `..` o `/` inicial dentro de
 * `getNextEntry()`: en el teléfono esos casos salen como `IOException("zip inválido")` y no como
 * [IntegrityException]. Quien llame (la interfaz) debe tratar ambos como "zip rechazado".
 *
 * El [InputStream] es de quien llama y lo cierra él: aquí se envuelve en un flujo cuyo `close()` no
 * hace nada, y el [ZipInputStream] se cierra siempre al final (libera su `Inflater` nativo).
 * No es seguro importar a la vez que se descarga o instala el mismo modelo.
 */
class ModelImporter internal constructor(
    private val modelsDir: File,
    private val installer: ModelInstaller,
    private val newOutput: (Path) -> OutputStream,
) {
    constructor(modelsDir: File, installer: ModelInstaller) : this(modelsDir, installer, ::createNew)

    /** Lee el zip (sin copiarlo entero), identifica el modelo en [catalog], verifica e instala. */
    fun import(zip: InputStream, catalog: Catalog): InstalledModel {
        val limits = Limits.of(catalog)
        val tmpRoot = File(modelsDir, ModelFiles.TMP_DIR)
        val created = ArrayList<Path>()
        try {
            val (model, staging) = try {
                prepare(zip, catalog, limits, tmpRoot, created)
            } catch (e: InvalidZip) {
                throw IOException("zip inválido")
            } catch (e: IOException) {
                // Solo quedan errores de archivos (los de lectura del zip ya son InvalidZip): llevan rutas.
                throw fileError()
            } catch (e: DirectoryIteratorException) {
                throw fileError()
            }
            return installer.install(model, staging)
        } catch (e: Throwable) {
            cleanup(created, tmpRoot)
            throw e
        }
    }

    private fun prepare(
        zip: InputStream,
        catalog: Catalog,
        limits: Limits,
        tmpRoot: File,
        created: MutableList<Path>,
    ): Pair<CatalogModel, File> {
        Files.createDirectories(modelsDir.toPath())
        ModelFiles.ensureRealDir(tmpRoot)
        val work = ModelFiles.child(tmpRoot, "import-" + randomHex())
        Files.createDirectory(work.toPath())
        created.add(work.toPath())

        val zis = ZipInputStream(NonClosing(zip))
        val written = try {
            readEntries(zis, limits, work)
        } finally {
            closeQuietly(zis)
        }
        if (written.isEmpty()) throw InvalidZip()

        val model = identify(catalog, written)
        ModelFiles.checkNames(model)
        ModelFiles.requireSimpleName(model.id)
        ModelFiles.requireSimpleName(model.pair)

        // Una descarga a medias del mismo modelo se reemplaza por lo importado (ya verificado).
        val staging = ModelFiles.child(tmpRoot, model.id)
        ModelFiles.deleteTree(staging.toPath())
        Files.move(work.toPath(), staging.toPath(), StandardCopyOption.ATOMIC_MOVE)
        created.add(staging.toPath())
        return model to staging
    }

    /** Escribe cada entrada aceptada en [work]; devuelve nombre → (bytes, sha256). */
    private fun readEntries(zis: ZipInputStream, limits: Limits, work: File): Map<String, Written> {
        val written = LinkedHashMap<String, Written>()
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val entry = nextEntry(zis) ?: break
            val name = entry.name
            if (entry.isDirectory || !ModelFiles.isValidFileName(name) || name in written) {
                throw IntegrityException("el zip contiene una entrada no válida")
            }
            val max = limits.maxSizeByName[name] ?: throw notInCatalog()
            if (written.size + 1 > limits.maxEntries) {
                throw IntegrityException("el zip tiene más archivos que cualquier modelo del catálogo")
            }
            val digest = ModelFiles.newDigest()
            var size = 0L
            newOutput(ModelFiles.child(work, name).toPath()).use { out ->
                while (true) {
                    val n = read(zis, buf)
                    if (n < 0) break
                    if (size + n > max) throw IntegrityException("el archivo $name supera el tamaño del catálogo")
                    if (total + n > limits.maxTotal) {
                        throw IntegrityException("el zip es más grande que cualquier modelo del catálogo")
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    size += n
                    total += n
                }
            }
            written[name] = Written(size, ModelFiles.hex(digest.digest()))
        }
        return written
    }

    private fun identify(catalog: Catalog, written: Map<String, Written>): CatalogModel {
        val candidates = catalog.models.filter { m -> m.files.map { it.name }.toSet() == written.keys }
        if (candidates.isEmpty()) throw notInCatalog()
        candidates.firstOrNull { m -> m.files.all { f -> written[f.name] == Written(f.size, f.sha256) } }
            ?.let { return it }
        if (candidates.size == 1) {
            val bad = candidates[0].files.first { f -> written[f.name] != Written(f.size, f.sha256) }
            throw IntegrityException("el archivo ${bad.name} no coincide con el catálogo")
        }
        throw IntegrityException("el zip no coincide con ningún modelo del catálogo")
    }

    /** Borra lo creado por esta importación; los errores de limpieza se ignoran (llevarían rutas). */
    private fun cleanup(created: List<Path>, tmpRoot: File) {
        for (p in created.asReversed()) {
            try {
                ModelFiles.deleteTree(p)
            } catch (e: IOException) {
                // Nada más que hacer.
            } catch (e: DirectoryIteratorException) {
                // Nada más que hacer.
            }
        }
        // Solo si quedó vacía (puede haber otras descargas en curso).
        tmpRoot.delete()
    }

    private fun fileError() = ModelFileException("error de archivos al importar el modelo")

    private fun notInCatalog() = CatalogException("el zip no corresponde a ningún modelo del catálogo")

    /** Lectura del zip: cualquier fallo (corrupto, truncado, cifrado, nombre mal codificado) es "zip inválido". */
    private fun nextEntry(zis: ZipInputStream): ZipEntry? = try {
        zis.nextEntry
    } catch (e: IOException) {
        throw InvalidZip()
    } catch (e: IllegalArgumentException) {
        throw InvalidZip()
    }

    private fun read(zis: ZipInputStream, buf: ByteArray): Int = try {
        zis.read(buf)
    } catch (e: IOException) {
        throw InvalidZip()
    } catch (e: IllegalArgumentException) {
        throw InvalidZip()
    }

    /** Cerrar el zip no debe cerrar el flujo de quien llama. */
    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() {
            // El flujo es de quien llama.
        }
    }

    private fun closeQuietly(zis: ZipInputStream) {
        try {
            zis.close()
        } catch (e: IOException) {
            // Solo libera el Inflater; el flujo de abajo no se cierra.
        }
    }

    private data class Written(val size: Long, val sha256: String)

    /** Topes derivados del catálogo (solo nombres que pasan la regla compartida). */
    private class Limits(val maxSizeByName: Map<String, Long>, val maxEntries: Int, val maxTotal: Long) {
        companion object {
            fun of(catalog: Catalog): Limits {
                val bySize = HashMap<String, Long>()
                for (m in catalog.models) {
                    for (f in m.files) {
                        if (ModelFiles.isValidFileName(f.name)) bySize.merge(f.name, f.size, ::maxOf)
                    }
                }
                return Limits(
                    bySize,
                    catalog.models.maxOfOrNull { it.files.size } ?: 0,
                    catalog.models.maxOfOrNull { it.totalSize } ?: 0L,
                )
            }
        }
    }

    /** Señal interna: el zip no se pudo leer. No lleva mensaje ni causa. */
    private class InvalidZip : Exception(null, null, false, false)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        val random = SecureRandom()

        fun randomHex(): String = ModelFiles.hex(ByteArray(8).also { random.nextBytes(it) })

        fun createNew(p: Path): OutputStream =
            Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}
