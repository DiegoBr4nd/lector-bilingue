import java.util.Properties

plugins {
    id("lectorbilingue.android.application")
    id("lectorbilingue.android.compose")
    alias(libs.plugins.aboutlibraries)
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

// Lista de licencias de las librerías (pantalla «Licencias»). Se genera A MANO y se guarda en el repo:
//   ./gradlew :app:exportLibraryDefinitions -PaboutLibraries.exportVariant=playRelease
// Siempre solo (sin lint ni assemble en el mismo comando): escribe en src/main/assets, que esas tareas leen.
// El CI la vuelve a generar y falla si cambió (una dependencia nueva sin su licencia en la lista).
// Es el plugin "core" (no el de Android): no se engancha a la compilación ni mete nada en el APK.
aboutLibraries {
    offlineMode = true // sin red al compilar: los textos de licencia van en assets/licencias/textos/
    collect {
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }
    export {
        outputFile = file("src/main/assets/licencias/librerias.json")
        prettyPrint = true
        // Sin fecha de generación ni datos que no mostramos: el archivo sale igual en cualquier máquina.
        excludeFields.addAll("metadata", "description", "developers", "organization", "funding", "scm")
    }
}

// LicensesTest lee el catálogo real (assets y LICENSE) y la versión del build (versionCode, versionName):
// se declaran como entradas para que la prueba vuelva a correr cuando cambian (si no, Gradle la daría por
// "al día" con archivos nuevos).
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main/assets/licencias"))
        .withPropertyName("catalogoLicencias").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("core/ui/src/main/assets/lector/fuentes"))
        .withPropertyName("fuentesLicencias").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("LICENSE"))
        .withPropertyName("licenciaApp").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("versionBuild").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Firma de release con la llave de SUBIDA de Juan (docs/publicar-en-play.md). La ruta a su archivo de propiedades
// llega por ~/.gradle/gradle.properties (lector.firma=…) o la variable LECTOR_FIRMA: nunca desde el repositorio.
// Sin ella, release sale sin firmar y la compilación sigue (CI, Claude, F-Droid).
val firmaArchivo = providers.gradleProperty("lector.firma").orElse(providers.environmentVariable("LECTOR_FIRMA"))

android {
    namespace = "io.github.diegobr4nd.lectorbilingue"
    defaultConfig {
        applicationId = "io.github.diegobr4nd.lectorbilingue"
        versionCode = 1
        versionName = "0.9.0"
    }
    // Los esquemas exportados de Room alimentan la prueba de migración en el teléfono.
    sourceSets { getByName("androidTest").assets.srcDir("$rootDir/books/schemas") }
    // Readium (Theme) llama a android.graphics.Color.parseColor al cargarse; en la JVM devuelve 0 en vez de fallar.
    // Las pruebas nuevas no deben depender del fallo "Method ... not mocked": con esto ya no ocurre.
    testOptions { unitTests.isReturnDefaultValues = true }
    // BuildConfig.VERSION_NAME y FLAVOR para «Acerca de» y el correo de comentarios.
    buildFeatures { buildConfig = true }
    // Readium trae su reproductor de cómics (Divina) en assets/readium/divina/; la app solo lee EPUB, así que no viaja.
    androidResources { ignoreAssetsPatterns += "<dir>divina" }
    signingConfigs {
        if (firmaArchivo.isPresent) {
            val ruta = file(firmaArchivo.get())
            check(ruta.isFile) { "No existe el archivo de firma indicado en lector.firma / LECTOR_FIRMA" }
            val props = Properties().apply { ruta.reader().use { load(it) } }
            fun valor(clave: String): String = props.getProperty(clave)?.takeIf { it.isNotBlank() }
                ?: error("Falta «$clave» en el archivo de firma") // nunca se muestra el valor
            create("subida") {
                storeFile = file(valor("storeFile"))
                storePassword = valor("storePassword")
                keyAlias = valor("keyAlias")
                keyPassword = valor("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (firmaArchivo.isPresent) signingConfig = signingConfigs.getByName("subida")
            // Símbolos de las .so dentro del AAB (Play los usa para leer cierres nativos); el APK no cambia.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        // Copia release para probar en el Pixel JUNTO a la app de Juan (spec 6b §3): R8 como release, firma de debug,
        // otro applicationId (datos propios). Nunca se instala encima de io.github.diegobr4nd.lectorbilingue.
        create("prueba") {
            initWith(getByName("release"))
            applicationIdSuffix = ".prueba"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    // La copia de prueba usa el mismo «sin pantalla de desarrollador» que release.
    sourceSets.getByName("prueba").kotlin.directories.add("src/release/kotlin")
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
