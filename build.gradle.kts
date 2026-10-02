plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// aapt2 es un binario distinto por sistema operativo. Esta tarea baja las tres variantes
// para que verification-metadata.xml tenga sus huellas y el CI de Linux no falle.
// Uso: ./gradlew --write-verification-metadata sha256 resolveAapt2AllPlatforms ...
val aapt2AllPlatforms: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    val aapt2 = "com.android.tools.build:aapt2:${libs.versions.agp.get()}-15978811"
    listOf("windows", "linux", "osx").forEach { os ->
        aapt2AllPlatforms("$aapt2:$os")
    }
}

tasks.register("resolveAapt2AllPlatforms") {
    val files = aapt2AllPlatforms
    doLast { files.resolve() }
}
