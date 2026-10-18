package io.github.diegobr4nd.lectorbilingue.ui.nav

import io.github.diegobr4nd.lectorbilingue.ui.DeveloperEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutesTest {
    private val routes = listOf(
        Route.Welcome(1), Route.Welcome(2), Route.Welcome(3), Route.Library, Route.Languages, Route.HowTo,
        Route.About, Route.Licenses, Route.License("org.readium.kotlin-toolkit:readium-navigator"),
    )

    @Test fun `acerca de y licencias se guardan con nombres fijos`() {
        assertEquals("about", Route.About.encode())
        assertEquals("licenses", Route.Licenses.encode())
        assertEquals("license:slimt", Route.License("slimt").encode())
    }

    @Test fun `una licencia sin id no se recupera`() {
        assertNull(decodeRoute("license:"))
        assertEquals(Route.License("a:b:c"), decodeRoute("license:a:b:c"))
    }

    @Test fun `cada ruta se guarda y se recupera igual`() {
        for (r in routes) assertEquals(r, decodeRoute(r.encode()))
    }

    @Test fun `Como se usa se guarda como howto`() {
        assertEquals("howto", Route.HowTo.encode())
        assertEquals(Route.HowTo, decodeRoute(Route.HowTo.encode()))
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

    // Auditoría Compose (I2): la pila nunca se recupera vacía, así AppNav no tiene que arreglarla mientras dibuja.
    @Test fun `una pila guardada sin rutas validas se recupera con la de inicio`() {
        assertEquals(listOf(Route.Library), restoreRoutes(listOf("nada", "welcome:9")) { Route.Library })
        assertEquals(listOf(Route.Welcome(1)), restoreRoutes(emptyList()) { Route.Welcome(1) })
    }

    @Test fun `una pila guardada valida se recupera igual y sin pedir la de inicio`() {
        val saved = listOf(Route.Library, Route.About, Route.Licenses).map { it.encode() }
        assertEquals(listOf(Route.Library, Route.About, Route.Licenses), restoreRoutes(saved) { error("no hace falta") })
    }
}
