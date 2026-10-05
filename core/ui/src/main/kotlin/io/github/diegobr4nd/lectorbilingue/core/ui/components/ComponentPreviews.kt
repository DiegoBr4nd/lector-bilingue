package io.github.diegobr4nd.lectorbilingue.core.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.core.ui.R
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing

/** Claro y oscuro, letra normal y al 200 %, teléfono (360 dp) y tableta (840 dp). */
@Preview(name = "Claro 360", widthDp = 360, showBackground = true)
@Preview(name = "Oscuro 360", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Claro 360 letra 200", widthDp = 360, fontScale = 2f, showBackground = true)
@Preview(
    name = "Oscuro 360 letra 200",
    widthDp = 360,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(name = "Claro 840", widthDp = 840, showBackground = true)
@Preview(name = "Oscuro 840", widthDp = 840, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
private annotation class LectorPreviews

@Composable
private fun Muestra(content: @Composable () -> Unit) {
    LectorTheme {
        Surface { Column(Modifier.padding(Spacing.l)) { content() } }
    }
}

@LectorPreviews
@Composable
private fun ModelRowEstados() = Muestra {
    ModelRow(EngineKind.QUALITY, 180, "Español → Inglés", ModelRowState.NotInstalled, {}, {}, {}, enabled = true)
    ModelRow(EngineKind.FAST, 40, "Español → Inglés", ModelRowState.Downloading(0.4f), {}, {}, {}, enabled = true)
    ModelRow(EngineKind.QUALITY, 180, "Español → Inglés", ModelRowState.Installed, {}, {}, {}, enabled = true)
    ModelRow(EngineKind.FAST, 40, "Español → Inglés", ModelRowState.InUse, {}, {}, {}, enabled = true)
}

@LectorPreviews
@Composable
private fun ModelRowDescargaSinTotal() = Muestra {
    ModelRow(EngineKind.FAST, 40, "Español → Inglés", ModelRowState.Downloading(null), {}, {}, {}, enabled = true)
    ModelRow(EngineKind.QUALITY, 180, "Español → Inglés", ModelRowState.NotInstalled, {}, {}, {}, enabled = false)
}

@LectorPreviews
@Composable
private fun DownloadProgressPreview() = Muestra {
    DownloadProgress(fraction = 0.65f)
    DownloadProgress(fraction = null)
}

@LectorPreviews
@Composable
private fun PrivacyBadgePreview() = Muestra { PrivacyBadge() }

@LectorPreviews
@Composable
private fun ConfirmDialogPreview() = LectorTheme {
    ConfirmDialog(
        title = stringResource(R.string.preview_dialog_title),
        body = stringResource(R.string.preview_dialog_body),
        confirmLabel = stringResource(R.string.preview_dialog_confirm),
        onConfirm = {},
        onDismiss = {},
    )
}
