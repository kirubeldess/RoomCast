package com.androidtowebosmirroring

import androidx.lifecycle.ViewModelStore
import com.androidtowebosmirroring.data.InMemorySessionRepository
import com.androidtowebosmirroring.domain.*
import com.androidtowebosmirroring.presentation.MirrorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MirrorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @BeforeTest fun prepare() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
    }

    private class Search(
        val found: (Receiver) -> Unit,
        val finished: (String) -> Unit,
    ) : ReceiverDiscovery {
        var closed = false
        override fun start() = Unit
        override fun close() { closed = true }
    }

    @Test fun refreshClosesOldDiscoveryAndIgnoresLateResults() = runTest(dispatcher) {
        val searches = mutableListOf<Search>()
        val vm = MirrorViewModel(InMemorySessionRepository(), DiscoveryFactory { found, finished ->
            Search(found, finished).also(searches::add)
        })
        store.put("screen", vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.scan()
        vm.scan()
        assertTrue(searches.first().closed)
        searches.first().found(Receiver("old", "Old TV", "DLNA"))
        searches.first().finished("Old result")
        searches.last().found(Receiver("new", "New TV", "DLNA"))
        runCurrent()
        assertEquals(listOf("new"), vm.state.value.receivers.map { it.id })
        assertTrue(vm.state.value.scanning)
        searches.last().finished("Done")
        runCurrent()
        assertFalse(vm.state.value.scanning)
        assertEquals("Done", vm.state.value.message)
        store.clear()
        assertTrue(searches.last().closed)
    }

    @Test fun activeSessionPreventsDiscovery() = runTest(dispatcher) {
        val sessions = InMemorySessionRepository()
        sessions.update { it.copy(active = true) }
        val vm = MirrorViewModel(sessions, DiscoveryFactory { _, _ -> error("Must not discover") })
        store.put("screen", vm)
        vm.scan()
        assertTrue(sessions.state.value.active)
    }

    @Test fun failedDiscoveryDoesNotLeaveScreenSearching() = runTest(dispatcher) {
        val vm = MirrorViewModel(InMemorySessionRepository(), DiscoveryFactory { _, _ -> error("network unavailable") })
        store.put("screen", vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.scan()
        runCurrent()
        assertFalse(vm.state.value.scanning)
        assertTrue(vm.state.value.message.contains("network unavailable"))
    }
}
