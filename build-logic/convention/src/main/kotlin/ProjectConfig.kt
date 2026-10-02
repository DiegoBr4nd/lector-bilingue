import org.gradle.api.JavaVersion

/** Valores compartidos por todos los módulos. Cambiar aquí cambia todo el proyecto. */
object ProjectConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 26
    val JAVA_VERSION = JavaVersion.VERSION_17

    /** NDK y CMake fijos: el código nativo (CTranslate2, SentencePiece) compila igual en toda máquina. */
    const val NDK_VERSION = "30.0.16248370"
    const val CMAKE_VERSION = "4.1.2"
}
