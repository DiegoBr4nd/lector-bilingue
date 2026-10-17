package io.github.diegobr4nd.lectorbilingue.ui.about

import java.io.File
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackTest {
    /** Los textos reales de strings.xml (con \n de Android ya convertidos). */
    private fun string(name: String): String {
        val xml = File("src/main/res/values/strings.xml").readText()
        val raw = Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
        return raw.replace("\\n", "\n").replace("\\'", "'")
    }

    private val info = DeviceInfo("0.1.0", "play", "14", "Google", "Pixel 7")
    private val mail by lazy { feedbackMail("Lector bilingüe", string("feedback_subject"), string("feedback_body"), info) }

    @Test fun `asunto con nombre y version`() {
        assertEquals("Lector bilingüe · comentarios (0.1.0)", mail.subject)
    }

    @Test fun `cuerpo con espacio para escribir y solo los datos permitidos`() {
        assertEquals(
            "Escribe aquí tu comentario:\n\n\n---\nVersión de la app: 0.1.0 (play)\nAndroid: 14\nTeléfono: Google Pixel 7",
            mail.body,
        )
    }

    @Test fun `DeviceInfo solo tiene los cinco datos del spec`() {
        val fields = DeviceInfo::class.java.declaredFields.filterNot { it.isSynthetic || it.name.startsWith('$') }.map { it.name }.toSet()
        assertEquals(setOf("versionName", "flavor", "android", "manufacturer", "model"), fields)
    }

    @Test fun `la direccion es un correo real y no la personal de Juan`() {
        assertTrue(Regex("""^[^@\s<>]+@[^@\s<>]+\.[a-z]{2,}$""").matches(FEEDBACK_EMAIL), FEEDBACK_EMAIL)
        assertFalse(FEEDBACK_EMAIL.startsWith("diego.guevara"), "es el correo personal")
        assertEquals(FEEDBACK_EMAIL, mail.to)
    }

    @Test fun `mailto codifica todo y se puede leer de vuelta`() {
        val odd = feedbackMail("A & B", "%1\$s · ü (%2\$s)", "x %1\$s %2\$s %3\$s %4\$s %5\$s", DeviceInfo("1+2", "fdroid", "8.0", "Sam&sung", "SM-A12 #1"))
        val uri = mailtoUri(odd)
        assertTrue(uri.startsWith("mailto:$FEEDBACK_EMAIL?subject="), uri)
        assertFalse(uri.contains(' ') || uri.contains('+') || uri.contains('\n'), uri)
        val query = uri.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(setOf("subject", "body"), query.keys)
        assertEquals(odd.subject, query["subject"])
        assertEquals(odd.body, query["body"])
    }

    @Test fun `saltos de linea como 0A y espacios como 20`() {
        val uri = mailtoUri(mail)
        assertTrue("%0A" in uri && "%20" in uri, uri)
    }
}
