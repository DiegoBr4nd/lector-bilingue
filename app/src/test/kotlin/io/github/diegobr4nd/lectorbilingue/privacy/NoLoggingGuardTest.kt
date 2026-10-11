package io.github.diegobr4nd.lectorbilingue.privacy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guardia de privacidad (regla 5 de CLAUDE.md): el código de producción de TODOS los módulos
 * no debe registrar nada. Como las pruebas JVM usan `isReturnDefaultValues = true`, un `Log`
 * olvidado ya no las haría fallar; esta prueba lo vigila a propósito.
 */
class NoLoggingGuardTest {
    private val prohibidos = listOf(
        Regex("""android\.util\.Log\b"""),
        Regex("""\bLog\.[a-z]+\("""),
        Regex("""\bprintln\("""),
        Regex("""\bprintStackTrace\("""),
        Regex("""\bTimber\b"""),
    )

    /** Sube desde el directorio de trabajo hasta encontrar settings.gradle.kts. */
    private fun raizDelRepo(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "No se encontró la raíz del repo" }
    }

    private fun fuentesDeProduccion(raiz: File): List<File> =
        raiz.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val ruta = f.relativeTo(raiz).invariantSeparatorsPath
                "/src/main/" in "/$ruta"
            }
            .toList()

    @Test
    fun `ningun modulo registra texto en su codigo de produccion`() {
        val archivos = fuentesDeProduccion(raizDelRepo())
        assertTrue(archivos.size > 10, "La búsqueda debería encontrar muchos .kt de producción (halló ${archivos.size})")
        val infracciones = archivos.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, linea ->
                val sinComentario = linea.substringBefore("//")
                if (prohibidos.any { it.containsMatchIn(sinComentario) }) "${f.name}:${i + 1}" else null
            }
        }
        if (infracciones.isNotEmpty()) fail("Registro prohibido (regla 5) en: ${infracciones.joinToString()}")
    }
}
