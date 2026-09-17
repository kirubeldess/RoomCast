package com.androidtowebosmirroring

import kotlin.test.*

class CaptureTimingTest {
    @Test fun videoModeRemovesOnlyCenteredSixteenByNineSideAreas() {
        val inset = videoSideInset(1600, 720, true)
        assertEquals(0.1f, inset, 0.00001f)
        assertEquals(1280f, 1600 * (1 - 2 * inset), 0.001f)
        assertEquals(0f, videoSideInset(1600, 720, false))
        assertEquals(0f, videoSideInset(720, 1600, true))
        assertEquals(0f, videoSideInset(1280, 720, true))
        assertEquals(0f, videoSideInset(960, 720, true))
        assertFailsWith<IllegalArgumentException> { videoSideInset(0, 720, true) }
    }
    @Test fun rotationFitsLandscapeWithoutChangingTvResolution() {
        val portrait = fitViewport(720, 1600, 1280, 720)
        assertEquals(VideoViewport(478, 0, 324, 720), portrait)
        val landscape = fitViewport(1600, 720, 1280, 720)
        assertEquals(VideoViewport(0, 72, 1280, 576), landscape)
        assertEquals(VideoViewport(0, 0, 1280, 720), fitViewport(1920, 1080, 1280, 720))
    }
    @Test fun audioUsesCaptureTimeNotReadTime() {
        val clock = AudioCaptureClock(1_000_000)
        // Captured frame 4800 was sampled at 1.1 s, regardless of when the app reads it.
        assertEquals(1_000_000, clock.presentationTime(0, 4800, 1_100_000))
        assertEquals(1_021_333, clock.presentationTime(1024, 9600, 1_200_000))
        assertEquals(1_042_666, clock.presentationTime(2048, 14400, 1_300_000))
    }
    @Test fun fallbackAndClockCorrectionsNeverMoveBackwards() {
        val clock = AudioCaptureClock(1_000_000)
        assertEquals(1_000_000, clock.presentationTime(0))
        val a = clock.presentationTime(1024, 4800, 1_000_000)
        val b = clock.presentationTime(2048, 4800, 900_000)
        assertTrue(a > 1_000_000); assertTrue(b > a)
    }
    @Test fun hardwareDriftCorrectionIsBounded() {
        val clock = AudioCaptureClock(1_000_000)
        clock.presentationTime(0, 0, 1_000_000)
        assertEquals(1_021_533, clock.presentationTime(1024, 4800, 1_150_000))
    }
    @Test fun missedFrameDeadlinesDoNotProduceCatchUpBurst() {
        val cadence = FrameCadence(10)
        assertTrue(cadence.due(100)); assertFalse(cadence.due(105))
        assertTrue(cadence.due(200))
        assertFalse(cadence.due(200)); assertFalse(cadence.due(201))
        assertTrue(cadence.due(210))
    }
    @Test fun schedulerJitterDoesNotAccumulateOrChangeTimestampSpacing() {
        val cadence = FrameCadence(100)
        assertEquals(1000L, cadence.frameTime(1000))
        for (frame in 1..10000) {
            val slot = 1000L + frame * 100
            assertEquals(slot, cadence.frameTime(slot + frame % 20))
            assertNull(cadence.frameTime(slot + 30))
        }
    }
    @Test fun aLongStallSkipsSlotsWithoutResettingTheOriginalPhase() {
        val cadence = FrameCadence(100)
        assertEquals(1000L, cadence.frameTime(1000))
        assertEquals(2500L, cadence.frameTime(2555))
        assertNull(cadence.frameTime(2599))
        assertEquals(2600L, cadence.frameTime(2600))
    }
    @Test fun nonPositiveIntervalsAreRejected() {
        assertFailsWith<IllegalArgumentException> { FrameCadence(0) }
        assertFailsWith<IllegalArgumentException> { FrameCadence(-1) }
    }
}
