package com.androidtowebosmirroring.domain

import kotlinx.coroutines.flow.StateFlow

data class Receiver(
    val id: String,
    val name: String,
    val protocol: String,
    val controlUrl: String = "",
    val serviceType: String = "",
)

data class MirrorRequest(
    val receiver: Receiver,
    val audio: Boolean = true,
    val quality: Int = 720,
    val videoMode: Boolean = false,
)

data class SessionState(
    val active: Boolean = false,
    val message: String = "Connect your phone and TV to the same network.",
    val diagnostics: String = "",
)

/** Application-scoped session state, independent of the screen's lifecycle. */
interface SessionRepository {
    val state: StateFlow<SessionState>
    fun update(change: (SessionState) -> SessionState)
}

/** One engine per session. run and close belong to the same worker; stop is thread-safe. */
interface CaptureEngine {
    val stopRequested: Boolean
    fun run(request: MirrorRequest)
    fun requestStop()
    fun close()
}

interface ReceiverDiscovery {
    fun start()
    fun close()
}

/** Implementations must tolerate close during discovery and suppress stale callbacks. */
fun interface DiscoveryFactory {
    fun create(found: (Receiver) -> Unit, finished: (String) -> Unit): ReceiverDiscovery
}

fun availableQualities(maxHeight: Int): List<Int> =
    listOf(480, 720, 1080).filter { it <= maxHeight }
