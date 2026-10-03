package io.github.diegobr4nd.lectorbilingue.models

import org.json.JSONObject
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var modelsDir: File

    private val bodyA = bytes(5000, seed = 1)
    private val bodyB = bytes(3000, seed = 2)

    @Before
    fun setUp() {
        modelsDir = tmp.newFolder("models")
    }

    private fun bytes(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 31 + seed * 7) and 0xFF).toByte() }

    private fun sha(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun file(name: String, body: ByteArray, size: Long = body.size.toLong()) =
        ModelFile(name, size, sha(body), "https://example.invalid/$name")

    private fun model(id: String = "opus-en-es-2", version: String = "2.0") = CatalogModel(
        id, "en-es", "opus", version, "CC-BY-4.0", "Helsinki-NLP",
        listOf(file("model.bin", bodyA), file("vocab.spm", bodyB)),
    )

    /** Deja una carpeta temporal como la que produce [ModelDownloader]. */
    private fun staging(id: String = "opus-en-es-2", a: ByteArray = bodyA, b: ByteArray = bodyB): File {
        val dir = File(modelsDir, ".tmp/$id")
        dir.mkdirs()
        File(dir, "model.bin").writeBytes(a)
        File(dir, "vocab.spm").writeBytes(b)
        return dir
    }

    /** Un modelo anterior ya instalado en `en-es/`. */
    private fun previous(): File {
        val dir = File(modelsDir, "en-es")
        dir.mkdirs()
        File(dir, "model.bin").writeText("viejo")
        File(dir, ".installed.json").writeText(
            InstalledModel("opus-en-es-1", "en-es", "opus", "1.0", listOf("model.bin")).toJson(),
        )
        return dir
    }

    /** El mensaje no puede llevar rutas internas ni nombres de carpetas temporales. */
    private fun assertNoPath(e: Throwable) {
        val msg = e.message.orEmpty()
        assertFalse(msg.contains('/') || msg.contains('\\'), "el mensaje lleva una ruta")
        assertFalse(msg.contains(tmp.root.name), "el mensaje lleva la carpeta temporal")
        assertFalse(msg.contains("opus-en-es-2"), "el mensaje lleva el id de la carpeta")
        assertEquals(null, e.cause, "la causa podría llevar rutas")
    }

    private fun atomicMove(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }

    // ---------------------------------------------------------------- instalación

    @Test
    fun install_instalaEnLaCarpetaDelParYDevuelveElModelo() {
        val st = staging()
        val result = ModelInstaller(modelsDir).install(model(), st)
        assertEquals(InstalledModel("opus-en-es-2", "en-es", "opus", "2.0", listOf("model.bin", "vocab.spm")), result)
        val dir = File(modelsDir, "en-es")
        assertEquals(listOf(".installed.json", "model.bin", "vocab.spm"), dir.list()!!.sorted())
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
        assertContentEquals(bodyB, File(dir, "vocab.spm").readBytes())
        assertFalse(st.exists())
        assertFalse(File(modelsDir, ".tmp").exists(), "la carpeta .tmp vacía se borra")
    }

    @Test
    fun install_noBorraOtrasDescargasEnCurso() {
        File(modelsDir, ".tmp/otro-modelo").mkdirs()
        ModelInstaller(modelsDir).install(model(), staging())
        assertTrue(File(modelsDir, ".tmp/otro-modelo").isDirectory)
    }

    @Test
    fun install_escribeUnInstalledJsonCorrecto() {
        ModelInstaller(modelsDir).install(model(), staging())
        val text = File(modelsDir, "en-es/.installed.json").readText()
        val o = JSONObject(text)
        assertEquals("opus-en-es-2", o.getString("id"))
        assertEquals("en-es", o.getString("pair"))
        assertEquals("opus", o.getString("engine"))
        assertEquals("2.0", o.getString("modelVersion"))
        val files = o.getJSONArray("files")
        assertEquals(listOf("model.bin", "vocab.spm"), (0 until files.length()).map { files.getString(it) })
        assertEquals(
            InstalledModel("opus-en-es-2", "en-es", "opus", "2.0", listOf("model.bin", "vocab.spm")),
            InstalledModel.fromJson(text),
        )
    }

    // ---------------------------------------------------------------- reemplazo

    @Test
    fun install_reemplazaElModeloAnteriorYNoDejaRestos() {
        previous()
        ModelInstaller(modelsDir).install(model(), staging())
        val dir = File(modelsDir, "en-es")
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
        assertEquals("opus-en-es-2", InstalledModel.fromJson(File(dir, ".installed.json").readText()).id)
        assertEquals(listOf("en-es"), modelsDir.list()!!.sorted(), "ni .old-* ni .tmp")
    }

    @Test
    fun install_ordenRenombraViejoLuegoNuevoLuegoBorraViejo() {
        previous()
        val st = staging()
        val moves = mutableListOf<Pair<Path, Path>>()
        val installer = ModelInstaller(modelsDir) { from, to ->
            moves += from to to
            atomicMove(from, to)
        }
        installer.install(model(), st)
        assertEquals(2, moves.size)
        val pairDir = File(modelsDir, "en-es").toPath()
        val (from1, to1) = moves[0]
        assertEquals(pairDir.toFile().canonicalFile, from1.toFile().canonicalFile)
        assertTrue(to1.fileName.toString().startsWith(".old-en-es-"))
        assertEquals(modelsDir.canonicalFile, to1.toFile().canonicalFile.parentFile)
        val (from2, to2) = moves[1]
        assertEquals(st.canonicalFile, from2.toFile().canonicalFile)
        assertEquals(pairDir.toFile().canonicalFile, to2.toFile().canonicalFile)
        assertFalse(Files.exists(to1), "el viejo se borra al final")
    }

    @Test
    fun install_siFallaElSegundoRenombradoElViejoSigueDisponible() {
        previous()
        val st = staging()
        var calls = 0
        val installer = ModelInstaller(modelsDir) { from, to ->
            calls++
            if (calls == 2) throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "x")
            atomicMove(from, to)
        }
        val e = assertFailsWith<IOException> { installer.install(model(), st) }
        assertNoPath(e)
        val dir = File(modelsDir, "en-es")
        assertEquals("viejo", File(dir, "model.bin").readText())
        assertEquals("opus-en-es-1", InstalledModel.fromJson(File(dir, ".installed.json").readText()).id)
        assertTrue(st.isDirectory, "la descarga verificada se conserva para reintentar")
        assertFalse(modelsDir.list()!!.any { it.startsWith(".old-") })
    }

    @Test
    fun install_reintentoTrasFalloFuncionaConElMismoStaging() {
        previous()
        val st = staging()
        var calls = 0
        val flaky = ModelInstaller(modelsDir) { from, to ->
            calls++
            if (calls == 2) throw IOException("fallo simulado")
            atomicMove(from, to)
        }
        assertFailsWith<IOException> { flaky.install(model(), st) }
        // El .installed.json que quedó en staging no impide reintentar.
        ModelInstaller(modelsDir).install(model(), st)
        assertContentEquals(bodyA, File(modelsDir, "en-es/model.bin").readBytes())
    }

    @Test
    fun install_falloDelPrimerRenombradoNoFiltraRutas() {
        previous()
        val st = staging()
        val installer = ModelInstaller(modelsDir) { from, to ->
            throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "x")
        }
        val e = assertFailsWith<IOException> { installer.install(model(), st) }
        assertNoPath(e)
        assertEquals("viejo", File(modelsDir, "en-es/model.bin").readText())
    }

    @Test
    fun install_tmpQueEsEnlaceSimbolicoSeRechaza() {
        val outside = tmp.newFolder("fuera")
        val realStaging = File(outside, "opus-en-es-2").apply { mkdirs() }
        File(realStaging, "model.bin").writeBytes(bodyA)
        File(realStaging, "vocab.spm").writeBytes(bodyB)
        val link = File(modelsDir, ".tmp").toPath()
        val created = try {
            Files.createSymbolicLink(link, outside.toPath())
            true
        } catch (e: Exception) {
            false // Windows sin privilegios: no se pueden crear enlaces.
        }
        Assume.assumeTrue(created)
        assertFailsWith<IntegrityException> {
            ModelInstaller(modelsDir).install(model(), File(modelsDir, ".tmp/opus-en-es-2"))
        }
        assertFalse(File(modelsDir, "en-es").exists())
        assertTrue(File(realStaging, "model.bin").isFile, "no se mueve nada desde fuera de modelsDir")
    }

    // ---------------------------------------------------------------- re-verificación

    @Test
    fun install_archivoCambiadoEnStagingLanzaIntegrityYNoTocaElAnterior() {
        previous()
        val st = staging(a = bytes(bodyA.size, seed = 9))
        val e = assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertTrue(e.message.orEmpty().contains("model.bin"))
        assertFalse(e.message.orEmpty().contains(sha(bodyA)))
        assertEquals("viejo", File(modelsDir, "en-es/model.bin").readText())
    }

    @Test
    fun install_tamanoDistintoLanzaIntegrity() {
        val st = staging(b = bodyB.copyOfRange(0, 100))
        assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertFalse(File(modelsDir, "en-es").exists())
    }

    @Test
    fun install_archivoFaltanteLanzaIntegrity() {
        val st = staging()
        File(st, "vocab.spm").delete()
        assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertFalse(File(modelsDir, "en-es").exists())
    }

    @Test
    fun install_archivoQueNoEsDelCatalogoLanzaIntegrity() {
        val st = staging()
        File(st, "extra.so").writeText("x")
        assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertFalse(File(modelsDir, "en-es").exists())
    }

    @Test
    fun install_subcarpetaEnStagingLanzaIntegrity() {
        val st = staging()
        File(st, "sub").mkdirs()
        assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertFalse(File(modelsDir, "en-es").exists())
    }

    @Test
    fun install_carpetaConNombreDeArchivoLanzaIntegrity() {
        val st = staging()
        File(st, "vocab.spm").delete()
        File(st, "vocab.spm").mkdirs()
        assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
    }

    @Test
    fun install_soloAceptaLaCarpetaTemporalDelModelo() {
        val elsewhere = tmp.newFolder("otra")
        File(elsewhere, "model.bin").writeBytes(bodyA)
        File(elsewhere, "vocab.spm").writeBytes(bodyB)
        assertFailsWith<IllegalArgumentException> { ModelInstaller(modelsDir).install(model(), elsewhere) }
        val otherId = staging(id = "otro-id")
        assertFailsWith<IllegalArgumentException> { ModelInstaller(modelsDir).install(model(), otherId) }
        assertFalse(File(modelsDir, "en-es").exists())
    }

    // ---------------------------------------------------------------- InstalledModel

    @Test
    fun installedModel_idaYVueltaPorJson() {
        val m = InstalledModel("firefox-es-en-3", "es-en", "firefox", "3.1", listOf("a.bin", "lex.s2t", "vocab.spm"))
        assertEquals(m, InstalledModel.fromJson(m.toJson()))
    }

    @Test
    fun installedModel_fromJsonRechazaContenidoInvalido() {
        val bad = listOf(
            "",
            "no es json",
            "[]",
            """{"pair":"en-es","engine":"opus","modelVersion":"1","files":["a"]}""",
            """{"id":"x","pair":"en-es","engine":"opus","modelVersion":"1","files":"a"}""",
            """{"id":"x","pair":"en-es","engine":"opus","modelVersion":"1","files":[1]}""",
            """{"id":"x","pair":"en-es","engine":"opus","modelVersion":"1","files":["../a"]}""",
            """{"id":"x","pair":"../x","engine":"opus","modelVersion":"1","files":["a"]}""",
            """{"id":"x","pair":"en-es","engine":7,"modelVersion":"1","files":["a"]}""",
            """{"id":"x","pair":"en-es","engine":"otro","modelVersion":"1","files":["a"]}""",
        )
        for (text in bad) {
            assertFailsWith<IllegalArgumentException>(text) { InstalledModel.fromJson(text) }
        }
    }
}
