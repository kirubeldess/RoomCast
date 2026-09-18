package com.androidtowebosmirroring

import com.androidtowebosmirroring.domain.availableQualities

import kotlin.test.Test
import kotlin.test.assertEquals

class QualityOptionsTest {
    @Test fun optionsDoNotAdvertiseResolutionAbovePhoneScreen() {
        assertEquals(listOf(480, 720), availableQualities(720))
        assertEquals(listOf(480, 720, 1080), availableQualities(1080))
        assertEquals(listOf(480, 720, 1080), availableQualities(1440))
        assertEquals(listOf(480), availableQualities(540))
    }
}
