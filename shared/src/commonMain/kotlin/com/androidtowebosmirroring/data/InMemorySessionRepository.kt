package com.androidtowebosmirroring.data

import com.androidtowebosmirroring.domain.SessionRepository
import com.androidtowebosmirroring.domain.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemorySessionRepository : SessionRepository {
    private val mutableState = MutableStateFlow(SessionState())
    override val state = mutableState.asStateFlow()

    override fun update(change: (SessionState) -> SessionState) {
        mutableState.update(change)
    }
}
