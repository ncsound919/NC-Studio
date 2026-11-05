package com.example.audio.plugins

import com.overlay.audio.plugins.PluginCatalog
import com.overlay.audio.plugins.PluginKind
import com.overlay.audio.plugins.PluginSlot

/**
 * Canonical lead-vocal insert order.
 *
 * The order follows the near-universal studio convention documented in
 * MIX_PLUGIN_RESEARCH.md and the engineer templates:
 *
 *   clean -> level -> correct -> control -> shape -> space
 *
 * in practice:
 *
 *   Trim -> denoise -> rider -> EQ -> resonance -> compressor 1 ->
 *   compressor 2 -> de-esser -> air -> saturation -> delay -> reverb
 *
 * The two compressor stages are deliberate: the fast FET stage catches peaks,
 * then the slow optical stage levels the performance (the "staged compression"
 * approach used by Elmhirst and Braithwaite). A de-esser sits after compression
 * because compression can exaggerate sibilance. Delay comes before reverb so the
 * reverb tails the repeats instead of smearing every dry word.
 *
 * Every stage is a normal rack slot: it can be bypassed, moved, swapped for any
 * other module, or removed. This object only supplies a good starting point.
 */
object VocalChainPresets {

    private fun slot(kind: PluginKind, vararg params: Pair<String, Float>): PluginSlot {
        var s = PluginCatalog.defaultSlot(kind)
        for ((key, value) in params) s = s.withParam(key, value)
        return s
    }

    fun leadVocal(
        eqKind: PluginKind = PluginKind.NEVE_1073,
        eqParams: Map<String, Float> = mapOf(
            "driveDb" to 2.5f,
            "hpfHz" to 85f,
            "midHz" to 1600f,
            "midDb" to -1f,
            "hfDb" to 1f
        ),
        comp1Kind: PluginKind = PluginKind.FET_1176,
        comp1Params: Map<String, Float> = mapOf(
            "inputDb" to 4f,
            "ratio" to 4f,
            "attackUs" to 200f,
            "releaseMs" to 100f,
            "outputDb" to 0f
        ),
        comp2Kind: PluginKind = PluginKind.OPTO_LA2A,
        comp2Params: Map<String, Float> = mapOf(
            "peakReduction" to 3f,
            "gainDb" to 4f
        ),
        deEssFrequencyHz: Float = 6000f,
        deEssAmountDb: Float = 3f,
        airGainDb: Float = 2f,
        includeDenoise: Boolean = true,
        includeSaturation: Boolean = true,
        delayParams: Map<String, Float> = mapOf(
            "division" to 1f,
            "feedback" to 0.22f,
            "hpHz" to 250f,
            "lpHz" to 4500f,
            "ducking" to 1f,
            "mix" to 0.12f
        ),
        reverbKind: PluginKind = PluginKind.PLATE_REVERB,
        reverbParams: Map<String, Float> = mapOf(
            "decaySec" to 1.3f,
            "preDelayMs" to 30f,
            "hpHz" to 220f,
            "lpHz" to 8500f,
            "width" to 0.95f,
            "mix" to 0.14f
        )
    ): List<PluginSlot> {
        val chain = mutableListOf<PluginSlot>()

        // 1. Gain staging first: every analog-modelled stage downstream expects
        //    a predictable input level.
        chain += slot(PluginKind.TRIM, "autoStage" to 1f, "gainDb" to 0f)

        // 2. Clean before you compress. Denoise is deliberately gentle: it is a
        //    safety net, not a gate that removes breaths.
        if (includeDenoise) {
            chain += slot(
                PluginKind.PHONE_NOISE_REDUCTION,
                "thresholdDb" to -45f,
                "reductionDb" to 6f,
                "smoothingMs" to 120f,
                "windDetection" to 1f,
                "handlingDetection" to 1f
            )
        }

        // 3. Ride the level into the chain. This is why the later compressors can
        //    stay gentle instead of clamping every quiet phrase.
        chain += slot(
            PluginKind.VOCAL_RIDER,
            "targetLufs" to -18f,
            "maxGainDb" to 5f,
            "minGainDb" to -5f,
            "sensitivity" to 0.55f
        )

        // 4. Corrective EQ before dynamics. A high-pass and a small mid cut are
        //    usually all a vocal needs here; boosts wait until after compression.
        var eq = slot(eqKind)
        for ((key, value) in eqParams) eq = eq.withParam(key, value)
        chain += eq

        // 5. Tame resonances before the compressors can make them more obvious.
        chain += slot(
            PluginKind.RESONANCE_SMOOTHER,
            "frequencyHz" to 3200f,
            "thresholdDb" to -24f,
            "depthDb" to 6f,
            "qFactor" to 4f
        )

        // 6-7. Staged compression: fast peak control, then slow optical leveling.
        var comp1 = slot(comp1Kind)
        for ((key, value) in comp1Params) comp1 = comp1.withParam(key, value)
        chain += comp1
        var comp2 = slot(comp2Kind)
        for ((key, value) in comp2Params) comp2 = comp2.withParam(key, value)
        chain += comp2

        // 8. De-ess after compression, because the compressors can lift sibilance.
        chain += slot(
            PluginKind.DE_ESSER,
            "freqHz" to deEssFrequencyHz,
            "thresholdDb" to -24f,
            "amountDb" to deEssAmountDb,
            "split" to 1f
        )

        // 9. Additive tone only after the level is under control.
        chain += slot(PluginKind.AIR_SHELF, "freqHz" to 11000f, "gainDb" to airGainDb)

        // 10. Optional colour. Small mix so the chain stays clean and recallable.
        if (includeSaturation) {
            chain += slot(
                PluginKind.SATURATION,
                "type" to 1f,
                "driveDb" to 2.5f,
                "warmth" to 0.45f,
                "mix" to 0.25f,
                "oversample" to 2f
            )
        }

        // 11-12. Space last, always. Delay feeds the reverb, never the reverse.
        var delay = slot(PluginKind.TEMPO_DELAY)
        for ((key, value) in delayParams) delay = delay.withParam(key, value)
        chain += delay

        var reverb = slot(reverbKind)
        for ((key, value) in reverbParams) reverb = reverb.withParam(key, value)
        chain += reverb

        return chain
    }

    /** The chain used by "Vocal chain" in the rack and by the voice engineer. */
    fun standard(): List<PluginSlot> = leadVocal()
}
