package com.example.session.net

/** Measured link quality, from heartbeat pings and the upload probe. */
data class NetworkQuality(
    val rttMs: Long = -1L,
    val jitterMs: Long = 0L,
    val lossPercent: Float = 0f,
    val uploadKbps: Int = -1,
)

/** Everything preflight checks before the artist says "ready". */
data class PreflightReport(
    val micPermission: Boolean,
    val headphones: Boolean,
    val freeBytes: Long,
    val network: NetworkQuality,
    val calibrationOffsetFrames: Int?,
    val problems: List<String>,
    val passed: Boolean,
)

/**
 * Preflight policy: mic permission, wired headphones, free storage and network
 * quality. Warnings here are advisory; only missing permission, low storage
 * and a missing headphone route block a session, because a speaker would bleed
 * the backing track back into the artist's mic.
 */
object PreflightPolicy {

    /** 24-bit mono at 48 kHz: ~8.64 MB per minute. */
    const val BYTES_PER_MINUTE = 8_640_000L

    /** Minimum free space reserved for a session (about 30 minutes of take). */
    const val MIN_FREE_BYTES = 30 * BYTES_PER_MINUTE

    const val GOOD_RTT_MS = 200L
    const val GOOD_JITTER_MS = 40L
    const val GOOD_LOSS_PERCENT = 2f
    const val GOOD_UPLOAD_KBPS = 400

    fun evaluate(
        micPermission: Boolean,
        headphones: Boolean,
        freeBytes: Long,
        network: NetworkQuality,
        calibrationOffsetFrames: Int?,
    ): PreflightReport {
        val problems = mutableListOf<String>()
        if (!micPermission) problems += "Microphone permission is required"
        if (!headphones) problems += "Wired headphones are required to avoid bleed"
        if (freeBytes in 0 until MIN_FREE_BYTES) {
            val minutes = freeBytes / BYTES_PER_MINUTE
            problems += "Only $minutes minutes of storage free; free up space first"
        }
        network.rttMs.takeIf { it > GOOD_RTT_MS }?.let {
            problems += "Round trip is $it ms; the monitor stream may stutter"
        }
        network.uploadKbps.takeIf { it in 0 until GOOD_UPLOAD_KBPS }?.let {
            problems += "Upload measured at $it kbps; take upload will be slow"
        }
        if (calibrationOffsetFrames == null) {
            problems += "Run the loopback calibration for this audio route"
        }

        val blockers = listOf(!micPermission, !headphones, freeBytes in 0 until MIN_FREE_BYTES)
        return PreflightReport(
            micPermission = micPermission,
            headphones = headphones,
            freeBytes = freeBytes,
            network = network,
            calibrationOffsetFrames = calibrationOffsetFrames,
            problems = problems,
            passed = blockers.none { it },
        )
    }

    /** Free space as a human string for the preflight card. */
    fun formatFree(freeBytes: Long): String = when {
        freeBytes < 0 -> "unknown"
        freeBytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(freeBytes / (1024.0 * 1024 * 1024))
        else -> "${freeBytes / (1024 * 1024)} MB"
    }
}
