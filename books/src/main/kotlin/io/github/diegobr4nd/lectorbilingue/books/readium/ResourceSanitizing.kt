package io.github.diegobr4nd.lectorbilingue.books.readium

import io.github.diegobr4nd.lectorbilingue.books.readium.HtmlSanitizer.Kind
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.data.ReadError

/**
 * Qué recursos del libro se sanean y cómo. Lógica pura (sin Android) para probarla en la JVM.
 *
 * Regla: fallar cerrado. Solo pasa sin sanear una lista corta de tipos que el navegador nunca ejecuta;
 * todo lo demás (HTML, XML, texto, tipos desconocidos o sin tipo) pasa por [HtmlSanitizer].
 */
internal object ResourceSanitizing {
    /** Lado máximo de la portada decodificada, en píxeles. Una de 2000 × 2000 ocupa unos 16 MB. */
    const val MAX_COVER_SIDE = 2000

    /** Si el archivo de la portada pesa más que esto, no se intenta decodificar. */
    const val MAX_COVER_BYTES = 20L * 1024 * 1024

    /**
     * Tipos que el navegador muestra sin ejecutar nada: imágenes (menos SVG o XML), fuentes, audio, vídeo y CSS.
     * Recibe el tipo ya en minúsculas o no; ignora los parámetros (`; charset=…`).
     */
    fun isInert(mediaType: String): Boolean {
        val mt = mediaType.substringBefore(';').trim().lowercase()
        // Cualquier imagen con "svg" o "xml" en el tipo (image/svg+xml, image/svg, image/svg-xml…) se sanea.
        return (mt.startsWith("image/") && "svg" !in mt && "xml" !in mt) ||
            mt.startsWith("font/") || mt.startsWith("application/font-") || mt.startsWith("application/x-font") ||
            mt == "application/vnd.ms-opentype" ||
            mt.startsWith("audio/") || mt.startsWith("video/") ||
            mt == "text/css"
    }

    /**
     * Cómo sanear un recurso servido con [mediaType] (el mismo tipo que Readium pondrá en la respuesta:
     * el del manifiesto o, si no está, el que Android deduce de la extensión). `null` = se sirve tal cual.
     * Lo desconocido o sin tipo va como HTML: es lo que hace el navegador cuando adivina el tipo.
     */
    fun kindFor(mediaType: String?): Kind? {
        val mt = mediaType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            mt.isNotEmpty() && isInert(mt) -> null
            mt == "image/svg+xml" -> Kind.SVG
            mt == "application/xhtml+xml" || mt == "application/xml" || mt == "text/xml" || mt.endsWith("+xml") -> Kind.XHTML
            else -> Kind.HTML
        }
    }

    /**
     * true si el tipo servido trae un parámetro `charset` que no es UTF-8 (o viene vacío).
     *
     * Readium (`WebViewServer.serveResource`) usa el tipo COMPLETO del manifiesto como Content-Type, y su
     * `charset` manda sobre nuestra declaración XML o `<meta charset>`. Un libro podría hacer que el WebView
     * lea nuestros bytes UTF-8 saneados como, p. ej., ISO-2022-JP y "fabrique" etiquetas que no estaban.
     * Por eso esos recursos no se sirven (fallar cerrado).
     */
    fun hasForeignCharset(mediaType: String?): Boolean {
        val params = mediaType?.split(';')?.drop(1) ?: return false
        return params.any { param ->
            val key = param.substringBefore('=', missingDelimiterValue = "").trim().lowercase()
            if (key != "charset") return@any false
            val value = param.substringAfter('=').trim().trim('"', '\'').trim().lowercase()
            value != "utf-8" && value != "utf8"
        }
    }

    /** Error de lectura para un recurso que no se puede servir con seguridad. Sin texto del libro. */
    fun refused(): Try<ByteArray, ReadError> = Try.failure(ReadError.Decoding("recurso con un charset no admitido"))

    /**
     * Sanea sin dejar escapar nunca los bytes originales: si el sanitizador falla (excepción, pila agotada,
     * sin memoria), la lectura del recurso devuelve un error y Readium muestra su página de error.
     * El mensaje del error no lleva texto del libro (CLAUDE.md, regla 5).
     */
    fun sanitizeSafely(
        bytes: ByteArray,
        kind: Kind,
        sanitize: (ByteArray, Kind) -> ByteArray = { b, k -> HtmlSanitizer.sanitize(b, k) },
    ): Try<ByteArray, ReadError> =
        try {
            Try.success(sanitize(bytes, kind))
        } catch (e: Exception) {
            Try.failure(ReadError.Decoding("no se pudo sanear el recurso"))
        } catch (e: StackOverflowError) {
            Try.failure(ReadError.Decoding("no se pudo sanear el recurso"))
        } catch (e: OutOfMemoryError) {
            Try.failure(ReadError.Decoding("no se pudo sanear el recurso"))
        }

    /**
     * Factor de muestreo (potencia de 2, como pide `BitmapFactory`) para que el lado mayor de la portada
     * quede en [maxSide] o menos. `null` si las medidas no tienen sentido.
     */
    fun coverSampleSize(width: Int, height: Int, maxSide: Int): Int? {
        if (width <= 0 || height <= 0) return null
        val largest = maxOf(width, height).toLong()
        var sample = 1
        while ((largest + sample - 1) / sample > maxSide) sample *= 2
        return sample
    }
}
