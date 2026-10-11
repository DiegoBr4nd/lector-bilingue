package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.data.TranslationRules
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Un párrafo de la página visible: [index] es su posición entre los párrafos del recurso (0, 1, 2…). [cut]: la página lo
 * recortó al tope ([TranslationRules.MAX_PARAGRAPH_CHARS]); es demasiado largo aunque [text] quede en el tope.
 */
data class PageParagraph(val index: Int, val text: String, val cut: Boolean = false)

/** Lo que muestra la tarjeta bajo un párrafo. */
sealed interface Card {
    /** "Traduciendo…" */
    data object Skeleton : Card

    /** "Preparando el traductor…" */
    data object Preparing : Card

    data class Text(val translation: String) : Card

    /**
     * Texto ya armado, con el tamaño del modelo que falta; [action] ("Descargar") va como enlace subrayado. [spoken] es
     * el nombre de la tarjeta para TalkBack, con "a" en vez de la flecha.
     */
    data class MissingModel(val label: String, val action: String, val spoken: String) : Card

    /** [spoken]: el nombre de la tarjeta para TalkBack ("No se pudo traducir este párrafo. Reintentar"). */
    data class Failed(val label: String, val retry: String, val spoken: String) : Card

    /** "Este párrafo es demasiado largo…": estilo de error, sin enlace (reintentar daría lo mismo). */
    data class TooLong(val label: String) : Card

    /**
     * Solo traducción (3c-2): la traducción EN LUGAR del original, con la letra del libro. El original queda oculto
     * (clase `lector-oculto`) y la tarjeta copia su estilo calculado ([ParagraphScripts.COPIED_STYLE]).
     */
    data class Solo(val translation: String) : Card

    /** Solo traducción, aún sin traducir: el original se ve en gris y debajo [label] ("Traduciendo…"). */
    data class Waiting(val label: String) : Card
}

/**
 * Qué se mantiene quieto en pantalla al poner, quitar o cambiar una tarjeta (Ruling F de la 3c-2). [wire] es el valor
 * que va al script, de esta lista fija.
 */
enum class CardAnchor(val wire: String) {
    /** El propio párrafo cambiado: en TAP la tarjeta sale justo debajo del tocado (como en la 3b). */
    SELF("self"),

    /** Lo que se lee: el primer bloque que empieza en pantalla (Intercalado y Solo traducción). */
    SCREEN("screen"),
}

/**
 * Textos fijos de la tarjeta (vienen de strings.xml). [translationPrefix] es el nombre que oye TalkBack. [original] es
 * la marca pequeña ("Original") que se ve bajo el original mostrado en Solo traducción.
 */
data class CardLabels(val translationPrefix: String, val skeleton: String, val preparing: String, val original: String)

/**
 * Arma los scripts propios que se ejecutan en la página del libro (vía `evaluateJavascript`) y lee sus respuestas.
 * Puro: no toca el WebView, así se prueba en la JVM.
 *
 * Reglas de seguridad (spec 3b §5.1 y §7):
 * - Cada script es una función autoejecutada con UN solo argumento: un objeto JSON armado con org.json. El texto del
 *   libro o de la traducción nunca se pega como código: comillas, `</script>` o U+2028 no pueden romper el script.
 * - Hacia la página solo texto, con `textContent`. Nunca marcado (`innerHTML` y parecidos) ni atributos `on…`.
 * - La página no puede llamar a la app: no hay `addJavascriptInterface`; la app pregunta y la página solo responde.
 */
object ParagraphScripts {
    /** Qué cuenta como párrafo. El índice es la posición entre todos los que casan, sin contar los de las tarjetas. */
    const val SELECTOR = "p, li, blockquote, h1, h2, h3, h4, h5, h6, dd"

    private const val CARD_CLASS = "lector-tarjeta"

    /**
     * Párrafos del recurso: solo hojas (un `blockquote` o `li` que contiene un `p` no cuenta, sí su `p`: así el texto no
     * se repite) y sin los que estén dentro de una tarjeta nuestra. Lo usan todos los scripts.
     */
    private const val PARAGRAPHS =
        "var all = Array.prototype.filter.call(document.querySelectorAll(a.sel), " +
            "function (e) { return !e.closest('.$CARD_CLASS') && !e.querySelector(a.sel); });"

