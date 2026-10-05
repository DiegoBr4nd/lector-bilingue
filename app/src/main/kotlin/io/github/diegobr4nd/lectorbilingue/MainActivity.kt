package io.github.diegobr4nd.lectorbilingue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.ui.DeveloperEntries

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LectorTheme {
                val developer = DeveloperEntries.current
                // Provisional: la Tarea 6 reemplaza esto por la Bienvenida y la biblioteca.
                if (developer.available) {
                    developer.Screen(onBack = { finish() })
                } else {
                    Text(getString(R.string.app_name))
                }
            }
        }
    }
}
