package com.overlay.audio.plugins

import kotlin.math.abs

/**
 * One insert module in a rack. Modules are built once per chain structure and
 * re-configured in place when a parameter moves, so editing a control never
 * rebuilds filter state or clicks.
 */
interface RackModule {
    val kind: PluginKind
    var enabled: Boolean

    /** Applies a slot's parameters. Safe to call from the UI thread. */
    fun configure(slot: PluginSlot)

    /** Processes one stereo frame in place into [out]. Real-time safe. */
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray)

    fun reset() = Unit

    /** Current gain reduction in dB (0 when not a dynamics module). */
    val gainReductionDb: Float get() = 0f

    /** External key input for sidechain modules (kick -> bass ducking). */
    fun setKey(l: Float, r: Float) = Unit

    /** Tempo for grid-locked modules (delays). */
    fun setTempoBpm(bpm: Float) = Unit

    /** Reported processing latency in samples, used by the rack meter. */
    val latencySamples: Int get() = 0

    /** Last processed input/output peak for rack metering. */
    val inputPeakLevel: Float get() = 0f
    val outputPeakLevel: Float get() = 0f
}

/**
 * Trim / auto gain staging. The guides' golden rule is that analog-modeled
 * modules sound right when fed -18 dBFS to -12 dBFS; auto-stage rides a slow
 * RMS detector towards -18 dBFS so a hot or quiet import lands in the sweet
 * spot before the first color module.
 */
class TrimModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.TRIM
    override var enabled = true
    private var gain = 1f
    private var manualGain = 1f
    private var autoStage = false
    private var rms = 0f
    private val rmsCoeff = kotlin.math.exp(-1.0 / (0.3 * sampleRate)).toFloat()
    private val targetRms = Db.toLinear(-18f)

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        manualGain = Db.toLinear(slot.value("gainDb"))
        autoStage = slot.value("autoStage") >= 0.5f
        if (!autoStage) rms = 0f
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        if (autoStage) {
            val mono = (inL + inR) * 0.5f
            rms = rms + (1f - rmsCoeff) * (mono * mono - rms)
            val level = kotlin.math.sqrt(rms.coerceAtLeast(0f))
            val desired = if (level > 1e-5f) (targetRms / level).coerceIn(0.25f, 4f) else 1f
            gain = gain + 0.001f * (desired - gain)
        } else {
            gain = 1f
        }
        val total = manualGain * gain
        out[0] = inL * total
        out[1] = inR * total
    }

    override fun reset() { rms = 0f; gain = 1f }
}

/** Shared peak meter helper so every module reports consistent levels. */
internal class PeakMeter {
    var peak = 0f
        private set

    fun update(l: Float, r: Float) {
        val p = maxOf(abs(l), abs(r))
        peak = p
    }

    fun reset() { peak = 0f }
}
