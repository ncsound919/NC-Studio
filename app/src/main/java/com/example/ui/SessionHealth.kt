package com.example.ui

import com.example.audio.model.AudioTelemetry
import com.example.audio.model.DawSettings

enum class HealthLevel(val label: String) {
    HEALTHY("Healthy"),
    FAIR("Fair"),
    AT_RISK("At risk")
}

data class SessionHealth(
    val level: HealthLevel,
    val headline: String,
    val details: List<String>
)

/**
 * Condenses audio and UI pacing telemetry into one plain-language status.
 * Pure so settings diagnostics can be tested without an audio device.
 */
fun buildSessionHealth(
    telemetry: AudioTelemetry,
    uiFrameMeanMs: Float,
    uiFrameP95Ms: Float,
    settings: DawSettings
): SessionHealth {
    val bufferMs = telemetry.bufferSizeFrames.toFloat() / telemetry.sampleRate.coerceAtLeast(1) * 1000f
    val callbackBudgetPercent = if (bufferMs > 0f) telemetry.callbackP95Ms / bufferMs * 100f else 0f
    val details = buildList {
        add("Audio callback p95 ${formatOneDecimal(telemetry.callbackP95Ms)} ms of ${formatOneDecimal(bufferMs)} ms budget")
        add("Buffer underruns ${telemetry.bufferUnderruns}")
        if (uiFrameP95Ms > 0f) {
            add("UI frame p95 ${formatOneDecimal(uiFrameP95Ms)} ms")
        }
        add("${settings.bufferSize.frames} frames · ${settings.sampleRateHz / 1000} kHz")
    }

    val atRisk = telemetry.bufferUnderruns > 0 ||
        callbackBudgetPercent >= 80f ||
        uiFrameP95Ms >= 34f
    val fair = callbackBudgetPercent >= 55f || uiFrameP95Ms >= 20f || uiFrameMeanMs >= 12f
    val level = when {
        atRisk -> HealthLevel.AT_RISK
        fair -> HealthLevel.FAIR
        else -> HealthLevel.HEALTHY
    }
    val headline = when (level) {
        HealthLevel.HEALTHY -> "Audio and UI pacing are within budget"
        HealthLevel.FAIR -> "Headroom is getting tight"
        HealthLevel.AT_RISK -> "Audio dropouts are likely"
    }
    return SessionHealth(level, headline, details)
}

private fun formatOneDecimal(value: Float): String =
    String.format(java.util.Locale.US, "%.1f", value)
