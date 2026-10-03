package io.github.diegobr4nd.lectorbilingue.models

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.max

/**
 * Descarga los archivos de un modelo a `modelsDir/.tmp/<id>/`, reanudando lo que quedó a medias
 * y verificando tamaño y SHA-256 de cada uno contra el catálogo firmado.
 *
 * Por archivo: se baja a `<name>.part` y, solo si coincide, se renombra a `<name>`.
 * - Un `<name>` ya presente se vuelve a hashear antes de fiarse de él; si no coincide, se borra.
 * - Un `<name>.part` presente se hashea primero (el [HttpFetcher] solo entrega los bytes nuevos):
 *   si ya está completo y correcto se renombra sin usar la red; si es más grande que el tamaño
 *   del catálogo o está completo pero mal, se borra y se empieza de cero; si es más pequeño, se reanuda.
 * - Si al terminar no coincide → se borra el `.part` y se lanza [IntegrityException].
 * Al final la carpeta contiene solo los archivos del modelo (se borra cualquier otra cosa).
 *
 * Progreso: `onProgress(bajado, total)`, donde `bajado` incluye lo que ya estaba en disco.
 * Siempre es monótono y termina en `total` ([CatalogModel.totalSize]); si el servidor obliga a
 * reiniciar un archivo desde cero (ignora el `Range`), el valor se queda quieto hasta recuperarse.
 *
 * Contención: `modelsDir/.tmp` y `.tmp/<id>` deben ser carpetas reales; si son un archivo o un
 * enlace simbólico se borra ese archivo/enlace (nunca su destino) y se crea la carpeta.
 *
 * Errores: los de red ([IOException] de [HttpFetcher], [NetworkPolicyException]) se propagan tal
 * cual y dejan el `.part` para reanudar en el siguiente intento. Los de archivos (que en java.io/nio
 * llevan rutas) salen como [ModelFileException] con mensaje fijo y sin causa. No es seguro llamar dos veces a la vez con el mismo modelo.
 */
class ModelDownloader(private val modelsDir: File, private val fetcher: HttpFetcher) {

    /** Baja (reanudando) y verifica cada archivo en modelsDir/.tmp/<id>/. Devuelve esa carpeta verificada. */
    fun download(model: CatalogModel, onProgress: (downloaded: Long, total: Long) -> Unit): File {
        // Se validan todos los nombres antes de tocar el disco o la red.
        ModelFiles.checkNames(model)
        ModelFiles.requireSimpleName(model.id)
        model.files.forEach { ModelFiles.requireSimpleName(it.name) }
        try {
            return downloadChecked(model, onProgress)
        } catch (e: FileSystemException) {
            throw fileError()
        } catch (e: FileNotFoundException) {
            throw fileError()
        } catch (e: DirectoryIteratorException) {
            throw fileError()
        }
    }

    private fun downloadChecked(model: CatalogModel, onProgress: (Long, Long) -> Unit): File {
        val tmpRoot = File(modelsDir, ModelFiles.TMP_DIR)
        Files.createDirectories(modelsDir.toPath())
        ModelFiles.ensureRealDir(tmpRoot)
        ModelFiles.ensureRealDir(File(tmpRoot, model.id))
        val staging = ModelFiles.child(tmpRoot, model.id)
        val targets = model.files.map { f ->
            Triple(f, ModelFiles.child(staging, f.name), ModelFiles.child(staging, f.name + ModelFiles.PART_SUFFIX))
        }

        val finalNames = model.files.map { it.name }.toSet()
        removeStray(staging, finalNames + finalNames.map { it + ModelFiles.PART_SUFFIX })

        val total = model.totalSize
        // Lo emitido nunca baja: si el servidor obliga a reiniciar un archivo, la barra se queda quieta
        // hasta que lo nuevo supera lo ya mostrado.
        var lastEmitted = 0L
        var completed = 0L
        for ((file, target, part) in targets) {
            val base = completed
            fetchOne(file, target, part) { current ->
                lastEmitted = max(lastEmitted, base + current)
                onProgress(lastEmitted, total)
            }
            completed += file.size
        }
        removeStray(staging, finalNames)
        return staging
    }

    /**
     * Las excepciones de java.io/java.nio llevan rutas internas en el mensaje: se sustituyen por un
     * mensaje fijo y SIN causa. Los errores de red de [HttpFetcher] no pasan por aquí (ya van sin rutas).
     */
    private fun fileError() = ModelFileException("error de archivos al descargar el modelo")

    private fun fetchOne(file: ModelFile, target: File, part: File, progress: (Long) -> Unit) {
        val targetPath = target.toPath()
        val partPath = part.toPath()

        if (ModelFiles.existsNoFollow(targetPath)) {
            if (ModelFiles.matches(target, file)) {
                ModelFiles.deleteTree(partPath)
                progress(file.size)
                return
            }
            ModelFiles.deleteTree(targetPath)
        }

        val digest = ModelFiles.newDigest()
        var have = 0L
        if (ModelFiles.existsNoFollow(partPath)) {
            if (!ModelFiles.isRegularFileNoFollow(partPath) || Files.size(partPath) > file.size) {
                ModelFiles.deleteTree(partPath)
            } else {
                ModelFiles.hashInto(part, digest)
                have = Files.size(partPath)
                if (have == file.size) {
                    if (ModelFiles.hex(digest.digest()) == file.sha256) {
                        promote(partPath, target)
                        progress(file.size)
                        return
                    }
                    // Completo pero corrupto: no se puede reanudar, se baja de nuevo.
                    ModelFiles.deleteTree(partPath)
                    digest.reset()
                    have = 0L
                }
            }
        }
        progress(have)

        fetcher.downloadTo(
            url = file.url,
            target = part,
            maxBytes = file.size,
            onBytes = { buf, n ->
                digest.update(buf, 0, n)
                have += n
                progress(have)
            },
            onReset = {
                digest.reset()
                have = 0L
                progress(0L)
            },
        )

        if (!ModelFiles.isRegularFileNoFollow(partPath) ||
            Files.size(partPath) != file.size ||
            ModelFiles.hex(digest.digest()) != file.sha256
        ) {
            ModelFiles.deleteTree(partPath)
            throw IntegrityException("el archivo ${file.name} no coincide con el catálogo")
        }
        promote(partPath, target)
    }

    private fun promote(part: Path, target: File) {
        Files.move(part, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Borra de [dir] todo lo que no esté en [keep] (sin seguir enlaces). */
    private fun removeStray(dir: File, keep: Set<String>) {
        Files.newDirectoryStream(dir.toPath()).use { children ->
            for (c in children.toList()) {
                val name = c.fileName.toString()
                if (name !in keep || !ModelFiles.isRegularFileNoFollow(c)) ModelFiles.deleteTree(c)
            }
        }
    }
}
