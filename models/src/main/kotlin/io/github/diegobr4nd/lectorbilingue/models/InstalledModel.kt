package io.github.diegobr4nd.lectorbilingue.models

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Un modelo instalado en `modelsDir/<engine>/<pair>/`, tal como lo describe su `.installed.json`.
 * [files] son solo los nombres de archivo (sin rutas).
 *
 * [toJson] y [fromJson] son el único sitio que conoce el formato de `.installed.json`.
 */
data class InstalledModel(
    val id: String,
    val pair: String,
    val engine: String,
    val modelVersion: String,
    val files: List<String>,
    /** Tamaño en bytes de cada archivo; vacío en los `.installed.json` de la 2b (entonces no se comprueba). */
    val sizes: Map<String, Long> = emptyMap(),
) {
    fun toJson(): String {
        val o = JSONObject()
            .put("id", id)
            .put("pair", pair)
            .put("engine", engine)
            .put("modelVersion", modelVersion)
            .put("files", JSONArray(files))
        if (sizes.isNotEmpty()) {
            val so = JSONObject()
            for (name in files) sizes[name]?.let { so.put(name, it) }
            o.put("sizes", so)
        }
        return o.toString()
    }

    companion object {
        private val ID = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")
        private val PAIR = Regex("^[a-z]{2,3}-[a-z]{2,3}$")
        private val ENGINES = setOf("opus", "firefox")
        private const val MAX_TEXT = 256
        private const val MAX_FILES = 32

        /**
         * Lee un `.installed.json`. Valida tipos y formatos (mismas reglas que el catálogo) porque
         * los nombres se usan para construir rutas. Si algo falla → [IllegalArgumentException]
         * con el nombre del campo, nunca el valor.
         */
        fun fromJson(text: String): InstalledModel {
            val o = try {
                val tokener = JSONTokener(text)
                val value = tokener.nextValue()
                if (tokener.nextClean() != 0.toChar()) throw IllegalArgumentException(".installed.json no válido")
                value as? JSONObject ?: throw IllegalArgumentException(".installed.json no válido")
            } catch (e: JSONException) {
                throw IllegalArgumentException(".installed.json no válido")
            } catch (e: StackOverflowError) {
                throw IllegalArgumentException(".installed.json no válido")
            }
            val id = string(o, "id")
            require(ID.matches(id) && !id.contains("..")) { ".installed.json: id" }
            val pair = string(o, "pair")
            require(PAIR.matches(pair)) { ".installed.json: pair" }
            val engine = string(o, "engine")
            require(engine in ENGINES) { ".installed.json: engine" }
            val modelVersion = string(o, "modelVersion")
            require(modelVersion.isNotEmpty() && modelVersion.length <= MAX_TEXT) { ".installed.json: modelVersion" }
            val arr = o.opt("files") as? JSONArray ?: throw IllegalArgumentException(".installed.json: files")
            require(arr.length() in 1..MAX_FILES) { ".installed.json: files" }
            val files = (0 until arr.length()).map { i ->
                val name = arr.opt(i) as? String ?: throw IllegalArgumentException(".installed.json: files")
                require(ModelFiles.isValidFileName(name)) { ".installed.json: files" }
                name
            }
            require(files.toSet().size == files.size) { ".installed.json: files" }
            val sizes = HashMap<String, Long>()
            if (o.has("sizes")) {
                val so = o.opt("sizes") as? JSONObject ?: throw IllegalArgumentException(".installed.json: sizes")
                require(so.length() == files.size) { ".installed.json: sizes" }
                for (name in files) {
                    val v = so.opt(name)
                    require(v is Int || v is Long) { ".installed.json: sizes" }
                    val n = (v as Number).toLong()
                    require(n >= 0) { ".installed.json: sizes" }
                    sizes[name] = n
                }
            }
            return InstalledModel(id, pair, engine, modelVersion, files, sizes)
        }

        private fun string(o: JSONObject, key: String): String =
            o.opt(key) as? String ?: throw IllegalArgumentException(".installed.json: $key")
    }
}
