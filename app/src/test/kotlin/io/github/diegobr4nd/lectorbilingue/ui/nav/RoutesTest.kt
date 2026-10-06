package io.github.diegobr4nd.lectorbilingue.ui.nav

import io.github.diegobr4nd.lectorbilingue.ui.DeveloperEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutesTest {
    private val routes = listOf(Route.Welcome(1), Route.Welcome(2), Route.Welcome(3), Route.Library, Route.Languages)

    @Test fun `cada ruta se guarda y se recupera igual`() {
        for (r in routes) assertEquals(r, decodeRoute(r.encode()))
    }

    @Test fun `el Inicio guardado de la version anterior abre la Biblioteca`() = assertEquals(Route.Library, decodeRoute("home"))

    @Test fun `Desarrollador solo existe si la version lo trae`() {
        val expected = if (DeveloperEntries.current.available) Route.Developer else null
        assertEquals(expected, decodeRoute(Route.Developer.encode()))
    }

    @Test fun `una lista de rutas conserva el orden`() {
        val saved = routes.map { it.encode() }
        assertEquals(routes, saved.mapNotNull { decodeRoute(it) })
        assertEquals(emptyList(), emptyList<String>().mapNotNull { decodeRoute(it) })
    }

    @Test fun `textos raros no dan ruta`() {
        assertNull(decodeRoute("welcome:abc"))
        assertNull(decodeRoute("welcome:"))
        assertNull(decodeRoute("nada"))
        assertNull(decodeRoute(""))
    }

    @Test fun `los pasos de Bienvenida van de 1 a 3`() {
        assertNull(decodeRoute("welcome:0"))
        assertNull(decodeRoute("welcome:4"))
        assertNull(decodeRoute("welcome:-1"))
        assertEquals(Route.Welcome(3), decodeRoute("welcome:3"))
    }

    @Test fun `Siguiente nunca pasa del paso 3`() {
        assertEquals(Route.Welcome(2), StartRules.next(Route.Welcome(1)))
        assertEquals(Route.Welcome(3), StartRules.next(Route.Welcome(2)))
        assertEquals(Route.Welcome(3), StartRules.next(Route.Welcome(3)))
        assertEquals(Route.Library, StartRules.next(Route.Library))
    }
}
