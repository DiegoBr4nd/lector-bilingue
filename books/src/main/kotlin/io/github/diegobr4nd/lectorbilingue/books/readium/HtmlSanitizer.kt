package io.github.diegobr4nd.lectorbilingue.books.readium

import org.jsoup.Jsoup
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayInputStream

/**
 * Limpia el HTML de un libro antes de mostrarlo: el libro no puede ejecutar código ni usar la red.
 * Corre ANTES de que Readium añada sus propios scripts, así que no los toca.
 *
 * Es la barrera PRINCIPAL contra el JavaScript del libro. La [CSP] es la segunda: no deja correr scripts en línea,
 * pero tiene que permitir `https:` para que Readium funcione, así que no frena un `<script src>`. Por eso:
 * - compara etiquetas y atributos por su nombre LOCAL (sin prefijo `h:`, `svg:`…), porque el navegador
 *   decide por espacio de nombres y no por el prefijo;
 * - lee el documento con el MISMO analizador que usará el navegador ([Kind]), para que un comentario o un
 *   CDATA no signifique una cosa aquí y otra allí;
 * - si algo no cuadra (un XHTML sin `<html>` en la raíz), no sirve el original: devuelve una página vacía.
 *
 * Es idempotente: Readium 3.4.0 lo aplica dos veces al mismo capítulo (spike, P3), y sanear dos veces
 * da exactamente lo mismo que sanear una (una sola CSP, la nuestra).
 */
object HtmlSanitizer {
    /** Cómo leerá el navegador el recurso; depende del tipo con que Readium lo sirve ([ResourceSanitizing.kindFor]). */
    enum class Kind {
        /** XHTML y otros XML: el navegador usa su analizador XML. Lleva CSP. */
        XHTML,

        /** `text/html` o tipo desconocido: el navegador usa su analizador HTML. Lleva CSP. */
        HTML,

        /** `image/svg+xml`: analizador XML. Sin CSP (un SVG no admite `<meta>`). */
        SVG,
    }

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
     *
     * Por qué `script-src` NO lleva `'unsafe-inline'` (seguridad M3, segunda barrera):
     * - Readium solo inyecta `<script src>` y su JS no usa `eval` ni manejadores `on…`; lo que manda desde Kotlin
     *   va por `evaluateJavascript`, que la CSP no frena. Comprobado en el Pixel 7 (MaliciousEpubOnDeviceTest).
     * - Así, si algún día un `<script>` en línea, un `on…` o un `javascript:` se saltara el saneado, el WebView
     *   no lo ejecuta.
     * - `frame-src`, `worker-src` y `manifest-src` en `'none'`: los `<iframe>` ya se quitan y el modo continuo no
     *   usa marcos dentro del capítulo.
     */
    const val CSP = "default-src 'self' https: data: blob:; script-src 'self' https:; " +
        "style-src 'self' https: 'unsafe-inline' data:; img-src 'self' https: data: blob:; font-src 'self' https: data:; " +
        "media-src 'self' data: blob:; connect-src 'none'; object-src 'none'; frame-src 'none'; worker-src 'none'; " +
        "manifest-src 'none'; form-action 'none'; base-uri 'none'"

    private const val XHTML_NS = "http://www.w3.org/1999/xhtml"

    /** Página vacía que se sirve cuando un XHTML no tiene `<html>` en la raíz (fallar cerrado). */
    private const val EMPTY_XHTML = """<html xmlns="$XHTML_NS"><head><title></title></head><body></body></html>"""

    /** Se quitan con todo su contenido, en cualquier espacio de nombres. */
    private val REMOVE_ALWAYS = setOf(
        "script", "iframe", "frame", "frameset", "object", "embed", "applet", "form", "base", "portal",
        // El navegador (con JavaScript activo) lee su contenido como texto crudo y jsoup como elementos:
        // esa diferencia permite colar etiquetas (mXSS). Con JavaScript activo no se muestran, así que no se pierde nada.
        "noscript", "noembed", "noframes", "xmp", "plaintext",
        // SVG Tiny 1.2: manejadores de eventos como elementos.
        "handler", "listener",
    )

