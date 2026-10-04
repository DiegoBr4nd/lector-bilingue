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
import kotlin.test.assertIs
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

    private fun model(id: String = "opus-en-es-2", version: String = "2.0", engine: String = "opus") = CatalogModel(
        id, "en-es", engine, version, "CC-BY-4.0", "Helsinki-NLP",
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
        val dir = File(modelsDir, "opus/en-es")
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
        val dir = File(modelsDir, "opus/en-es")
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
        val text = File(modelsDir, "opus/en-es/.installed.json").readText()
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
        val dir = File(modelsDir, "opus/en-es")
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
        assertEquals("opus-en-es-2", InstalledModel.fromJson(File(dir, ".installed.json").readText()).id)
        assertEquals(listOf("opus"), modelsDir.list()!!.sorted(), "ni .tmp")
        assertEquals(listOf("en-es"), File(modelsDir, "opus").list()!!.sorted(), "ni .old-*")
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
        val pairDir = File(modelsDir, "opus/en-es").toPath()
        val (from1, to1) = moves[0]
        assertEquals(pairDir.toFile().canonicalFile, from1.toFile().canonicalFile)
        assertTrue(to1.fileName.toString().startsWith(".old-en-es-"))
        assertEquals(File(modelsDir, "opus").canonicalFile, to1.toFile().canonicalFile.parentFile)
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
        assertIs<ModelFileException>(e)
        assertNoPath(e)
        val dir = File(modelsDir, "opus/en-es")
        assertEquals("viejo", File(dir, "model.bin").readText())
        assertEquals("opus-en-es-1", InstalledModel.fromJson(File(dir, ".installed.json").readText()).id)
        assertTrue(st.isDirectory, "la descarga verificada se conserva para reintentar")
        assertFalse(File(modelsDir, "opus").list()!!.any { it.startsWith(".old-") })
        assertFalse(modelsDir.list()!!.any { it.startsWith(".old-") })
    }

    private fun assertInstallSurvivesOldDeleteFailure(failure: Throwable) {
        previous()
        val installer = ModelInstaller(modelsDir, deleteOld = { throw failure }) { from, to -> atomicMove(from, to) }
        val result = installer.install(model(), staging())
        assertEquals("opus-en-es-2", result.id)
        assertContentEquals(bodyA, File(modelsDir, "opus/en-es/model.bin").readBytes())
        assertTrue(File(modelsDir, "opus").list()!!.any { it.startsWith(".old-en-es-") }, "la vieja queda para la limpieza al arrancar")
    }

    @Test
    fun install_siNoSePuedeBorrarElViejoPorIOExceptionLaInstalacionNoFalla() {
        assertInstallSurvivesOldDeleteFailure(IOException("fallo simulado"))
    }

    @Test
    fun install_siNoSePuedeBorrarElViejoPorDirectoryIteratorExceptionLaInstalacionNoFalla() {
        assertInstallSurvivesOldDeleteFailure(java.nio.file.DirectoryIteratorException(IOException("fallo simulado")))
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
        assertContentEquals(bodyA, File(modelsDir, "opus/en-es/model.bin").readBytes())
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
        assertEquals("viejo", File(modelsDir, "opus/en-es/model.bin").readText())
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
        assertFalse(File(modelsDir, "opus/en-es").exists())
        assertTrue(File(realStaging, "model.bin").isFile, "no se mueve nada desde fuera de modelsDir")
    }

    // ---------------------------------------------------------------- una carpeta por motor

    @Test
    fun install_firefoxVaASuCarpetaSinTocarOpus() {
        previous()
        val result = ModelInstaller(modelsDir).install(model(id = "firefox-en-es-1", engine = "firefox"), staging(id = "firefox-en-es-1"))
        assertEquals("firefox", result.engine)
        val ff = File(modelsDir, "firefox/en-es")
        assertEquals(listOf(".installed.json", "model.bin", "vocab.spm"), ff.list()!!.sorted())
        assertContentEquals(bodyA, File(ff, "model.bin").readBytes())
        assertEquals("viejo", File(modelsDir, "opus/en-es/model.bin").readText(), "opus/en-es no se toca")
        assertEquals("opus-en-es-1", InstalledModel.fromJson(File(modelsDir, "opus/en-es/.installed.json").readText()).id)
        assertEquals(listOf("firefox", "opus"), modelsDir.list()!!.sorted())
    }

    @Test
    fun install_reemplazoDeFirefoxDejaSuViejaEnFirefoxYLaLimpia() {
        previous()
        val m1 = model(id = "firefox-en-es-1", engine = "firefox", version = "1.0")
        ModelInstaller(modelsDir).install(m1, staging(id = "firefox-en-es-1"))
        val moves = mutableListOf<Pair<Path, Path>>()
        val installer = ModelInstaller(modelsDir) { from, to ->
            moves += from to to
            atomicMove(from, to)
        }
        val m2 = model(id = "firefox-en-es-2", engine = "firefox", version = "2.0")
        installer.install(m2, staging(id = "firefox-en-es-2"))
        val old = moves[0].second
        assertTrue(old.fileName.toString().startsWith(".old-en-es-"))
        assertEquals(File(modelsDir, "firefox").canonicalFile, old.toFile().canonicalFile.parentFile)
        assertFalse(Files.exists(old), "la vieja se borra al final")
        assertEquals(listOf("en-es"), File(modelsDir, "firefox").list()!!.sorted())
        assertEquals(listOf("en-es"), File(modelsDir, "opus").list()!!.sorted(), "opus no se toca")
        assertEquals("firefox-en-es-2", InstalledModel.fromJson(File(modelsDir, "firefox/en-es/.installed.json").readText()).id)
    }

    @Test
    fun install_motorInvalidoLanzaIAE() {
        for (bad in listOf("..", ".tmp", "otro", "Opus")) {
            assertFailsWith<IllegalArgumentException>(bad) { ModelInstaller(modelsDir).install(model(engine = bad), staging()) }
        }
        assertEquals(listOf(".tmp"), modelsDir.list()!!.sorted())
    }

    @Test
    fun install_carpetaDeMotorQueEsEnlaceNoInstalaFuera() {
        val outside = tmp.newFolder("fuera")
        val created = try {
            Files.createSymbolicLink(File(modelsDir, "opus").toPath(), outside.toPath())
            true
        } catch (e: Exception) {
            false // Windows sin privilegios: no se pueden crear enlaces.
        }
        Assume.assumeTrue(created)
        ModelInstaller(modelsDir).install(model(), staging())
        assertFalse(File(outside, "en-es").exists(), "nada se instala a través de un enlace")
        assertTrue(Files.isDirectory(File(modelsDir, "opus").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertContentEquals(bodyA, File(modelsDir, "opus/en-es/model.bin").readBytes())
    }

    // ---------------------------------------------------------------- re-verificación

    @Test
    fun install_archivoCambiadoEnStagingLanzaIntegrityYNoTocaElAnterior() {
        previous()
        val st = staging(a = bytes(bodyA.size, seed = 9))
        val e = assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertTrue(e.message.orEmpty().contains("model.bin"))
        assertFalse(e.message.orEmpty().contains(sha(bodyA)))
        assertEquals("viejo", File(modelsDir, "opus/en-es/model.bin").readText())
    }

    @Test
    fun install_tamanoDistintoLanzaIntegrity() {
        val st = staging(b = bodyB.copyOfRange(0, 100))
        assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertFalse(File(modelsDir, "opus/en-es").exists())
    }

    @Test
    fun install_archivoFaltanteLanzaIntegrity() {
        val st = staging()
        File(st, "vocab.spm").delete()
        assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertFalse(File(modelsDir, "opus/en-es").exists())
    }

    @Test
    fun install_archivoQueNoEsDelCatalogoLanzaIntegrity() {
        val st = staging()
        File(st, "extra.so").writeText("x")
        assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertFalse(File(modelsDir, "opus/en-es").exists())
    }

    @Test
    fun install_subcarpetaEnStagingLanzaIntegrity() {
        val st = staging()
        File(st, "sub").mkdirs()
        assertFailsWith<IntegrityException> { ModelInstaller(modelsDir).install(model(), st) }
        assertFalse(File(modelsDir, "opus/en-es").exists())
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
        assertFalse(File(modelsDir, "opus/en-es").exists())
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