    /** El selector de la tarjeta del párrafo [n] (una expresión JS que da un entero puesto por la app). */
    private fun cardSelector(n: String) = "'aside.$CARD_CLASS[data-lector-i=\"' + $n + '\"]'"

    /**
     * La tarjeta del párrafo `p` (= `all[a.i]`), si existe: SOLO su hermano siguiente con nuestra clase y su índice.
     * Un `aside` en otro sitio no cuenta (el saneado ya quita esas marcas del libro; esto es la segunda barrera).
     */
    private val FIND_CARD =
        "var card = p.nextElementSibling; if (!(card && card.matches(${cardSelector("a.i")}))) card = null;"

    /** Clase que esconde el original en Solo traducción (tarjeta.css: `display: none` solo con el modo "solo"). */
    private const val HIDDEN_CLASS = "lector-oculto"

    /**
     * Estilo que la tarjeta Solo copia del original (Ruling D, spec §11 M4): una lista FIJA de propiedades, con valores
     * que salen solo de `getComputedStyle` (nunca del libro como texto ni como marcado). Así un `h2` sigue grande, una
     * cita con sangría y un `li` con viñeta. Fondo, borde y color los pone tarjeta.css.
     */
    val COPIED_STYLE = listOf(
        "display", "font-family", "font-size", "font-weight", "font-style", "line-height", "text-indent", "text-align",
        "letter-spacing", "word-spacing", "text-transform", "font-variant", "margin-top", "margin-right", "margin-bottom",
        "margin-left", "padding-top", "padding-right", "padding-bottom", "padding-left", "list-style-type",
        "list-style-position",
    )

    /** Rectángulo del párrafo `all[k]`: si está oculto (mide 0×0), el de su tarjeta (Ruling E). Solo lee posiciones. */
    private val RECT =
        "function rectOf(k) { var e = all[k]; if (e.classList.contains('$HIDDEN_CLASS')) { var c = e.nextElementSibling; " +
            "if (c && c.matches(${cardSelector("k")})) return c.getBoundingClientRect(); } return e.getBoundingClientRect(); }"

    /**
     * Sin saltos (Ruling C, técnica b de la spec §11). `anchor()` busca lo que se está leyendo: el primer bloque hoja
     * que EMPIEZA dentro de la pantalla (`0 ≤ top < alto de la ventana`, alto > 0); si no hay (un párrafo más alto que
     * la pantalla), el que cruza el borde de arriba (`top < 0 < bottom`), fijado por su `top`; si tampoco, -1 (no se
     * corrige). Nunca uno de más abajo: una tarjeta entre lo visible y ese bloque haría saltar el texto.
     */
    private val ANCHOR_FN = RECT +
        " function anchor() { var h = window.innerHeight, cross = -1; for (var k = 0; k < all.length; k++) {" +
        " var r = rectOf(k); if (r.height <= 0) continue;" +
        " if (r.top >= 0 && r.top < h && r.height > 0) return k;" +
        " if (cross < 0 && r.top < 0 && r.bottom > 0) cross = k;" +
        " if (r.top >= h) break; } return cross; }"

    /** Ancla de lo que se lee en pantalla (para [setMode], que cambia toda la página). */
    private val ANCHOR = ANCHOR_FN + " var b = anchor(), top0 = b < 0 ? 0 : rectOf(b).top;"

    /**
     * Ancla según la política `a.anchor` ([CardAnchor], Ruling F): "self" fija el propio párrafo cambiado (TAP: la
     * tarjeta sale justo debajo del tocado, como en la 3b); "screen", lo que se lee en pantalla (Intercalado y Solo).
     */
    private val ANCHOR_BY_POLICY = ANCHOR_FN + " var b = a.anchor === 'self' ? a.i : anchor(), top0 = b < 0 ? 0 : rectOf(b).top;"

