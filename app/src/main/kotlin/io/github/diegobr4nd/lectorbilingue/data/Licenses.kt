package io.github.diegobr4nd.lectorbilingue.data

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/** Grupos de la pantalla «Licencias», en el orden en que se muestran. */
enum class LicenseGroup { APP, LIBRARIES, NATIVE, FONTS_ICONS }

/**
 * Una fila de «Licencias». [texts]: rutas en assets que se muestran en orden (aviso de copyright propio primero,
 * luego el texto de la licencia). [url]: código o sitio del componente.
 */
data class LicenseEntry(
    val id: String,
    val name: String,
    val license: String,
    val group: LicenseGroup,
    val texts: List<String>,
    val url: String?,
)

/**
 * El catálogo: [GENERATED] lo escribe AboutLibraries al compilar (librerías de Gradle); [EXTRA] lo escribimos a mano
 * (la app, el código nativo, fuentes, íconos). Todo vive en assets: funciona sin internet.
 */
object Licenses {
    const val GENERATED = "licencias/librerias.json"
    const val EXTRA = "licencias/extra.json"

    /** Código fuente de ESTA versión (GPL-3.0 §6): el tag `v<versionName>` que Juan crea al publicar. */
    fun sourceCodeUrl(versionName: String): String = "https://github.com/DiegoBr4nd/lector-bilingue/tree/v$versionName"

    /** Aviso de copyright propio de una librería de Gradle (opcional; obligatorio para MIT/BSD, lo vigila la prueba). */
    fun noticePath(uniqueId: String): String = "licencias/avisos/" + uniqueId.replace(':', '_') + ".txt"

    fun textPath(licenseKey: String): String = "licencias/textos/$licenseKey.txt"

    /** [exists]: si una ruta está en assets. Cualquier JSON roto o inesperado → null (la pantalla muestra el error). */
    fun parse(generated: String, extra: String, exists: (String) -> Boolean): List<LicenseEntry>? = runCatching {
        (parseExtra(extra) + parseGenerated(generated, exists))
            .sortedWith(compareBy<LicenseEntry>({ it.group.ordinal }, { it.name.lowercase() }))
    }.getOrNull()

    fun load(assets: AssetManager): List<LicenseEntry>? = runCatching {
        parse(assets.readText(GENERATED), assets.readText(EXTRA)) { path -> assets.has(path) }
    }.getOrNull()

    /**
     * Los textos de [entry] unidos por una línea en blanco; si falta alguno → null. Un texto repetido sale una sola vez
     * (Readium: su aviso propio y el texto de su licencia son el mismo archivo LICENSE).
     */
    fun readTexts(assets: AssetManager, entry: LicenseEntry): String? = runCatching {
        entry.texts.map { assets.readText(it).trim() }.distinct().joinToString("\n\n")
    }.getOrNull()

    private fun parseGenerated(json: String, exists: (String) -> Boolean): List<LicenseEntry> {
        val root = JSONObject(json)
        val licenses = root.getJSONObject("licenses")
        val libs = root.getJSONArray("libraries")
        return (0 until libs.length()).map { i ->
            val lib = libs.getJSONObject(i)
            val id = lib.getString("uniqueId")
            val keys = lib.optJSONArray("licenses").strings()
            LicenseEntry(
                id = id,
                name = lib.optString("name").ifBlank { id },
                license = keys.joinToString(" / ") { k -> licenses.optJSONObject(k)?.optString("name")?.ifBlank { null } ?: k },
                group = LicenseGroup.LIBRARIES,
                texts = listOfNotNull(noticePath(id).takeIf(exists)) + keys.map(::textPath),
                url = lib.optString("website").ifBlank { null },
            )
        }
    }

    private fun parseExtra(json: String): List<LicenseEntry> {
        val entries = JSONObject(json).getJSONArray("entries")
        return (0 until entries.length()).map { i ->
            val e = entries.getJSONObject(i)
            LicenseEntry(
                id = e.getString("id"),
                name = e.getString("name"),
                license = e.getString("license"),
                group = when (val g = e.getString("group")) {
                    "app" -> LicenseGroup.APP
                    "libraries" -> LicenseGroup.LIBRARIES
                    "native" -> LicenseGroup.NATIVE
                    "fonts_icons" -> LicenseGroup.FONTS_ICONS
                    else -> error("grupo desconocido: $g")
                },
                texts = e.getJSONArray("texts").strings().also { require(it.isNotEmpty()) },
                url = e.optString("url").ifBlank { null },
            )
        }
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map(::getString)

    private fun AssetManager.readText(path: String): String = open(path).bufferedReader().use { it.readText() }

    private fun AssetManager.has(path: String): Boolean = runCatching { open(path).close() }.isSuccess
}

/**
 * Los textos de licencia vienen cortados a ~80 columnas; en un teléfono (y más con letra al 200 %) esos cortes caen en
 * mitad de la frase. Esto une las líneas de un mismo párrafo con un espacio y deja todo lo demás tal cual: líneas en
 * blanco, líneas con sangría, viñetas, secciones numeradas («1.», «2)», «a)», «(a)»), líneas de copyright y títulos
 * en mayúsculas. Es idempotente (aplicarla dos veces da lo mismo). Solo para mostrar: los archivos no cambian.
 */
fun reflowLicense(text: String): String {
    val out = mutableListOf<String>()
    for (raw in text.replace("\r\n", "\n").split('\n')) {
        val line = if (raw.isBlank()) "" else raw
        val prev = out.lastOrNull()
        if (prev.isNullOrEmpty() || line.isEmpty() || startsOwnLine(line) || isAllCaps(line) != isAllCaps(prev)) {
            out += line
        } else {
            out[out.lastIndex] = prev.trimEnd() + " " + line
        }
    }
    return out.joinToString("\n")
}

// Viñeta, sección numerada o con letra («1.», «2.1.», «2)», «a)», «(a)», «(iv)»), línea de copyright o comentario de
// código («//», como la cabecera de XH Scanner).
private val ownLineStart =
    Regex("""^([-*•#]|//|\d+(\.\d+)*[.)](\s|$)|[A-Za-z][.)](\s|$)|\([A-Za-z0-9]+\)|copyright\b|©)""", RegexOption.IGNORE_CASE)

private fun startsOwnLine(line: String): Boolean = line.first().isWhitespace() || ownLineStart.containsMatchIn(line)

/** Una línea sin minúsculas y con al menos dos letras: un título, o parte de un párrafo todo en mayúsculas. */
private fun isAllCaps(line: String): Boolean = line.count { it.isLetter() } >= 2 && line.none { it.isLowerCase() }
