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
    // Los esquemas exportados de Room alimentan la prueba de migración en el teléfono.
    sourceSets { getByName("androidTest").assets.srcDir("$rootDir/books/schemas") }
    // Readium (Theme) llama a android.graphics.Color.parseColor al cargarse; en la JVM devuelve 0 en vez de fallar.
    // Las pruebas nuevas no deben depender del fallo "Method ... not mocked": con esto ya no ocurre.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:text"))
    implementation(project(":engine:api"))
    implementation(project(":engine:opus"))
    implementation(project(":engine:firefox"))
    implementation(project(":core:ui"))
    implementation(project(":models"))
    implementation(project(":books"))
    implementation(libs.readium.navigator)
    implementation(libs.androidx.fragment.compose)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.orgjson) // org.json de verdad en las pruebas JVM (en android.jar solo hay stubs).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.room.testing)
}