    /** Guardia de la tarjeta Solo y de [showOriginal]: fuera del modo "solo" no se oculta ningún original. */
    private const val NOT_SOLO_MODE = "document.documentElement.getAttribute('data-lector-modo') !== 'solo'"
    private const val KEEP_ANCHOR = "if (b >= 0) { var d = rectOf(b).top - top0; if (d) window.scrollBy(0, d); }"

    /** Quita de `card` el estilo copiado (solo las propiedades de la lista fija) y muestra el original `p`. */
    private const val UNSTYLE_AND_SHOW =
        "function unstyleAndShow() { a.props.forEach(function (k) { card.style.removeProperty(k); }); " +
            "p.classList.remove('$HIDDEN_CLASS'); }"

    /**
     * Copia a la tarjeta el estilo calculado del original `p` y luego lo oculta. El original se muestra antes de leerlo
     * (oculto, su `display` sería `none`); todo pasa en el mismo script, sin pintar en medio.
     */
    private const val COPY_AND_HIDE =
        "function copyAndHide() { p.classList.remove('$HIDDEN_CLASS'); var cs = getComputedStyle(p); " +
            "a.props.forEach(function (k) { card.style.setProperty(k, cs.getPropertyValue(k), 'important'); }); " +
            "p.classList.add('$HIDDEN_CLASS'); }"

    /** Un párrafo como lo manda la página: espacios colapsados y, si pasa del tope, cortado ahí más "…" y `cut`. */
    private val ITEM =
        """
        function item(i, t) {
          t = t.replace(/[ \t\n\x0B\f\r\u00A0]+/g, ' ').trim();
          return t.length > a.max ? { i: i, t: t.slice(0, a.max) + '…', cut: true } : { i: i, t: t };
        }
        """.trimIndent()

    /**
     * El párrafo bajo el punto (en px CSS) y los [following] siguientes con texto.
     * Responde `JSON.stringify([{i, t}, …])`, el tocado primero; `[]` si no hay párrafo con texto. Cada `t` va con los
     * espacios colapsados como en [TranslationRules.normalize] y, si pasa de [TranslationRules.MAX_PARAGRAPH_CHARS],
     * cortado ahí más "…" y con `cut: true`: así la app sabe que es demasiado largo sin recibir megas de texto (la marca
     * cuenta aunque Kotlin, al recortar espacios, lo deje justo en el tope).
     */
    fun find(xCss: Double, yCss: Double, following: Int = 5): String = script(
        JSONObject().put("sel", SELECTOR).put("x", xCss).put("y", yCss).put("n", following)
            .put("max", TranslationRules.MAX_PARAGRAPH_CHARS),
        """
        var hit = document.elementFromPoint(a.x, a.y);
        var el = hit && hit.closest ? hit.closest(a.sel) : null;
        if (!el || el.closest('.$CARD_CLASS')) return JSON.stringify([]);
        $PARAGRAPHS
        var at = all.indexOf(el);
        var text = el.textContent || '';
        if (at < 0 || !text.trim()) return JSON.stringify([]);
        $ITEM
        var out = [item(at, text)];
        for (var k = at + 1; k < all.length && out.length <= a.n; k++) {
          var t = all[k].textContent || '';
          if (t.trim()) out.push(item(k, t));
        }
        return JSON.stringify(out);
        """,
    )

    /**
     * Lee la respuesta de [find]. Normaliza el texto (espacios colapsados). Vacía si no hay párrafo tocado con texto o si
     * los datos no tienen la forma esperada (fallar cerrado). WebView entrega el valor como JSON: si llega como cadena
     * citada (lo que devuelve `JSON.stringify`), se desenvuelve una vez.
     */
    fun parseFind(json: String?): List<PageParagraph> = parseItems(json, firstNeedsText = true)

