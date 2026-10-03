package io.github.diegobr4nd.lectorbilingue.models

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale

/** Una URL (inicial o de redirección) no cumple la [HostPolicy]; no se llegó a conectar con ella. */
class NetworkPolicyException(message: String) : Exception(message)

/** Decide si se permite conectar con una URL. Se consulta antes de CADA conexión, incluidas las redirecciones. */
fun interface HostPolicy {
    fun allows(url: URL): Boolean
}

/**
 * Política de producción: solo HTTPS, sin `usuario@`, puerto 443 (implícito o explícito)
 * y host exactamente igual a uno de la lista blanca (nada de subdominios ni sufijos).
 */
object GitHubHostPolicy : HostPolicy {
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    override fun allows(url: URL): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        if (url.userInfo != null) return false
        if (url.port != -1 && url.port != 443) return false
        val host = url.host ?: return false
        return host.lowercase(Locale.ROOT) in ALLOWED_HOSTS
    }
}

/**
 * Descargas HTTP(S) con [HttpURLConnection], sin seguir redirecciones automáticamente:
 * cada salto se valida con [policy] antes de conectar (máximo [MAX_REDIRECTS]).
 *
 * Solo GET, con `User-Agent: lector-bilingue`, `Accept-Encoding: identity`, sin caché y sin cookies.
 * Si hay un [CookieHandler] global instalado se niega a trabajar (no se puede desactivar por conexión).
 * Los mensajes de error contienen como mucho el código HTTP y el host, nunca la URL completa ni el cuerpo.
 */