    /** En modo HTML jsoup no aplica las reglas de "contenido extranjero" del navegador: otra fuente de mXSS. */
    private val REMOVE_IN_HTML = setOf("svg", "math")

    /** Animaciones SVG que podrían convertir un enlace en `javascript:` aunque el valor inicial sea inocente. */
    private val ANIMATIONS = setOf("set", "animate", "animatemotion", "animatetransform")

    /**
     * Únicos valores de `rel` que puede llevar un `<link>`. Los demás se quitan (fallar cerrado): las pistas de red
     * (`preconnect`, `dns-prefetch`, `prefetch`, `prerender`, `preload`, `modulepreload`…) no siempre pasan por
     * Readium ni por la CSP, y avisarían a un servidor de que el libro se abrió.
     */
    private val LINK_REL_ALLOWED = setOf("stylesheet", "alternate")

    /** Atributos que piden a la red o crean documentos, sea cual sea el elemento: `<a ping>`, `srcdoc`, `attributionsrc`. */
    private val REMOVE_ATTRIBUTES = setOf("ping", "srcdoc", "attributionsrc")

    fun sanitize(markup: String, kind: Kind = Kind.XHTML): String =
        clean(Jsoup.parse(markup, "", parserFor(kind)), kind)

    /**
     * Versión por bytes, la que usa Readium. Detecta la codificación (BOM, luego la declaración XML,
     * luego `<meta charset>`; si no hay nada, UTF-8) y siempre devuelve UTF-8, con la declaración o el
     * `<meta charset>` reescritos a UTF-8.
     */
    fun sanitize(bytes: ByteArray, kind: Kind): ByteArray =
        clean(Jsoup.parse(ByteArrayInputStream(bytes), null, "", parserFor(kind)), kind).toByteArray(Charsets.UTF_8)

    private fun parserFor(kind: Kind): Parser = if (kind == Kind.HTML) Parser.htmlParser() else Parser.xmlParser()

    private fun clean(doc: Document, kind: Kind): String {
        val xml = kind != Kind.HTML
        doc.outputSettings()
            .syntax(if (xml) Document.OutputSettings.Syntax.xml else Document.OutputSettings.Syntax.html)
            .prettyPrint(false)
            .escapeMode(if (xml) Entities.EscapeMode.xhtml else Entities.EscapeMode.base)
            .charset(Charsets.UTF_8)

        for (node in allNodes(doc)) {
            when (node) {
                is Comment -> node.remove()
                // CDATA pasa a texto normal: al escribirlo se escapan `<` y `&`, y ya no puede volverse marcado.
                is CDataNode -> node.replaceWith(TextNode(node.text()))
                // Todas las instrucciones de proceso (xml-stylesheet, XSLT…) y la declaración XML; esta se repone abajo.
                is XmlDeclaration -> node.remove()
                // Un DOCTYPE con subconjunto interno define entidades que el analizador XML del navegador expandiría.
                is DocumentType -> if (xml) node.remove()
                is Element -> if (isDangerous(node, kind)) node.remove() else cleanAttributes(node)
                else -> Unit
            }
        }

        if (kind != Kind.SVG && !insertCsp(doc, kind)) {
            // Fallar cerrado: un XHTML sin <html> en la raíz no puede llevar la CSP. Se sirve una página vacía.
            return clean(Jsoup.parse(EMPTY_XHTML, "", Parser.xmlParser()), kind)
        }
        if (xml) {
            doc.prependChild(XmlDeclaration("xml", false).attr("version", "1.0").attr("encoding", "UTF-8"))
        }
        return doc.outerHtml()
    }

    private fun allNodes(root: Node): List<Node> {
        val out = ArrayList<Node>()
        NodeTraversor.traverse(NodeVisitor { node, _ -> out += node }, root)
        return out
    }

    private fun localName(qualified: String): String = qualified.substringAfterLast(':').trim().lowercase()