    /**
     * Los párrafos hoja que se ven (tocan el viewport) y los [following] siguientes con texto, de arriba abajo
     * (spec 3c-2 §5.2). Responde `JSON.stringify([{i, t, cut}, …])` con el mismo recorte y marca que [find]. Un original
     * oculto en Solo traducción se mide con el rectángulo de su tarjeta. Las tarjetas no cuentan. Solo lee.
     */
    fun visible(following: Int = 10): String {
        require(following in 0..MAX_FOLLOWING) { "Número de siguientes fuera de rango" }
        return script(
            JSONObject().put("sel", SELECTOR).put("n", following).put("max", TranslationRules.MAX_PARAGRAPH_CHARS),
            """
            $PARAGRAPHS
            $ITEM
            $RECT
            var h = window.innerHeight, out = [], extra = 0, below = false;
            for (var k = 0; k < all.length; k++) {
              var r = rectOf(k);
              // Arriba de la pantalla, o sin caja (un elemento oculto del libro mide 0×0).
              if (r.bottom <= 0) continue;
              var t = all[k].textContent || '';
              if (!below && r.top < h) { if (t.trim()) out.push(item(k, t)); continue; }
              below = true;
              if (extra >= a.n) break;
              if (t.trim()) { out.push(item(k, t)); extra++; }
            }
            return JSON.stringify(out);
            """,
        )
    }

    /**
     * Lee la respuesta de [visible], como [parseFind] (normaliza, se salta los vacíos). Falla cerrado (lista vacía) si
     * la forma no cuadra, si los índices no van de arriba abajo sin repetirse o si llegan más de [MAX_VISIBLE_ITEMS].
     */
    fun parseVisible(json: String?): List<PageParagraph> {
        val items = parseItems(json, firstNeedsText = false)
        if (items.size > MAX_VISIBLE_ITEMS) return emptyList()
        if (items.zipWithNext().any { (x, y) -> y.index <= x.index }) return emptyList()
        return items
    }

    /** Modos de la página que entiende tarjeta.css (`data-lector-modo` en `<html>`). */
    val MODES = setOf("tocar", "intercalado", "solo")

    /**
     * Pone `data-lector-modo` en el `<html>` de la página, como [setTheme]: solo un valor de [MODES] (cualquier otro
     * lanza IllegalArgumentException) y como dato JSON. Al salir de "solo" quita el estilo copiado de todas las tarjetas
     * y vuelve a mostrar los originales (Ruling D). Sin saltos (Ruling C). Responde true.
     */
    fun setMode(mode: String): String {
        require(mode in MODES) { "Modo de página desconocido" }
        return script(
            JSONObject().put("sel", SELECTOR).put("modo", mode).put("props", JSONArray(COPIED_STYLE)),
            """
            $PARAGRAPHS
            $ANCHOR
            document.documentElement.setAttribute('data-lector-modo', a.modo);
            if (a.modo !== 'solo') {
              var cards = document.querySelectorAll('aside.$CARD_CLASS[data-lector-i]');
              for (var k = 0; k < cards.length; k++) {
                var c = cards[k];
                a.props.forEach(function (n) { c.style.removeProperty(n); });
                c.removeAttribute('data-lector-original');
              }
              var hidden = document.querySelectorAll('.$HIDDEN_CLASS');
              for (var k = 0; k < hidden.length; k++) hidden[k].classList.remove('$HIDDEN_CLASS');
            }
            $KEEP_ANCHOR
            return true;
            """,
        )
    }

    /**
     * Solo traducción: con [on] muestra el original del párrafo [index] (quita `lector-oculto`) y la tarjeta pasa a
     * la marca "Original" (`data-lector-original`, sin el estilo copiado); sin [on], vuelve a la traducción. Solo actúa
     * en el modo "solo" y si el párrafo tiene una tarjeta Solo; responde true si cambió algo. Sin saltos (Ruling C,
     * con la política [anchor]).
     */
    fun showOriginal(index: Int, on: Boolean, anchor: CardAnchor): String {
        require(index >= 0) { "Índice negativo" }
        return script(
            JSONObject().put("sel", SELECTOR).put("i", index).put("on", on).put("props", JSONArray(COPIED_STYLE))
                .put("anchor", anchor.wire),
            """
            if ($NOT_SOLO_MODE) return false;
            $PARAGRAPHS
            var p = all[a.i];
            if (!p) return false;
            $FIND_CARD
            if (!card || card.dataset.lectorEstado !== 'solo') return false;
            $UNSTYLE_AND_SHOW
            $COPY_AND_HIDE
            $ANCHOR_BY_POLICY
            if (a.on) { card.setAttribute('data-lector-original', ''); unstyleAndShow(); }
            else { card.removeAttribute('data-lector-original'); copyAndHide(); }
            $KEEP_ANCHOR
            return true;
            """,
        )
    }

