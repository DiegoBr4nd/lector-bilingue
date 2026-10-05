plugins {
    id("lectorbilingue.android.library")
    id("lectorbilingue.android.compose")
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue.core.ui"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
