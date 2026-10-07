package io.github.diegobr4nd.lectorbilingue.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StartRulesTest {
    @Test fun sinBienvenidaEmpiezaEnElPaso1() = assertEquals(Route.Welcome(1), StartRules.start(false))

    @Test fun conBienvenidaHechaEmpiezaEnInicio() = assertEquals(Route.Library, StartRules.start(true))

    @Test fun atrasEnBienvenidaVuelveAlPasoAnterior() =
        assertEquals(Route.Welcome(2), StartRules.back(Route.Welcome(3)))

    @Test fun atrasEnElPaso1CierraLaApp() = assertNull(StartRules.back(Route.Welcome(1)))

    @Test fun atrasEnOtrasRutasLoSacaLaPila() = assertNull(StartRules.back(Route.Languages))
}