    /**
     * Crea (o reemplaza el contenido de) la tarjeta bajo el párrafo [index]. Responde true si el párrafo existe (una
     * tarjeta Solo, además, solo en el modo "solo"). Sin saltos con la política [anchor] (Ruling C y F).
     */
    fun insert(index: Int, card: Card, labels: CardLabels, anchor: CardAnchor): String {
        val data = JSONObject().put("sel", SELECTOR).put("i", index).put("prefix", labels.translationPrefix)
            .put("props", JSONArray(COPIED_STYLE)).put("anchor", anchor.wire)
        when (card) {
            Card.Skeleton -> data.put("kind", "esqueleto").put("text", labels.skeleton)
            Card.Preparing -> data.put("kind", "preparando").put("text", labels.preparing)
            is Card.Text -> data.put("kind", "texto").put("text", card.translation)
            is Card.MissingModel -> data.put("kind", "falta-modelo").put("text", card.label).put("retry", card.action).put("spoken", card.spoken)
            is Card.Failed -> data.put("kind", "error").put("text", card.label).put("retry", card.retry).put("spoken", card.spoken)
            is Card.TooLong -> data.put("kind", "error").put("text", card.label).put("spoken", card.label)
            is Card.Solo -> data.put("kind", "solo").put("text", card.translation).put("mark", labels.original)
            is Card.Waiting -> data.put("kind", "espera").put("text", card.label).put("spoken", card.label)
        }
        return script(
            data,
            """
            if (a.kind === 'solo' && $NOT_SOLO_MODE) return false;
            $PARAGRAPHS
            var p = all[a.i];
            if (!p) return false;
            $FIND_CARD
            $UNSTYLE_AND_SHOW
            $COPY_AND_HIDE
            $ANCHOR_BY_POLICY
            if (!card) {
              card = document.createElement('aside');
              card.classList.add('$CARD_CLASS');
              card.dataset.lectorI = String(a.i);
              card.setAttribute('role', 'note');
              p.after(card);
            }
            // Con texto, sin nombre propio: TalkBack, al deslizar, lee la traducción (un nombre la taparía).
            if (a.kind === 'texto' || a.kind === 'solo') card.removeAttribute('aria-label');
            else card.setAttribute('aria-label', typeof a.spoken === 'string' ? a.spoken : a.prefix);
            card.dataset.lectorEstado = a.kind;
            card.textContent = '';
            if (a.kind === 'solo') {
              // La traducción en lugar del original y, escondida, la marca "Original" (se ve con data-lector-original).
              var tr = document.createElement('span');
              tr.classList.add('lector-traduccion');
              tr.textContent = a.text;
              card.appendChild(tr);
              var m = document.createElement('span');
              m.classList.add('lector-marca');
              m.textContent = a.mark;
              card.appendChild(m);
              // Si la persona está viendo el original, se queda así; si no, la tarjeta toma su estilo y lo oculta.
              if (card.hasAttribute('data-lector-original')) unstyleAndShow(); else copyAndHide();
            } else {
              // Cualquier otra tarjeta va bajo el original visible y con el estilo de tarjeta.
              card.removeAttribute('data-lector-original');
              unstyleAndShow();
              if (a.kind === 'esqueleto' || a.kind === 'preparando') {
                var s = document.createElement('span');
                s.classList.add('lector-esqueleto');
                s.textContent = a.text;
                card.appendChild(s);
              } else {
                card.textContent = a.text;
              }
              if (typeof a.retry === 'string') {
                var r = document.createElement('span');
                r.classList.add('lector-reintentar');
                r.textContent = a.retry;
                card.appendChild(document.createTextNode(' '));
                card.appendChild(r);
              }
            }
            $KEEP_ANCHOR
            return true;
            """,
        )
    }

