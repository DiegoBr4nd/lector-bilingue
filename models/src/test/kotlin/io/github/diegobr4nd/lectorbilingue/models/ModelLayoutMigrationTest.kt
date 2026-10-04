package io.github.diegobr4nd.lectorbilingue.models

import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paso del formato de la 2b (`models/<pair>/`) al de la 2c (`models/<engine>/<pair>/`):
 * [ModelStore.migrateLegacyLayout] y la recuperación de `.old-<pair>-*` de primer nivel.
 */
class ModelLayoutMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var modelsDir: File
    private lateinit var store: ModelStore

    private val bodyA = ByteArray(300) { (it * 7 + 1).toByte() }
    private val bodyB = ByteArray(77) { (it * 13 + 5).toByte() }

    @Before
    fun setUp() {
        modelsDir = tmp.newFolder("models")
        store = ModelStore(modelsDir)
    }

    private fun model(pair: String, engine: String = "opus", version: String = "1.0") =
        InstalledModel("$engine-$pair-1", pair, engine, version, listOf("model.bin", "vocab.spm"))

    /** Crea `models/<path>/` con dos archivos y, si [model] no es null, su `.installed.json`. */
    private fun dir(path: String, model: InstalledModel?): File {
        val dir = File(modelsDir, path)
        dir.mkdirs()
        File(dir, "model.bin").writeBytes(bodyA)
        File(dir, "vocab.spm").writeBytes(bodyB)
        if (model != null) File(dir, ".installed.json").writeText(model.toJson())
        return dir
    }

    private fun opus() = File(modelsDir, "opus")

    /** Foto de un árbol: ruta relativa → bytes (solo archivos). */
    private fun snapshot(root: File): Map<String, List<Byte>> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    private fun trySymlink(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (e: Exception) {
        false // Windows sin privilegios: no se pueden crear enlaces.
    }

    // ---------------------------------------------------------------- migrateLegacyLayout

    @Test
    fun migra_parDePrimerNivelASuMotorConLosMismosBytes() {
        val legacy = dir("en-es", model("en-es"))
        val before = snapshot(legacy)
        store.migrateLegacyLayout()
        assertFalse(legacy.exists())
        val moved = File(opus(), "en-es")
        assertEquals(before, snapshot(moved))
        assertContentEquals(bodyA, File(moved, "model.bin").readBytes())
        assertTrue(store.isInstalled("opus", "en-es"))
        assertEquals(listOf("opus"), modelsDir.list()!!.sorted())
    }

    @Test
    fun migra_cadaParAlMotorDeSuJson() {
        dir("en-es", model("en-es"))
        dir("es-en", model("es-en", engine = "firefox"))
        store.migrateLegacyLayout()
        assertTrue(store.isInstalled("opus", "en-es"))
        assertTrue(store.isInstalled("firefox", "es-en"))
        assertEquals(listOf("firefox", "opus"), modelsDir.list()!!.sorted())
    }

    @Test
    fun migra_carpetaSinJsonSeDejaQuieta() {
        val sinJson = dir("en-es", null)
        val roto = dir("es-en", null).also { File(it, ".installed.json").writeText("{roto") }
        val otroPar = dir("fr-es", model("de-es"))
        store.migrateLegacyLayout()
        assertTrue(File(sinJson, "model.bin").isFile)
        assertTrue(File(roto, "model.bin").isFile)
        assertTrue(File(otroPar, "model.bin").isFile)
        assertFalse(opus().exists())
        assertEquals(emptyList(), store.installed())
    }

    @Test
    fun migra_noTocaTmpNiViejasNiNombresRaros() {
        dir(".tmp/opus-en-es-1", null)
        dir(".old-en-es-3", model("en-es"))
        dir("no_es_par", model("en-es"))
        store.migrateLegacyLayout()
        assertEquals(listOf(".old-en-es-3", ".tmp", "no_es_par"), modelsDir.list()!!.sorted())
    }

    @Test
    fun migra_siElDestinoYaExisteNoTocaNiElLegadoNiElDestino() {
        val legacy = dir("en-es", model("en-es", version = "legado"))
        val target = dir("opus/en-es", model("en-es", version = "nuevo"))
        File(target, "model.bin").writeText("destino")
        val legacyBefore = snapshot(legacy)
        val targetBefore = snapshot(target)
        store.migrateLegacyLayout()
        assertEquals(legacyBefore, snapshot(legacy))
        assertEquals(targetBefore, snapshot(target))
    }

    @Test
    fun migra_dosVecesDaElMismoResultado() {
        dir("en-es", model("en-es"))
        dir("es-en", null)
        store.migrateLegacyLayout()
        val once = snapshot(modelsDir)
        store.migrateLegacyLayout()
        assertEquals(once, snapshot(modelsDir))
        assertTrue(store.isInstalled("opus", "en-es"))
    }

    @Test
    fun migra_corteAMitadEsRepetibleSinPerderDatos() {
        dir("en-es", model("en-es"))
        dir("es-en", model("es-en"))
        val before = snapshot(modelsDir)
        var calls = 0
        val flaky = ModelStore(modelsDir) { from: Path, to: Path ->
            calls++
            if (calls == 2) throw IOException("fallo simulado")
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }
        flaky.migrateLegacyLayout() // no lanza
        assertTrue(store.isInstalled("opus", "en-es"), "el primero queda migrado")
        assertTrue(File(modelsDir, "es-en/.installed.json").isFile, "el segundo sigue en su sitio")
        assertFalse(store.isInstalled("opus", "es-en"))

        flaky.migrateLegacyLayout()
        assertTrue(store.isInstalled("opus", "en-es"))
        assertTrue(store.isInstalled("opus", "es-en"))
        assertEquals(listOf("opus"), modelsDir.list()!!.sorted())
        // Mismos archivos y bytes, solo con el prefijo del motor.
        assertEquals(before.mapKeys { "opus/" + it.key }, snapshot(modelsDir))
    }

    @Test
    fun migra_carpetaDeModelosInexistenteNoFallaNiLaCrea() {
        val missing = File(modelsDir, "no-existe")
        ModelStore(missing).migrateLegacyLayout()
        assertFalse(missing.exists())
    }

    @Test
    fun migra_legadoQueEsEnlaceNoSeMueve() {
        val outside = tmp.newFolder("fuera")
        val real = File(outside, "en-es").apply { mkdirs() }
        File(real, "model.bin").writeBytes(bodyA)
        File(real, "vocab.spm").writeBytes(bodyB)
        File(real, ".installed.json").writeText(model("en-es").toJson())
        Assume.assumeTrue(trySymlink(File(modelsDir, "en-es"), real))
        store.migrateLegacyLayout()
        assertFalse(store.isInstalled("opus", "en-es"))
        assertTrue(File(real, "model.bin").isFile)
    }

    @Test
    fun migra_carpetaDeMotorQueEsEnlaceNoMueveNadaAFuera() {
        val outside = tmp.newFolder("fuera")
        dir("en-es", model("en-es"))
        Assume.assumeTrue(trySymlink(opus(), outside))
        store.migrateLegacyLayout()
        assertFalse(File(outside, "en-es").exists(), "nada se mueve a través de un enlace")
    }

    // ---------------------------------------------------------------- recover de viejas de primer nivel (2b)

    @Test
    fun recover_viejaDePrimerNivelVaASuMotorSiFaltaElPar() {
        dir(".old-en-es-3", model("en-es"))
        store.recover(catalogIds = null)
        assertTrue(store.isInstalled("opus", "en-es"))
        assertEquals(listOf("opus"), modelsDir.list()!!.sorted())
        assertEquals(listOf("en-es"), opus().list()!!.sorted())
    }

    @Test
    fun recover_viejaDePrimerNivelSeBorraSiElParYaExiste() {
        dir("opus/en-es", model("en-es", version = "actual"))
        dir(".old-en-es-3", model("en-es", version = "vieja"))
        store.recover(catalogIds = null)
        assertEquals(listOf("opus"), modelsDir.list()!!.sorted())
        assertEquals("actual", store.installed().single().modelVersion)
    }

    @Test
    fun recover_variasViejasDePrimerNivelGanaLaMasNueva() {
        dir(".old-en-es-3", model("en-es", version = "3"))
        dir(".old-en-es-9", model("en-es", version = "9"))
        dir(".old-en-es-1", null)
        store.recover(catalogIds = null)
        assertEquals("9", store.installed().single().modelVersion)
        assertEquals(listOf("opus"), modelsDir.list()!!.sorted())
    }

    @Test
    fun recover_viejaDePrimerNivelDeFirefoxVaAFirefox() {
        dir(".old-en-es-3", model("en-es", engine = "firefox"))
        store.recover(catalogIds = null)
        assertTrue(store.isInstalled("firefox", "en-es"))
        assertFalse(store.isInstalled("opus", "en-es"))
    }

    @Test
    fun recover_siFallaMoverLaViejaDePrimerNivelSeConservaYReintenta() {
        dir(".old-en-es-3", model("en-es"))
        var fails = 1
        val flaky = ModelStore(modelsDir) { from: Path, to: Path ->
            if (fails-- > 0) throw IOException("fallo simulado")
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }
        flaky.recover(catalogIds = null)
        assertTrue(File(modelsDir, ".old-en-es-3/.installed.json").isFile, "la única copia no se borra")
        flaky.recover(catalogIds = null)
        assertTrue(store.isInstalled("opus", "en-es"))
        assertFalse(File(modelsDir, ".old-en-es-3").exists())
    }

    @Test
    fun recover_viejaDePrimerNivelSinJsonValidoSeBorra() {
        dir(".old-en-es-3", null)
        store.recover(catalogIds = null)
        assertEquals(emptyList(), modelsDir.list()!!.toList())
    }

    @Test
    fun recover_viejaDePrimerNivelEsperaSiElLegadoAunNoSeMigro() {
        // Si `models/en-es/` (2b) sigue sin migrar, la vieja se deja para el siguiente arranque.
        dir("en-es", model("en-es", version = "legado"))
        dir(".old-en-es-3", model("en-es", version = "vieja"))
        store.recover(catalogIds = null)
        assertTrue(File(modelsDir, ".old-en-es-3").isDirectory)
        assertFalse(store.isInstalled("opus", "en-es"))
        store.migrateLegacyLayout()
        store.recover(catalogIds = null)
        assertEquals("legado", store.installed().single().modelVersion)
        assertEquals(listOf("opus"), modelsDir.list()!!.sorted())
    }

    @Test
    fun delete_borraTambienLoHeredadoDeEseMotorParaQueNoResucite() {
        dir("opus/en-es", model("en-es"))
        dir(".old-en-es-3", model("en-es"))
        dir(".old-en-es-4", model("en-es", engine = "firefox"))
        store.delete("opus", "en-es")
        store.recover(catalogIds = null)
        assertFalse(store.isInstalled("opus", "en-es"))
        assertTrue(store.isInstalled("firefox", "en-es"), "lo de otro motor no se toca")
    }

    // ---------------------------------------------------------------- el caso del Pixel de Juan

    @Test
    fun casoReal_ochoArchivosOpusEnEsMigranIntactosYSobrevivenUnCorte() {
        val names = listOf(
            "config.json", "model.bin", "shared_vocabulary.json", "source.spm",
            "target.spm", "LICENSE", "ATTRIBUTION.txt", "MODEL_CARD.md",
        )
        val legacy = File(modelsDir, "en-es").apply { mkdirs() }
        names.forEachIndexed { i, n -> File(legacy, n).writeBytes(ByteArray(50 + i) { (it + i).toByte() }) }
        File(legacy, ".installed.json").writeText(InstalledModel("opus-en-es-1", "en-es", "opus", "1.0", names).toJson())
        val before = snapshot(legacy)

        // Corte: el renombre falla (como si la app muriera justo antes).
        ModelStore(modelsDir) { _: Path, _: Path -> throw IOException("corte") }.migrateLegacyLayout()
        assertEquals(before, snapshot(legacy), "nada se pierde")

        store.migrateLegacyLayout()
        store.recover(catalogIds = null)
        assertEquals(before, snapshot(File(opus(), "en-es")))
        assertEquals(File(opus(), "en-es").canonicalFile, store.installedDir("opus", "en-es")?.canonicalFile)
    }
}
