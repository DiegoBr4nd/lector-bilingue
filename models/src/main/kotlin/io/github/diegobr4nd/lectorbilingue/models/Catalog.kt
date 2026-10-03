package io.github.diegobr4nd.lectorbilingue.models

import java.time.Instant

/** Un archivo de un modelo, tal como lo describe el catálogo firmado. */
data class ModelFile(val name: String, val size: Long, val sha256: String, val url: String)

/** Un modelo de traducción descargable (un par de idiomas, un motor). */
data class CatalogModel(
    val id: String,
    val pair: String,
    val engine: String,
    val modelVersion: String,
    val license: String,
    val attribution: String,
    val files: List<ModelFile>,
) {
    val totalSize: Long get() = files.sumOf { it.size }
}

/** Catálogo ya validado por [CatalogParser]. */
data class Catalog(val version: Int, val generated: Instant, val models: List<CatalogModel>) {
    fun findByPair(pair: String): List<CatalogModel> = models.filter { it.pair == pair }
}

/** Catálogo rechazado. El mensaje nombra el campo y la posición, nunca el valor recibido. */
class CatalogException(message: String) : Exception(message)
