package com.overlay.audio.plugins

import kotlin.math.min
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Resonant 4-pole ladder filter — the shared version of the ZDF ladder that
 * NCSoundLab grew in `src/audio/dsp/FilterFamily.ts`.
 *
 * The HPF/LPF pair in [PluginKind.FILTER] cannot do what every wobble, growl
 * and sweep preset asks for: one cutoff with resonance behind it. This module
 * adds it as a catalog kind, so the same patch renders identically in NC
 * Studio, NC Phone Beat and the web app.
 *
 * Parameters use the catalog's musical scale rather than the raw topology's:
 *  - `resonance` 0..20 sweeps the feedback coefficient from 0 to full
 *    self-oscillation. The bare topology saturates around 1, so a 0..1 knob
 *    would use 5 % of its travel.
 *  - `drive` 0..10 is pre-filter drive into the tanh stage.
 *  - `mode` picks the family: Moog, Juno (rounder) or Prophet/Curtis (brighter
 *    resonance, milder drive).
 *
 * Every intermediate is Float, exactly like the rest of the shared suite, so
 * the TypeScript port can funnel its own intermediates through `Math.fround`
 * and land inside the parity tolerance.
 */
class LadderFilterModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.LADDER_FILTER
    override var enabled = true

    private var cutoffHz = 1200f
    private var resonance = 4f
    private var drive = 0f
    private var mode = 0

    // Derived from the parameters in configure(); all Float to match the port.
    private var g = 0f
    private var g4 = 0f
    private var k = 0f
    private var driveScale = 1f

    private val stateL = FloatArray(4)
    private val stateR = FloatArray(4)

    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        cutoffHz = slot.value("cutoffHz")
        resonance = slot.value("resonance")
        drive = slot.value("drive")
        mode = slot.value("mode").toInt().coerceIn(0, 2)

        val sr = if (sampleRate > 0f) sampleRate else 44100f
        val fc = cutoffHz.coerceIn(1f, sr * 0.45f)
        // w0 is already radians per sample: the pre-warped ZDF coefficient is
        // tan(w0 / 2). Dividing by the sample rate a second time makes g tiny
        // enough that the ladder passes everything and the cutoff does nothing.
        val w0 = (TWO_PI * fc) / sr
        // Capped at a stable ceiling: the 4-pole recursion only decays for
        // g <= ~1, so above that the filter is simply wide open and clamping
        // keeps it finite.
        g = min(tan(w0 * 0.5f), 0.95f)
        val bigG = g / (1f + g)
        g4 = bigG * bigG * bigG * bigG

        // Feedback ceiling is family-dependent; resonance maps linearly onto it.
        val ceiling = when (mode) {
            1 -> 3.9f
            2 -> 3.99f
            else -> 3.95f
        }
        k = min(resonance * (ceiling / 20f), ceiling)
        driveScale = when (mode) {
            1 -> 1.2f
            2 -> 1.6f
            else -> 2f
        }
    }

    private fun render(x: Float, state: FloatArray): Float {
        val bigG = g / (1f + g)
        val bigG2 = bigG * bigG
        val s0 = state[0]
        val s1 = state[1]
        val s2 = state[2]
        val s3 = state[3]

        val driven = tanh(x * (1f + (drive / 10f) * driveScale))

        // Output of the 4th stage with u = 0. Each stage is y = G*x + (1-G)*s,
        // so the feedback numerator is G^3(1-G)s0 + G^2(1-G)s1 + G(1-G)s2 +
        // (1-G)s3. This must match the state update below exactly or the
        // feedback solve detunes the resonance.
        val oneMinus = 1f - bigG
        val sum = ((s0 * oneMinus) * bigG2 * bigG) +
            ((s1 * oneMinus) * bigG2) +
            ((s2 * oneMinus) * bigG) +
            (s3 * oneMinus)
        val u = (driven - k * sum) / (1f + k * g4)

        val v0 = (u - s0) * bigG
        val y0 = v0 + s0
        state[0] = y0 + v0

        val v1 = (y0 - s1) * bigG
        val y1 = v1 + s1
        state[1] = y1 + v1

        val v2 = (y1 - s2) * bigG
        val y2 = v2 + s2
        state[2] = y2 + v2

        val v3 = (y2 - s3) * bigG
        val y3 = v3 + s3
        state[3] = y3 + v3

        // One non-finite sample would poison every later stage. Clear the
        // stages so the filter recovers instead of staying silent forever.
        if (!y3.isFinite()) {
            state.fill(0f)
            return 0f
        }
        return y3
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) {
            inputMeter.update(inL, inR)
            out[0] = inL
            out[1] = inR
            return
        }
        val l = render(inL, stateL)
        val r = render(inR, stateR)
        inputMeter.update(inL, inR)
        outputMeter.update(l, r)
        out[0] = l
        out[1] = r
    }

    override fun reset() {
        stateL.fill(0f)
        stateR.fill(0f)
        inputMeter.reset()
        outputMeter.reset()
    }

    companion object {
        private const val TWO_PI = 6.2831855f
    }
}
