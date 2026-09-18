package com.androidtowebosmirroring.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidtowebosmirroring.domain.DiscoveryFactory
import com.androidtowebosmirroring.domain.Receiver
import com.androidtowebosmirroring.domain.ReceiverDiscovery
import com.androidtowebosmirroring.domain.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class MirrorState(
    val receivers: List<Receiver> = emptyList(),
    val scanning: Boolean = false,
    val active: Boolean = false,
    val message: String = "Connect your phone and TV to the same network.",
    val diagnostics: String = "",
)

private data class DiscoveryState(val receivers: List<Receiver> = emptyList(), val scanning: Boolean = false)

class MirrorViewModel(
    private val sessions: SessionRepository,
    private val discoveryFactory: DiscoveryFactory,
) : ViewModel() {
    private val discoveryState = MutableStateFlow(DiscoveryState())
    private var discovery: ReceiverDiscovery? = null
    private val searchToken = MutableStateFlow<Any?>(null)
    val state = combine(sessions.state, discoveryState) { session, nearby ->
        MirrorState(nearby.receivers, nearby.scanning, session.active, session.message, session.diagnostics)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MirrorState())

    fun scan() {
        if (sessions.state.value.active) return
        val token = Any()
        searchToken.value = token
        runCatching { discovery?.close() }
        discovery = null
        discoveryState.value = DiscoveryState(scanning = true)
        showMessage("Searching your local network…")
        try {
            discovery = discoveryFactory.create(
                found = { receiver ->
                    discoveryState.update {
                        if (searchToken.value !== token) it
                        else it.copy(receivers = it.receivers.filterNot { old -> old.id == receiver.id } + receiver)
                    }
                },
                finished = { message ->
                    if (searchToken.value === token) {
                        discoveryState.update { it.copy(scanning = false) }
                        sessions.update { if (it.active) it else it.copy(message = message) }
                    }
                },
            )
            discovery?.start()
        } catch (error: Exception) {
            searchToken.value = null
            runCatching { discovery?.close() }
            discoveryState.update { it.copy(scanning = false) }
            showMessage("Cannot search for TVs: ${error.message}")
        }
    }

    fun showMessage(message: String) {
        sessions.update { it.copy(message = message) }
    }

    override fun onCleared() {
        searchToken.value = null
        runCatching { discovery?.close() }
    }
}