    private fun isDangerous(el: Element, kind: Kind): Boolean {
        val name = localName(el.tagName())
        return name in REMOVE_ALWAYS ||
            (kind == Kind.HTML && name in REMOVE_IN_HTML) ||
            // Todos los <meta http-equiv> (refresh, CSP del libro, content-type…) y <meta charset>: se pone lo nuestro.
            (name == "meta" && el.attributes().asList().any { localName(it.key) == "http-equiv" || localName(it.key) == "charset" }) ||
            (name in ANIMATIONS && el.attributes().asList().any { localName(it.key) == "attributename" && localName(it.value) == "href" }) ||
            (name == "link" && hasForbiddenRel(el))
    }

    /** true si algún token de `rel` (separados por espacios, sin distinguir mayúsculas, con o sin prefijo) no está permitido. */
    private fun hasForbiddenRel(link: Element): Boolean =
        link.attributes().asList()
            .filter { localName(it.key) == "rel" }
            .flatMap { it.value.lowercase().split(' ', '\t', '\n', '\r', '\u000C') }
            .any { it.isNotEmpty() && it !in LINK_REL_ALLOWED }

    private fun cleanAttributes(el: Element) {
        val toRemove = el.attributes().asList().filter { attr ->
            localName(attr.key).startsWith("on") || localName(attr.key) in REMOVE_ATTRIBUTES ||
                attr.key.lowercase() == "xml:base" || isDangerousUrl(attr.value)
        }
        for (attr in toRemove) el.removeAttr(attr.key)
    }

    /**
     * Se mira en TODOS los atributos (sea cual sea su nombre o prefijo). "java\tscript:", " JavaScript:"…
     * el navegador ignora espacios y controles: aquí también. `data:` solo se deja para tipos inertes
     * (imágenes que no son SVG, fuentes, audio, vídeo, CSS); `data:text/html` y similares se quitan.
     */
    private fun isDangerousUrl(value: String): Boolean {
        val v = value.filterNot { it.isWhitespace() || it.isISOControl() }.lowercase()
        if (v.startsWith("javascript:") || v.startsWith("vbscript:") || v.startsWith("livescript:")) return true
        if (!v.startsWith("data:")) return false
        val type = v.removePrefix("data:").substringBefore(',').substringBefore(';')
        return !ResourceSanitizing.isInert(type)
    }

    /**
     * Deja una sola CSP: la nuestra, la primera dentro de `<head>`. Las demás ya se quitaron en [isDangerous]
     * (la del libro o la de una pasada anterior), así que sanear dos veces no duplica la etiqueta.
     * Devuelve false si no hay dónde ponerla (XHTML sin `<html>` en la raíz).
     */
    private fun insertCsp(doc: Document, kind: Kind): Boolean {
        if (kind == Kind.HTML) {
            // jsoup siempre crea <html>, <head> y <body> en modo HTML.
            val head = doc.head()
            head.prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", CSP)
            head.prependElement("meta").attr("charset", "UTF-8")
            return true
        }
        val root = doc.children().firstOrNull() ?: return false
        if (localName(root.tagName()) != "html") return false
        // La raíz pasa a ser XHTML de verdad (si no, el navegador no la trata como HTML y no aplica la CSP).
        val prefix = root.tagName().substringBeforeLast(':', missingDelimiterValue = "")
        val nsAttr = if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
        fun q(local: String) = if (prefix.isEmpty()) local else "$prefix:$local"
        root.attr(nsAttr, XHTML_NS)
        // Solo vale un <head> hijo directo, con el mismo prefijo y sin redefinir ese prefijo a otro espacio de nombres.
        val head = root.children().firstOrNull { localName(it.tagName()) == "head" }
            ?.takeIf { it.tagName() == q("head") && (!it.hasAttr(nsAttr) || it.attr(nsAttr) == XHTML_NS) }
            ?: root.prependElement(q("head"))
        head.prependElement(q("meta")).attr("http-equiv", "Content-Security-Policy").attr("content", CSP)
        return true
    }
}
