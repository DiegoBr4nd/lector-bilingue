package io.github.diegobr4nd.lectorbilingue.engine.firefox

/** Implementación real: llama a libslimtbridge.so (slimt + SentencePiece + ruy). */
object SlimtNativeBridge : NativeBridge {
    init {
        System.loadLibrary("slimtbridge")
    }

    override fun load(modelDir: String, threads: Int): Long = nativeLoad(modelDir, threads)

    override fun translate(handle: Long, sentences: Array<String>): Array<String> =
        nativeTranslate(handle, sentences)

    override fun release(handle: Long) = nativeRelease(handle)

    @JvmStatic private external fun nativeLoad(modelDir: String, threads: Int): Long
    @JvmStatic private external fun nativeTranslate(handle: Long, sentences: Array<String>): Array<String>
    @JvmStatic private external fun nativeRelease(handle: Long)
}
