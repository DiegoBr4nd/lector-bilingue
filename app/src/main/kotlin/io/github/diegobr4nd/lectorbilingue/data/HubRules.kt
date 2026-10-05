package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineChoice
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineSelector
import io.github.diegobr4nd.lectorbilingue.models.Catalog
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Un modelo del catálogo para un par. [download] es el último estado conocido de su descarga en esta
 * sesión (activo o final), o null si no hubo ninguna: "descargando" es `ModelActions.isActive(download)`.
 */
data class RowStatus(
    val modelId: String,
    val engine: EngineId,
    val sizeBytes: Long,
    val installed: Boolean,
    val download: DownloadState?,
)

/** Un par del catálogo (p. ej. "en-es") con una fila por motor, en orden: OPUS y luego Firefox. */
data class PairStatus(val pair: String, val rows: List<RowStatus>)

/** Reglas puras (sin Android) del gestor de modelos compartido: se prueban en la JVM. */
object HubRules {
    private const val GIB = 1024.0 * 1024 * 1024

    /**
     * Pares del catálogo en el orden en que aparecen, cada uno con sus filas. [installed] son pares
     * (motor, par) como "firefox" to "en-es"; [downloads] va por id de modelo. Sin catálogo, lista vacía.
     */
    fun pairStatuses(
        catalog: Catalog?,
        installed: Set<Pair<String, String>>,
        downloads: Map<String, DownloadState?>,
    ): List<PairStatus> {
        if (catalog == null) return emptyList()
        return catalog.models.map { it.pair }.distinct().mapNotNull { pair ->
            val rows = ModelActions.pickModels(catalog, pair).mapNotNull { m ->
                EngineId.fromWire(m.engine)?.let { engine ->
                    RowStatus(m.id, engine, m.totalSize, (m.engine to pair) in installed, downloads[m.id])
                }
            }
            if (rows.isEmpty()) null else PairStatus(pair, rows)
        }
    }

    /** La fila que conviene descargar: lo que elegiría Automático si todos los motores del catálogo estuvieran. */
    fun recommended(status: PairStatus, totalRamBytes: Long): RowStatus? =
        pick(status, status.rows.mapTo(mutableSetOf()) { it.engine }, totalRamBytes, null)

    /** El motor que se usaría ahora con [pref], solo entre los instalados (null si falta el que toca). */
    fun inUse(status: PairStatus, totalRamBytes: Long, pref: EnginePreference): RowStatus? =
        pick(status, status.rows.filter { it.installed }.mapTo(mutableSetOf()) { it.engine }, totalRamBytes, pref.toForced())

    /** Volver a descargar un modelo ya instalado pide confirmación. */
    fun needsConfirmToDownload(row: RowStatus): Boolean = row.installed

    /**
     * Al pedir la descarga de un modelo cuyo último estado es [current]: si ya está en cola o en curso,
     * se conserva ese estado (y su avance) en vez de volver a "en cola" desde cero.
     */
    fun keepsCurrentDownload(current: DownloadState?): Boolean = ModelActions.isActive(current)

    /**
     * Quita de [downloads] los estados finales (fallo, cancelada, terminada) de los modelos del catálogo de
     * [engine] (nombre en el catálogo) y [pair]: tras importar o borrar, un fallo viejo ya no dice nada de la
     * fila. Las descargas en cola o en curso se conservan.
     */
    fun withoutFinishedDownloads(
        catalog: Catalog?,
        engine: String,
        pair: String,
        downloads: Map<String, DownloadState?>,
    ): Map<String, DownloadState?> {
        val ids = catalog?.models.orEmpty().filter { it.engine == engine && it.pair == pair }.mapTo(mutableSetOf()) { it.id }
        return downloads.filterNot { (id, state) -> id in ids && state != null && !ModelActions.isActive(state) }
    }

    private fun pick(status: PairStatus, engines: Set<EngineId>, ram: Long, forced: EngineId?): RowStatus? =
        when (val c = EngineSelector.choose(engines, ram, forced)) {
            is EngineChoice.Use -> status.rows.firstOrNull { it.engine == c.engine }
            is EngineChoice.Missing -> null
        }

    /** RAM total en GB redondeados (7,6 GiB se muestra como 8). */
    fun ramGb(totalRamBytes: Long): Long = (totalRamBytes / GIB).roundToLong()

    /**
     * Texto de la RAM para la pantalla: GB enteros, salvo a ±0,5 GB del umbral de 4 GiB, donde lleva un decimal
     * (así "3,6" nunca se lee como "4" junto a "Firefox por RAM").
     */
    fun ramText(totalRamBytes: Long, locale: Locale = Locale.getDefault()): String {
        val gib = totalRamBytes / GIB
        val threshold = EngineSelector.OPUS_MIN_RAM_BYTES / GIB
        return if (abs(gib - threshold) <= 0.5) String.format(locale, "%.1f", gib) else ramGb(totalRamBytes).toString()
    }
}
