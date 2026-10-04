package io.github.diegobr4nd.lectorbilingue.engine.firefox

/**
 * Las tres operaciones del motor nativo de Firefox (slimt). Existe como interfaz para
 * probar FirefoxEngine en la JVM sin la librería nativa.
 * La carpeta del modelo debe traer `slimt.json` (ver native/slimtbridge/README.md).
 * Los errores llegan como IllegalArgumentException / IllegalStateException
 * con mensajes que nunca contienen el texto traducido.
 */
interface NativeBridge {
    /** Devuelve un handle opaco; 0 = error. [threads] va de 1 a [MAX_THREADS]. */
    fun load(modelDir: String, threads: Int): Long
    fun translate(handle: Long, sentences: Array<String>): Array<String>
    fun release(handle: Long)

    companion object {
        // Mismos valores que el NativeBridge de OPUS.
        const val MAX_SENTENCE_CHARS = 1000
        const val MAX_BATCH = 64
        const val MAX_THREADS = 4
    }
}
