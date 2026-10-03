package io.github.diegobr4nd.lectorbilingue.models

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Los modelos instalados en `modelsDir/<pair>/` (cada uno con su `.installed.json`).
 *
 * - Se ignoran `.tmp/` (descargas en curso), `.old-*` (restos de un reemplazo) y cualquier carpeta cuyo
 *   nombre no sea un par válido o cuyo `.installed.json` falte, esté roto o sea de otro par.
 * - Nunca se siguen enlaces simbólicos: un enlace no cuenta como modelo y al borrar se borra el enlace.
 * - Los `pair` que llegan de fuera se validan con `^[a-z]{2,3}-[a-z]{2,3}$`; si no → [IllegalArgumentException].
 * - Errores de archivos: [ModelFileException] con mensaje fijo, sin rutas.
 *
 * Concurrencia: no es seguro llamar a [delete] ni a [recover] mientras se instala un modelo. La app lo
 * garantiza haciéndolo dentro de [Models.withModelsLock].
 */
class ModelStore internal constructor(
    private val modelsDir: File,
    private val deleteTree: (Path) -> Unit = ModelFiles::deleteTree,
    private val move: (from: Path, to: Path) -> Unit,
) {
    constructor(modelsDir: File) : this(modelsDir, move = ::atomicMove)

    companion object {
        /** Tope de `.installed.json` (32 nombres de ≤ 128 caracteres caben de sobra). */
        const val MAX_INSTALLED_JSON = 64 * 1024

        private val PAIR = Regex("^[a-z]{2,3}-[a-z]{2,3}$")

        /** `.old-<pair>-<nanoTime>`, tal como lo crea [ModelInstaller] (nanoTime puede ser negativo). */
        private val OLD = Regex("^\\.old-([a-z]{2,3}-[a-z]{2,3})-(-?[0-9]{1,19})$")
        private const val IMPORT_PREFIX = "import-"

        private fun atomicMove(from: Path, to: Path) {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Modelos instalados y válidos, ordenados por par. Nunca lanza: lo ilegible simplemente no aparece. */
    fun installed(): List<InstalledModel> {
        val names = try {
            listNames(modelsDir.toPath())
        } catch (e: IOException) {
            return emptyList()
        } catch (e: DirectoryIteratorException) {
            return emptyList()
        }
        return names.filter { PAIR.matches(it) }
            .sorted()
            .mapNotNull { readInstalled(File(modelsDir, it).toPath(), it) }
    }

    fun isInstalled(pair: String): Boolean {
        requirePair(pair)
        return readInstalled(File(modelsDir, pair).toPath(), pair) != null
    }

    /** Bytes de los archivos normales dentro de `<pair>/` (sin seguir enlaces); 0 si no existe. */
    fun sizeOnDisk(pair: String): Long {
        requirePair(pair)
        return try {
            sizeOf(File(modelsDir, pair).toPath())
        } catch (e: IOException) {
            throw ModelFileException("error de archivos al medir el modelo")
        } catch (e: DirectoryIteratorException) {
            throw ModelFileException("error de archivos al medir el modelo")
        }
    }

    /**
     * Borra `<pair>/` entera y también sus restos `.old-<pair>-*` (para que [recover] no resucite un
     * modelo borrado). Si es un enlace, borra solo el enlace. Si no existe, no hace nada.
     *
     * Orden pensado para un corte a mitad: primero se quita el `.installed.json` de todas (así una carpeta
     * a medio borrar nunca cuenta como modelo válido ni llega al motor nativo), luego se borran las viejas
     * y al final `<pair>/`.
     */
    fun delete(pair: String) {
        requirePair(pair)
        try {
            val olds = listNames(modelsDir.toPath())
                .filter { OLD.matchEntire(it)?.groupValues?.get(1) == pair }
                .map { File(modelsDir, it).toPath() }
            // listOf: Path es Iterable, así que `olds + path` sumaría sus trozos y no la ruta.
            val all = olds + listOf(File(modelsDir, pair).toPath())
            for (dir in all) {
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(dir.resolve(ModelFiles.INSTALLED_JSON))
            }
            for (dir in all) deleteTree(dir)
        } catch (e: IOException) {
            throw ModelFileException("error de archivos al borrar el modelo")
        } catch (e: DirectoryIteratorException) {
            throw ModelFileException("error de archivos al borrar el modelo")
        }
    }

    /**
     * Repara lo que deja un cierre brusco de la app. Idempotente; nunca lanza por errores de archivos
     * (se hace lo que se pueda y se sigue). Solo toca nombres conocidos dentro de [modelsDir].
     *
     * 1. Para cada par con restos `.old-<pair>-<n>`:
     *    - si `<pair>/` no existe, se renombra a `<pair>` la vieja con mayor `n` que tenga un
     *      `.installed.json` válido de ese par (`n` es `System.nanoTime()`: dentro de un mismo arranque
     *      del teléfono, mayor = más reciente);
     *    - después se borran todas las viejas SOLO si `<pair>/` existe o si no había ninguna vieja válida.
     *      Si el renombre falló, se conservan todas (la válida es la única copia) y se reintenta en el
     *      siguiente [recover].
     * 2. En `.tmp/`: se borran las importaciones a medias (`import-*`) y todo lo que no sea carpeta.
     *    Las carpetas `.tmp/<id>` (descargas reanudables) se conservan, salvo que [catalogIds] no sea
     *    null y no contenga ese id. Si `.tmp` es un archivo o un enlace, se borra (nunca su destino).
     */
    fun recover(catalogIds: Set<String>?) {
        val names = try {
            listNames(modelsDir.toPath())
        } catch (e: IOException) {
            return
        } catch (e: DirectoryIteratorException) {
            return
        }
        recoverOld(names)
        if (ModelFiles.TMP_DIR in names) recoverTmp(catalogIds)
    }

    private fun recoverOld(names: List<String>) {
        val byPair = HashMap<String, MutableList<Pair<Long, String>>>()
        for (name in names) {
            val m = OLD.matchEntire(name) ?: continue
            val n = m.groupValues[2].toLongOrNull() ?: continue
            byPair.getOrPut(m.groupValues[1]) { ArrayList() }.add(n to name)
        }
        for ((pair, olds) in byPair) {
            val pairPath = File(modelsDir, pair).toPath()
            var validBackup = false
            if (!ModelFiles.existsNoFollow(pairPath)) {
                val best = olds.sortedWith(compareByDescending<Pair<Long, String>> { it.first }.thenByDescending { it.second })
                    .firstOrNull { readInstalled(File(modelsDir, it.second).toPath(), pair) != null }
                if (best != null) {
                    validBackup = true
                    quietly { move(File(modelsDir, best.second).toPath(), pairPath) }
                }
            }
            // Nunca se borra la única copia válida: solo si el par ya está en su sitio o no había copia.
            if (!ModelFiles.existsNoFollow(pairPath) && validBackup) continue
            for ((_, name) in olds) {
                val p = File(modelsDir, name).toPath()
                if (ModelFiles.existsNoFollow(p)) quietly { ModelFiles.deleteTree(p) }
            }
        }
    }

    private fun recoverTmp(catalogIds: Set<String>?) {
        val tmpRoot = File(modelsDir, ModelFiles.TMP_DIR)
        val tmpPath = tmpRoot.toPath()
        if (!Files.isDirectory(tmpPath, LinkOption.NOFOLLOW_LINKS)) {
            quietly { ModelFiles.deleteTree(tmpPath) }
            return
        }
        val children = try {
            listNames(tmpPath)
        } catch (e: IOException) {
            return
        } catch (e: DirectoryIteratorException) {
            return
        }
        for (name in children) {
            val p = File(tmpRoot, name).toPath()
            val stale = name.startsWith(IMPORT_PREFIX) ||
                !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ||
                (catalogIds != null && name !in catalogIds)
            if (stale) quietly { ModelFiles.deleteTree(p) }
        }
        // Solo si quedó vacía.
        tmpRoot.delete()
    }

    /** Lee `<dir>/.installed.json` sin seguir enlaces; null si algo no cuadra o no es de [pair]. */
    private fun readInstalled(dir: Path, pair: String): InstalledModel? = try {
        val json = dir.resolve(ModelFiles.INSTALLED_JSON)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) ||
            !ModelFiles.isRegularFileNoFollow(json) ||
            Files.size(json) > MAX_INSTALLED_JSON
        ) {
            null
        } else {
            InstalledModel.fromJson(String(Files.readAllBytes(json), Charsets.UTF_8)).takeIf { it.pair == pair }
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun sizeOf(p: Path): Long = when {
        ModelFiles.isRegularFileNoFollow(p) -> Files.size(p)
        Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ->
            Files.newDirectoryStream(p).use { children -> children.sumOf { sizeOf(it) } }
        else -> 0L
    }

    /** Nombres dentro de [dir] (vacío si no es una carpeta real). */
    private fun listNames(dir: Path): List<String> {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.newDirectoryStream(dir).use { children -> children.map { it.fileName.toString() } }
    }

    private fun requirePair(pair: String) {
        require(PAIR.matches(pair)) { "par no válido" }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            // Lo que no se pudo reparar se reintenta en el siguiente arranque.
        } catch (e: DirectoryIteratorException) {
            // Igual.
        }
    }
}
