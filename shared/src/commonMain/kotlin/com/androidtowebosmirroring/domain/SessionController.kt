package com.androidtowebosmirroring.domain

/**
 * Blocking, single-use session lifecycle. The platform host owns scheduling and permissions.
 * Stop is a signal: resource disposal stays on the capture worker, never the UI thread.
 */
class SessionController(
    private val engine: CaptureEngine,
    private val sessions: SessionRepository,
    private val reportFailure: (Throwable) -> Unit = {},
) {
    private var started = false

    fun run(request: MirrorRequest) {
        check(!started) { "Create a new controller for each session" }
        started = true
        var failure: Throwable? = null
        try {
            if (!engine.stopRequested) {
                sessions.update { SessionState(active = true, message = "Preparing screen capture…") }
                engine.run(request)
            }
        } catch (error: Exception) {
            runCatching { reportFailure(error) }
            if (!engine.stopRequested) failure = error
        } finally {
            engine.requestStop()
            try {
                engine.close()
            } catch (error: Exception) {
                runCatching { reportFailure(error) }
                if (failure == null) failure = error
            }
            val message = failure?.let { "Mirroring failed: ${it.message ?: "Unknown error"}" }
                ?: "Mirroring stopped. Screen and audio capture are off."
            sessions.update { it.copy(active = false, message = message) }
        }
    }

    fun stop() = engine.requestStop()
}
