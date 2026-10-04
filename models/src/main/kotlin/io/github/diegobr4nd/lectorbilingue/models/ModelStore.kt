package io.github.diegobr4nd.lectorbilingue.models

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Los modelos instalados, una carpeta por motor: `modelsDir/<engine>/<pair>/` (cada uno con su
 * `.installed.json`), con `<engine>` ∈ {opus, firefox}.
 *
 * ```
 * models/
 *   .tmp/<id>/              descargas en curso (una sola para todos los motores)
 *   opus/en-es/             modelo instalado
 *   opus/.old-en-es-<n>     resto de un reemplazo cortado
 *   firefox/es-en/
 * ```
 *
 * - La puerta única para los motores es [installedDir]: solo devuelve una carpeta con un
 *   `.installed.json` válido del mismo motor y par, y con todos sus `files` como archivos regulares.
 * - Se ignoran `.tmp/`, `.old-*`, motores desconocidos y cualquier carpeta cuyo nombre no sea un par
 *   válido o cuyo `.installed.json` falte, esté roto o sea de otro par o de otro motor.
 * - Nunca se siguen enlaces simbólicos: ni la carpeta del motor ni la del par pueden ser enlaces; un
 *   enlace no cuenta como modelo y al borrar se borra el enlace.
 * - `pair` se valida con `^[a-z]{2,3}-[a-z]{2,3}$` y `engine` con `^(opus|firefox)$`; si no →
 *   [IllegalArgumentException].
 * - Errores de archivos: [ModelFileException] con mensaje fijo, sin rutas.
 * - Formato viejo (2b), `modelsDir/<pair>/`: lo pasa al nuevo [migrateLegacyLayout], y [recover]
 *   recoloca sus `.old-<pair>-*` de primer nivel.
 *
 * Concurrencia: no es seguro llamar a [delete], [migrateLegacyLayout] ni a [recover] mientras se instala
 * un modelo. La app lo garantiza haciéndolo dentro de [Models.withModelsLock].
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

    /**
     * Modelos instalados y válidos (la misma regla que [installedDir]) de todos los motores, ordenados
     * por motor y luego por par. Nunca lanza: lo ilegible simplemente no aparece.
     */
    fun installed(): List<InstalledModel> = ModelFiles.ENGINES.flatMap { engine ->
        val engineDir = realEngineDir(engine) ?: return@flatMap emptyList()
        val names = try {
            listNames(engineDir.toPath())
        } catch (e: IOException) {
            return@flatMap emptyList()
        } catch (e: DirectoryIteratorException) {
            return@flatMap emptyList()
        }
        names.filter { PAIR.matches(it) }
            .sorted()
            .mapNotNull { checkedInstalled(File(engineDir, it).toPath(), engine, it) }
    }

    /**
     * La carpeta `modelsDir/<engine>/<pair>/` si contiene un modelo listo para cargar:
     * - la carpeta del motor y la del par son carpetas reales (no enlaces);
     * - su `.installed.json` es válido y dice el mismo [engine] y el mismo [pair];
     * - todos los `files` que lista existen como archivos regulares (no enlaces).
     *
     * Si algo no cuadra → null; nunca lanza por el estado del disco. Solo un [engine] o un [pair] con
     * formato inválido lanzan [IllegalArgumentException] (un error de programación, no del disco).
     */
    fun installedDir(engine: String, pair: String): File? {
        ModelFiles.requireEngine(engine)
        requirePair(pair)
        val engineDir = realEngineDir(engine) ?: return null
        val dir = File(engineDir, pair)
        return if (checkedInstalled(dir.toPath(), engine, pair) != null) dir else null
    }

    fun isInstalled(engine: String, pair: String): Boolean = installedDir(engine, pair) != null

    /** Bytes de los archivos normales dentro de `<engine>/<pair>/` (sin seguir enlaces); 0 si no existe. */
    fun sizeOnDisk(engine: String, pair: String): Long {
        ModelFiles.requireEngine(engine)
        requirePair(pair)
        val engineDir = realEngineDir(engine) ?: return 0L
        return try {
            sizeOf(File(engineDir, pair).toPath())
        } catch (e: IOException) {
            throw ModelFileException("error de archivos al medir el modelo")
        } catch (e: DirectoryIteratorException) {
            throw ModelFileException("error de archivos al medir el modelo")
        }
    }

    /**
     * Borra `<engine>/<pair>/` entera y también sus restos `<engine>/.old-<pair>-*` (para que [recover]
     * no resucite un modelo borrado). Lo heredado de la 2b de ese mismo motor (`<pair>/` y
     * `.old-<pair>-*` de primer nivel cuyo `.installed.json` dice [engine]) también se borra, por la
     * misma razón. Nunca toca otro motor. Si algo es un enlace, borra solo el enlace; si la carpeta del
     * motor es un enlace, no se entra en ella. Si no existe nada, no hace nada.
     *
     * Orden pensado para un corte a mitad: primero se quita el `.installed.json` de todas (así una carpeta
     * a medio borrar nunca cuenta como modelo válido ni llega al motor nativo), luego se borran las viejas
     * y al final `<pair>/`.
     */
    fun delete(engine: String, pair: String) {
        ModelFiles.requireEngine(engine)
        requirePair(pair)
        try {
            val engineDir = realEngineDir(engine)
            val olds = ArrayList<Path>()
            if (engineDir != null) {
                listNames(engineDir.toPath())
                    .filter { OLD.matchEntire(it)?.groupValues?.get(1) == pair }
                    .mapTo(olds) { File(engineDir, it).toPath() }
            }
            val legacy = listNames(modelsDir.toPath())
                .filter { it == pair || OLD.matchEntire(it)?.groupValues?.get(1) == pair }
                .map { File(modelsDir, it).toPath() }
                .filter { readInstalled(it, pair)?.engine == engine }
            val (legacyPair, legacyOlds) = legacy.partition { it.fileName.toString() == pair }
            val all = olds + legacyOlds + legacyPair + listOfNotNull(engineDir?.let { File(it, pair).toPath() })
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
     * Pasa el formato de la 2b al de la 2c: cada carpeta de primer nivel `modelsDir/<pair>/` con un
     * `.installed.json` válido de ese par se renombra (atómico) a `modelsDir/<engine>/<pair>/`, con el
     * `engine` de su JSON.
     *
     * - Lo que no tiene `.installed.json` válido (o es un enlace) se deja quieto.
     * - Si el destino ya existe, no se toca ni el legado ni el destino.
     * - Un renombre que falla deja ese par en su sitio y se sigue con los demás.
     * Idempotente y repetible tras un corte a mitad (cada par es un solo renombre); nunca lanza.
     */
    fun migrateLegacyLayout() {
        val names = try {
            listNames(modelsDir.toPath())
        } catch (e: IOException) {
            return
        } catch (e: DirectoryIteratorException) {
            return
        }
        for (pair in names.filter { PAIR.matches(it) }.sorted()) {
            val legacy = File(modelsDir, pair).toPath()
            val model = readInstalled(legacy, pair) ?: continue
            quietly { moveIntoEngine(legacy, model.engine, pair) }
        }
    }

    /**
     * Repara lo que deja un cierre brusco de la app. Idempotente; nunca lanza por errores de archivos
     * (se hace lo que se pueda y se sigue). Solo toca nombres conocidos dentro de [modelsDir].
     *
     * 1. En cada carpeta de motor `<engine>/` (si es una carpeta real), para cada par con restos
     *    `.old-<pair>-<n>`:
     *    - si `<engine>/<pair>/` no existe, se renombra a `<pair>` la vieja con mayor `n` que tenga un
     *      `.installed.json` válido de ese par y de ese motor (`n` es `System.nanoTime()`: dentro de un
     *      mismo arranque del teléfono, mayor = más reciente);
     *    - después se borran todas las viejas SOLO si `<pair>/` existe o si no había ninguna vieja válida.
     *      Si el renombre falló, se conservan todas (la válida es la única copia) y se reintenta en el
     *      siguiente [recover].
     * 2. Viejas de primer nivel heredadas de la 2b (`modelsDir/.old-<pair>-<n>`), salvo que
     *    `modelsDir/<pair>/` siga sin migrar (entonces se espera al siguiente arranque):
     *    - de la más nueva a la más vieja, cada una con `.installed.json` válido se mueve a
     *      `<engine>/<pair>/` (su motor según el JSON) si ese destino falta;
     *    - luego se borran todas las de ese par, salvo que algún movimiento haya fallado (entonces se
     *      conservan todas y se reintenta).
     * 3. En `.tmp/`: se borran las importaciones a medias (`import-*`) y todo lo que no sea carpeta.
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
        for (engine in ModelFiles.ENGINES) {
            val engineDir = realEngineDir(engine) ?: continue
            val engineNames = try {
                listNames(engineDir.toPath())
            } catch (e: IOException) {
                continue
            } catch (e: DirectoryIteratorException) {
                continue
            }
            recoverOld(engineDir, engine, engineNames)
        }
        recoverLegacyOld(names)
        if (ModelFiles.TMP_DIR in names) recoverTmp(catalogIds)
    }

    private fun recoverOld(engineDir: File, engine: String, names: List<String>) {
        for ((pair, olds) in oldsByPair(names)) {
            val pairPath = File(engineDir, pair).toPath()
            var validBackup = false
            if (!ModelFiles.existsNoFollow(pairPath)) {
                val best = olds.firstOrNull { readInstalled(File(engineDir, it).toPath(), pair)?.engine == engine }
                if (best != null) {
                    validBackup = true
                    quietly { move(File(engineDir, best).toPath(), pairPath) }
                }
            }
            // Nunca se borra la única copia válida: solo si el par ya está en su sitio o no había copia.
            if (!ModelFiles.existsNoFollow(pairPath) && validBackup) continue
            for (name in olds) {
                val p = File(engineDir, name).toPath()
                if (ModelFiles.existsNoFollow(p)) quietly { ModelFiles.deleteTree(p) }
            }
        }
    }

    /** Viejas `.old-<pair>-*` de primer nivel que dejó la 2b: van a su motor o se borran. */
    private fun recoverLegacyOld(names: List<String>) {
        for ((pair, olds) in oldsByPair(names)) {
            // Un `<pair>/` de la 2b sin migrar es más nuevo que sus viejas: se espera a que migre.
            if (ModelFiles.existsNoFollow(File(modelsDir, pair).toPath())) continue
            var keep = false
            for (name in olds) {
                val p = File(modelsDir, name).toPath()
                val model = readInstalled(p, pair) ?: continue
                try {
                    moveIntoEngine(p, model.engine, pair)
                } catch (e: IOException) {
                    keep = true
                } catch (e: DirectoryIteratorException) {
                    keep = true
                }
            }
            // Nunca se borra la única copia válida: si un movimiento falló, se conservan todas.
            if (keep) continue
            for (name in olds) {
                val p = File(modelsDir, name).toPath()
                if (ModelFiles.existsNoFollow(p)) quietly { ModelFiles.deleteTree(p) }
            }
        }
    }

    /** Renombra [from] a `<engine>/<pair>` si ese destino no existe (la carpeta del motor queda real). */
    private fun moveIntoEngine(from: Path, engine: String, pair: String) {
        ModelFiles.requireEngine(engine)
        ModelFiles.ensureRealDir(File(modelsDir, engine))
        val engineDir = ModelFiles.child(modelsDir, engine)
        val target = File(engineDir, pair).toPath()
        if (!ModelFiles.existsNoFollow(target)) move(from, target)
    }

    /** Agrupa las `.old-<pair>-<n>` por par, cada grupo de la más nueva a la más vieja. */
    private fun oldsByPair(names: List<String>): Map<String, List<String>> {
        val byPair = HashMap<String, MutableList<Pair<Long, String>>>()
        for (name in names) {
            val m = OLD.matchEntire(name) ?: continue
            val n = m.groupValues[2].toLongOrNull() ?: continue
            byPair.getOrPut(m.groupValues[1]) { ArrayList() }.add(n to name)
        }
        return byPair.mapValues { (_, olds) ->
            olds.sortedWith(compareByDescending<Pair<Long, String>> { it.first }.thenByDescending { it.second })
                .map { it.second }
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

    /** `modelsDir/<engine>` si es una carpeta real (no enlace) justo dentro de [modelsDir]; si no, null. */
    private fun realEngineDir(engine: String): File? = try {
        ModelFiles.child(modelsDir, engine).takeIf { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        // `child` lo lanza si la carpeta del motor es un enlace que lleva fuera de modelsDir.
        null
    }

    /** [readInstalled] + mismo motor + todos los `files` presentes como archivos regulares. */
    private fun checkedInstalled(dir: Path, engine: String, pair: String): InstalledModel? {
        val model = readInstalled(dir, pair) ?: return null
        if (model.engine != engine) return null
        return model.takeIf { m ->
            m.files.all { name ->
                val f = dir.resolve(name)
                // Con tamaños (2c) un archivo truncado no cuenta; los `.installed.json` de la 2b no los traen.
                ModelFiles.isRegularFileNoFollow(f) &&
                    (m.sizes[name]?.let { runCatching { Files.size(f) == it }.getOrDefault(false) } ?: true)
            }
        }
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
