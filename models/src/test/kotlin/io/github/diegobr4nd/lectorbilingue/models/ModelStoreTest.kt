package io.github.diegobr4nd.lectorbilingue.models

import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var modelsDir: File
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        modelsDir = tmp.newFolder("models")
        store = ModelStore(modelsDir)
    }

    private fun installedModel(
        pair: String,
        id: String = "opus-$pair-1",
        version: String = "1.0",
        engine: String = "opus",
    ) = InstalledModel(id, pair, engine, version, listOf("model.bin", "vocab.spm"))

    /** Crea `<engine>/<dirName>/` con dos archivos y su `.installed.json`. */
    private fun install(
        dirName: String,
        model: InstalledModel = installedModel(dirName),
        engine: String = "opus",
    ): File {
        val dir = File(modelsDir, "$engine/$dirName")
        dir.mkdirs()
        File(dir, "model.bin").writeBytes(ByteArray(100))
        File(dir, "vocab.spm").writeBytes(ByteArray(23))
        File(dir, ".installed.json").writeText(model.toJson())
        return dir
    }

    private fun opusDir() = File(modelsDir, "opus")

    private fun trySymlink(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (e: Exception) {
        false // Windows sin privilegios: no se pueden crear enlaces.
    }

    // ---------------------------------------------------------------- installed / isInstalled

    @Test
    fun installed_carpetaInexistenteDevuelveVacio() {
        assertEquals(emptyList(), ModelStore(File(modelsDir, "no-existe")).installed())
    }

    @Test
    fun installed_leeCadaCarpetaOrdenadaPorMotorYPar() {
        install("es-en")
        install("en-es")
        install("es-en", installedModel("es-en", id = "firefox-es-en-1", engine = "firefox"), engine = "firefox")
        assertEquals(
            listOf(
                installedModel("es-en", id = "firefox-es-en-1", engine = "firefox"),
                installedModel("en-es"),
                installedModel("es-en"),
            ),
            store.installed(),
        )
    }

    @Test
    fun installed_ignoraTemporalesViejasYCarpetasRaras() {
        install("en-es")
        install(".tmp", installedModel("fr-es"))
        install(".old-de-es-123", installedModel("de-es"))
        install("no_es_par", installedModel("it-es"))
        File(opusDir(), "pt-es").writeText("un archivo, no una carpeta")
        // Carpetas de primer nivel (formato de la 2b) y motores desconocidos no cuentan.
        File(modelsDir, "ru-es").mkdirs()
        File(modelsDir, "ru-es/.installed.json").writeText(installedModel("ru-es").toJson())
        File(modelsDir, "otro/en-es").mkdirs()
        File(modelsDir, "otro/en-es/.installed.json").writeText(installedModel("en-es").toJson())
        assertEquals(listOf(installedModel("en-es")), store.installed())
    }

    @Test
    fun installed_ignoraJsonRotoAusenteDeOtroParODeOtroMotor() {
        install("en-es")
        File(opusDir(), "fr-es").mkdirs()
        install("de-es").let { File(it, ".installed.json").writeText("{roto") }
        install("it-es", installedModel("pt-es"))
        install("ca-es", installedModel("ca-es", engine = "firefox"))
        assertEquals(listOf(installedModel("en-es")), store.installed())
    }

    @Test
    fun installed_ignoraModelosConArchivosFaltantes() {
        install("en-es")
        install("es-en").let { File(it, "vocab.spm").delete() }
        assertEquals(listOf(installedModel("en-es")), store.installed())
    }

    @Test
    fun installed_ignoraJsonGigante() {
        install("en-es").let { File(it, ".installed.json").writeBytes(ByteArray(ModelStore.MAX_INSTALLED_JSON + 1) { ' '.code.toByte() }) }
        assertEquals(emptyList(), store.installed())
    }

    @Test
    fun isInstalled_verdaderoSoloSiHayInstalacionValida() {
        install("en-es")
        File(opusDir(), "fr-es").mkdirs()
        assertTrue(store.isInstalled("opus", "en-es"))
        assertFalse(store.isInstalled("firefox", "en-es"))
        assertFalse(store.isInstalled("opus", "fr-es"))
        assertFalse(store.isInstalled("opus", "de-es"))
    }

    @Test
    fun isInstalled_parOMotorInvalidoLanzaIAE() {
        for (bad in listOf("", "..", "../x", "en/es", "EN-ES", ".tmp", "en-es-x", "e-es")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.isInstalled("opus", bad) }
        }
        for (bad in listOf("", "..", ".tmp", "OPUS", "otro", "opus/..", "firefox ")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.isInstalled(bad, "en-es") }
        }
    }

    @Test
    fun installed_noSigueEnlaces() {
        val outside = tmp.newFolder("fuera")
        install("en-es").copyRecursively(File(outside, "copia"))
        Assume.assumeTrue(trySymlink(File(opusDir(), "fr-es"), File(outside, "copia")))
        assertEquals(listOf("en-es"), store.installed().map { it.pair })
    }

    @Test
    fun installed_carpetaDeMotorQueEsEnlaceNoCuenta() {
        val outside = tmp.newFolder("fuera")
        install("en-es", engine = "firefox", model = installedModel("en-es"))
            .copyRecursively(File(outside, "en-es"))
        File(modelsDir, "firefox").deleteRecursively()
        Assume.assumeTrue(trySymlink(opusDir(), outside))
        assertEquals(emptyList(), store.installed())
        assertEquals(null, store.installedDir("opus", "en-es"))
    }

    // ---------------------------------------------------------------- installedDir

    @Test
    fun installedDir_devuelveLaCarpetaSiTodoCuadra() {
        val dir = install("en-es")
        assertEquals(dir.canonicalFile, store.installedDir("opus", "en-es")?.canonicalFile)
    }

    @Test
    fun installedDir_nullSiFaltaElJson() {
        install("en-es").let { File(it, ".installed.json").delete() }
        assertEquals(null, store.installedDir("opus", "en-es"))
    }

    @Test
    fun installedDir_nullSiElJsonEsDeOtroMotor() {
        install("en-es", installedModel("en-es", engine = "firefox"))
        assertEquals(null, store.installedDir("opus", "en-es"))
        assertEquals(null, store.installedDir("firefox", "en-es"))
    }

    @Test
    fun installedDir_nullSiElJsonEsDeOtroPar() {
        install("en-es", installedModel("es-en"))
        assertEquals(null, store.installedDir("opus", "en-es"))
    }

    @Test
    fun installedDir_nullSiFaltaUnArchivoListado() {
        install("en-es").let { File(it, "vocab.spm").delete() }
        assertEquals(null, store.installedDir("opus", "en-es"))
    }

    @Test
    fun installedDir_nullSiUnArchivoListadoEsCarpeta() {
        install("en-es").let {
            File(it, "vocab.spm").delete()
            File(it, "vocab.spm").mkdirs()
        }
        assertEquals(null, store.installedDir("opus", "en-es"))
    }

    @Test
    fun installedDir_nullSiUnArchivoListadoEsEnlace() {
        val outside = tmp.newFolder("fuera")
        val target = File(outside, "vocab.spm").apply { writeBytes(ByteArray(23)) }
        val dir = install("en-es")
        File(dir, "vocab.spm").delete()
        Assume.assumeTrue(trySymlink(File(dir, "vocab.spm"), target))
        assertEquals(null, store.installedDir("opus", "en-es"))
    }

    @Test
    fun installedDir_nullSiLaCarpetaEsEnlace() {
        val outside = tmp.newFolder("fuera")
        install("es-en", installedModel("en-es")).copyRecursively(File(outside, "copia"))
        Assume.assumeTrue(trySymlink(File(opusDir(), "en-es"), File(outside, "copia")))
        assertEquals(null, store.installedDir("opus", "en-es"))
    }

    @Test
    fun installedDir_parOMotorInvalidoLanzaIAE() {
        for (bad in listOf("", "..", "../x", "en/es", "EN-ES", ".tmp", "en-es-x", "e-es")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.installedDir("opus", bad) }
        }
        for (bad in listOf("", "..", ".tmp", "OPUS", "otro", "opus/..", "firefox ")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.installedDir(bad, "en-es") }
        }
    }

    @Test
    fun installedDir_sinCarpetaDeModelosDevuelveNull() {
        assertEquals(null, ModelStore(File(modelsDir, "no-existe")).installedDir("opus", "en-es"))
    }

    // ---------------------------------------------------------------- sizeOnDisk

    @Test
    fun sizeOnDisk_sumaLosArchivos() {
        install("en-es")
        val json = File(opusDir(), "en-es/.installed.json").length()
        assertEquals(123L + json, store.sizeOnDisk("opus", "en-es"))
        assertEquals(0L, store.sizeOnDisk("firefox", "en-es"))
    }

    @Test
    fun sizeOnDisk_ceroSiNoExiste() {
        assertEquals(0L, store.sizeOnDisk("opus", "en-es"))
    }

    @Test
    fun sizeOnDisk_parOMotorInvalidoLanzaIAE() {
        assertFailsWith<IllegalArgumentException> { store.sizeOnDisk("opus", "../x") }
        assertFailsWith<IllegalArgumentException> { store.sizeOnDisk("..", "en-es") }
    }

    @Test
    fun sizeOnDisk_noCuentaLoQueHayDetrasDeUnEnlace() {
        val outside = tmp.newFolder("fuera")
        File(outside, "grande.bin").writeBytes(ByteArray(5000))
        val dir = install("en-es")
        Assume.assumeTrue(trySymlink(File(dir, "enlace"), outside))
        val json = File(dir, ".installed.json").length()
        assertEquals(123L + json, store.sizeOnDisk("opus", "en-es"))
    }

    @Test
    fun sizeOnDisk_carpetaDeMotorQueEsEnlaceCuentaCero() {
        val outside = tmp.newFolder("fuera")
        File(outside, "en-es").mkdirs()
        File(outside, "en-es/grande.bin").writeBytes(ByteArray(5000))
        Assume.assumeTrue(trySymlink(opusDir(), outside))
        assertEquals(0L, store.sizeOnDisk("opus", "en-es"))
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun delete_borraLaCarpetaDelPar() {
        install("en-es")
        install("es-en")
        store.delete("opus", "en-es")
        assertFalse(File(opusDir(), "en-es").exists())
        assertTrue(store.isInstalled("opus", "es-en"))
    }

    @Test
    fun delete_firefoxNoTocaOpus() {
        install("en-es")
        install(".old-en-es-1", installedModel("en-es"))
        val ff = installedModel("en-es", id = "firefox-en-es-1", engine = "firefox")
        install("en-es", ff, engine = "firefox")
        install(".old-en-es-7", ff, engine = "firefox")
        install(".old-en-es--3", ff, engine = "firefox")
        store.delete("firefox", "en-es")
        assertEquals(emptyList(), File(modelsDir, "firefox").list()!!.toList())
        assertEquals(listOf(".old-en-es-1", "en-es"), opusDir().list()!!.sorted())
        assertTrue(store.isInstalled("opus", "en-es"))
        assertTrue(File(opusDir(), ".old-en-es-1/.installed.json").isFile)
    }

    @Test
    fun delete_noExisteNoHaceNada() {
        store.delete("opus", "en-es")
        assertFalse(opusDir().exists())
    }

    @Test
    fun delete_parOMotorInvalidoLanzaIAEYNoBorraNada() {
        install("en-es")
        for (bad in listOf("..", ".", "", ".tmp", "en-es/..", "en-es\\x", "/en-es")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.delete("opus", bad) }
        }
        for (bad in listOf("..", ".", "", ".tmp", "opus/..", "Opus")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.delete(bad, "en-es") }
        }
        assertTrue(store.isInstalled("opus", "en-es"))
        assertTrue(modelsDir.isDirectory)
    }

    @Test
    fun delete_noSigueEnlacesDentroDeLaCarpeta() {
        val outside = tmp.newFolder("fuera")
        val precious = File(outside, "precioso.txt").apply { writeText("no borrar") }
        val dir = install("en-es")
        Assume.assumeTrue(trySymlink(File(dir, "enlace"), outside))
        store.delete("opus", "en-es")
        assertFalse(dir.exists())
        assertTrue(precious.isFile)
    }

    @Test
    fun delete_carpetaDelParQueEsEnlaceBorraSoloElEnlace() {
        val outside = tmp.newFolder("fuera")
        val precious = File(outside, "precioso.txt").apply { writeText("no borrar") }
        opusDir().mkdirs()
        Assume.assumeTrue(trySymlink(File(opusDir(), "en-es"), outside))
        store.delete("opus", "en-es")
        assertFalse(Files.exists(File(opusDir(), "en-es").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(precious.isFile)
    }

    @Test
    fun delete_carpetaDeMotorQueEsEnlaceNoBorraNadaDeFuera() {
        val outside = tmp.newFolder("fuera")
        val precious = File(outside, "en-es/precioso.txt").apply {
            parentFile.mkdirs()
            writeText("no borrar")
        }
        File(outside, "en-es/.installed.json").writeText(installedModel("en-es").toJson())
        Assume.assumeTrue(trySymlink(opusDir(), outside))
        store.delete("opus", "en-es")
        assertTrue(precious.isFile)
        assertTrue(File(outside, "en-es/.installed.json").isFile)
    }

    // ---------------------------------------------------------------- recover

    @Test
    fun recover_sinParRestauraLaViejaMasNueva() {
        install(".old-en-es-100", installedModel("en-es", version = "1.0"))
        install(".old-en-es-300", installedModel("en-es", version = "3.0"))
        install(".old-en-es-200", installedModel("en-es", version = "2.0"))
        store.recover(catalogIds = null)
        assertEquals(listOf(installedModel("en-es", version = "3.0")), store.installed())
        assertEquals(listOf("en-es"), opusDir().list()!!.sorted())
    }

    @Test
    fun recover_restauraOpusOld5SiFaltaElPar() {
        install(".old-en-es-5", installedModel("en-es"))
        store.recover(catalogIds = null)
        assertTrue(store.isInstalled("opus", "en-es"))
        assertEquals(listOf("en-es"), opusDir().list()!!.sorted())
    }

    @Test
    fun recover_cadaMotorPorSeparado() {
        val ff = installedModel("en-es", id = "firefox-en-es-1", engine = "firefox")
        install(".old-en-es-5", ff, engine = "firefox")
        install("en-es")
        install(".old-en-es-9", installedModel("en-es", version = "0.1"))
        store.recover(catalogIds = null)
        assertEquals(listOf("en-es"), opusDir().list()!!.sorted())
        assertEquals("1.0", store.installed().single { it.engine == "opus" }.modelVersion)
        assertTrue(store.isInstalled("firefox", "en-es"))
    }

    @Test
    fun recover_noRestauraUnaViejaDeOtroMotor() {
        install(".old-en-es-5", installedModel("en-es", engine = "firefox"))
        store.recover(catalogIds = null)
        assertFalse(store.isInstalled("opus", "en-es"))
        assertFalse(store.isInstalled("firefox", "en-es"))
        assertEquals(emptyList(), opusDir().list()!!.toList())
    }

    @Test
    fun recover_saltaViejasSinInstalacionValida() {
        install(".old-en-es-100", installedModel("en-es", version = "1.0"))
        install(".old-en-es-300", installedModel("en-es", version = "3.0"))
            .let { File(it, ".installed.json").writeText("{roto") }
        install(".old-en-es-400", installedModel("fr-es")) // .installed.json de otro par
        store.recover(catalogIds = null)
        assertEquals(listOf(installedModel("en-es", version = "1.0")), store.installed())
        assertEquals(listOf("en-es"), opusDir().list()!!.sorted())
    }

    @Test
    fun recover_sinViejaValidaBorraLasViejasYNoCreaElPar() {
        install(".old-en-es-1", installedModel("en-es")).let { File(it, ".installed.json").delete() }
        store.recover(catalogIds = null)
        assertEquals(emptyList(), opusDir().list()!!.toList())
    }

    @Test
    fun recover_conParPresenteBorraLasViejas() {
        install("en-es", installedModel("en-es", version = "2.0"))
        install(".old-en-es-5", installedModel("en-es", version = "1.0"))
        install(".old-en-es-6", installedModel("en-es", version = "0.9"))
        store.recover(catalogIds = null)
        assertEquals(listOf("en-es"), opusDir().list()!!.sorted())
        assertEquals(listOf(installedModel("en-es", version = "2.0")), store.installed())
    }

    @Test
    fun recover_nanoTimeNegativoSeOrdenaComoNumero() {
        install(".old-en-es--50", installedModel("en-es", version = "viejo"))
        install(".old-en-es-7", installedModel("en-es", version = "nuevo"))
        store.recover(catalogIds = null)
        assertEquals("nuevo", store.installed().single().modelVersion)
    }

    @Test
    fun recover_ignoraNombresQueNoSonViejasValidas() {
        install(".old-EN-es-1", installedModel("en-es"))
        install(".old-en-es-abc", installedModel("en-es"))
        File(opusDir(), "otra-cosa.txt").writeText("x")
        File(modelsDir, "otra-cosa.txt").writeText("x")
        store.recover(catalogIds = null)
        assertEquals(listOf(".old-EN-es-1", ".old-en-es-abc", "otra-cosa.txt"), opusDir().list()!!.sorted())
        assertEquals(listOf("opus", "otra-cosa.txt"), modelsDir.list()!!.sorted())
    }

    @Test
    fun recover_viejaQueEsEnlaceSeBorraSinSeguirlaNiRestaurarla() {
        val outside = tmp.newFolder("fuera")
        val fake = install("en-es").let { real ->
            File(outside, "falsa").also {
                real.copyRecursively(it)
                real.deleteRecursively()
            }
        }
        Assume.assumeTrue(trySymlink(File(opusDir(), ".old-en-es-9"), fake))
        store.recover(catalogIds = null)
        assertFalse(Files.exists(File(opusDir(), "en-es").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(File(opusDir(), ".old-en-es-9").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(fake, "model.bin").isFile, "no se toca nada fuera de modelsDir")
    }

    @Test
    fun recover_carpetaDeMotorQueEsEnlaceNoSeSigue() {
        val outside = tmp.newFolder("fuera")
        val fake = install(".old-en-es-9").let { real ->
            File(outside, ".old-en-es-9").also {
                real.copyRecursively(it)
                real.deleteRecursively()
            }
        }
        opusDir().deleteRecursively()
        Assume.assumeTrue(trySymlink(opusDir(), outside))
        store.recover(catalogIds = null)
        assertTrue(File(fake, "model.bin").isFile, "no se toca nada fuera de modelsDir")
        assertFalse(File(outside, "en-es").exists(), "no se renombra nada fuera de modelsDir")
    }

    @Test
    fun recover_borraImportacionesAMedias() {
        File(modelsDir, ".tmp/import-0123abcd").mkdirs()
        File(modelsDir, ".tmp/import-0123abcd/model.bin").writeText("x")
        File(modelsDir, ".tmp/opus-en-es-1").mkdirs()
        File(modelsDir, ".tmp/opus-en-es-1/model.bin.part").writeText("parcial")
        store.recover(catalogIds = null)
        assertEquals(listOf("opus-en-es-1"), File(modelsDir, ".tmp").list()!!.toList())
        assertTrue(File(modelsDir, ".tmp/opus-en-es-1/model.bin.part").isFile)
    }

    @Test
    fun recover_conCatalogoBorraDescargasDeModelosQueYaNoEstan() {
        File(modelsDir, ".tmp/opus-en-es-1").mkdirs()
        File(modelsDir, ".tmp/opus-en-es-2").mkdirs()
        File(modelsDir, ".tmp/suelto.txt").writeText("x")
        store.recover(catalogIds = setOf("opus-en-es-2"))
        assertEquals(listOf("opus-en-es-2"), File(modelsDir, ".tmp").list()!!.toList())
    }

    @Test
    fun recover_sinCatalogoConservaLasDescargas() {
        File(modelsDir, ".tmp/opus-en-es-1").mkdirs()
        File(modelsDir, ".tmp/suelto.txt").writeText("x")
        store.recover(catalogIds = null)
        assertEquals(listOf("opus-en-es-1"), File(modelsDir, ".tmp").list()!!.toList())
    }

    @Test
    fun recover_tmpQueEsEnlaceSeBorraSinSeguirlo() {
        val outside = tmp.newFolder("fuera")
        File(outside, "import-1").mkdirs()
        Assume.assumeTrue(trySymlink(File(modelsDir, ".tmp"), outside))
        store.recover(catalogIds = setOf())
        assertFalse(Files.exists(File(modelsDir, ".tmp").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(outside, "import-1").isDirectory)
    }

    @Test
    fun recover_siFallaRestaurarConservaLaUnicaCopiaYReintentaDespues() {
        var fails = 1
        val flaky = ModelStore(modelsDir) { from, to ->
            if (fails-- > 0) throw IOException("fallo simulado")
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }
        install(".old-en-es-1", installedModel("en-es", version = "1.0"))
        install(".old-en-es-2", installedModel("en-es", version = "2.0"))
        flaky.recover(catalogIds = null)
        assertEquals(listOf(".old-en-es-1", ".old-en-es-2"), opusDir().list()!!.sorted(), "no se borra ninguna copia")
        flaky.recover(catalogIds = null)
        assertEquals(listOf("en-es"), opusDir().list()!!.sorted())
        assertEquals("2.0", store.installed().single().modelVersion)
    }

    @Test
    fun delete_borraTambienLasViejasDelParYNoResucita() {
        install("en-es")
        install(".old-en-es-5", installedModel("en-es"))
        install(".old-es-en-3", installedModel("es-en"))
        store.delete("opus", "en-es")
        assertEquals(listOf(".old-es-en-3"), opusDir().list()!!.sorted())
        store.recover(catalogIds = null)
        assertFalse(store.isInstalled("opus", "en-es"))
        assertTrue(store.isInstalled("opus", "es-en"))
    }

    @Test
    fun delete_aMediasNoDejaNadaQueParezcaInstalado() {
        install("en-es")
        install(".old-en-es-5", installedModel("en-es"))
        var calls = 0
        val roto = ModelStore(modelsDir, deleteTree = { p ->
            if (calls++ == 0) throw IOException("fallo simulado")
            ModelFiles.deleteTree(p)
        }) { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) }
        assertFailsWith<ModelFileException> { roto.delete("opus", "en-es") }
        assertTrue(opusDir().list()!!.isNotEmpty(), "el borrado quedó a medias")
        store.recover(catalogIds = null)
        assertFalse(store.isInstalled("opus", "en-es"), "ni <pair>/ ni una .old- a medias cuentan como instalado")
        assertEquals(emptyList(), store.installed())
    }

    @Test
    fun recover_esIdempotenteYSinCarpetaNoFalla() {
        ModelStore(File(modelsDir, "no-existe")).recover(catalogIds = null)
        install(".old-en-es-1", installedModel("en-es"))
        store.recover(catalogIds = null)
        store.recover(catalogIds = null)
        assertEquals(listOf("en-es"), opusDir().list()!!.toList())
        assertFalse(File(modelsDir, "no-existe").exists())
    }

    // ---------------------------------------------------------------- tamaños en .installed.json

    private fun withSizes(bin: Long, spm: Long) =
        installedModel("en-es").copy(sizes = mapOf("model.bin" to bin, "vocab.spm" to spm))

    @Test
    fun tamanos_coincidentesCuentanComoInstalado() {
        install("en-es", withSizes(100, 23))
        assertTrue(store.isInstalled("opus", "en-es"))
        assertEquals(1, store.installed().size)
    }

    @Test
    fun tamanos_distintosExcluyenElModelo() {
        val dir = install("en-es", withSizes(100, 23))
        File(dir, "model.bin").writeBytes(ByteArray(10)) // truncado
        assertEquals(null, store.installedDir("opus", "en-es"))
        assertEquals(emptyList(), store.installed())
    }

    @Test
    fun tamanos_ausentesDeLa2bSeAceptanSinComprobar() {
        val dir = install("en-es", installedModel("en-es"))
        File(dir, "model.bin").writeBytes(ByteArray(10))
        assertTrue(store.isInstalled("opus", "en-es"))
    }

    @Test
    fun tamanos_jsonDe2bSinSizesSeLee() {
        val json = """{"id":"opus-en-es-1","pair":"en-es","engine":"opus","modelVersion":"1.0","files":["model.bin"]}"""
        assertEquals(emptyMap(), InstalledModel.fromJson(json).sizes)
    }

    @Test
    fun tamanos_roundTripYValidacion() {
        val m = withSizes(100, 23)
        assertEquals(m, InstalledModel.fromJson(m.toJson()))
        val bad = """{"id":"opus-en-es-1","pair":"en-es","engine":"opus","modelVersion":"1.0","files":["model.bin"],"sizes":{"model.bin":-1}}"""
        assertFailsWith<IllegalArgumentException> { InstalledModel.fromJson(bad) }
        val missing = """{"id":"opus-en-es-1","pair":"en-es","engine":"opus","modelVersion":"1.0","files":["model.bin"],"sizes":{}}"""
        assertFailsWith<IllegalArgumentException> { InstalledModel.fromJson(missing) }
    }
}
