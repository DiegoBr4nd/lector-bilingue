package io.github.diegobr4nd.lectorbilingue.books.readium

import io.github.diegobr4nd.lectorbilingue.books.readium.HtmlSanitizer.Kind
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource

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
     * Tope de bytes REALES de un recurso que se sanea (HTML, XHTML, SVG, XML). Por encima no se lee con jsoup:
     * se sirve una página de aviso ([OVERSIZE_MESSAGE]).
     *
     * Por qué 8 MB (seguridad, ronda 3):
     * - jsoup arma un árbol con un objeto por etiqueta. Un capítulo hecho solo de `<p>a</p>` tiene una etiqueta
     *   cada 8 bytes: con 8 MB son unos 2 millones de nodos y pide unos 170-190 MB de memoria (medido en la JVM
     *   del escritorio); con 32 MB (el tope del ZIP) no cabe y la app se queda sin memoria.
     * - Los capítulos reales pesan mucho menos de 1 MB: Calibre parte los archivos a partir de unos 260 KB y los
     *   lectores antiguos de Adobe no abrían archivos de más de 300 KB. Un libro entero en un solo archivo
     *   (p. ej. una novela muy larga) ronda los 3-4 MB y además es prosa, con pocas etiquetas por byte.
     */
    const val MAX_MARKUP_BYTES = 8L * 1024 * 1024

    /** Lo que se muestra en lugar de un capítulo por encima de [MAX_MARKUP_BYTES]. Sin texto del libro. */
    const val OVERSIZE_MESSAGE = "Este capítulo es demasiado grande para mostrarlo."

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

    /**
     * El recurso saneado que se sirve al WebView. Nunca lee más de [maxBytes] + 1 bytes del original: pide
     * ese rango y no el recurso entero, así que un capítulo enorme no llega a memoria para decidir.
     * Si el original pasa del tope, se sirve la página de aviso (con la misma CSP) en vez de sanearlo.
     */
    fun sanitizing(resource: Resource, kind: Kind, maxBytes: Long = MAX_MARKUP_BYTES): Resource =
        TransformingResource(BoundedRead(resource, maxBytes)) { bytes ->
            if (bytes.size > maxBytes) sanitizeSafely(oversizePage(kind), kind) else sanitizeSafely(bytes, kind)
        }

    /** Página mínima de aviso en el formato que espera el navegador; luego pasa por [HtmlSanitizer] para llevar la CSP. */
    private fun oversizePage(kind: Kind): ByteArray = when (kind) {
        Kind.SVG -> """<svg xmlns="http://www.w3.org/2000/svg"></svg>"""
        Kind.HTML -> """<!DOCTYPE html><html lang="es"><head><title></title></head><body><p>$OVERSIZE_MESSAGE</p></body></html>"""
        Kind.XHTML -> """<html xmlns="http://www.w3.org/1999/xhtml" lang="es" xml:lang="es"><head><title></title></head>""" +
            """<body><p>$OVERSIZE_MESSAGE</p></body></html>"""
    }.toByteArray(Charsets.UTF_8)

    /**
     * Envoltorio que limita la lectura a los primeros [max] + 1 bytes. `TransformingResource` lee el recurso
     * "entero" (sin rango); aquí ese "entero" se cambia por el rango `0..max`. Si llegan más de [max] bytes,
     * el original pasa del tope. Readium lee un rango de una entrada del ZIP sin descomprimir el resto.
     */
    private class BoundedRead(private val source: Resource, private val max: Long) : Resource by source {
        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
            val r = range ?: (0L..max)
            return source.read(r.first..minOf(r.last, max))
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
