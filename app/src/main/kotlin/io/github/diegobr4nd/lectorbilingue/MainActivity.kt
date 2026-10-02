package io.github.diegobr4nd.lectorbilingue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.diegobr4nd.lectorbilingue.ui.enginetest.EngineTestScreen
import io.github.diegobr4nd.lectorbilingue.ui.theme.LectorBilingueTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LectorBilingueTheme {
                EngineTestScreen()
            }
        }
    }
}
