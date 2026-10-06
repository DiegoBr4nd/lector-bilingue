package io.github.diegobr4nd.lectorbilingue.books.readium

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser

/**
 * Limpia el HTML de un libro antes de mostrarlo: el libro no puede ejecutar código ni usar la red.
 * Corre ANTES de que Readium añada sus propios scripts, así que no los toca.
 *
 * Es idempotente: Readium 3.4.0 lo aplica dos veces al mismo capítulo (spike, P3), y sanear dos veces
 * da exactamente lo mismo que sanear una (una sola CSP, la nuestra).
 */
object HtmlSanitizer {
    /**
     * Política de seguridad de contenido (CSP) que se inserta en cada capítulo.
     *
     * Por qué permite `https:` en scripts, estilos, imágenes y fuentes:
     * - Readium sirve el libro desde `https://readium_package` y SUS PROPIOS scripts y CSS desde
     *   `https://readium_assets`, otro origen. Con solo `'self'` Readium no arranca (spike, P3).
     * - Chromium rechaza `https://readium_assets` como fuente de la CSP (el `_` no es válido en un host),
     *   así que no se puede nombrar ese host: solo queda permitir el esquema `https:`.
     * - Es seguro porque Readium intercepta TODA petición del WebView y la atiende él mismo como
     *   recurso del libro: nada llega a la red (spike, P4: 0 conexiones, con y sin sanitizador).
     * - Además, los scripts del libro ya se quitan aquí; `connect-src 'none'` y `form-action 'none'`
     *   cierran fetch/XHR/WebSocket y formularios, y `base-uri 'none'` impide cambiar la base.
     */
    const val CSP = "default-src 'self' https: data: blob:; script-src 'self' https: 'unsafe-inline'; " +
        "style-src 'self' https: 'unsafe-inline' data:; img-src 'self' https: data: blob:; font-src 'self' https: data:; " +
        "media-src 'self' data: blob:; connect-src 'none'; object-src 'none'; frame-src 'self'; form-action 'none'; base-uri 'none'"

    private const val REMOVE = "script, iframe, object, embed, form, base, meta[http-equiv~=(?i)refresh]"
    private const val CSP_META = "meta[http-equiv~=(?i)^content-security-policy$]"
    private val LINK_ATTRS = setOf("href", "src", "xlink:href", "action", "formaction", "data", "poster")

    fun sanitize(markup: String, isSvg: Boolean = false): String {
        val doc = Jsoup.parse(markup, "", Parser.xmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false).escapeMode(Entities.EscapeMode.xhtml).charset("UTF-8")
        doc.select(REMOVE).remove()
        for (el in doc.allElements) cleanAttributes(el)
        if (!isSvg) insertCsp(doc)
        return doc.outerHtml()
    }

    private fun cleanAttributes(el: Element) {
        val toRemove = el.attributes().asList().filter { attr ->
            val key = attr.key.lowercase()
            key.startsWith("on") || (key in LINK_ATTRS && isJavascriptUrl(attr.value))
        }
        for (attr in toRemove) el.removeAttr(attr.key)
    }

    /** "java\tscript:", " JavaScript:"… el navegador ignora espacios y controles: aquí también. */
    private fun isJavascriptUrl(value: String): Boolean =
        value.filterNot { it.isWhitespace() || it.isISOControl() }.lowercase().let { it.startsWith("javascript:") || it.startsWith("vbscript:") }

    /**
     * Deja una sola CSP: la nuestra. Quita cualquier otra (la del libro o la de una pasada anterior)
     * y pone la nuestra la primera en `<head>`. Así sanear dos veces no duplica la etiqueta.
     */
    private fun insertCsp(doc: Document) {
        val html = doc.selectFirst("html") ?: return
        doc.select(CSP_META).remove()
        val head = html.selectFirst("head") ?: html.prependElement("head")
        head.prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", CSP)
    }
}
