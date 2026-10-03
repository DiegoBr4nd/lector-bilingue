package io.github.diegobr4nd.lectorbilingue.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Guarda y entrega el catálogo de modelos firmado.
 *
 * Fuentes:
 * - el catálogo **guardado** en [dir] (`catalog.json` + `catalog.json.minisig`), aceptado en un [refresh] anterior;
 * - el catálogo **incrustado** en el APK ([bundled]), que sirve sin red y como piso del antirretroceso.
 *
 * Ambos se re-verifican (firma + validación) en cada lectura: un archivo alterado en disco simplemente
 * deja de contar. La "última fecha aceptada" sale del propio catálogo guardado y verificado (no hay otro
 * archivo de estado).
 *
 * Persistencia: se escriben `catalog.json.minisig.tmp` y `catalog.json.tmp` (con `fsync`), luego se renombra
 * la firma y por último el catálogo. Si la app muere entre los dos renombres queda la firma nueva junto al
 * catálogo viejo (par mezclado, no verifica) y el catálogo nuevo aún en `catalog.json.tmp`. Al leer, si el
 * par principal no verifica se prueba `(catalog.json.tmp, catalog.json.minisig)`, verificado como cualquier
 * otro par: así el piso del antirretroceso no baja, y el siguiente [refresh] repara el par principal.
 * Nunca se acepta un par mezclado. No se hace `fsync` de la carpeta tras los renombres (Java no lo expone de
 * forma portable): en el peor caso, tras un corte de luz, se vuelve a un par anterior, que se re-verifica al
 * leer como siempre.
 *
 * Bloqueo: la descarga, la firma y la validación de [refresh] van FUERA del candado; solo el antirretroceso,
 * el guardado y el cálculo del resultado van dentro. Así [current] (llamado desde la UI) nunca espera a la red.
 */
