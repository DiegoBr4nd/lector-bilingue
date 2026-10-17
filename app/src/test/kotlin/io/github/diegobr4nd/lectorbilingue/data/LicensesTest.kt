package io.github.diegobr4nd.lectorbilingue.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** El catálogo REAL de assets (app + core/ui): completo, con textos y sin nada de la máquina que lo generó. */
class LicensesTest {
    // Gradle corre las pruebas JVM con el módulo app como carpeta de trabajo.
    private val roots = listOf(File("src/main/assets"), File("../core/ui/src/main/assets"))
    private fun file(path: String): File? = roots.map { File(it, path) }.firstOrNull { it.isFile }
    private fun read(path: String): String = assertNotNull(file(path), "falta el asset $path").readText()
    private val entries by lazy {
        assertNotNull(Licenses.parse(read(Licenses.GENERATED), read(Licenses.EXTRA)) { file(it) != null }, "catálogo ilegible")
    }

    @Test fun `la primera fila es la app con GPL y el enlace al codigo`() {
        val first = entries.first()
        assertEquals(LicenseGroup.APP, first.group)
        assertEquals("GPL-3.0-or-later", first.license)
        assertEquals("https://github.com/DiegoBr4nd/lector-bilingue", first.url)
        assertEquals(1, entries.count { it.group == LicenseGroup.APP })
    }

    @Test fun `el texto de la app es la LICENSE del repositorio`() {
        val norm = { s: String -> s.replace("\r\n", "\n").trim() }
        assertEquals(norm(File("../LICENSE").readText()), norm(read(entries.first().texts.single())))
    }

    @Test fun `cada fila tiene licencia y textos que existen y no estan vacios`() {
        for (e in entries) {
            assertTrue(e.license.isNotBlank(), "${e.id} sin licencia")
            assertTrue(e.texts.isNotEmpty(), "${e.id} sin textos")
            for (t in e.texts) assertTrue(read(t).isNotBlank(), "${e.id}: $t vacío")
        }
    }

    @Test fun `las librerias MIT o BSD llevan su aviso de copyright propio`() {
        val needsNotice = Regex("MIT|BSD", RegexOption.IGNORE_CASE)
        for (e in entries.filter { it.group == LicenseGroup.LIBRARIES && needsNotice.containsMatchIn(it.license) }) {
            assertTrue(e.texts.first().startsWith("licencias/avisos/"), "${e.id} (${e.license}) sin aviso de copyright")
        }
    }

    @Test fun `estan los componentes nativos fuentes e iconos conocidos`() {
        val ids = entries.map { it.id }.toSet()
        val expected = setOf(
            "ctranslate2", "ruy", "cpuinfo", "abseil", "sentencepiece", "protobuf-lite", "slimt",
            "spdlog", "darts-clone", "bs-thread-pool", "neon-mathfun", "xhscanner", "ssplit", "bergamot-marian",
            "literata", "inter", "atkinson-hyperlegible", "material-symbols",
            // Fuentes que trae el navegador de Readium dentro de su AAR (van en el APK).
            "ia-writer-duospace", "accessible-dfa", "opendyslexic",
        )
        assertTrue(ids.containsAll(expected), "faltan: ${expected - ids}")
    }

    @Test fun `estan las librerias principales del APK`() {
        val ids = entries.map { it.id }.toSet()
        for (id in listOf("org.readium.kotlin-toolkit:readium-navigator", "androidx.room:room-runtime", "org.bouncycastle:bcprov-jdk18on")) {
            assertTrue(id in ids, "falta $id")
        }
        assertTrue(ids.any { it.startsWith("androidx.compose.material3:") }, "falta Compose Material 3")
        assertTrue(ids.any { "desugar" in it }, "falta desugar_jdk_libs")
    }

    @Test fun `ninguna libreria de pruebas o de depuracion`() {
        val ids = entries.map { it.id }
        assertTrue(ids.none { it.startsWith("junit:") || "ui-test" in it || it.endsWith(":ui-tooling") || "mockwebserver" in it }, ids.joinToString())
    }

    @Test fun `sin nombres sin resolver`() {
        assertTrue(entries.none { "\${" in it.name || it.name.isBlank() })
    }

    @Test fun `el archivo generado no trae fechas ni rutas locales`() {
        val g = read(Licenses.GENERATED)
        assertFalse(JSONObject(g).has("metadata"), "trae metadata (fecha de generación)")
        assertFalse(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:""").containsMatchIn(g), "trae una fecha")
        assertFalse(Regex("""[A-Za-z]:\\\\|/home/|/Users/|\\\\Users\\\\""").containsMatchIn(g), "trae una ruta local")
    }

    @Test fun `ordenado por grupos`() {
        val order = entries.map { it.group.ordinal }
        assertEquals(order.sorted(), order)
    }

    @Test fun `json roto da null sin excepcion`() {
        assertNull(Licenses.parse("{", read(Licenses.EXTRA)) { true })
        assertNull(Licenses.parse(read(Licenses.GENERATED), "no es json") { true })
        assertNull(Licenses.parse("""{"libraries":[],"licenses":{}}""", """{"entries":[{"id":"x","group":"rara","name":"x","license":"x","texts":["a"]}]}""") { true })
    }
}