    /**
     * Quita la tarjeta del párrafo [index] si existe y vuelve a mostrar el original si estaba oculto. Responde true si
     * había tarjeta. Sin saltos con la política [anchor] (Ruling C y F).
     */
    fun remove(index: Int, anchor: CardAnchor): String = script(
        JSONObject().put("sel", SELECTOR).put("i", index).put("anchor", anchor.wire),
        """
        $PARAGRAPHS
        var p = all[a.i];
        if (!p) return false;
        $FIND_CARD
        if (!card && !p.classList.contains('$HIDDEN_CLASS')) return false;
        $ANCHOR_BY_POLICY
        if (card) card.remove();
        p.classList.remove('$HIDDEN_CLASS');
        $KEEP_ANCHOR
        return !!card;
        """,
    )

    /**
     * Si la página ya cargó del todo y es el recurso [path] (el `href` del localizador, sin `#`), quita TODAS nuestras
     * tarjetas y responde true; si no, false (la pantalla reintenta poco después). Así una tarjeta vieja (de un capítulo
     * vecino que el ViewPager tenía cargado) no queda antes de reponer las abiertas (spec §12, Ruling K). Solo quita.
     */
    fun removeAll(path: String): String = script(
        JSONObject().put("path", path.substringBefore('#').substringBefore('?')),
        """
        if (document.readyState !== 'complete') return false;
        var here, want;
        try { here = decodeURIComponent(location.pathname); want = decodeURIComponent(a.path); } catch (e) { return false; }
        want = '/' + want.replace(/^\/+/, '');
        if (here.slice(-want.length) !== want) return false;
        var cards = document.querySelectorAll('aside.$CARD_CLASS[data-lector-i]');
        for (var k = 0; k < cards.length; k++) cards[k].remove();
        // Sin tarjetas, ningún original puede quedar oculto (Solo traducción).
        var hidden = document.querySelectorAll('.$HIDDEN_CLASS');
        for (var k = 0; k < hidden.length; k++) hidden[k].classList.remove('$HIDDEN_CLASS');
        return true;
        """,
    )

    /** Lee la respuesta de [removeAll]: true solo si la página estaba lista (y ya sin tarjetas). */
    fun parseReady(json: String?): Boolean = parse(json) == true

    /** Índice del párrafo o de la tarjeta bajo el punto (px CSS); -1 si no hay ninguno. Para cerrar tocando la tarjeta. */
    fun indexAt(xCss: Double, yCss: Double): String = script(
        JSONObject().put("sel", SELECTOR).put("x", xCss).put("y", yCss),
        """
        var hit = document.elementFromPoint(a.x, a.y);
        if (!hit || !hit.closest) return -1;
        $PARAGRAPHS
        var card = hit.closest('aside.$CARD_CLASS');
        if (card) {
          var n = parseInt(card.dataset.lectorI, 10);
          // Solo si de verdad es la tarjeta de ese párrafo (su hermano siguiente con el mismo índice).
          return !isNaN(n) && all[n] && all[n].nextElementSibling === card && card.matches(${cardSelector("n")}) ? n : -1;
        }
        var el = hit.closest(a.sel);
        if (!el) return -1;
        return all.indexOf(el);
        """,
    )

    /**
     * Bordes de la página visible, como número: 1 = arriba del todo, 2 = abajo del todo (3 = los dos: el capítulo cabe
     * entero). Con 2 px de tolerancia por el redondeo del desplazamiento. Solo lee posiciones, nunca texto.
     */
    fun edges(): String = script(
        JSONObject(),
        """
        var e = document.scrollingElement || document.documentElement;
        var y = window.scrollY, h = window.innerHeight;
        return (y <= 2 ? 1 : 0) + (y + h >= e.scrollHeight - 2 ? 2 : 0);
        """,
    )

