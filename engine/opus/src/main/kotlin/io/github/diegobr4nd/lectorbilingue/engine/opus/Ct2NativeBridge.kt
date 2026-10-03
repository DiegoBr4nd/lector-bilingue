package io.github.diegobr4nd.lectorbilingue.engine.opus

/** Implementación real: llama a libct2bridge.so (CTranslate2 + SentencePiece). */
object Ct2NativeBridge : NativeBridge {
    init {
        System.loadLibrary("ct2bridge")
    }

    override fun load(modelDir: String, threads: Int, beamSize: Int): Long =
        nativeLoad(modelDir, threads, beamSize)

    override fun translate(handle: Long, sentences: Array<String>): Array<String> =
        nativeTranslate(handle, sentences)

    override fun unload(handle: Long) = nativeUnload(handle)

    /**
     * Solo para pruebas: UTF-16 → UTF-8 → UTF-16 dentro del nativo.
     * Es pública porque la prueba instrumentada vive en otro módulo (:app).
     */
    fun utf8RoundTrip(text: String): String = nativeUtf8RoundTrip(text)

    /** Solo para pruebas: decodifica bytes UTF-8 crudos con el decodificador estricto del nativo. */
    fun utf8BytesToString(bytes: ByteArray): String {
        require(bytes.size <= 4096) { "demasiados bytes (máx. 4096)" }
        return nativeUtf8BytesToString(bytes)
    }

    @JvmStatic private external fun nativeLoad(modelDir: String, threads: Int, beamSize: Int): Long
    @JvmStatic private external fun nativeTranslate(handle: Long, sentences: Array<String>): Array<String>
    @JvmStatic private external fun nativeUnload(handle: Long)
    @JvmStatic private external fun nativeUtf8RoundTrip(text: String): String
    @JvmStatic private external fun nativeUtf8BytesToString(bytes: ByteArray): String
}
