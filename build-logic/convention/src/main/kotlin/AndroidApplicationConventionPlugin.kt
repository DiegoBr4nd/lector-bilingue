import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk = ProjectConfig.COMPILE_SDK
            // El APK quita los símbolos de las .so nativas con la herramienta "strip" de este NDK.
            ndkVersion = ProjectConfig.NDK_VERSION
            defaultConfig {
                minSdk = ProjectConfig.MIN_SDK
                targetSdk = ProjectConfig.TARGET_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = ProjectConfig.JAVA_VERSION
                targetCompatibility = ProjectConfig.JAVA_VERSION
            }

            flavorDimensions += "distribution"
            productFlavors {
                create("fdroid") { dimension = "distribution" }
                create("play") { dimension = "distribution" }
            }

            buildTypes {
                getByName("release") {
                    isMinifyEnabled = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    vcsInfo.include = false
                }
            }

            // F-Droid: sin el bloque cifrado de dependencias que solo Google puede leer.
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }

            lint {
                abortOnError = true
                checkDependencies = true
            }
        }
    }
}
