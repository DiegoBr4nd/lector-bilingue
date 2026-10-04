package io.github.diegobr4nd.lectorbilingue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.engine.firefox.SlimtNativeBridge
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cada slimt.json malo debe rechazarse con un mensaje fijo: sin rutas ni contenido del archivo.
 * Usa carpetas temporales con archivos de relleno (no son un modelo real).
 */
@RunWith(AndroidJUnit4::class)
class SlimtConfigOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before fun crearRaiz() {
        root = File(context.cacheDir, "slimt-config-test").also {
            it.deleteRecursively()
            it.mkdirs()
        }
    }

    @After fun limpiar() {
        root.deleteRecursively()
    }

    private fun goodJson(
        model: String = "\"m.bin\"",
        vocab: String = "\"v.spm\"",
        decoder: String = "4",
    ) = """{"model": $model, "vocabulary": $vocab, "shortlist": "s.bin", "encoder_layers": 6, "decoder_layers": $decoder, "heads": 8}"""

    /** Crea una carpeta con archivos de relleno y devuelve la carpeta; [writeJson] escribe slimt.json. */
    private fun modelDir(name: String, writeJson: (File) -> Unit): File {
        val dir = File(root, name).also { it.mkdirs() }
        for (f in listOf("m.bin", "v.spm", "s.bin")) File(dir, f).writeBytes(byteArrayOf(1, 2, 3, 4))
        writeJson(dir)
        return dir
    }

    private fun assertRejected(dir: File) {
        try {
            SlimtNativeBridge.load(dir.path, 1)
            fail("debía rechazar la configuración")
        } catch (e: IllegalArgumentException) {
            checkMessage(e)
        } catch (e: IllegalStateException) {
            checkMessage(e)
        }
    }

    private fun checkMessage(e: Exception) {
        val msg = e.message.orEmpty()
        assertFalse(msg.contains('/') || msg.contains('\\'), "el mensaje filtra una ruta")
        // Ningún trozo del contenido del archivo ni de los nombres de relleno.
        for (leak in listOf("m.bin", "v.spm", "s.bin", "encoder_layers", "decoder_layers")) {
            assertFalse(msg.contains(leak), "el mensaje filtra contenido")
        }
        assertTrue(msg.isNotEmpty(), "mensaje vacío")
    }

    private fun jsonCase(name: String, json: String) =
        assertRejected(modelDir(name) { File(it, "slimt.json").writeText(json) })

    @Test fun faltaUnaClave() = jsonCase(
        "falta",
        """{"model": "m.bin", "vocabulary": "v.spm", "shortlist": "s.bin", "encoder_layers": 6, "decoder_layers": 4}""",
    )

    @Test fun claveExtra() = jsonCase("extra", goodJson().dropLast(1) + """, "extra": 1}""")

    @Test fun claveRepetida() = jsonCase("repetida", goodJson().dropLast(1) + """, "heads": 8}""")

    @Test fun nombreConPuntosPuntos() = jsonCase("dotdot", goodJson(model = "\"..m.bin\""))

    @Test fun nombreConBarra() = jsonCase("barra", goodJson(model = "\"sub/m.bin\""))

    @Test fun capasDelDecodificadorCero() = jsonCase("dec0", goodJson(decoder = "0"))

    @Test fun capasDelDecodificadorTrece() = jsonCase("dec13", goodJson(decoder = "13"))

    @Test fun vocabularioComoLista() = jsonCase("vocablista", goodJson(vocab = """["v.spm", "w.spm"]"""))

    @Test fun archivoMayorDe4KiB() = jsonCase("grande", goodJson() + " ".repeat(4097))

    @Test fun conMarcaBom() = assertRejected(
        modelDir("bom") { File(it, "slimt.json").writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + goodJson().toByteArray()) },
    )

    @Test fun slimtJsonComoEnlaceSimbolico() {
        val dir = modelDir("symlink") { }
        val real = File(root, "real.json").also { it.writeText(goodJson()) }
        val created = try {
            Files.createSymbolicLink(File(dir, "slimt.json").toPath(), real.toPath())
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue("no se pudo crear el enlace simbólico", created)
        assertRejected(dir)
    }
}
