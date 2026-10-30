package com.example.session.calibration

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Round-trip latency calibration.
 *
 * The phone plays a chirp, records it back through the current audio route and
 * cross-correlates the two to find the offset, which is then applied to every
 * take's timeline position so overdubs line up. Offsets are stored per route
 * and re-measured whenever the route changes.
 *
 * Network RTT is deliberately not part of this: it is displayed, never used
 * for take alignment.
 */
object LoopbackCalibration {

    /** Linear sweep with a short fade, so the correlation peak stays sharp. */
    fun chirp(
        sampleRate: Int = 48_000,
        durationMs: Int = 220,
        f0: Double = 400.0,
        f1: Double = 4_000.0,
    ): FloatArray {
        require(sampleRate > 0 && durationMs > 0)
        val length = sampleRate * durationMs / 1000
        val out = FloatArray(length)
        val chirpRate = (f1 - f0) / (durationMs / 1000.0)
        for (i in 0 until length) {
            val t = i.toDouble() / sampleRate
            val phase = 2.0 * PI * (f0 * t + 0.5 * chirpRate * t * t)
            out[i] = (sin(phase) * envelope(i, length, sampleRate) * 0.8).toFloat()
        }
        return out
    }

    private fun envelope(index: Int, length: Int, sampleRate: Int): Double {
        val fade = (sampleRate * 0.005).toInt().coerceAtLeast(1) // 5 ms
        return when {
            index < fade -> index.toDouble() / fade
            index > length - fade -> (length - index).toDouble() / fade
            else -> 1.0
        }
    }

    /**
     * Frames of delay between [reference] and [captured], found by a coarse
     * then fine normalized cross-correlation. Returns 0 when the captured
     * buffer is too short to say anything.
     */
    fun estimateOffsetFrames(
        reference: FloatArray,
        captured: FloatArray,
        sampleRate: Int = 48_000,
        maxOffsetMs: Int = 300,
    ): Int {
        if (reference.isEmpty() || captured.size <= reference.size) return 0
        val maxLag = ((sampleRate.toLong() * maxOffsetMs) / 1000L).toInt()
            .coerceAtMost(captured.size - reference.size)
            .coerceAtLeast(1)

        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var lag = 0
        while (lag <= maxLag) {
            val score = correlationScore(reference, captured, lag)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
            lag += COARSE_STEP
        }

        val refineFrom = (bestLag - COARSE_STEP).coerceAtLeast(0)
        val refineTo = (bestLag + COARSE_STEP).coerceAtMost(maxLag)
        for (candidate in refineFrom..refineTo) {
            val score = correlationScore(reference, captured, candidate)
            if (score > bestScore) {
                bestScore = score
                bestLag = candidate
            }
        }
        return bestLag
    }

    private fun correlationScore(reference: FloatArray, captured: FloatArray, lag: Int): Double {
        if (lag < 0 || lag + reference.size > captured.size) return Double.NEGATIVE_INFINITY
        var dot = 0.0
        var energy = 0.0
        // Stride 2 during the coarse pass keeps the search fast without moving
        // the peak; the fine pass re-checks neighbouring lags at full rate.
        var i = 0
        while (i < reference.size) {
            val sample = captured[lag + i].toDouble()
            dot += reference[i] * sample
            energy += sample * sample
            i += 1
        }
        val norm = sqrt(energy).coerceAtLeast(1e-9)
        return dot / norm
    }

    /** Offset in frames for a given capture route, persisted between sessions. */
    fun offsetFramesToMs(offsetFrames: Int, sampleRate: Int): Float =
        (offsetFrames.toFloat() / sampleRate) * 1000f

    private const val COARSE_STEP = 8
}

/** Audio routes the app calibrates separately. */
object AudioRouteKey {
    fun of(hasWiredHeadset: Boolean, hasUsb: Boolean, hasBluetooth: Boolean): String = when {
        hasUsb -> "usb"
        hasWiredHeadset -> "wired"
        hasBluetooth -> "bluetooth"
        else -> "speaker"
    }
}

/** Per-route offsets, stored as JSON next to the session data. */
class RouteOffsetStore(rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter<Map<String, Int>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Integer::class.java),
        )

    fun read(): Map<String, Int> {
        if (!file.exists()) return emptyMap()
        return runCatching { adapter.fromJson(file.readText()) }.getOrNull() ?: emptyMap()
    }

    fun offsetFor(route: String): Int? = read()[route]

    fun save(route: String, offsetFrames: Int) {
        val updated = read().toMutableMap()
        updated[route] = offsetFrames
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(adapter.toJson(updated).toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    /** Called when the route changes: the previous offset no longer applies. */
    fun invalidate(route: String) {
        val updated = read().toMutableMap()
        if (updated.remove(route) != null) {
            file.parentFile?.mkdirs()
            file.writeText(adapter.toJson(updated))
        }
    }

    companion object {
        const val FILE_NAME = "calibration-routes.json"
    }
}
