plugins {
    id("lectorbilingue.android.library")
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue.models"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.orgjson)
}
