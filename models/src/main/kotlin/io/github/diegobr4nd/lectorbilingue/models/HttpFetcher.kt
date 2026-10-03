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

    /** Descarga completa a memoria con tope (catálogo y firma). Solo acepta 200. */
    fun fetchBytes(url: String, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "maxBytes negativo" }
        val conn = open(url, rangeStart = null)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw httpError(code, conn.url)
            val declared = conn.contentLengthLong
            if (declared > maxBytes) throw tooBig(conn.url)
            val out = ByteArrayOutputStream(if (declared in 0..maxBytes) declared.toInt() else 8192)
            conn.inputStream.use { input ->
                copyCapped(input, maxBytes.toLong(), conn.url) { buf, n -> out.write(buf, 0, n) }
            }
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
     *   [onBytes] no recibe los bytes que ya estaban en el archivo antes de llamar.
     * - Llama a [onReset] cada vez que el archivo se trunca a cero (el servidor ignoró el `Range`,
     *   respondió un rango que no empieza donde esperábamos, o el parcial ya superaba [maxBytes]).
     *   Quien calcule un hash incremental debe reiniciarlo ahí.
     * - El archivo nunca supera [maxBytes]: se aborta con IOException antes de escribir el bloque que lo excedería.
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
        if (existing > maxBytes) {
            truncate(target)
            onReset()
            existing = 0L
        }
        var restarted = false
        while (true) {
            val rangeStart = if (existing > 0) existing else null
            val conn = open(url, rangeStart)
            try {
                val code = conn.responseCode
                val append: Boolean
                when (code) {
                    HttpURLConnection.HTTP_OK -> {
                        append = false
                        if (conn.contentLengthLong > maxBytes) throw tooBig(conn.url)
                    }
                    HttpURLConnection.HTTP_PARTIAL -> {
                        if (rangeStart == null) throw IOException("HTTP 206 sin haber pedido rango desde ${conn.url.host}")
                        val range = parseContentRange(conn.getHeaderField("Content-Range"))
                        if (range == null || range.first != rangeStart) {
                            if (restarted) throw IOException("rango inválido desde ${conn.url.host}")
                            conn.disconnect()
                            truncate(target)
                            onReset()
                            existing = 0L
                            restarted = true
                            continue
                        }
                        val total = range.second
                        if (total != null && total > maxBytes) throw tooBig(conn.url)
                        val len = conn.contentLengthLong
                        if (len >= 0 && rangeStart + len > maxBytes) throw tooBig(conn.url)
                        append = true
                    }
                    else -> throw httpError(code, conn.url)
                }
                if (!append && existing > 0) onReset()
                val start = if (append) existing else 0L
                conn.inputStream.use { input ->
                    FileOutputStream(target, append).use { out ->
                        copyCapped(input, maxBytes - start, conn.url) { buf, n ->
                            out.write(buf, 0, n)
                            onBytes(buf, n)
                        }
                    }
                }
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

    /** Copia en bloques de 64 KiB; lanza IOException antes de entregar un bloque que supere [limit]. */
    private inline fun copyCapped(input: InputStream, limit: Long, url: URL, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            if (n == 0) continue
            if (total + n > limit) throw tooBig(url)
            sink(buf, n)
            total += n
        }
    }

    private fun truncate(target: File) {
        FileOutputStream(target, false).use { }
    }

    private fun httpError(code: Int, url: URL) = IOException("HTTP $code desde ${url.host}")

    private fun tooBig(url: URL) = IOException("la respuesta de ${url.host} supera el tamaño máximo")

    companion object {
        const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "lector-bilingue"
        private const val BUFFER_SIZE = 64 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("""^bytes (\d{1,18})-(\d{1,18})/(\d{1,18}|\*)$""")

        /** Devuelve (inicio, total o null si es `*`) o null si la cabecera no es válida. */
        internal fun parseContentRange(header: String?): Pair<Long, Long?>? {
            val m = CONTENT_RANGE.matchEntire(header?.trim() ?: return null) ?: return null
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLong()
            val total = m.groupValues[3].let { if (it == "*") null else it.toLong() }
            if (end < start) return null
            if (total != null && end >= total) return null
            return start to total
        }
    }
}
