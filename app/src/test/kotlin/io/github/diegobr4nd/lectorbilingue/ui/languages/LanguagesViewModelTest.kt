package io.github.diegobr4nd.lectorbilingue.ui.languages

import android.net.Uri
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.MemoryPrefs
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.TranslationCacheApi
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LanguagesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeHub : ModelHubApi {
        override val pairs: StateFlow<List<PairStatus>> = MutableStateFlow(emptyList())
        override val loaded: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun refresh(): ModelMessage? = null
        override fun download(modelId: String) {}
        override fun cancel(modelId: String) {}
        override suspend fun delete(engine: EngineId, pair: String): ModelMessage? = null
        override suspend fun import(uri: Uri): ModelMessage = ModelMessage.IMPORT_OK
        override fun totalRamBytes(): Long = 8L * 1024 * 1024 * 1024
    }

    private class FakeCache(var bytes: Long, var fail: Boolean = false) : TranslationCacheApi {
        val gate = CompletableDeferred<Unit>()
        var holdClear = false
        override suspend fun cacheBytes(): Long = bytes
        override suspend fun clearCache() {
            if (holdClear) gate.await()
            if (fail) throw IllegalStateException("texto del libro que no debe verse")
            bytes = 0
        }
    }

    private fun vm(cache: FakeCache) = LanguagesViewModel(FakeHub(), AppSettings(MemoryPrefs()), cache)

    @Test fun `borrar pide confirmar, trabaja con busy, avisa y vuelve a medir`() = runTest(dispatcher) {
        val cache = FakeCache(bytes = 3_355_443L).apply { holdClear = true }
        val vm = vm(cache)
        vm.onEnter(true)
        advanceUntilIdle()
        assertEquals(3_355_443L, vm.state.value.cacheBytes)
        vm.requestClearCache()
        assertTrue(vm.state.value.confirmCache)
        vm.confirmClearCache()
        runCurrent()
        assertFalse(vm.state.value.confirmCache)
        assertTrue(vm.state.value.busy)
        assertNull(vm.state.value.message)
        cache.gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertEquals(ModelMessage.CACHE_CLEARED, vm.state.value.message)
        assertEquals(0L, vm.state.value.cacheBytes) // se midió otra vez
    }

    @Test fun `si borrar falla, mensaje fijo y la fila no cambia`() = runTest(dispatcher) {
        val cache = FakeCache(bytes = 2_000_000L, fail = true)
        val vm = vm(cache)
        vm.onEnter(true)
        advanceUntilIdle()
        vm.confirmClearCache()
        advanceUntilIdle()
        assertEquals(ModelMessage.CACHE_CLEAR_FAILED, vm.state.value.message)
        assertFalse(vm.state.value.busy)
        assertEquals(2_000_000L, vm.state.value.cacheBytes)
    }

    @Test fun `cancelar la confirmacion no borra`() = runTest(dispatcher) {
        val cache = FakeCache(bytes = 1_000_000L)
        val vm = vm(cache)
        vm.onEnter(true)
        advanceUntilIdle()
        vm.requestClearCache()
        vm.dismissClearCache()
        advanceUntilIdle()
        assertFalse(vm.state.value.confirmCache)
        assertEquals(1_000_000L, cache.bytes)
    }
}