    /** Temas de la tarjeta que entiende tarjeta.css (ver [ReadingRules.cardTheme]). */
    val CARD_THEMES = setOf("claro", "sepia", "oscuro", "negro")

    /**
     * Pone `data-lector-tema` en el `<html>` de la página: tarjeta.css elige con él la paleta de la tarjeta. Solo un valor
     * de [CARD_THEMES] (cualquier otro lanza IllegalArgumentException), y aun así va como dato JSON. Responde true.
     */
    fun setTheme(theme: String): String {
        require(theme in CARD_THEMES) { "Tema de tarjeta desconocido" }
        return script(
            JSONObject().put("tema", theme),
            """
            document.documentElement.setAttribute('data-lector-tema', a.tema);
            return true;
            """,
        )
    }

    /** Lee la respuesta de [edges], o null si no es un entero entre 0 y 3 (página no lista, respuesta rara). */
    fun parseEdges(json: String?): PageEdges? {
        val n = (parse(json) as? Int)?.takeIf { it in 0..3 } ?: return null
        return PageEdges(atTop = n and 1 != 0, atBottom = n and 2 != 0)
    }

    /**
     * Pasa el punto tocado (px del aparato) a px CSS. null si algo no es un número finito o la densidad no es positiva:
     * org.json no admite NaN ni infinitos y el script no tendría sentido.
     */
    fun cssPoint(xPx: Float, yPx: Float, density: Float): Pair<Double, Double>? {
        if (!density.isFinite() || density <= 0f) return null
        val x = xPx.toDouble() / density
        val y = yPx.toDouble() / density
        return if (x.isFinite() && y.isFinite()) x to y else null
    }

    /** Lee la respuesta de [indexAt]: el índice, o null si no hay párrafo (o la respuesta no es un entero). */
    fun parseIndex(json: String?): Int? = (parse(json) as? Int)?.takeIf { it >= 0 }

    private val WHITESPACE = Regex("\\s+")

    /** Tope de "siguientes" que se piden a [visible]. */
    private const val MAX_FOLLOWING = 50

    /** Más de esto no es una pantalla de párrafos más los siguientes: respuesta rara, se descarta. */
    private const val MAX_VISIBLE_ITEMS = 500

    /**
     * Lee `[{i, t, cut}, …]` (o la misma lista como cadena citada, que se desenvuelve una vez). Normaliza el texto
     * (espacios colapsados) y se salta los vacíos; con [firstNeedsText], si el primero está vacío no hay nada. Si algo no
     * tiene la forma esperada, lista vacía (fallar cerrado).
     */
    private fun parseItems(json: String?, firstNeedsText: Boolean): List<PageParagraph> {
        val array = when (val v = parse(json)) {
            is JSONArray -> v
            is String -> parse(v) as? JSONArray
            else -> null
        } ?: return emptyList()
        val out = ArrayList<PageParagraph>(array.length())
        for (k in 0 until array.length()) {
            val o = array.opt(k) as? JSONObject ?: return emptyList()
            val i = o.opt("i") as? Int ?: return emptyList()
            val t = o.opt("t") as? String ?: return emptyList()
            if (i < 0) return emptyList()
            val text = t.trim().replace(WHITESPACE, " ")
            if (text.isEmpty()) {
                if (k == 0 && firstNeedsText) return emptyList() else continue
            }
            out += PageParagraph(i, text, cut = o.opt("cut") == true)
        }
        return out
    }

    private fun parse(json: String?): Any? =
        if (json == null) null else runCatching { JSONTokener(json).nextValue() }.getOrNull()

    /**
     * `(function (a) { cuerpo })(datos);`. org.json ya escapa `</` y U+2028/U+2029; se escapan otra vez aquí a mano
     * para no depender de esa versión: dentro de un literal JS esos dos caracteres terminarían la línea.
     */
    private fun script(data: JSONObject, body: String): String {
        val json = data.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029").replace("</", "<\\/")
        return "(function (a) {\n${body.trimIndent()}\n})($json);"
    }
}
