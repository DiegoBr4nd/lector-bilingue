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
 * la firma y por último el catálogo. Si la app muere entre los dos renombres queda una firma nueva con el
 * catálogo viejo: ese par no verifica, así que [current] lo ignora y cae al incrustado, y el siguiente
 * [refresh] lo repara. Nunca se acepta un par mezclado.
 *
 * [current] y [refresh] se sincronizan entre sí (un mismo objeto por proceso).
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

    /** Un catálogo verificado junto con los bytes exactos que se firmaron. */
    private class Verified(val bytes: ByteArray, val catalog: Catalog)

    private val lock = Any()

    /** Solo pruebas: se llama entre el renombre de la firma y el del catálogo (simula un corte). */
    internal var afterSignatureRename: () -> Unit = {}

    /** El mejor catálogo válido conocido sin red: guardado o incrustado (el más nuevo). */
    fun current(): Catalog? = synchronized(lock) { best()?.catalog }

    /**
     * Descarga, verifica, aplica antirretroceso y guarda. Devuelve el catálogo vigente tras la operación.
     *
     * Orden: topes de tamaño → firma sobre los bytes exactos → validación → antirretroceso → guardado.
     * Ante cualquier fallo no toca lo guardado y relanza ([IOException], [NetworkPolicyException],
     * [SignatureException] o [CatalogException]).
     */
    fun refresh(): Catalog = synchronized(lock) {
        val bytes = fetcher.fetchBytes(catalogUrl, CatalogParser.MAX_BYTES)
        val signature = String(fetcher.fetchBytes("$catalogUrl.minisig", MAX_SIGNATURE_BYTES), Charsets.UTF_8)
        verifier.verify(bytes, signature)
        val catalog = CatalogParser.parse(bytes)

        var sameAsKnown = false
        for (known in listOfNotNull(loadSaved(), loadBundled())) {
            val cmp = catalog.generated.compareTo(known.catalog.generated)
            if (cmp < 0) throw CatalogException("catálogo más antiguo")
            if (cmp == 0) {
                if (!bytes.contentEquals(known.bytes)) throw CatalogException("catálogo con la misma fecha y otro contenido")
                sameAsKnown = true
            }
        }
        if (!sameAsKnown) persist(bytes, signature)
        best()?.catalog ?: catalog
    }

    private fun best(): Verified? {
        val saved = loadSaved()
        val inApk = loadBundled()
        return when {
            saved == null -> inApk
            inApk == null -> saved
            inApk.catalog.generated > saved.catalog.generated -> inApk
            else -> saved
        }
    }

    private fun loadSaved(): Verified? {
        val catalogFile = File(dir, CATALOG_FILE)
        val signatureFile = File(dir, SIGNATURE_FILE)
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
        if (bytes.size > CatalogParser.MAX_BYTES || signature.length > MAX_SIGNATURE_BYTES) return null
        verifier.verify(bytes, signature)
        Verified(bytes, CatalogParser.parse(bytes))
    } catch (e: Exception) {
        null
    }

    private fun persist(bytes: ByteArray, signature: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no se pudo crear la carpeta del catálogo")
        val catalogTmp = File(dir, CATALOG_FILE + TMP_SUFFIX)
        val signatureTmp = File(dir, SIGNATURE_FILE + TMP_SUFFIX)
        try {
            writeSynced(signatureTmp, signature.toByteArray(Charsets.UTF_8))
            writeSynced(catalogTmp, bytes)
            move(signatureTmp, File(dir, SIGNATURE_FILE))
            afterSignatureRename()
            move(catalogTmp, File(dir, CATALOG_FILE))
        } finally {
            catalogTmp.delete()
            signatureTmp.delete()
        }
    }

    private fun writeSynced(file: File, data: ByteArray) {
        FileOutputStream(file, false).use { out ->
            out.write(data)
            out.fd.sync()
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
