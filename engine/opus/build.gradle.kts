plugins {
    id("lectorbilingue.android.library")
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue.engine.opus"
    ndkVersion = ProjectConfig.NDK_VERSION

    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5")
                // Solo el puente: sin esto se compilan también los programas de prueba de
                // las dependencias (cpuinfo, spm_encode...), que no van en el APK.
                targets += "ct2bridge"
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("../../native/ct2bridge/CMakeLists.txt")
            version = ProjectConfig.CMAKE_VERSION
        }
    }
}

dependencies {
    api(project(":engine:api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
