package com.example.ui

import com.example.audio.model.AudioTelemetry
import com.example.audio.model.DawSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHealthAndPacingTest {

    private val healthyTelemetry = AudioTelemetry(
        callbackMeanMs = 3.2f,
        callbackP95Ms = 4.5f,
        bufferUnderruns = 0,
        sampleRate = 48000,
        bufferSizeFrames = 512
    )

    @Test
    fun healthySessionReportsWithinBudget() {
        val health = buildSessionHealth(healthyTelemetry, uiFrameMeanMs = 5f, uiFrameP95Ms = 9f, settings = DawSettings())
        assertEquals(HealthLevel.HEALTHY, health.level)
        assertTrue(health.details.any { it.contains("512 frames") })
    }

    @Test
    fun tightCallbackHeadroomIsFair() {
        val tight = healthyTelemetry.copy(callbackP95Ms = 8f) // 75% of the 10.7ms buffer
        val health = buildSessionHealth(tight, uiFrameMeanMs = 5f, uiFrameP95Ms = 9f, settings = DawSettings())
        assertEquals(HealthLevel.FAIR, health.level)
    }

    @Test
    fun underrunsOrSlowUiFramesAreAtRisk() {
        val underrun = buildSessionHealth(
            healthyTelemetry.copy(bufferUnderruns = 2),
            uiFrameMeanMs = 5f,
            uiFrameP95Ms = 9f,
            settings = DawSettings()
        )
        assertEquals(HealthLevel.AT_RISK, underrun.level)

        val janky = buildSessionHealth(healthyTelemetry, uiFrameMeanMs = 20f, uiFrameP95Ms = 42f, settings = DawSettings())
        assertEquals(HealthLevel.AT_RISK, janky.level)
    }

    @Test
    fun frameTrackerReportsMeanP95AndSlowFrames() {
        val tracker = FramePacingTracker(windowSize = 4)
        assertNull(tracker.snapshot())

        listOf(16_000_000L, 16_000_000L, 16_000_000L, 50_000_000L).forEach(tracker::record)
        val snapshot = tracker.snapshot()!!

        assertEquals(24.5f, snapshot.meanMs, 0.01f)
        assertEquals(50f, snapshot.p95Ms, 0.01f)
        assertEquals(25f, snapshot.slowFramePercent, 0.01f)
    }

    @Test
    fun frameTrackerRollsOlderFramesOut() {
        val tracker = FramePacingTracker(windowSize = 2)
        tracker.record(100_000_000L)
        tracker.record(16_000_000L)
        tracker.record(16_000_000L)

        val snapshot = tracker.snapshot()!!
        assertEquals(16f, snapshot.meanMs, 0.01f)
        assertEquals(0f, snapshot.slowFramePercent, 0.01f)
    }
}
