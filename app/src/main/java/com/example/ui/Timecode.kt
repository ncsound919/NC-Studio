package com.example.ui

import java.util.Locale

/**
 * mm:ss.cc timecode shared by the transport readout, the Spot dialog field and
 * the status messages, so a time that is typed in and a time that is reported
 * back always mean the same frame.
 */
object Timecode {

    /** Formats a frame position as mm:ss.cc at [sampleRate]. */
    fun format(frame: Long, sampleRate: Int): String {
        val rate = sampleRate.coerceAtLeast(1)
        val seconds = frame.coerceAtLeast(0L).toDouble() / rate
        val minutes = (seconds / 60.0).toInt()
        return String.format(Locale.US, "%02d:%05.2f", minutes, seconds - minutes * 60.0)
    }

    /**
     * Parses "mm:ss.cc", "mm:ss", "ss.cc" or plain seconds into frames. Returns
     * null when the text is not a time at all, so callers can show an error
     * instead of dropping an edit somewhere unexpected.
     */
    fun parse(text: String, sampleRate: Int): Long? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(':')
        if (parts.size > 2) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds < 0.0) return null
        val minutes = if (parts.size == 2) {
            parts.first().trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        } else {
            0
        }
        val totalSeconds = minutes * 60.0 + seconds
        return (totalSeconds * sampleRate.coerceAtLeast(1)).toLong().coerceAtLeast(0L)
    }
}
