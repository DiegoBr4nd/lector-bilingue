plugins {
    id("lectorbilingue.android.application")
    id("lectorbilingue.android.compose")
}

// Copia SOLO sustitutos.txt (textos sin copyright) a una carpeta generada; así un archivo
// privado en bench/ nunca entra al APK. Una "tarea" de Gradle es un paso de la compilación.
abstract class CopyBenchAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        source.get().asFile.copyTo(out.resolve("sustitutos.txt"), overwrite = true)
    }
}

val copyBenchAssets = tasks.register<CopyBenchAssets>("copyBenchAssets") {
    source.set(rootProject.layout.projectDirectory.file("bench/sustitutos.txt"))
    outputDir.set(layout.buildDirectory.dir("generated/benchAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyBenchAssets, CopyBenchAssets::outputDir)
    }
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue"
    defaultConfig {
        applicationId = "io.github.diegobr4nd.lectorbilingue"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":core:text"))
    implementation(project(":engine:api"))
    implementation(project(":engine:opus"))
    implementation(project(":models"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
}
