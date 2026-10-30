package com.example.observability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local-only analytics for the DAW.
 *
 * Counts are intentionally in-memory and aggregate: no project name, file path,
 * audio content or device identifier is recorded. They exist so the app can
 * answer "was this build actually opened, and was the deep link used?" without
 * adding a network dependency or a tracking SDK.
 */
object StudioAnalytics {

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun track(event: String) {
        if (event.isBlank()) return
        counts.computeIfAbsent(event) { AtomicInteger(0) }.incrementAndGet()
    }

    /** Snapshot for a diagnostics screen or a bug report. */
    fun analyticsSnapshot(): Map<String, Int> =
        counts.entries.associate { it.key to it.value.get() }

    /** Test/process-reset helper; not called from production code. */
    fun reset() = counts.clear()
}
