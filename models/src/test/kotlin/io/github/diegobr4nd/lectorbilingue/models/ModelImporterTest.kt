package io.github.diegobr4nd.lectorbilingue.models

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var modelsDir: File

    private val bodyA = bytes(5000, seed = 1)
    private val bodyB = bytes(3000, seed = 2)

    @Before
    fun setUp() {
        modelsDir = tmp.newFolder("models")
    }

    // ---------------------------------------------------------------- ayudas

    private fun bytes(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 31 + seed * 7 + it / 97) and 0xFF).toByte() }

    private fun sha(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun file(name: String, body: ByteArray, size: Long = body.size.toLong()) =
        ModelFile(name, size, sha(body), "https://example.invalid/$name")

    private fun model(
        id: String = "opus-en-es-2",
        pair: String = "en-es",
        files: List<ModelFile> = listOf(file("model.bin", bodyA), file("vocab.spm", bodyB)),
        engine: String = "opus",
    ) = CatalogModel(id, pair, engine, "2.0", "CC-BY-4.0", "Helsinki-NLP", files)

    private fun catalog(vararg models: CatalogModel) = Catalog(1, Instant.parse("2026-10-03T00:00:00Z"), models.toList())

    private fun defaultCatalog() = catalog(model())

    /** Arma un zip; DEFLATED por defecto, STORED si se pide (con tamaño y CRC reales). */
    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = body.size.toLong()
                    e.compressedSize = body.size.toLong()
                    e.crc = CRC32().also { it.update(body) }.value
                }
                z.putNextEntry(e)
                z.write(body)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun validZip() = zip("model.bin" to bodyA, "vocab.spm" to bodyB)

    /** Cambia todas las apariciones de [from] por [to] (mismo largo) en los bytes del zip. */
    private fun patch(zip: ByteArray, from: String, to: String): ByteArray {
        val f = from.toByteArray()
        val t = to.toByteArray()
        require(f.size == t.size)
        val out = zip.copyOf()
        var i = 0
        var count = 0
        while (i <= out.size - f.size) {
            if ((f.indices).all { out[i + it] == f[it] }) {
                t.copyInto(out, i)
                count++
                i += f.size
            } else {
                i++
            }
        }
        require(count > 0)
        return out
    }

    private fun importer(installer: ModelInstaller = ModelInstaller(modelsDir)) = ModelImporter(modelsDir, installer)

    private fun import(bytes: ByteArray, cat: Catalog = defaultCatalog(), imp: ModelImporter = importer()) =
        imp.import(ByteArrayInputStream(bytes), cat)

    /** Tras un rechazo: no queda carpeta temporal ni nada nuevo en modelsDir. */
    private fun assertCleanAfterReject(before: Set<String> = emptySet()) {
        val tmpRoot = File(modelsDir, ".tmp")
        if (tmpRoot.exists()) {
            assertEquals(emptyList(), tmpRoot.list()!!.toList(), "quedaron temporales en .tmp")
        }
        val now = modelsDir.list()!!.filter { it != ".tmp" }.toSet()
        assertEquals(before, now, "apareció algo nuevo en modelsDir")
    }

    /** El mensaje no puede llevar rutas internas ni nombres de carpetas temporales; sin causa. */
    private fun assertNoPath(e: Throwable) {
        val msg = e.message.orEmpty()
        assertFalse(msg.contains('/') || msg.contains('\\'), "el mensaje lleva una ruta")
        assertFalse(msg.contains(tmp.root.name), "el mensaje lleva la carpeta temporal")
        assertFalse(msg.contains("import-"), "el mensaje lleva la carpeta de importación")
        assertFalse(msg.contains("..") , "el mensaje lleva el nombre malicioso")
        assertEquals(null, e.cause, "la causa podría llevar rutas")
    }

    // ---------------------------------------------------------------- caso válido

    @Test
    fun import_zipValidoSeInstala() {
        val result = import(validZip())
        assertEquals(InstalledModel("opus-en-es-2", "en-es", "opus", "2.0", listOf("model.bin", "vocab.spm"), mapOf("model.bin" to 5000L, "vocab.spm" to 3000L)), result)
        val dir = File(modelsDir, "opus/en-es")
        assertEquals(listOf(".installed.json", "model.bin", "vocab.spm"), dir.list()!!.sorted())
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
        assertContentEquals(bodyB, File(dir, "vocab.spm").readBytes())
        assertFalse(File(modelsDir, ".tmp").exists(), "no quedan temporales")
    }

    @Test
    fun import_zipStoredValidoSeInstala() {
        import(zip("vocab.spm" to bodyB, "model.bin" to bodyA, stored = true))
        assertContentEquals(bodyA, File(modelsDir, "opus/en-es/model.bin").readBytes())
    }

    @Test
    fun import_reemplazaUnaDescargaViejaDelMismoModelo() {
        val stale = File(modelsDir, ".tmp/opus-en-es-2")
        stale.mkdirs()
        File(stale, "model.bin.part").writeText("a medias")
        File(stale, "basura").writeText("x")
        import(validZip())
        assertContentEquals(bodyA, File(modelsDir, "opus/en-es/model.bin").readBytes())
        assertFalse(File(modelsDir, ".tmp").exists())
    }

    @Test
    fun import_conservaOtrasDescargasEnCurso() {
        File(modelsDir, ".tmp/otro-modelo").mkdirs()
        import(validZip())
        assertTrue(File(modelsDir, ".tmp/otro-modelo").isDirectory)
    }

    @Test
    fun import_dosModelosConLosMismosNombresGanaElQueCoincideEnSha() {
        val other = model(id = "opus-en-es-1", files = listOf(file("model.bin", bodyB), file("vocab.spm", bodyA)))
        val result = import(validZip(), catalog(other, model()))
        assertEquals("opus-en-es-2", result.id)
        // Y al revés: el zip del otro modelo instala el otro.
        val result2 = import(zip("model.bin" to bodyB, "vocab.spm" to bodyA), catalog(other, model()))
        assertEquals("opus-en-es-1", result2.id)
    }

    @Test
    fun import_identificaPorElConjuntoExactoDeNombres() {
        val small = model(id = "solo-modelo", pair = "fr-es", files = listOf(file("model.bin", bodyA)))
        val result = import(zip("model.bin" to bodyA), catalog(model(), small))
        assertEquals("solo-modelo", result.id)
        assertTrue(File(modelsDir, "opus/fr-es/model.bin").isFile)
        assertFalse(File(modelsDir, "opus/en-es").exists())
    }

    @Test
    fun import_zipDeUnModeloFirefoxVaASuCarpeta() {
        val ff = model(
            id = "firefox-es-en-1",
            pair = "es-en",
            files = listOf(file("model.esen.intgemm.alphas.bin", bodyA), file("vocab.esen.spm", bodyB)),
            engine = "firefox",
        )
        val result = import(zip("model.esen.intgemm.alphas.bin" to bodyA, "vocab.esen.spm" to bodyB), catalog(model(), ff))
        assertEquals("firefox", result.engine)
        val dir = File(modelsDir, "firefox/es-en")
        assertEquals(listOf(".installed.json", "model.esen.intgemm.alphas.bin", "vocab.esen.spm"), dir.list()!!.sorted())
        assertContentEquals(bodyA, File(dir, "model.esen.intgemm.alphas.bin").readBytes())
        assertEquals(listOf("firefox"), modelsDir.list()!!.sorted())
    }

    // ---------------------------------------------------------------- nombres maliciosos

    private fun assertRejectedInvalidEntry(bytes: ByteArray) {
        val e = assertFailsWith<IntegrityException> { import(bytes) }
        assertEquals("el zip contiene una entrada no válida", e.message)
        assertNoPath(e)
        assertCleanAfterReject()
        assertFalse(File(tmp.root, "x").exists(), "zip slip escribió fuera")
    }

    @Test
    fun import_entradaConPuntoPuntoSeRechaza() {
        assertRejectedInvalidEntry(zip("../x" to bodyA))
    }

    @Test
    fun import_entradaConPuntoPuntoDespuesDeUnaValidaSeRechaza() {
        assertRejectedInvalidEntry(zip("model.bin" to bodyA, "../../x" to bodyB))
    }

    @Test
    fun import_entradaEnSubcarpetaSeRechaza() {
        assertRejectedInvalidEntry(zip("sub/model.bin" to bodyA, "vocab.spm" to bodyB))
    }

    @Test
    fun import_entradaConBarraInvertidaSeRechaza() {
        assertRejectedInvalidEntry(zip("sub\\model.bin" to bodyA, "vocab.spm" to bodyB))
    }

    @Test
    fun import_entradaAbsolutaSeRechaza() {
        assertRejectedInvalidEntry(zip("/model.bin" to bodyA, "vocab.spm" to bodyB))
    }

    @Test
    fun import_entradaDeCarpetaSeRechaza() {
        assertRejectedInvalidEntry(zip("model.bin" to bodyA, "sub/" to ByteArray(0)))
    }

    @Test
    fun import_entradaConPuntoInicialSeRechaza() {
        assertRejectedInvalidEntry(zip(".installed.json" to "{}".toByteArray()))
    }

    @Test
    fun import_entradaRepetidaSeRechaza() {
        // ZipOutputStream no deja repetir nombres: se escribe "model.biX" y se parchea a "model.bin".
        val bytes = patch(zip("model.bin" to bodyA, "model.biX" to bodyA, "vocab.spm" to bodyB), "model.biX", "model.bin")
        assertRejectedInvalidEntry(bytes)
    }

    @Test
    fun import_entradaConByteNulSeRechaza() {
        val bytes = patch(zip("model.biX" to bodyA, "vocab.spm" to bodyB), "model.biX", "model.bi ")
        assertRejectedInvalidEntry(bytes)
    }

    @Test
    fun import_entradaConLetraDeUnidadSeRechaza() {
        assertRejectedInvalidEntry(zip("C:model.bin" to bodyA, "vocab.spm" to bodyB))
    }

    @Test
    fun import_entradaConLetraCirilicaParecidaSeRechaza() {
        // "о" cirílica (U+043E) en lugar de la "o" latina.
        assertRejectedInvalidEntry(zip("mоdel.bin" to bodyA, "vocab.spm" to bodyB))
    }

    @Test
    fun import_entradaConMayusculasDistintasSeRechaza() {
        // "Model.bin" cumple la regla de nombres pero no es "model.bin": no está en el catálogo.
        val e = assertFailsWith<CatalogException> { import(zip("Model.bin" to bodyA, "vocab.spm" to bodyB)) }
        assertEquals("el zip no corresponde a ningún modelo del catálogo", e.message)
        assertNoPath(e)
        assertCleanAfterReject()
        assertFalse(File(modelsDir, "opus/en-es").exists())
    }

    // ---------------------------------------------------------------- entradas sobrantes o faltantes

    @Test
    fun import_entradaExtraNoListadaSeRechaza() {
        val e = assertFailsWith<CatalogException> {
            import(zip("model.bin" to bodyA, "vocab.spm" to bodyB, "readme.txt" to "hola".toByteArray()))
        }
        assertEquals("el zip no corresponde a ningún modelo del catálogo", e.message)
        assertCleanAfterReject()
    }

    @Test
    fun import_faltaUnArchivoSeRechaza() {
        val e = assertFailsWith<CatalogException> { import(zip("model.bin" to bodyA)) }
        assertEquals("el zip no corresponde a ningún modelo del catálogo", e.message)
        assertCleanAfterReject()
    }

    @Test
    fun import_zipDeOtroModeloSeRechazaConCatalogException() {
        val e = assertFailsWith<CatalogException> { import(zip("pesos.bin" to bodyA)) }
        assertEquals("el zip no corresponde a ningún modelo del catálogo", e.message)
        assertCleanAfterReject()
    }

    @Test
    fun import_masEntradasQueElModeloMasGrandeSeRechaza() {
        // Cada nombre está en algún modelo, pero ningún modelo tiene más de 2 archivos.
        val cat = catalog(
            model(),
            model(id = "otro", pair = "fr-es", files = listOf(file("extra.bin", bodyB))),
        )
        val e = assertFailsWith<IntegrityException> {
            import(zip("model.bin" to bodyA, "vocab.spm" to bodyB, "extra.bin" to bodyB), cat)
        }
        assertEquals("el zip tiene más archivos que cualquier modelo del catálogo", e.message)
        assertCleanAfterReject()
    }

    @Test
    fun import_superarElTamanoTotalDelModeloMasGrandeSeRechaza() {
        val cat = catalog(
            model(id = "a", pair = "en-es", files = listOf(file("a.bin", bodyA), file("b.bin", bytes(10, 3)))),
            model(id = "b", pair = "fr-es", files = listOf(file("c.bin", bodyA))),
        )
        val e = assertFailsWith<IntegrityException> { import(zip("a.bin" to bodyA, "c.bin" to bodyA), cat) }
        assertEquals("el zip es más grande que cualquier modelo del catálogo", e.message)
        assertCleanAfterReject()
    }

    // ---------------------------------------------------------------- bombas

    /** Cuenta cuántos bytes se escriben en cada archivo. */
    private class Counting(private val counts: MutableMap<String, Long>) : (Path) -> OutputStream {
        override fun invoke(p: Path): OutputStream {
            val name = p.fileName.toString()
            val real = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            return object : FilterOutputStream(real) {
                override fun write(b: Int) {
                    counts.merge(name, 1L, Long::plus)
                    out.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    counts.merge(name, len.toLong(), Long::plus)
                    out.write(b, off, len)
                }
            }
        }
    }

    private fun assertBombRejected(stored: Boolean) {
        val small = ByteArray(1024) { 7 }
        val cat = catalog(model(files = listOf(file("model.bin", small))))
        val bomb = zip("model.bin" to ByteArray(10 * 1024 * 1024), stored = stored)
        val counts = HashMap<String, Long>()
        val imp = ModelImporter(modelsDir, ModelInstaller(modelsDir), Counting(counts))
        val e = assertFailsWith<IntegrityException> { imp.import(ByteArrayInputStream(bomb), cat) }
        assertEquals("el archivo model.bin supera el tamaño del catálogo", e.message)
        val written = counts.values.sum()
        assertTrue(written <= 1024 + 64 * 1024, "se escribieron $written bytes")
        assertCleanAfterReject()
    }

    @Test
    fun import_bombaDeflatedSeRechazaSinEscribirDeMas() {
        assertBombRejected(stored = false)
    }

    @Test
    fun import_bombaStoredSeRechazaSinEscribirDeMas() {
        assertBombRejected(stored = true)
    }

    @Test
    fun import_entradaUnByteMasGrandeQueElCatalogoSeRechaza() {
        val e = assertFailsWith<IntegrityException> { import(zip("model.bin" to bodyA + 0, "vocab.spm" to bodyB)) }
        assertEquals("el archivo model.bin supera el tamaño del catálogo", e.message)
        assertCleanAfterReject()
    }

    // ---------------------------------------------------------------- huellas

    @Test
    fun import_shaDistintoSeRechaza() {
        val bad = bodyB.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val e = assertFailsWith<IntegrityException> { import(zip("model.bin" to bodyA, "vocab.spm" to bad)) }
        assertEquals("el archivo vocab.spm no coincide con el catálogo", e.message)
        assertNoPath(e)
        assertCleanAfterReject()
    }

    @Test
    fun import_archivoMasCortoSeRechaza() {
        val e = assertFailsWith<IntegrityException> {
            import(zip("model.bin" to bodyA.copyOf(4000), "vocab.spm" to bodyB))
        }
        assertEquals("el archivo model.bin no coincide con el catálogo", e.message)
        assertCleanAfterReject()
    }

    @Test
    fun import_variosCandidatosYNingunoCoincideSeRechaza() {
        val other = model(id = "opus-en-es-1", files = listOf(file("model.bin", bodyB), file("vocab.spm", bodyA)))
        val e = assertFailsWith<IntegrityException> {
            import(zip("model.bin" to bodyB, "vocab.spm" to bodyB), catalog(other, model()))
        }
        assertEquals("el zip no coincide con ningún modelo del catálogo", e.message)
        assertCleanAfterReject()
    }

    // ---------------------------------------------------------------- zips rotos

    private fun assertInvalidZip(bytes: ByteArray) {
        val e = assertFailsWith<IOException> { import(bytes) }
        assertEquals("zip inválido", e.message)
        assertNoPath(e)
        assertCleanAfterReject()
    }

    @Test
    fun import_zipTruncadoSeRechaza() {
        assertInvalidZip(validZip().copyOf(200))
    }

    @Test
    fun import_zipCorruptoSeRechaza() {
        val bytes = validZip()
        // Se estropean los datos comprimidos de la primera entrada (tras la cabecera local).
        for (i in 60 until 400) bytes[i] = (bytes[i].toInt() xor 0x5A).toByte()
        assertInvalidZip(bytes)
    }

    @Test
    fun import_zipVacioSeRechaza() {
        assertInvalidZip(zip())
    }

    @Test
    fun import_bytesQueNoSonZipSeRechazan() {
        assertInvalidZip("esto no es un zip".toByteArray())
    }

    /** Flujo que recuerda si alguien lo cerró. */
    private class TrackingInput(bytes: ByteArray) : java.io.FilterInputStream(ByteArrayInputStream(bytes)) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test
    fun import_noCierraElFlujoDeQuienLlamaAlTerminarBien() {
        val input = TrackingInput(validZip())
        importer().import(input, defaultCatalog())
        assertFalse(input.closed, "el flujo es de quien llama")
    }

    @Test
    fun import_noCierraElFlujoDeQuienLlamaAlFallar() {
        val input = TrackingInput(zip("../x" to bodyA))
        assertFailsWith<IntegrityException> { importer().import(input, defaultCatalog()) }
        assertFalse(input.closed, "el flujo es de quien llama")
    }

    // ---------------------------------------------------------------- errores al instalar

    @Test
    fun import_siFallaLaInstalacionNoQuedaNadaYElModeloAnteriorSigue() {
        val prev = File(modelsDir, "opus/en-es")
        prev.mkdirs()
        File(prev, "model.bin").writeText("viejo")
        var calls = 0
        val flaky = ModelInstaller(modelsDir) { from, to ->
            calls++
            if (calls == 2) throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "x")
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }
        val e = assertFailsWith<IOException> { import(validZip(), imp = importer(flaky)) }
        assertNoPath(e)
        assertEquals("viejo", File(prev, "model.bin").readText())
        assertCleanAfterReject(before = setOf("opus"))
    }

    @Test
    fun import_errorDeArchivosNoFiltraRutas() {
        // modelsDir es un archivo: no se puede crear .tmp.
        val notDir = tmp.newFile("no-es-carpeta")
        val imp = ModelImporter(notDir, ModelInstaller(notDir))
        val e = assertFailsWith<IOException> { imp.import(ByteArrayInputStream(validZip()), defaultCatalog()) }
        assertEquals("error de archivos al importar el modelo", e.message)
        kotlin.test.assertIs<ModelFileException>(e)
        assertNoPath(e)
    }

    @Test
    fun import_catalogoVacioSeRechaza() {
        val e = assertFailsWith<CatalogException> { import(validZip(), catalog()) }
        assertEquals("el zip no corresponde a ningún modelo del catálogo", e.message)
        assertCleanAfterReject()
    }
}
