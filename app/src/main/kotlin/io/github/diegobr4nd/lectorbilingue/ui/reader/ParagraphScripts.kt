package io.github.diegobr4nd.lectorbilingue.ui.reader

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Un párrafo de la página visible: [index] es su posición entre los párrafos del recurso (0, 1, 2…). */
data class PageParagraph(val index: Int, val text: String)

/** Lo que muestra la tarjeta bajo un párrafo. */
sealed interface Card {
    /** "Traduciendo…" */
    data object Skeleton : Card

    /** "Preparando el traductor…" */
    data object Preparing : Card

    data class Text(val translation: String) : Card

    /** Texto ya armado, con el tamaño del modelo que falta. */
    data class MissingModel(val label: String) : Card

    data class Failed(val label: String, val retry: String) : Card
}

/** Textos fijos de la tarjeta (vienen de strings.xml). [translationPrefix] es el nombre que oye TalkBack. */
data class CardLabels(val translationPrefix: String, val skeleton: String, val preparing: String)

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

    /** Párrafos del recurso, sin los que estén dentro de una tarjeta nuestra. Lo usan todos los scripts. */
    private const val PARAGRAPHS =
        "var all = Array.prototype.filter.call(document.querySelectorAll(a.sel), " +
            "function (e) { return !e.closest('.$CARD_CLASS'); });"

    /** La tarjeta del párrafo `a.i`, si existe (a.i es un entero que pone la app). */
    private const val FIND_CARD = "var card = document.querySelector('aside.$CARD_CLASS[data-lector-i=\"' + a.i + '\"]');"

    /**
     * El párrafo bajo el punto (en px CSS) y los [following] siguientes con texto.
     * Responde `JSON.stringify([{i, t}, …])`, el tocado primero; `[]` si no hay párrafo con texto.
     */
    fun find(xCss: Double, yCss: Double, following: Int = 5): String = script(
        JSONObject().put("sel", SELECTOR).put("x", xCss).put("y", yCss).put("n", following),
        """
        var hit = document.elementFromPoint(a.x, a.y);
        var el = hit && hit.closest ? hit.closest(a.sel) : null;
        if (!el || el.closest('.$CARD_CLASS')) return JSON.stringify([]);
        $PARAGRAPHS
        var at = all.indexOf(el);
        var text = el.textContent || '';
        if (at < 0 || !text.trim()) return JSON.stringify([]);
        var out = [{ i: at, t: text }];
        for (var k = at + 1; k < all.length && out.length <= a.n; k++) {
          var t = all[k].textContent || '';
          if (t.trim()) out.push({ i: k, t: t });
        }
        return JSON.stringify(out);
        """,
    )

    /**
     * Lee la respuesta de [find]. Normaliza el texto (espacios colapsados). Vacía si no hay párrafo tocado con texto o si
     * los datos no tienen la forma esperada (fallar cerrado). WebView entrega el valor como JSON: si llega como cadena
     * citada (lo que devuelve `JSON.stringify`), se desenvuelve una vez.
     */
    fun parseFind(json: String?): List<PageParagraph> {
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
                if (k == 0) return emptyList() else continue
            }
            out += PageParagraph(i, text)
        }
        return out
    }

    /** Crea (o reemplaza el contenido de) la tarjeta bajo el párrafo [index]. Responde true si el párrafo existe. */
    fun insert(index: Int, card: Card, labels: CardLabels): String {
        val data = JSONObject().put("sel", SELECTOR).put("i", index).put("prefix", labels.translationPrefix)
        when (card) {
            Card.Skeleton -> data.put("kind", "esqueleto").put("text", labels.skeleton)
            Card.Preparing -> data.put("kind", "preparando").put("text", labels.preparing)
            is Card.Text -> data.put("kind", "texto").put("text", card.translation)
            is Card.MissingModel -> data.put("kind", "falta-modelo").put("text", card.label)
            is Card.Failed -> data.put("kind", "error").put("text", card.label).put("retry", card.retry)
        }
        return script(
            data,
            """
            $PARAGRAPHS
            var p = all[a.i];
            if (!p) return false;
            $FIND_CARD
            if (!card) {
              card = document.createElement('aside');
              card.classList.add('$CARD_CLASS');
              card.dataset.lectorI = String(a.i);
              card.setAttribute('role', 'note');
              card.setAttribute('aria-label', a.prefix);
              p.after(card);
            }
            card.dataset.lectorEstado = a.kind;
            card.textContent = '';
            if (a.kind === 'esqueleto') {
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
            return true;
            """,
        )
    }

    /** Quita la tarjeta del párrafo [index] si existe. Responde true si había una. */
    fun remove(index: Int): String = script(
        JSONObject().put("i", index),
        """
        $FIND_CARD
        if (!card) return false;
        card.remove();
        return true;
        """,
    )

    /** Índice del párrafo o de la tarjeta bajo el punto (px CSS); -1 si no hay ninguno. Para cerrar tocando la tarjeta. */
    fun indexAt(xCss: Double, yCss: Double): String = script(
        JSONObject().put("sel", SELECTOR).put("x", xCss).put("y", yCss),
        """
        var hit = document.elementFromPoint(a.x, a.y);
        if (!hit || !hit.closest) return -1;
        var card = hit.closest('aside.$CARD_CLASS');
        if (card) { var n = parseInt(card.dataset.lectorI, 10); return isNaN(n) ? -1 : n; }
        var el = hit.closest(a.sel);
        if (!el) return -1;
        $PARAGRAPHS
        return all.indexOf(el);
        """,
    )

    /** Lee la respuesta de [indexAt]: el índice, o null si no hay párrafo (o la respuesta no es un entero). */
    fun parseIndex(json: String?): Int? = (parse(json) as? Int)?.takeIf { it >= 0 }

    private val WHITESPACE = Regex("\\s+")

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