class CatalogRepository(
    private val dir: File,
    private val verifier: MinisignVerifier,
    private val fetcher: HttpFetcher,
    private val bundled: () -> Pair<ByteArray, String>?,
    private val catalogUrl: String = CATALOG_URL,
) {
    companion object {
        const val CATALOG_URL = "https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/catalogo/catalog.json"

        /** Tope de la firma `.minisig` (spec: ≤ 4 KiB). */
        const val MAX_SIGNATURE_BYTES = 4096

        private const val CATALOG_FILE = "catalog.json"
        private const val SIGNATURE_FILE = "catalog.json.minisig"
        private const val TMP_SUFFIX = ".tmp"
    }

    /**
     * Un catálogo verificado junto con los bytes exactos que se firmaron.
     * [recovered] = viene de `catalog.json.tmp` (corte entre renombres): hay que reparar el par principal.
     */
    private class Verified(val bytes: ByteArray, val catalog: Catalog, val recovered: Boolean = false)

    private val lock = Any()

    /**
     * SOLO PARA PRUEBAS (no usar en código de la app): se llama entre el renombre de la firma y el del
     * catálogo. Si lanza, simula que la app murió ahí (se conserva `catalog.json.tmp`).
     */
    internal var afterSignatureRename: () -> Unit = {}

    /** El mejor catálogo válido conocido sin red: guardado o incrustado (el más nuevo). */
    fun current(): Catalog? = synchronized(lock) { newest(loadSaved(), loadBundled())?.catalog }

    /**
     * Descarga, verifica, aplica antirretroceso y guarda. Devuelve el catálogo vigente tras la operación.
     *
     * Orden: topes de tamaño → firma sobre los bytes exactos → validación → antirretroceso → guardado.
     * Ante cualquier fallo no toca lo guardado y relanza ([IOException], [NetworkPolicyException],
     * [SignatureException] o [CatalogException]). Los errores de disco salen como [ModelFileException], sin rutas.
     */
    fun refresh(): Catalog {
        // Fuera del candado: red, firma y validación solo usan valores locales.
        val bytes = fetcher.fetchBytes(catalogUrl, CatalogParser.MAX_BYTES)
        val signatureBytes = fetcher.fetchBytes("$catalogUrl.minisig", MAX_SIGNATURE_BYTES)
        verifier.verify(bytes, String(signatureBytes, Charsets.UTF_8))
        val downloaded = CatalogParser.parse(bytes)

        return synchronized(lock) {
            val saved = loadSaved()
            val inApk = loadBundled()
            var sameAsKnown = false
            for (known in listOfNotNull(saved, inApk)) {
                val cmp = downloaded.generated.compareTo(known.catalog.generated)
                if (cmp < 0) throw CatalogException("catálogo más antiguo")
                if (cmp == 0) {
                    if (!bytes.contentEquals(known.bytes)) throw CatalogException("catálogo con la misma fecha y otro contenido")
                    // Igual a un par recuperado del temporal: se guarda igual para reparar el par principal.
                    if (!known.recovered) sameAsKnown = true
                }
            }
            if (sameAsKnown) {
                newest(saved, inApk)!!.catalog
            } else {
                persist(bytes, signatureBytes)
                downloaded
            }
        }
    }

    /** El más nuevo de los dos; en empate, el guardado. */
    private fun newest(saved: Verified?, inApk: Verified?): Verified? = when {
        saved == null -> inApk
        inApk == null -> saved
        inApk.catalog.generated > saved.catalog.generated -> inApk
        else -> saved
    }

    private fun loadSaved(): Verified? {
        val signatureFile = File(dir, SIGNATURE_FILE)
        return loadPair(File(dir, CATALOG_FILE), signatureFile)
            ?: loadPair(File(dir, CATALOG_FILE + TMP_SUFFIX), signatureFile)
                ?.let { Verified(it.bytes, it.catalog, recovered = true) }
    }

    private fun loadPair(catalogFile: File, signatureFile: File): Verified? {
        if (!catalogFile.isFile || !signatureFile.isFile) return null
        if (catalogFile.length() > CatalogParser.MAX_BYTES || signatureFile.length() > MAX_SIGNATURE_BYTES) return null
        return verifyOrNull {
            Pair(catalogFile.readBytes(), String(signatureFile.readBytes(), Charsets.UTF_8))
        }
    }

    private fun loadBundled(): Verified? = verifyOrNull(bundled)

    /**
     * Lee el par con [read] y lo verifica; ante cualquier problema devuelve null sin registrar nada.
     * Se atrapa [Exception] (no [Error]) porque un archivo dañado no debe tumbar la app desde [current].
     */
    private fun verifyOrNull(read: () -> Pair<ByteArray, String>?): Verified? = try {
        val (bytes, signature) = read() ?: return null
        // Tope de la firma en bytes UTF-8 (no en caracteres UTF-16).
        if (bytes.size > CatalogParser.MAX_BYTES || signature.toByteArray(Charsets.UTF_8).size > MAX_SIGNATURE_BYTES) return null
        verifier.verify(bytes, signature)
        Verified(bytes, CatalogParser.parse(bytes))
    } catch (e: Exception) {
        null
    }

    private fun persist(bytes: ByteArray, signature: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw fileError()
        val catalogTmp = File(dir, CATALOG_FILE + TMP_SUFFIX)
        val signatureTmp = File(dir, SIGNATURE_FILE + TMP_SUFFIX)
        var signatureRenamed = false
        try {
            writeSynced(signatureTmp, signature)
            writeSynced(catalogTmp, bytes)
            move(signatureTmp, File(dir, SIGNATURE_FILE))
            signatureRenamed = true
            afterSignatureRename()
            move(catalogTmp, File(dir, CATALOG_FILE))
        } finally {
            signatureTmp.delete()
            // Tras renombrar la firma, catalog.json.tmp es la pareja de la firma vigente: se conserva.
            if (!signatureRenamed) catalogTmp.delete()
        }
    }

    private fun writeSynced(file: File, data: ByteArray) = fileOp {
        FileOutputStream(file, false).use { out ->
            out.write(data)
            out.fd.sync()
        }
    }

    private fun move(from: File, to: File) = fileOp {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Errores de disco → [ModelFileException] sin ruta ni causa (los de java.io/nio llevan rutas internas). */
    private inline fun fileOp(block: () -> Unit) = try {
        block()
    } catch (e: IOException) {
        throw fileError()
    }

    private fun fileError() = ModelFileException("error de archivos al guardar el catálogo")
}
