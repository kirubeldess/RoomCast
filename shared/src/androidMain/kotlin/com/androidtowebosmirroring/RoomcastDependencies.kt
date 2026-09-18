package com.androidtowebosmirroring

import com.androidtowebosmirroring.data.InMemorySessionRepository
import com.androidtowebosmirroring.domain.SessionRepository

/** Composition root: the activity and service share one process-scoped repository. */
object RoomcastDependencies {
    val sessions: SessionRepository = InMemorySessionRepository()
}