class HttpFetcher(
    private val policy: HostPolicy = GitHubHostPolicy,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {

    /**
     * Descarga completa a memoria con tope (catálogo y firma). Solo acepta 200.
     * No tiene forma de cancelarse a mitad: es solo para archivos pequeños (unos pocos MiB como mucho).
     */
    fun fetchBytes(url: String, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "maxBytes negativo" }
        val conn = open(url, rangeStart = null)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw httpError(code, conn.url)
            val declared = conn.contentLengthLong
            if (declared > maxBytes) throw tooBig(conn.url)
            val out = ByteArrayOutputStream(if (declared in 0..maxBytes) declared.toInt() else 8192)
            val total = conn.inputStream.use { input ->
                copyCapped(input, maxBytes.toLong(), { tooBig(conn.url) }) { buf, n -> out.write(buf, 0, n) }
            }
            if (declared >= 0 && total != declared) throw incomplete(conn.url)
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Descarga a [target] reanudando desde `target.length()` si existe (cabecera `Range`).
     *
     * - Llama a [onBytes] con cada bloque **después** de escribirlo; el arreglo se reutiliza, así que
     *   quien lo reciba debe copiar o consumir los `n` primeros bytes en el momento (SHA-256, progreso).
     *   [onBytes] no recibe los bytes que ya estaban en el archivo antes de llamar: al reanudar,
     *   quien llama debe haber procesado ya esos bytes (por ejemplo, hashearlos).
     * - **Cancelar:** lanzar una excepción desde [onBytes] (p. ej. `CancellationException`) corta la
     *   descarga; esa excepción se propaga tal cual y el parcial queda en disco para reanudar.
     * - Llama a [onReset] cada vez que el archivo se trunca a cero, **después** de truncarlo (el
     *   servidor ignoró el `Range`, respondió un rango que no empieza donde esperábamos, o el parcial
     *   ya ocupaba [maxBytes] o más). Quien calcule un hash incremental debe reiniciarlo ahí.
     * - **Verificar antes:** si [target] quizá ya está completo (cualquier tamaño, no solo [maxBytes]),
     *   quien llama debe comprobarlo (tamaño y SHA-256) ANTES de llamar: pedir `Range` desde el final
     *   haría que el servidor responda 416 → IOException.
     * - Si el parcial ya mide [maxBytes] o más, NO se pide `Range` (daría 416 para siempre): se trunca
     *   y se descarga desde cero.
     * - Un 206 cuyo Content-Range tiene total conocido debe terminar en `total - 1`; si no →
     *   IOException sin escribir nada (el parcial queda igual y el siguiente intento reanuda).
     * - El archivo nunca supera [maxBytes]: se aborta con IOException antes de escribir el bloque que lo excedería.
     * - Si la respuesta anuncia su longitud (Content-Length en 200; `end-start+1` del Content-Range en 206)
     *   y llegan menos bytes → IOException "respuesta incompleta" y el parcial se conserva para reanudar.
     *   Si llegan más de los anunciados → IOException sin escribir el exceso.
     * - 416 u otro código distinto de 200/206 → IOException y el archivo queda como estaba.
     * - Redirección hacia un host no permitido → [NetworkPolicyException], sin conectar.
     */
    fun downloadTo(
        url: String,
        target: File,
        maxBytes: Long,
        onBytes: (ByteArray, Int) -> Unit,
        onReset: () -> Unit = {},
    ) {
        require(maxBytes >= 0) { "maxBytes negativo" }
        var existing = if (target.isFile) target.length() else 0L
        if (existing > 0 && existing >= maxBytes) {
            truncate(target)
            onReset()
            existing = 0L
        }
        // Como mucho dos vueltas: tras reiniciar, existing == 0, ya no se pide Range y un 206 es error.
        while (true) {
            val rangeStart = if (existing > 0) existing else null
            val conn = open(url, rangeStart)
            try {
                val code = conn.responseCode
                val start: Long
                val expected: Long?
                when (code) {
                    HttpURLConnection.HTTP_OK -> {
                        val len = conn.contentLengthLong
                        if (len > maxBytes) throw tooBig(conn.url)
                        if (existing > 0) {
                            truncate(target)
                            onReset()
                            existing = 0L
                        }
                        start = 0L
                        expected = if (len >= 0) len else null
                    }
                    HttpURLConnection.HTTP_PARTIAL -> {
                        if (rangeStart == null) throw IOException("HTTP 206 sin haber pedido rango desde ${conn.url.host}")
                        val range = parseContentRange(conn.getHeaderField("Content-Range"))
                        if (range == null || range.start != rangeStart) {
                            conn.disconnect()
                            truncate(target)
                            onReset()
                            existing = 0L
                            continue
                        }
                        val total = range.total
                        if (total != null && total > maxBytes) throw tooBig(conn.url)
                        // Un 206 debe llegar hasta el final del archivo; si no, terminaríamos "bien" con un archivo corto.
                        if (total != null && range.end != total - 1) {
                            throw IOException("rango que no llega al final desde ${conn.url.host}")
                        }
                        if (range.end + 1 > maxBytes) throw tooBig(conn.url)
                        val rangeLen = range.end - range.start + 1
                        val len = conn.contentLengthLong
                        if (len >= 0 && len != rangeLen) throw IOException("respuesta incoherente desde ${conn.url.host}")
                        start = rangeStart
                        expected = rangeLen
                    }
                    else -> throw httpError(code, conn.url)
                }
                val limit = expected ?: (maxBytes - start)
                val overflow = {
                    if (expected != null) IOException("respuesta más larga de lo anunciado desde ${conn.url.host}") else tooBig(conn.url)
                }
                val written = conn.inputStream.use { input ->
                    FileOutputStream(target, start > 0).use { out ->
                        copyCapped(input, limit, overflow) { buf, n ->
                            out.write(buf, 0, n)
                            onBytes(buf, n)
                        }
                    }
                }
                if (expected != null && written != expected) throw incomplete(conn.url)
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Abre la conexión siguiendo redirecciones a mano. Devuelve la conexión con una respuesta
     * que no es redirección; quien llama debe hacer `disconnect()`.
     */
    private fun open(url: String, rangeStart: Long?): HttpURLConnection {
        if (CookieHandler.getDefault() != null) {
            throw NetworkPolicyException("hay un CookieHandler global instalado; descarga rechazada")
        }
        var current = parse(url)
        var redirects = 0
        while (true) {
            if (!policy.allows(current)) throw NetworkPolicyException("host no permitido: ${current.host}")
            val conn = current.openConnection() as? HttpURLConnection
                ?: throw NetworkPolicyException("esquema no permitido: ${current.protocol}")
            var handedOver = false
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.allowUserInteraction = false
                conn.doInput = true
                conn.doOutput = false
                conn.requestMethod = "GET"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("Accept", "*/*")
                if (rangeStart != null) conn.setRequestProperty("Range", "bytes=$rangeStart-")

                val code = conn.responseCode
                if (code !in REDIRECT_CODES) {
                    handedOver = true
                    return conn
                }
                if (redirects >= MAX_REDIRECTS) throw IOException("demasiadas redirecciones desde ${current.host}")
                val location = conn.getHeaderField("Location")
                if (location.isNullOrBlank()) throw IOException("redirección sin Location desde ${current.host}")
                current = resolve(current, location)
                redirects++
            } finally {
                if (!handedOver) conn.disconnect()
            }
        }
    }

    private fun parse(url: String): URL = try {
        val uri = URI(url)
        if (!uri.isAbsolute) throw NetworkPolicyException("URL no absoluta")
        uri.toURL()
    } catch (e: URISyntaxException) {
        throw NetworkPolicyException("URL no válida")
    } catch (e: IllegalArgumentException) {
        throw NetworkPolicyException("URL no válida")
    } catch (e: java.net.MalformedURLException) {
        throw NetworkPolicyException("URL no válida")
    }

    private fun resolve(base: URL, location: String): URL = try {
        val next = URL(base, location)
        next.toURI() // valida la sintaxis (rechaza caracteres ilegales)
        next
    } catch (e: java.net.MalformedURLException) {
        throw NetworkPolicyException("redirección no válida desde ${base.host}")
    } catch (e: URISyntaxException) {
        throw NetworkPolicyException("redirección no válida desde ${base.host}")
    }

    /**
     * Copia en bloques de 64 KiB y devuelve el total copiado. Lanza [overflow] antes de entregar
     * un bloque que haría superar [limit].
     */
    private inline fun copyCapped(
        input: InputStream,
        limit: Long,
        overflow: () -> IOException,
        sink: (ByteArray, Int) -> Unit,
    ): Long {
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return total
            if (n == 0) continue
            if (total + n > limit) throw overflow()
            sink(buf, n)
            total += n
        }
    }

    private fun truncate(target: File) {
        FileOutputStream(target, false).use { }
    }

    private fun httpError(code: Int, url: URL) = IOException("HTTP $code desde ${url.host}")

    private fun incomplete(url: URL) = IOException("respuesta incompleta desde ${url.host}")

    private fun tooBig(url: URL) = IOException("la respuesta de ${url.host} supera el tamaño máximo")

    /** Cabecera `Content-Range: bytes start-end/total`; [total] es null si el servidor envía `*`. */
    internal data class ContentRange(val start: Long, val end: Long, val total: Long?)

    companion object {
        const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "lector-bilingue"
        private const val BUFFER_SIZE = 64 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("""^bytes (\d{1,18})-(\d{1,18})/(\d{1,18}|\*)$""")

        /** Devuelve el rango o null si la cabecera no es válida. */
        internal fun parseContentRange(header: String?): ContentRange? {
            val m = CONTENT_RANGE.matchEntire(header?.trim() ?: return null) ?: return null
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLong()
            val total = m.groupValues[3].let { if (it == "*") null else it.toLong() }
            if (end < start) return null
            if (total != null && end >= total) return null
            return ContentRange(start, end, total)
        }
    }
}
