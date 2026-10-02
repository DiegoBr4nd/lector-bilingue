package io.github.diegobr4nd.lectorbilingue.engine.opus

/**
 * Las tres operaciones del motor nativo. Existe como interfaz para probar
 * OpusEngine en la JVM sin la librería nativa.
 * Los errores llegan como IllegalArgumentException / IllegalStateException
 * con mensajes que nunca contienen el texto traducido.
 */
interface NativeBridge {
    fun load(modelDir: String, threads: Int, beamSize: Int): Long
    fun translate(handle: Long, sentences: Array<String>): Array<String>
    fun unload(handle: Long)

    companion object {
        const val MAX_SENTENCE_CHARS = 1000
        const val MAX_BATCH = 64
        const val MAX_THREADS = 8
        const val MAX_BEAM = 8
    }
}
