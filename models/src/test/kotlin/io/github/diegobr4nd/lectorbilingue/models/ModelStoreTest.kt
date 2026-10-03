package io.github.diegobr4nd.lectorbilingue.models

import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
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

    private fun installedModel(pair: String, id: String = "opus-$pair-1", version: String = "1.0") =
        InstalledModel(id, pair, "opus", version, listOf("model.bin", "vocab.spm"))

    /** Crea `dirName/` con dos archivos y su `.installed.json`. */
    private fun install(dirName: String, model: InstalledModel = installedModel(dirName)): File {
        val dir = File(modelsDir, dirName)
        dir.mkdirs()
        File(dir, "model.bin").writeBytes(ByteArray(100))
        File(dir, "vocab.spm").writeBytes(ByteArray(23))
        File(dir, ".installed.json").writeText(model.toJson())
        return dir
    }

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
    fun installed_leeCadaCarpetaOrdenadaPorPar() {
        install("es-en")
        install("en-es")
        assertEquals(listOf(installedModel("en-es"), installedModel("es-en")), store.installed())
    }

    @Test
    fun installed_ignoraTemporalesViejasYCarpetasRaras() {
        install("en-es")
        install(".tmp", installedModel("fr-es"))
        install(".old-de-es-123", installedModel("de-es"))
        install("no_es_par", installedModel("it-es"))
        File(modelsDir, "pt-es").writeText("un archivo, no una carpeta")
        assertEquals(listOf(installedModel("en-es")), store.installed())
    }

    @Test
    fun installed_ignoraJsonRotoAusenteODeOtroPar() {
        install("en-es")
        File(modelsDir, "fr-es").mkdirs()
        install("de-es").let { File(it, ".installed.json").writeText("{roto") }
        install("it-es", installedModel("pt-es"))
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
        File(modelsDir, "fr-es").mkdirs()
        assertTrue(store.isInstalled("en-es"))
        assertFalse(store.isInstalled("fr-es"))
        assertFalse(store.isInstalled("de-es"))
    }

    @Test
    fun isInstalled_parInvalidoLanzaIAE() {
        for (bad in listOf("", "..", "../x", "en/es", "EN-ES", ".tmp", "en-es-x", "e-es")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.isInstalled(bad) }
        }
    }

    @Test
    fun installed_noSigueEnlaces() {
        val outside = tmp.newFolder("fuera")
        install("en-es").copyRecursively(File(outside, "copia"))
        Assume.assumeTrue(trySymlink(File(modelsDir, "fr-es"), File(outside, "copia")))
        assertEquals(listOf("en-es"), store.installed().map { it.pair })
    }

    // ---------------------------------------------------------------- sizeOnDisk

    @Test
    fun sizeOnDisk_sumaLosArchivos() {
        install("en-es")
        val json = File(modelsDir, "en-es/.installed.json").length()
        assertEquals(123L + json, store.sizeOnDisk("en-es"))
    }

    @Test
    fun sizeOnDisk_ceroSiNoExiste() {
        assertEquals(0L, store.sizeOnDisk("en-es"))
    }

    @Test
    fun sizeOnDisk_parInvalidoLanzaIAE() {
        assertFailsWith<IllegalArgumentException> { store.sizeOnDisk("../x") }
    }

    @Test
    fun sizeOnDisk_noCuentaLoQueHayDetrasDeUnEnlace() {
        val outside = tmp.newFolder("fuera")
        File(outside, "grande.bin").writeBytes(ByteArray(5000))
        val dir = install("en-es")
        Assume.assumeTrue(trySymlink(File(dir, "enlace"), outside))
        val json = File(dir, ".installed.json").length()
        assertEquals(123L + json, store.sizeOnDisk("en-es"))
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun delete_borraLaCarpetaDelPar() {
        install("en-es")
        install("es-en")
        store.delete("en-es")
        assertFalse(File(modelsDir, "en-es").exists())
        assertTrue(store.isInstalled("es-en"))
    }

    @Test
    fun delete_noExisteNoHaceNada() {
        store.delete("en-es")
        assertFalse(File(modelsDir, "en-es").exists())
    }

    @Test
    fun delete_parInvalidoLanzaIAEYNoBorraNada() {
        install("en-es")
        for (bad in listOf("..", ".", "", ".tmp", "en-es/..", "en-es\\x", "/en-es")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.delete(bad) }
        }
        assertTrue(store.isInstalled("en-es"))
        assertTrue(modelsDir.isDirectory)
    }

    @Test
    fun delete_noSigueEnlacesDentroDeLaCarpeta() {
        val outside = tmp.newFolder("fuera")
        val precious = File(outside, "precioso.txt").apply { writeText("no borrar") }
        val dir = install("en-es")
        Assume.assumeTrue(trySymlink(File(dir, "enlace"), outside))
        store.delete("en-es")
        assertFalse(dir.exists())
        assertTrue(precious.isFile)
    }

    @Test
    fun delete_carpetaDelParQueEsEnlaceBorraSoloElEnlace() {
        val outside = tmp.newFolder("fuera")
        val precious = File(outside, "precioso.txt").apply { writeText("no borrar") }
        Assume.assumeTrue(trySymlink(File(modelsDir, "en-es"), outside))
        store.delete("en-es")
        assertFalse(Files.exists(File(modelsDir, "en-es").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(precious.isFile)
    }

    // ---------------------------------------------------------------- recover

    @Test
    fun recover_sinParRestauraLaViejaMasNueva() {
        install(".old-en-es-100", installedModel("en-es", version = "1.0"))
        install(".old-en-es-300", installedModel("en-es", version = "3.0"))
        install(".old-en-es-200", installedModel("en-es", version = "2.0"))
        store.recover(catalogIds = null)
        assertEquals(listOf(installedModel("en-es", version = "3.0")), store.installed())
        assertEquals(listOf("en-es"), modelsDir.list()!!.sorted())
    }

    @Test
    fun recover_saltaViejasSinInstalacionValida() {
        install(".old-en-es-100", installedModel("en-es", version = "1.0"))
        install(".old-en-es-300", installedModel("en-es", version = "3.0"))
            .let { File(it, ".installed.json").writeText("{roto") }
        install(".old-en-es-400", installedModel("fr-es")) // .installed.json de otro par
        store.recover(catalogIds = null)
        assertEquals(listOf(installedModel("en-es", version = "1.0")), store.installed())
        assertEquals(listOf("en-es"), modelsDir.list()!!.sorted())
    }

    @Test
    fun recover_sinViejaValidaBorraLasViejasYNoCreaElPar() {
        install(".old-en-es-1", installedModel("en-es")).let { File(it, ".installed.json").delete() }
        store.recover(catalogIds = null)
        assertEquals(emptyList(), modelsDir.list()!!.toList())
    }

    @Test
    fun recover_conParPresenteBorraLasViejas() {
        install("en-es", installedModel("en-es", version = "2.0"))
        install(".old-en-es-5", installedModel("en-es", version = "1.0"))
        install(".old-en-es-6", installedModel("en-es", version = "0.9"))
        store.recover(catalogIds = null)
        assertEquals(listOf("en-es"), modelsDir.list()!!.sorted())
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
        File(modelsDir, "otra-cosa.txt").writeText("x")
        store.recover(catalogIds = null)
        assertEquals(listOf(".old-EN-es-1", ".old-en-es-abc", "otra-cosa.txt"), modelsDir.list()!!.sorted())
    }

    @Test
    fun recover_viejaQueEsEnlaceSeBorraSinSeguirlaNiRestaurarla() {
        val outside = tmp.newFolder("fuera")
        val fake = install("en-es").let { real ->
            File(outside, "falsa").also { real.copyRecursively(it); real.deleteRecursively() }
        }
        Assume.assumeTrue(trySymlink(File(modelsDir, ".old-en-es-9"), fake))
        store.recover(catalogIds = null)
        assertFalse(Files.exists(File(modelsDir, "en-es").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(File(modelsDir, ".old-en-es-9").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(fake, "model.bin").isFile, "no se toca nada fuera de modelsDir")
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
    fun recover_esIdempotenteYSinCarpetaNoFalla() {
        ModelStore(File(modelsDir, "no-existe")).recover(catalogIds = null)
        install(".old-en-es-1", installedModel("en-es"))
        store.recover(catalogIds = null)
        store.recover(catalogIds = null)
        assertEquals(listOf("en-es"), modelsDir.list()!!.toList())
        assertFalse(File(modelsDir, "no-existe").exists())
    }
}
