plugins {
    id("lectorbilingue.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue.books"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    testOptions { unitTests.isReturnDefaultValues = false }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    api(libs.readium.shared)
    api(libs.readium.streamer)
    // api: LectorDatabase (público) hereda de RoomDatabase y :app lo necesita en su classpath para compilar.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
