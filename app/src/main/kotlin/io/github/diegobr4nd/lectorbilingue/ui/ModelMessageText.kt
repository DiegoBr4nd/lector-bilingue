package io.github.diegobr4nd.lectorbilingue.ui

import androidx.annotation.StringRes
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage

/** Texto en lenguaje sencillo de cada mensaje fijo del gestor de modelos (nunca el texto de una excepción). */
@StringRes
fun ModelMessage.textRes(): Int = when (this) {
    ModelMessage.NO_CATALOG -> R.string.hub_msg_no_catalog
    ModelMessage.NO_CATALOG_IMPORT -> R.string.hub_msg_no_catalog_import
    ModelMessage.NO_MODEL -> R.string.hub_msg_no_model
    ModelMessage.DOWNLOAD_BUSY -> R.string.hub_msg_download_busy
    ModelMessage.CANCELLED -> R.string.hub_msg_cancelled
    ModelMessage.NETWORK -> R.string.hub_msg_network
    ModelMessage.POLICY -> R.string.hub_msg_policy
    ModelMessage.SIGNATURE -> R.string.hub_msg_signature
    ModelMessage.INTEGRITY -> R.string.hub_msg_integrity
    ModelMessage.CATALOG -> R.string.hub_msg_catalog
    ModelMessage.FILES -> R.string.hub_msg_files
    ModelMessage.INVALID_ZIP -> R.string.hub_msg_invalid_zip
    ModelMessage.IMPORT_NO_MATCH -> R.string.hub_msg_import_no_match
    ModelMessage.IMPORT_OK -> R.string.hub_msg_import_ok
    ModelMessage.DOWNLOAD_OK -> R.string.hub_msg_download_ok
    ModelMessage.DELETE_OK -> R.string.hub_msg_delete_ok
    ModelMessage.UNKNOWN -> R.string.hub_msg_unknown
}
