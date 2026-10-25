package com.example.audio.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Metronome click generator for the render loop.
 *
 * Uses a phase-accumulator approach (not modulo) so beat boundaries stay
 * sample-accurate even at non-integer BPM values and across seeks/loops.
 *
 * Produces classic studio click ticks (accented downbeat + regular beats).
 */
class MetronomeProcessor(private val sampleRate: Float = 48000f) {

    var beatsPerBar: Int = 4
        private set

    private var bpm = 120f
    private var enabled = false
    private var volumeLinear = 10.0f.pow(-6f / 20f)

    // Click voice state
    private var clickRemaining = 0
    private var clickPhase = 0f
    private var clickFreqHz = 1500f
    private var clickDecayPerSample = 1f

    // Phase accumulator for beat tracking (avoids modulo drift)
    private var beatPhaseAccumulator = 0.0
    private var beatFrames = 0.0
    private var lastFramePos = 0L
    private var initialized = false

    fun configure(enabled: Boolean, bpm: Float, volumeDb: Float, beatsPerBar: Int = 4) {
        this.enabled = enabled
        this.bpm = bpm.coerceIn(20f, 480f)
        this.volumeLinear = 10.0f.pow(volumeDb.coerceIn(-40f, 6f) / 20f)
        this.beatsPerBar = beatsPerBar.coerceIn(1, 16)
        this.beatFrames = (sampleRate * 60.0 / this.bpm).toDouble()
        reset()
    }

    fun isConfigured(): Boolean = enabled

    /**
     * Renders the click for one output frame at absolute [framePos].
     * Returns the click sample for this frame (0 when silent).
     * Call for every frame so beat boundaries stay sample-accurate.
     */
    fun processFrame(framePos: Long): Float {
        if (!enabled) return 0f

        // Detect seek (non-contiguous frame positions) and reset phase
        if (!initialized || framePos != lastFramePos + 1) {
            // Reset phase accumulator to align with current position
            beatPhaseAccumulator = (framePos.toDouble() % beatFrames)
            initialized = true
        }
        lastFramePos = framePos

        // Advance phase accumulator
        beatPhaseAccumulator += 1.0
        if (beatPhaseAccumulator >= beatFrames) {
            beatPhaseAccumulator -= beatFrames
            // Beat boundary crossed — trigger click
            val beatIndex = (framePos / beatFrames.toLong().coerceAtLeast(1)).toInt()
            val isDownbeat = beatIndex % beatsPerBar == 0
            clickFreqHz = if (isDownbeat) 2400f else 1500f
            clickPhase = 0f
            clickDecayPerSample = exp(-1f / (0.045f * sampleRate))
            clickRemaining = (0.05f * sampleRate).toInt() // ~50ms tail
        }

        return if (clickRemaining > 0) {
            val s = sin(2f * PI.toFloat() * clickFreqHz * clickPhase / sampleRate)
            clickPhase += 1f
            clickRemaining--
            s * clickDecayPerSample * volumeLinear
        } else 0f
    }

    fun reset() {
        clickRemaining = 0
        clickPhase = 0f
        beatPhaseAccumulator = 0.0
        lastFramePos = 0L
        initialized = false
    }
}
