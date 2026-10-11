package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
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
    NO_CATALOG, NO_CATALOG_IMPORT, NO_MODEL, DOWNLOAD_BUSY, CANCELLED(isError = false), NETWORK, POLICY,
    SIGNATURE, INTEGRITY, CATALOG, FILES, INVALID_ZIP, IMPORT_NO_MATCH, IMPORT_OK(isError = false),
    DOWNLOAD_OK(isError = false), DELETE_OK(isError = false), CACHE_CLEARED(isError = false), CACHE_CLEAR_FAILED, UNKNOWN,
}

/** Lógica pura (sin Android) de los botones de modelos: se prueba en la JVM. */
object ModelActions {
    private const val MIB = 1_048_576L

    /** Los modelos del [pair] (p. ej. "en-es") de los motores conocidos, en orden: OPUS y luego Firefox. */
    fun pickModels(catalog: Catalog, pair: String): List<CatalogModel> =
        catalog.findByPair(pair)
            .mapNotNull { m -> EngineId.fromWire(m.engine)?.let { it to m } }
            .sortedBy { it.first.ordinal }
            .map { it.second }

    /**
     * Qué hacer al tocar "Descargar" tras intentar refrescar el catálogo de la red y leer el guardado.
     * Devuelve (catálogo a usar, mensaje de problema); exactamente uno de los dos es null.
     * - Hay catálogo guardado (incrustado o ya refrescado): se usa; un fallo del refresco es silencioso.
     * - No hay catálogo y el refresco falló por firma, política de red o catálogo inválido: ese mensaje.
     * - No hay catálogo y el refresco falló por red/404 (o no falló): [ModelMessage.NO_CATALOG].
     */
    fun resolveCatalog(current: Catalog?, refreshError: Throwable?): Pair<Catalog?, ModelMessage?> = when {
        current != null -> current to null
        refreshError is SignatureException || refreshError is NetworkPolicyException || refreshError is CatalogException ->
            null to classify(refreshError)
        else -> null to ModelMessage.NO_CATALOG
    }

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

    /** Mensaje con el que termina una descarga (null si el estado no es final). */
    fun finalMessage(info: DownloadState?): ModelMessage? = when (info?.status) {
        DownloadState.Status.SUCCEEDED -> ModelMessage.DOWNLOAD_OK
        DownloadState.Status.CANCELLED -> ModelMessage.CANCELLED
        DownloadState.Status.FAILED -> fromCode(info.error)
        else -> null
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
