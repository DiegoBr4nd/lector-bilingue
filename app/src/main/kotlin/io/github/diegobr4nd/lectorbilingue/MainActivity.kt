package io.github.diegobr4nd.lectorbilingue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.ui.library.LibraryScreen
import io.github.diegobr4nd.lectorbilingue.ui.nav.AppNav
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as LectorApp
        setContent {
            LectorTheme {
                AppNav(
                    settings = app.settings,
                    hub = app.hub,
                    translationCache = app.translations,
                    onClose = { finish() },
                    library = { onLanguages, onDeveloper ->
                        LibraryScreen(app, onLanguages, onDeveloper, onOpenBook = { id -> startActivity(ReaderActivity.intent(this, id)) })
                    },
                )
            }
        }
    }
}
