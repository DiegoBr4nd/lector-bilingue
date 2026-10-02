import org.gradle.api.JavaVersion

/** Valores compartidos por todos los módulos. Cambiar aquí cambia todo el proyecto. */
object ProjectConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 26
    val JAVA_VERSION = JavaVersion.VERSION_17
}
