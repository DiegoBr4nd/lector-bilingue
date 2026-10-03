package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import io.github.diegobr4nd.lectorbilingue.models.Catalog
import io.github.diegobr4nd.lectorbilingue.models.CatalogException
import io.github.diegobr4nd.lectorbilingue.models.CatalogModel
import io.github.diegobr4nd.lectorbilingue.models.DownloadInProgressException
import io.github.diegobr4nd.lectorbilingue.models.IntegrityException
import io.github.diegobr4nd.lectorbilingue.models.ModelFileException
import io.github.diegobr4nd.lectorbilingue.models.NetworkPolicyException
import io.github.diegobr4nd.lectorbilingue.models.SignatureException
import java.io.IOException

/** Mensajes fijos (cada uno tiene su texto en strings.xml). Nunca se muestra el texto de una excepción. */
enum class ModelMessage {
    NO_CATALOG, NO_MODEL, DOWNLOAD_BUSY, CANCELLED, NETWORK, POLICY, SIGNATURE, INTEGRITY, CATALOG, FILES,
    INVALID_ZIP, IMPORT_OK, UNKNOWN,
}

/** Lógica pura (sin Android) de los botones de modelos: se prueba en la JVM. */
object ModelActions {
    private const val MIB = 1_048_576L

    /** El modelo en-es del motor opus, o null si el catálogo no lo trae. */
    fun pickModel(catalog: Catalog): CatalogModel? =
        catalog.findByPair("en-es").firstOrNull { it.engine == "opus" }

    /** Bytes a megabytes (MiB) redondeando al más cercano. */
    fun megabytes(bytes: Long): Long = (bytes + MIB / 2) / MIB

    /** Avance 0..1, o null si aún no se conoce el total. */
    fun fraction(bytes: Long, total: Long): Float? =
        if (total <= 0) null else (bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()

    /** Excepción de la capa de modelos a mensaje fijo. ModelFileException va antes que IOException (es subclase). */
    fun classify(e: Throwable): ModelMessage = when (e) {
        is DownloadInProgressException -> ModelMessage.DOWNLOAD_BUSY
        is ModelFileException -> ModelMessage.FILES
        is NetworkPolicyException -> ModelMessage.POLICY
        is IOException -> ModelMessage.NETWORK
        is SignatureException -> ModelMessage.SIGNATURE
        is IntegrityException -> ModelMessage.INTEGRITY
        is CatalogException -> ModelMessage.CATALOG
        is IllegalArgumentException -> ModelMessage.INVALID_ZIP
        else -> ModelMessage.UNKNOWN
    }

    /** Código de error de la descarga (integridad, firma…) a mensaje fijo. */
    fun fromCode(code: String?): ModelMessage = when (code) {
        "integridad" -> ModelMessage.INTEGRITY
        "firma" -> ModelMessage.SIGNATURE
        "catalogo" -> ModelMessage.CATALOG
        "politica" -> ModelMessage.POLICY
        "archivos" -> ModelMessage.FILES
        "red" -> ModelMessage.NETWORK
        else -> ModelMessage.UNKNOWN
    }
}
