package io.github.diegobr4nd.lectorbilingue.ui.tips

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreeBooksTest {
    private class FakeContext(private val throws: Boolean) : ContextWrapper(null) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            if (throws) throw ActivityNotFoundException()
            started += intent
        }
    }

    @Test fun `sin navegador no se cierra la app`() {
        assertFalse(startSafely(FakeContext(throws = true) as Context, Intent()))
    }

    // En la JVM la Intent es de mentira (sin acción ni dirección): la Intent de verdad (acción, dirección fija,
    // BROWSABLE, tarea nueva solo fuera de una Activity) la comprueba TipsOnDeviceTest.intencionDelNavegador.
    @Test fun `con navegador pide abrir una sola vez`() {
        val c = FakeContext(throws = false)
        assertTrue(startSafely(c, Intent()))
        assertEquals(1, c.started.size)
    }

    @Test fun `las direcciones son las fijas`() {
        assertEquals("https://www.gutenberg.org", GUTENBERG_URL)
        assertEquals("https://standardebooks.org", STANDARD_EBOOKS_URL)
    }
}
