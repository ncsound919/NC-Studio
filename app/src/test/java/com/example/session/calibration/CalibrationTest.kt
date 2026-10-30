package com.example.session.calibration

import com.example.session.net.PreflightPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.random.Random

class CalibrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun exactLoopbackDelayIsFoundWithinAFrame() {
        val sampleRate = 48_000
        val chirp = LoopbackCalibration.chirp(sampleRate)
        val delay = 2_400 // 50 ms
        val captured = FloatArray(delay + chirp.size + 200)
        chirp.copyInto(captured, delay)

        val estimate = LoopbackCalibration.estimateOffsetFrames(chirp, captured, sampleRate)

        assertEquals(delay.toDouble(), estimate.toDouble(), 2.0)
    }

    @Test
    fun offsetEstimateStaysInsideTheTwoMillisecondTolerance() {
        val sampleRate = 48_000
        val chirp = LoopbackCalibration.chirp(sampleRate)
        val random = Random(7)
        val delay = 3_333
        val captured = FloatArray(delay + chirp.size + 500) { 0f }
        chirp.copyInto(captured, delay)
        // Room noise around the capture.
        for (i in captured.indices) captured[i] += (random.nextFloat() - 0.5f) * 0.02f

        val estimate = LoopbackCalibration.estimateOffsetFrames(chirp, captured, sampleRate)
        val errorFrames = abs(estimate - delay)

        assertTrue(
            "estimate $estimate is ${errorFrames} frames off ($delay expected)",
            errorFrames <= 96, // 2 ms at 48 kHz
        )
    }

    @Test
    fun capturedBufferTooShortReturnsZero() {
        val chirp = LoopbackCalibration.chirp()
        assertEquals(0, LoopbackCalibration.estimateOffsetFrames(chirp, chirp.copyOf(chirp.size / 2)))
    }

    @Test
    fun routeKeysDistinguishCapturePaths() {
        assertEquals("usb", AudioRouteKey.of(hasWiredHeadset = true, hasUsb = true, hasBluetooth = false))
        assertEquals("wired", AudioRouteKey.of(true, false, true))
        assertEquals("bluetooth", AudioRouteKey.of(false, false, true))
        assertEquals("speaker", AudioRouteKey.of(false, false, false))
    }

    @Test
    fun routeOffsetsPersistAndCanBeInvalidated() {
        val store = RouteOffsetStore(temp.newFolder("session"))
        assertNull(store.offsetFor("wired"))
        store.save("wired", 1_920)
        store.save("usb", 480)
        assertEquals(1_920, store.offsetFor("wired"))
        assertEquals(480, store.read()["usb"])

        store.invalidate("wired")
        assertNull(store.offsetFor("wired"))
        assertEquals(480, store.offsetFor("usb"))
    }

    @Test
    fun preflightPassesAHealthySessionAndExplainsProblems() {
        val healthy = PreflightPolicy.evaluate(
            micPermission = true,
            headphones = true,
            freeBytes = 500L * 1024 * 1024,
            network = com.example.session.net.NetworkQuality(
                rttMs = 80,
                jitterMs = 10,
                lossPercent = 0.5f,
                uploadKbps = 900,
            ),
            calibrationOffsetFrames = 1_920,
        )
        assertTrue(healthy.passed)
        assertTrue(healthy.problems.isEmpty())

        val blocked = PreflightPolicy.evaluate(
            micPermission = false,
            headphones = false,
            freeBytes = 10L * 1024 * 1024,
            network = com.example.session.net.NetworkQuality(rttMs = 800, uploadKbps = 100),
            calibrationOffsetFrames = null,
        )
        assertFalse(blocked.passed)
        assertTrue(blocked.problems.any { it.contains("permission") })
        assertTrue(blocked.problems.any { it.contains("headphones") })
        assertTrue(blocked.problems.any { it.contains("storage") })

        val warningsOnly = PreflightPolicy.evaluate(
            micPermission = true,
            headphones = true,
            freeBytes = 500L * 1024 * 1024,
            network = com.example.session.net.NetworkQuality(rttMs = 800, uploadKbps = 100),
            calibrationOffsetFrames = null,
        )
        assertTrue("warnings alone do not block", warningsOnly.passed)
        assertTrue(warningsOnly.problems.any { it.contains("calibration") })
    }
}
