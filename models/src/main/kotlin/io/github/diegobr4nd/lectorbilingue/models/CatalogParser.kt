package io.github.diegobr4nd.lectorbilingue.models

import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Convierte los bytes de `catalog.json` en un [Catalog] validado (spec §3.1).
 *
 * Cualquier regla rota rechaza el catálogo entero con [CatalogException].
 * Los mensajes solo nombran el campo y su posición (p. ej. `models[0].files[1].sha256`),
 * nunca el valor recibido. Los campos desconocidos se ignoran (compatibilidad hacia adelante).
 *
 * Confianza: la firma minisign del catálogo se verifica ANTES de llamar a [parse]; ella es el
 * ancla de confianza, y esta validación es una segunda defensa. El `org.json` de Android es más
 * permisivo que la biblioteca usada en las pruebas JVM (acepta claves repetidas quedándose con
 * la última, y comentarios), así que el generador del catálogo nunca debe emitir claves repetidas.
 */
object CatalogParser {
    const val MAX_BYTES = 1 shl 20
    const val URL_PREFIX = "https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/"

    /** Largo máximo de los textos libres (`modelVersion`, `license`, `attribution`). */
    const val MAX_TEXT = 256
    const val MAX_MODELS = 200
    const val MAX_FILES = 32
    const val MAX_FILE_SIZE = 1L shl 31

    /** Fecha máxima de `generated`: un valor muy lejano bloquearía el antirretroceso para siempre. */
    private val MAX_GENERATED: Instant = Instant.parse("2101-01-01T00:00:00Z")

    /** El id se usa como carpeta (`.tmp/<id>/`): empieza por letra o dígito y no lleva `..`. */
    private val ID = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")
    private val PAIR = Regex("^[a-z]{2,3}-[a-z]{2,3}$")
    private val ENGINES = setOf("opus", "firefox")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val URL_REST = Regex("^([A-Za-z0-9._-]{1,128})/([A-Za-z0-9._-]{1,128})$")

    fun parse(bytes: ByteArray): Catalog {
        if (bytes.size > MAX_BYTES) throw CatalogException("catálogo inválido: tamaño mayor que 1 MiB")
        val root = try {
            val tokener = JSONTokener(String(bytes, Charsets.UTF_8))
            val value = tokener.nextValue()
            if (tokener.nextClean() != 0.toChar()) throw CatalogException("JSON inválido")
            value as? JSONObject ?: throw CatalogException("JSON inválido")
        } catch (e: JSONException) {
            throw CatalogException("JSON inválido")
        } catch (e: StackOverflowError) {
            // El org.json de Android no limita el anidamiento: "[[[[…" agota la pila.
            throw CatalogException("JSON inválido")
        }

        val version = int(root, "version", "version")
        if (version != 1) fail("version")

        val generatedText = string(root, "generated", "generated")
        if (!generatedText.endsWith("Z")) fail("generated")
        val generated = try {
            Instant.parse(generatedText)
        } catch (e: DateTimeParseException) {
            fail("generated")
        }
        if (!generated.isBefore(MAX_GENERATED)) fail("generated")

        val modelsJson = array(root, "models", "models")
        if (modelsJson.length() > MAX_MODELS) fail("models")
        val ids = HashSet<String>()
        val models = (0 until modelsJson.length()).map { i ->
            val model = parseModel(objectAt(modelsJson, i, "models[$i]"), "models[$i]")
            if (!ids.add(model.id)) fail("models[$i].id")
            model
        }
        return Catalog(version, generated, models)
    }

    private fun parseModel(o: JSONObject, at: String): CatalogModel {
        val id = string(o, "id", "$at.id")
        if (!ID.matches(id) || id.contains("..")) fail("$at.id")
        val pair = string(o, "pair", "$at.pair")
        if (!PAIR.matches(pair)) fail("$at.pair")
        val engine = string(o, "engine", "$at.engine")
        if (engine !in ENGINES) fail("$at.engine")
        val modelVersion = text(o, "modelVersion", "$at.modelVersion")
        val license = text(o, "license", "$at.license")
        val attribution = text(o, "attribution", "$at.attribution")

        val filesJson = array(o, "files", "$at.files")
        if (filesJson.length() !in 1..MAX_FILES) fail("$at.files")
        val names = HashSet<String>()
        val files = (0 until filesJson.length()).map { j ->
            val fAt = "$at.files[$j]"
            val file = parseFile(objectAt(filesJson, j, fAt), fAt)
            if (!names.add(file.name)) fail("$fAt.name")
            file
        }
        return CatalogModel(id, pair, engine, modelVersion, license, attribution, files)
    }

    private fun parseFile(o: JSONObject, at: String): ModelFile {
        val name = string(o, "name", "$at.name")
        // Regla compartida (sin `..`, sin punto inicial): ver ModelFiles.isValidFileName.
        if (!ModelFiles.isValidFileName(name)) fail("$at.name")
        val size = long(o, "size", "$at.size")
        if (size !in 1..MAX_FILE_SIZE) fail("$at.size")
        val sha256 = string(o, "sha256", "$at.sha256")
        if (!SHA256.matches(sha256)) fail("$at.sha256")
        val url = string(o, "url", "$at.url")
        if (!validUrl(url, name)) fail("$at.url")
        return ModelFile(name, size, sha256, url)
    }

    /** Prefijo exacto + `<etiqueta>/<name>`; sin `..`, `%`, `\`, `?`, `#` ni espacios (el regex no los admite). */
    private fun validUrl(url: String, name: String): Boolean {
        if (!url.startsWith(URL_PREFIX)) return false
        val rest = url.substring(URL_PREFIX.length)
        if (rest.contains("..")) return false
        val m = URL_REST.matchEntire(rest) ?: return false
        val tag = m.groupValues[1]
        return tag != "." && m.groupValues[2] == name
    }

    // ---- Lectura estricta: el tipo debe ser exactamente el esperado ----

    private fun string(o: JSONObject, key: String, at: String): String =
        o.opt(key) as? String ?: fail(at)

    private fun text(o: JSONObject, key: String, at: String): String {
        val s = string(o, key, at)
        if (s.isEmpty() || s.length > MAX_TEXT) fail(at)
        return s
    }

    private fun long(o: JSONObject, key: String, at: String): Long = when (val v = o.opt(key)) {
        is Int -> v.toLong()
        is Long -> v
        else -> fail(at)
    }

    private fun int(o: JSONObject, key: String, at: String): Int = when (val v = o.opt(key)) {
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else fail(at)
        else -> fail(at)
    }

    private fun array(o: JSONObject, key: String, at: String): JSONArray =
        o.opt(key) as? JSONArray ?: fail(at)

    private fun objectAt(a: JSONArray, i: Int, at: String): JSONObject =
        a.opt(i) as? JSONObject ?: fail(at)

    private fun fail(at: String): Nothing = throw CatalogException("catálogo inválido: $at")
}
