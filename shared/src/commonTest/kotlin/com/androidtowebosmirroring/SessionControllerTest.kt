package com.androidtowebosmirroring

import com.androidtowebosmirroring.data.InMemorySessionRepository
import com.androidtowebosmirroring.domain.CaptureEngine
import com.androidtowebosmirroring.domain.MirrorRequest
import com.androidtowebosmirroring.domain.Receiver
import com.androidtowebosmirroring.domain.SessionController
import kotlin.test.*

class SessionControllerTest {
    private val request = MirrorRequest(Receiver("tv", "TV", "DLNA"))

    private class FakeEngine : CaptureEngine {
        override var stopRequested = false
        var runs = 0
        var closes = 0
        var runAction: () -> Unit = {}
        var closeFailure = false
        override fun run(request: MirrorRequest) {
            runs++
            runAction()
        }
        override fun requestStop() { stopRequested = true }
        override fun close() {
            closes++
            if (closeFailure) error("cleanup failed")
        }
    }

    @Test fun normalSessionBecomesActiveAndAlwaysCleansUp() {
        val sessions = InMemorySessionRepository()
        val engine = FakeEngine()
        engine.runAction = { assertTrue(sessions.state.value.active) }
        SessionController(engine, sessions).run(request)
        assertEquals(1, engine.runs)
        assertEquals(1, engine.closes)
        assertTrue(engine.stopRequested)
        assertFalse(sessions.state.value.active)
        assertTrue(sessions.state.value.message.startsWith("Mirroring stopped"))
    }

    @Test fun failureIsReportedAfterCleanup() {
        val sessions = InMemorySessionRepository()
        val engine = FakeEngine().apply { runAction = { error("encoder unavailable") } }
        SessionController(engine, sessions).run(request)
        assertEquals(1, engine.closes)
        assertFalse(sessions.state.value.active)
        assertTrue(sessions.state.value.message.contains("encoder unavailable"))
    }

    @Test fun stoppingBeforeWorkerStartsDoesNotBeginCapture() {
        val sessions = InMemorySessionRepository()
        val engine = FakeEngine()
        val controller = SessionController(engine, sessions)
        controller.stop()
        controller.stop()
        controller.run(request)
        assertEquals(0, engine.runs)
        assertEquals(1, engine.closes)
        assertFalse(sessions.state.value.active)
    }

    @Test fun expectedStopDoesNotBecomeFailure() {
        val sessions = InMemorySessionRepository()
        val engine = FakeEngine()
        val controller = SessionController(engine, sessions)
        engine.runAction = {
            controller.stop()
            error("codec stopped")
        }
        controller.run(request)
        assertTrue(sessions.state.value.message.startsWith("Mirroring stopped"))
        assertEquals(1, engine.closes)
    }

    @Test fun cleanupFailureStillClearsActiveState() {
        val sessions = InMemorySessionRepository()
        val engine = FakeEngine().apply { closeFailure = true }
        SessionController(engine, sessions).run(request)
        assertFalse(sessions.state.value.active)
        assertTrue(sessions.state.value.message.contains("cleanup failed"))
    }

    @Test fun controllerCannotReuseAClosedEngine() {
        val engine = FakeEngine()
        val controller = SessionController(engine, InMemorySessionRepository())
        controller.run(request)
        assertFailsWith<IllegalStateException> { controller.run(request) }
        assertEquals(1, engine.runs)
        assertEquals(1, engine.closes)
    }

    @Test fun diagnosticReporterCannotPreventCleanup() {
        val sessions = InMemorySessionRepository()
        val engine = FakeEngine().apply { runAction = { error("capture failure") } }
        SessionController(engine, sessions) { error("logging failure") }.run(request)
        assertEquals(1, engine.closes)
        assertFalse(sessions.state.value.active)
    }
}
