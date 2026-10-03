package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import io.github.diegobr4nd.lectorbilingue.models.Catalog
import io.github.diegobr4nd.lectorbilingue.models.CatalogException
import io.github.diegobr4nd.lectorbilingue.models.CatalogModel
import io.github.diegobr4nd.lectorbilingue.models.DownloadInProgressException
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import io.github.diegobr4nd.lectorbilingue.models.IntegrityException
import io.github.diegobr4nd.lectorbilingue.models.ModelFileException
import io.github.diegobr4nd.lectorbilingue.models.NetworkPolicyException
import io.github.diegobr4nd.lectorbilingue.models.SignatureException
import java.io.IOException
import java.util.UUID

/** Mensajes fijos (cada uno tiene su texto en strings.xml). Nunca se muestra el texto de una excepción. */
enum class ModelMessage(val isError: Boolean = true) {
    NO_CATALOG, NO_MODEL, DOWNLOAD_BUSY, CANCELLED(isError = false), NETWORK, POLICY, SIGNATURE, INTEGRITY,
    CATALOG, FILES, INVALID_ZIP, IMPORT_NO_MATCH, IMPORT_OK(isError = false), UNKNOWN,
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

    /**
     * Errores de importar un zip. Aquí un IOException que no es de archivos es un zip roto/rechazado
     * (el importador lo lanza sin causa), no un fallo de red. Un CatalogException es "el zip no
     * corresponde a ningún modelo del catálogo" (la falta de catálogo se comprueba antes).
     */
    fun classifyImport(e: Throwable): ModelMessage = when (e) {
        is DownloadInProgressException -> ModelMessage.DOWNLOAD_BUSY
        is ModelFileException -> ModelMessage.FILES
        is IOException -> ModelMessage.INVALID_ZIP
        is CatalogException -> ModelMessage.IMPORT_NO_MATCH
        is IntegrityException -> ModelMessage.INTEGRITY
        is IllegalArgumentException -> ModelMessage.INVALID_ZIP
        else -> ModelMessage.UNKNOWN
    }

    /**
     * ¿Se procesa este estado de la descarga? Tras encolar, el primer estado puede ser de una descarga
     * anterior ya terminada: un estado final se acepta si es de la petición que encolamos ([requestId]),
     * o si ya vimos uno activo ([seenActive]). Los estados activos (o null) siempre se aceptan.
     */
    fun acceptDownloadState(info: DownloadState?, requestId: UUID?, seenActive: Boolean): Boolean {
        val terminal = info != null && info.status !in ACTIVE
        return !terminal || seenActive || (requestId != null && info?.workId == requestId)
    }

    fun isActive(info: DownloadState?): Boolean = info != null && info.status in ACTIVE

    private val ACTIVE = setOf(DownloadState.Status.QUEUED, DownloadState.Status.RUNNING)

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
