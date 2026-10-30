package com.example.audio.plugins

import com.overlay.audio.plugins.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Behavioural tests for the studio plugin DSP. Every assertion measures the
 * signal path (gain at a frequency, gain reduction over time, harmonic
 * content, aliasing energy, decay time) rather than checking configuration, so
 * the tests fail if the model stops behaving like the hardware it mirrors.
 */
class StudioPluginDspTest {

    private val sr = 48000f

    // ---------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------

    private fun slot(kind: PluginKind, vararg params: Pair<String, Float>): PluginSlot {
        var s = PluginCatalog.defaultSlot(kind)
        for ((key, value) in params) s = s.withParam(key, value)
        return s
    }

    private fun render(module: RackModule, input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        val frame = FloatArray(2)
        var i = 0
        while (i + 1 < input.size) {
            module.processSampleInto(input[i], input[i + 1], frame)
            out[i] = frame[0]
            out[i + 1] = frame[1]
            i += 2
        }
        return out
    }

    private fun sine(freq: Float, frames: Int, amplitude: Float = 0.4f): FloatArray {
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = amplitude * sin(2.0 * PI * freq * i / sr).toFloat()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    private fun silence(frames: Int): FloatArray = FloatArray(frames * 2)

    private fun rms(buffer: FloatArray, fromFraction: Float = 0f, toFraction: Float = 1f, channel: Int = 0): Float {
        val firstFrame = ((buffer.size / 2) * fromFraction).toInt()
        val lastFrame = ((buffer.size / 2) * toFraction).toInt()
        var sum = 0.0
        var count = 0
        for (f in firstFrame until lastFrame) {
            val v = buffer[f * 2 + channel]
            sum += v.toDouble() * v
            count++
        }
        return if (count == 0) 0f else sqrt(sum / count).toFloat()
    }

    private fun db(value: Float): Float = if (value <= 1e-7f) -140f else 20f * log10(value)

    /** Steady-state gain at [freq] in dB, measured after the transient settles. */
    private fun gainDbAt(module: RackModule, freq: Float): Float {
        val input = sine(freq, 24000, 0.2f)
        val output = render(module, input)
        return db(rms(output, 0.5f, 1f)) - db(rms(input, 0.5f, 1f))
    }

    /** Goertzel magnitude at [freq] on one channel (interleaved input). */
    private fun goertzel(buffer: FloatArray, freq: Float, channel: Int = 0, fromFrame: Int = 0, toFrame: Int = buffer.size / 2): Float {
        val k = 2.0 * PI * freq / sr
        val coeff = 2.0 * kotlin.math.cos(k)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        for (f in fromFrame until toFrame) {
            s0 = buffer[f * 2 + channel] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val real = s1 - s2 * kotlin.math.cos(k)
        val imag = s2 * sin(k)
        return sqrt(real * real + imag * imag).toFloat()
    }

    /** Total harmonic distortion of a steady sine rendered through a module. */
    private fun thd(module: RackModule, freq: Float, amplitude: Float, frames: Int = 32768): Float {
        val output = render(module, sine(freq, frames, amplitude))
        val from = frames / 2
        val fundamental = goertzel(output, freq, 0, from, frames)
        var harmonics = 0.0
        for (h in 2..6) {
            val m = goertzel(output, freq * h, 0, from, frames)
            harmonics += m.toDouble() * m
        }
        return if (fundamental <= 1e-6f) 0f else (sqrt(harmonics) / fundamental).toFloat()
    }

    /** Aliasing energy for a nonlinear stage: harmonics folding back below Nyquist. */
    private fun aliasEnergy(module: RackModule): Float {
        val freq = 15000f
        val output = render(module, sine(freq, 32768, 0.8f))
        // The 3rd harmonic at 45 kHz folds to 3 kHz at 48 kHz sample rate.
        return goertzel(output, 3000f, 0, 16384, 32768)
    }

    // ---------------------------------------------------------------
    // Catalog and chain model
    // ---------------------------------------------------------------

    @Test
    fun catalogDefaultsAreInsideTheirDocumentedRanges() {
        for (kind in PluginKind.values()) {
            val params = PluginCatalog.params(kind)
            assertTrue("${kind.name} has no parameters", params.isNotEmpty())
            for (param in params) {
                assertTrue("${kind.name}.${param.key} default below min", param.default >= param.min)
                assertTrue("${kind.name}.${param.key} default above max", param.default <= param.max)
                assertTrue("${kind.name}.${param.key} choice default not a choice",
                    !param.isChoice || param.choices.any { it.first == param.default })
            }
        }
    }

    @Test
    fun slotValuesClampAndChoiceParametersSnapToHardwarePositions() {
        val neve = PluginCatalog.defaultSlot(PluginKind.NEVE_1073)
        assertEquals(220f, neve.withParam("lfHz", 400f).value("lfHz"), 0.001f)
        assertEquals(700f, neve.withParam("midHz", 1000f).value("midHz"), 0.001f)
        assertEquals(16f, neve.withParam("hfDb", 40f).value("hfDb"), 0.001f)
        assertEquals(-16f, neve.withParam("hfDb", -40f).value("hfDb"), 0.001f)
    }

    @Test
    fun chainOrderFollowsInsertOrderAndMoveRewritesIt() {
        val a = PluginCatalog.defaultSlot(PluginKind.NEVE_1073)
        val b = PluginCatalog.defaultSlot(PluginKind.FET_1176)
        val c = PluginCatalog.defaultSlot(PluginKind.SATURATION)
        val chain = PluginChain(listOf(a, b, c))
        assertEquals(listOf(PluginKind.NEVE_1073, PluginKind.FET_1176, PluginKind.SATURATION),
            chain.slots.map { it.kind })
        val moved = chain.move(c.id, -2)
        assertEquals(listOf(PluginKind.SATURATION, PluginKind.NEVE_1073, PluginKind.FET_1176),
            moved.slots.map { it.kind })
        val toggled = moved.toggle(a.id)
        assertTrue(toggled.slots.first { it.id == a.id }.enabled.not())
        assertEquals(2, toggled.enabledSlots().size)
        assertEquals(2, chain.remove(a.id).slots.size)
    }

    // ---------------------------------------------------------------
    // Oversampling
    // ---------------------------------------------------------------

    @Test
    fun oversamplerRoundTripPreservesLevelAndCleanliness() {
        val module = SaturationModule(sr)
        module.configure(slot(PluginKind.SATURATION, "driveDb" to 0f, "mix" to 1f, "oversample" to 4f, "type" to 0f, "warmth" to 0f))
        val input = sine(5000f, 24000, 0.2f)
        val output = render(module, input)
        val levelDb = db(rms(output, 0.5f, 1f)) - db(rms(input, 0.5f, 1f))
        assertTrue("4x round trip must preserve level, was $levelDb dB", abs(levelDb) < 1.5f)
        assertTrue("4x round trip must stay clean", thd(module, 5000f, 0.2f) < 0.01f)
    }

    @Test
    fun oversamplingReducesAliasingFromHardNonlinearity() {
        val off = SaturationModule(sr)
        off.configure(slot(PluginKind.SATURATION, "driveDb" to 24f, "warmth" to 0.6f, "mix" to 1f, "type" to 3f, "oversample" to 1f))
        val on = SaturationModule(sr)
        on.configure(slot(PluginKind.SATURATION, "driveDb" to 24f, "warmth" to 0.6f, "mix" to 1f, "type" to 3f, "oversample" to 4f))

        val aliasOff = aliasEnergy(off)
        val aliasOn = aliasEnergy(on)
        assertTrue("Console saturation must alias without oversampling (off=$aliasOff)", aliasOff > 1f)
        assertTrue(
            "4x oversampling must cut the folded 3 kHz alias (off=$aliasOff on=$aliasOn)",
            aliasOn < aliasOff * 0.5f
        )
    }

    // ---------------------------------------------------------------
    // EQs
    // ---------------------------------------------------------------

    @Test
    fun neve1073ShelfAndMidBandsShapeTheToneWhereTheyClaimTo() {
        val flat = Neve1073Module(sr)
        flat.configure(slot(PluginKind.NEVE_1073, "driveDb" to 0f, "hpfHz" to 50f,
            "lfHz" to 60f, "lfDb" to 0f, "midHz" to 1600f, "midDb" to 0f, "hfDb" to 0f))
        val shaped = Neve1073Module(sr)
        shaped.configure(slot(PluginKind.NEVE_1073, "driveDb" to 0f, "hpfHz" to 50f,
            "lfHz" to 60f, "lfDb" to 6f, "midHz" to 1600f, "midDb" to -6f, "hfDb" to 4f))
        // Measured against the same module with the bands flat, so the
        // transformer weight and the HPF cancel out of the comparison. A shelf
        // is at half lift on its turnover, so the full-lift check belongs an
        // octave below it:
        val low = gainDbAt(shaped, 30f) - gainDbAt(flat, 30f)
        val lowCorner = gainDbAt(shaped, 60f) - gainDbAt(flat, 60f)
        val mid = gainDbAt(shaped, 1600f) - gainDbAt(flat, 1600f)
        val high = gainDbAt(shaped, 12000f) - gainDbAt(flat, 12000f)
        val highTop = gainDbAt(shaped, 20000f) - gainDbAt(flat, 20000f)
        assertTrue("LF shelf must reach full lift below its turnover, was $low dB at 30 Hz", low > 5f)
        assertTrue("LF shelf must already lift its turnover, was $lowCorner dB at 60 Hz", lowCorner > 2.5f)
        assertTrue("Mid band must cut 1.6 kHz, was $mid dB", mid < -4f)
        // The 1073's HF band is a fixed 12 kHz shelf, and a shelf sits at half
        // its dialled lift on the turnover - the same rule the LF checks above
        // and the air-shelf test follow. What has to be true is that the band is
        // already lifting 12 kHz and has reached the dialled +4 dB further up.
        assertTrue("HF shelf must already lift its 12 kHz turnover, was $high dB", high > 1.5f)
        assertTrue("HF shelf must reach full lift by 20 kHz, was $highTop dB", highTop > 3f)
    }

    @Test
    fun neve1073HighPassIsThirdOrderAndRejectsRumble() {
        val module = Neve1073Module(sr)
        module.configure(slot(PluginKind.NEVE_1073, "driveDb" to 0f, "hpfHz" to 80f))
        val rumble = gainDbAt(module, 20f)
        val pass = gainDbAt(module, 1000f)
        assertTrue("HPF must strongly reject 20 Hz, was $rumble dB", rumble < -22f)
        // The transformer weight is worth a fraction of a dB; what matters is
        // that the passband is not being tilted.
        assertTrue("Passband must stay essentially flat, was $pass dB", abs(pass) < 1f)
    }

    @Test
    fun neve1073PreampDriveAddsHarmonics() {
        val clean = Neve1073Module(sr)
        clean.configure(slot(PluginKind.NEVE_1073, "driveDb" to 0f))
        val driven = Neve1073Module(sr)
        driven.configure(slot(PluginKind.NEVE_1073, "driveDb" to 18f))
        val cleanThd = thd(clean, 500f, 0.25f)
        val drivenThd = thd(driven, 500f, 0.25f)
        assertTrue("Class-A preamp drive must add harmonics ($cleanThd -> $drivenThd)", drivenThd > cleanThd * 3f)
    }

    @Test
    fun sslChannelEqVariableQMidAndHighPassBehaveLikeTheConsole() {
        val module = SslChannelEqModule(sr)
        module.configure(slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 120f, "lmfHz" to 400f, "lmfDb" to 6f,
            "lmfQ" to 1.0f, "hmfHz" to 3000f, "hmfDb" to -6f))
        val sub = gainDbAt(module, 40f)
        val lowMid = gainDbAt(module, 400f)
        val highMid = gainDbAt(module, 3000f)
        assertTrue("18 dB/oct HPF must reject 40 Hz, was $sub dB", sub < -12f)
        assertTrue("LMF must boost 400 Hz, was $lowMid dB", lowMid > 4f)
        assertTrue("HMF must cut 3 kHz, was $highMid dB", highMid < -4f)
    }

    @Test
    fun pultecLowBoostPlusAttenTightensTheLowEndInsteadOfOnlyBoostingIt() {
        val module = PultecEqp1aModule(sr)
        module.configure(slot(PluginKind.PULTEC_EQP1A,
            "lowBoostHz" to 60f, "lowBoostDb" to 10f,
            "lowAttenHz" to 60f, "lowAttenDb" to 8f))
        val atBoost = gainDbAt(module, 60f)
        val aboveBoost = gainDbAt(module, 110f)
        assertTrue("Boost+cut must still lift 60 Hz, was $atBoost dB", atBoost > 3f)
        assertTrue(
            "Boost+cut must dip above the turnover (60 Hz $atBoost dB vs 110 Hz $aboveBoost dB)",
            aboveBoost < atBoost - 3f
        )
    }

    @Test
    fun pultecHighBoostAndAirShelfAddTopEnd() {
        val pultec = PultecEqp1aModule(sr)
        pultec.configure(slot(PluginKind.PULTEC_EQP1A, "highBoostHz" to 10000f, "highBoostDb" to 6f))
        val air = AirShelfModule(sr)
        air.configure(slot(PluginKind.AIR_SHELF, "freqHz" to 11000f, "gainDb" to 3f))
        assertTrue("EQP-1A high boost must lift 10 kHz", gainDbAt(pultec, 10000f) > 4f)
        // A shelf reaches its full gain above the corner, so measure there;
        // at the 11 kHz turnover it is roughly half the dialled lift.
        assertTrue("Air shelf must reach full lift by 16 kHz", gainDbAt(air, 16000f) > 2.5f)
        assertTrue("Air shelf turnover must already lift 11 kHz", gainDbAt(air, 11000f) > 1.2f)
        assertTrue("Air shelf must not touch 1 kHz", abs(gainDbAt(air, 1000f)) < 0.3f)
    }

    @Test
    fun filterModuleCutsRumbleAndFizz() {
        val module = FilterModule(sr)
        module.configure(slot(PluginKind.FILTER, "hpHz" to 80f, "lpHz" to 12000f))
        assertTrue("HPF must cut 25 Hz", gainDbAt(module, 25f) < -10f)
        assertTrue("LPF must cut 16 kHz", gainDbAt(module, 16000f) < -4f)
        assertTrue("Midrange must pass", abs(gainDbAt(module, 1000f)) < 0.5f)
    }

    @Test
    fun midSideEqKeepsBassMonoAndWidensTheSides() {
        val module = MidSideEqModule(sr)
        module.configure(slot(PluginKind.MID_SIDE_EQ, "sideHpHz" to 200f, "width" to 1.5f, "sideAirDb" to 2f))
        // 60 Hz panned hard left: the side component must vanish, which is
        // what "keep the low end mono" means in practice.
        val bassSide = sine(60f, 24000, 0.3f)
        for (i in 0 until 24000) bassSide[i * 2 + 1] = 0f
        val out = render(module, bassSide)
        val sideIn = sideEnergy(bassSide)
        val sideOut = sideEnergy(out)
        assertTrue("Side low cut must shrink the low side component ($sideIn -> $sideOut)", sideOut < sideIn * 0.5f)
        // The centre (mono sum) keeps the bass.
        val monoIn = rms(bassSide, 0.5f, 1f) * 0.5f
        val monoOut = (rms(out, 0.5f, 1f, 0) + rms(out, 0.5f, 1f, 1)) * 0.5f
        assertTrue("mono sum must keep the bass ($monoIn -> $monoOut)", monoOut > monoIn * 0.7f)
    }

    // ---------------------------------------------------------------
    // Dynamics
    // ---------------------------------------------------------------

    @Test
    fun fet1176RatioChangesGainReductionAndAttackIsFast() {
        fun gainReduction(ratio: Float): Float {
            val module = Fet1176Module(sr)
            module.configure(slot(PluginKind.FET_1176, "inputDb" to 12f, "ratio" to ratio, "attackUs" to 20f, "releaseMs" to 400f))
            // 250 samples = ~5 ms is already past the 20 us attack.
            render(module, sine(1000f, 250, 0.5f))
            return module.gainReductionDb
        }
        val ratio4 = gainReduction(4f)
        val ratio20 = gainReduction(20f)
        assertTrue("1176 must clamp down ($ratio4 dB at 4:1)", ratio4 > 2f)
        assertTrue("harder ratio must reduce more ($ratio4 vs $ratio20)", ratio20 > ratio4)
    }

    @Test
    fun fet1176ReleaseSpeedsUpAfterTransientsAndSlowsAfterSustainedLevel() {
        val module = Fet1176Module(sr)
        module.configure(slot(PluginKind.FET_1176, "inputDb" to 12f, "ratio" to 8f, "attackUs" to 20f, "releaseMs" to 100f))
        // Brief transient.
        render(module, sine(1000f, 2400, 0.6f))
        val afterTransient = module.gainReductionDb
        render(module, silence(2400))
        val recoveredBrief = afterTransient - module.gainReductionDb

        val sustained = Fet1176Module(sr)
        sustained.configure(slot(PluginKind.FET_1176, "inputDb" to 12f, "ratio" to 8f, "attackUs" to 20f, "releaseMs" to 100f))
        render(sustained, sine(1000f, 96000, 0.6f))
        val sustainedGr = sustained.gainReductionDb
        render(sustained, silence(2400))
        val recoveredSustained = sustainedGr - sustained.gainReductionDb
        assertTrue(
            "sustained level must release more slowly ($recoveredBrief dB vs $recoveredSustained dB in 50 ms)",
            recoveredSustained < recoveredBrief
        )
    }

    @Test
    fun fet1176AllButtonsInDistortsMoreThanTheNormalCircuit() {
        val normal = Fet1176Module(sr)
        normal.configure(slot(PluginKind.FET_1176, "inputDb" to 20f, "allButtons" to 0f, "ratio" to 12f))
        val british = Fet1176Module(sr)
        british.configure(slot(PluginKind.FET_1176, "inputDb" to 20f, "allButtons" to 1f, "ratio" to 12f))
        val normalThd = thd(normal, 400f, 0.5f)
        val britishThd = thd(british, 400f, 0.5f)
        assertTrue("all-buttons-in must add grit ($normalThd -> $britishThd)", britishThd > normalThd * 1.5f)
    }

    @Test
    fun la2aLevelsSmoothlyThenReleasesInTwoStages() {
        val module = OptoLa2aModule(sr)
        module.configure(slot(PluginKind.OPTO_LA2A, "peakReduction" to 8f, "gainDb" to 6f))
        render(module, sine(500f, 48000, 0.8f))
        val peakReduction = module.gainReductionDb
        assertTrue("LA-2A must level the loud passage, was $peakReduction dB", peakReduction > 4f)

        render(module, silence(4800)) // 100 ms
        val afterShort = module.gainReductionDb
        assertTrue("first release stage must give back at least 30% ($peakReduction -> $afterShort)",
            afterShort < peakReduction * 0.7f)

        render(module, silence(96000)) // 2 s
        val afterLong = module.gainReductionDb
        assertTrue("slow optical stage must still hold reduction after 2 s, was $afterLong dB", afterLong > 0.2f)
        assertTrue("slow stage must keep improving ($afterShort -> $afterLong)", afterLong < afterShort)
    }

    @Test
    fun sslBusCompressorUsesFeedbackAndProgramDependentAutoRelease() {
        val bus = VcaBusCompModule(sr)
        bus.configure(slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -14f, "ratio" to 2f,
            "attackMs" to 10f, "autoRelease" to 1f, "makeupDb" to 0f, "mix" to 1f))
        render(bus, sine(120f, 96000, 0.8f))
        assertTrue("bus comp must show real gain reduction, was ${bus.gainReductionDb}", bus.gainReductionDb > 1f)

        val autoGr = bus.gainReductionDb
        render(bus, silence(9600)) // 200 ms
        val autoRemaining = bus.gainReductionDb

        val fixed = VcaBusCompModule(sr)
        fixed.configure(slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -14f, "ratio" to 2f,
            "attackMs" to 10f, "autoRelease" to 0f, "releaseMs" to 100f, "makeupDb" to 0f, "mix" to 1f))
        render(fixed, sine(120f, 96000, 0.8f))
        render(fixed, silence(9600))
        val fixedRemaining = fixed.gainReductionDb
        assertTrue(
            "Auto release must hold longer than a 100 ms fixed release ($autoRemaining vs $fixedRemaining dB after 200 ms)",
            autoRemaining > fixedRemaining + 0.5f
        )
        assertTrue("Auto must still be releasing ($autoGr -> $autoRemaining)", autoRemaining < autoGr)
    }

    @Test
    fun sslBusCompressorStereoLinkFollowsTheLouderChannel() {
        val bus = VcaBusCompModule(sr)
        bus.configure(slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -20f, "ratio" to 4f, "autoRelease" to 0f, "releaseMs" to 300f))
        val frames = 24000
        val input = FloatArray(frames * 2)
        for (i in 0 until frames) {
            input[i * 2] = 0.8f * sin(2.0 * PI * 200.0 * i / sr).toFloat()
            input[i * 2 + 1] = 0.1f * sin(2.0 * PI * 200.0 * i / sr).toFloat()
        }
        val out = render(bus, input)
        // Both channels share one gain, so the quiet channel drops by the same
        // ratio as the loud one: the loud channel anchors the stereo image.
        val loudRatio = rms(out, 0.5f, 1f, 0) / rms(input, 0.5f, 1f, 0)
        val quietRatio = rms(out, 0.5f, 1f, 1) / rms(input, 0.5f, 1f, 1)
        assertEquals(loudRatio, quietRatio, 0.02f)
        assertTrue("Stereo link must compress the loud channel", loudRatio < 0.85f)
    }

    @Test
    fun variMuTimeConstantsBehaveDifferentlyAndRatioStiffens() {
        fun releaseAfterBurst(tc: Float): Float {
            val module = VariMuCompModule(sr)
            module.configure(slot(PluginKind.VARI_MU_COMP, "driveDb" to 8f, "thresholdDb" to -16f,
                "ratio" to 3f, "timeConstant" to tc, "makeupDb" to 0f))
            render(module, sine(120f, 48000, 0.8f))
            render(module, silence(48000))
            return module.gainReductionDb
        }
        val fast = releaseAfterBurst(0f)   // 0.2 ms / 300 ms
        val program = releaseAfterBurst(5f) // program dependent, 0.3 s / 25 s
        assertTrue("program-dependent position must hold reduction far longer ($fast vs $program)", program > fast + 1f)
    }

    @Test
    fun parallelCompressorBlendKeepsTheDrySignalIntactAtZero() {
        val module = ParallelCompModule(sr)
        module.configure(slot(PluginKind.PARALLEL_COMP, "thresholdDb" to -30f, "ratio" to 12f, "blend" to 0f, "makeupDb" to 6f))
        val input = sine(200f, 12000, 0.5f)
        val output = render(module, input)
        assertEquals(0f, db(rms(output, 0.25f, 1f)) - db(rms(input, 0.25f, 1f)), 0.1f)

        val full = ParallelCompModule(sr)
        full.configure(slot(PluginKind.PARALLEL_COMP, "thresholdDb" to -30f, "ratio" to 12f, "blend" to 1f, "makeupDb" to 6f))
        render(full, input)
        assertTrue("full blend must crush", full.gainReductionDb > 8f)
    }

    @Test
    fun deEsserTamesSibilanceWithoutDullingTheBody() {
        val module = DeEsserModule(sr)
        module.configure(slot(PluginKind.DE_ESSER, "freqHz" to 6500f, "thresholdDb" to -30f, "amountDb" to 8f, "split" to 1f))
        val sibIn = sine(6500f, 24000, 0.5f)
        val sibOut = render(module, sibIn)
        val sibReduction = db(rms(sibOut, 0.5f, 1f)) - db(rms(sibIn, 0.5f, 1f))
        assertTrue("de-esser must reduce 6.5 kHz, was $sibReduction dB", sibReduction < -1f)

        val body = DeEsserModule(sr)
        body.configure(slot(PluginKind.DE_ESSER, "freqHz" to 6500f, "thresholdDb" to -30f, "amountDb" to 8f, "split" to 1f))
        val bodyIn = sine(500f, 24000, 0.5f)
        val bodyOut = render(body, bodyIn)
        val bodyChange = db(rms(bodyOut, 0.5f, 1f)) - db(rms(bodyIn, 0.5f, 1f))
        assertTrue("body must stay untouched, changed $bodyChange dB", abs(bodyChange) < 1.5f)
    }

    @Test
    fun sidechainDuckFollowsTheKeyInputAndRespectsItsFloor() {
        val module = SidechainDuckModule(sr)
        module.configure(slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -22f, "ratio" to 8f, "floorDb" to -10f))
        val input = sine(80f, 12000, 0.5f)
        // Key quiet: no ducking.
        val outQuiet = renderKeyed(module, input, 0.01f)
        assertEquals(0f, db(rms(outQuiet, 0.5f, 1f)) - db(rms(input, 0.5f, 1f)), 0.2f)
        // Key hot: max 10 dB of duck, no more.
        val outHot = renderKeyed(module, input, 0.9f)
        val reduction = db(rms(outHot, 0.5f, 1f)) - db(rms(input, 0.5f, 1f))
        assertTrue("kick key must duck the 808, was $reduction dB", reduction < -4f)
        assertTrue("duck must never exceed the floor, was $reduction dB", reduction > -10.6f)
    }

    private fun renderKeyed(module: RackModule, input: FloatArray, keyAmplitude: Float): FloatArray {
        val out = FloatArray(input.size)
        val frame = FloatArray(2)
        for (f in 0 until input.size / 2) {
            val key = keyAmplitude * sin(2.0 * PI * 60.0 * f / sr).toFloat()
            module.setKey(key, key)
            module.processSampleInto(input[f * 2], input[f * 2 + 1], frame)
            out[f * 2] = frame[0]
            out[f * 2 + 1] = frame[1]
        }
        return out
    }

    @Test
    fun truePeakLimiterNeverExceedsItsCeilingEvenOnInterSamplePeaks() {
        val module = TruePeakLimiterModule(sr)
        module.configure(slot(PluginKind.TRUE_PEAK_LIMITER, "ceilingDb" to -1f, "oversample" to 4f, "releaseMs" to 100f))
        val input = sine(11000f, 24000, 0.995f)
        val output = render(module, input)
        val ceilingLinear = 10f.pow(-1f / 20f)
        var maxSample = 0f
        for (v in output) maxSample = max(maxSample, abs(v))
        assertTrue("limited output must respect the ceiling (peak $maxSample)", maxSample <= ceilingLinear * 1.02f)
        assertTrue("limiter must show gain reduction, was ${module.gainReductionDb} dB", module.gainReductionDb > 0.05f)
    }

    @Test
    fun channelLimiterClampsOverloadAndReportsReduction() {
        val module = ChannelLimiterModule(sr)
        module.configure(slot(PluginKind.CHANNEL_LIMITER, "ceilingDb" to -3f, "releaseMs" to 20f))
        val output = render(module, sine(1000f, 12000, 3f))
        var maxSample = 0f
        for (v in output) maxSample = max(maxSample, abs(v))
        assertTrue("channel limiter must clamp, peak $maxSample", maxSample <= 10f.pow(-3f / 20f) + 0.01f)
    }

    @Test
    fun trimModuleAutoStagesQuietMaterialTowardTheAnalogSweetSpot() {
        val module = TrimModule(sr)
        module.configure(slot(PluginKind.TRIM, "gainDb" to 0f, "autoStage" to 1f))
        val input = sine(1000f, 96000, 0.03f) // ~ -30 dBFS
        val output = render(module, input)
        val gained = db(rms(output, 0.8f, 1f)) - db(rms(input, 0.8f, 1f))
        assertTrue("auto-stage must lift quiet material, was $gained dB", gained > 6f)
        assertTrue("auto-stage must stay gradual, was $gained dB", gained < 14f)
    }

    // ---------------------------------------------------------------
    // Color and tape
    // ---------------------------------------------------------------

    @Test
    fun tapeMachineHeadBumpAndSpeedDependentHighEndMatchTheTransport() {
        val tape15 = TapeMachineModule(sr)
        tape15.configure(slot(PluginKind.TAPE_MACHINE, "driveDb" to 0f, "speedIps" to 15f, "bias" to 0f, "wowFlutter" to 0f))
        val bump15 = gainDbAt(tape15, 50f)
        assertTrue("15 ips must show a head bump at 50 Hz, was $bump15 dB", bump15 > 0.8f)

        val tape75 = TapeMachineModule(sr)
        tape75.configure(slot(PluginKind.TAPE_MACHINE, "driveDb" to 0f, "speedIps" to 7.5f, "bias" to 0f, "wowFlutter" to 0f))
        val tape30 = TapeMachineModule(sr)
        tape30.configure(slot(PluginKind.TAPE_MACHINE, "driveDb" to 0f, "speedIps" to 30f, "bias" to 0f, "wowFlutter" to 0f))
        val loss75 = gainDbAt(tape75, 12000f)
        val loss30 = gainDbAt(tape30, 12000f)
        assertTrue("slow tape must lose more top end than 30 ips ($loss75 dB vs $loss30 dB)", loss75 < loss30 - 1f)

        val overBiased = TapeMachineModule(sr)
        overBiased.configure(slot(PluginKind.TAPE_MACHINE, "driveDb" to 0f, "speedIps" to 15f, "bias" to 1f, "wowFlutter" to 0f))
        val biasLoss = gainDbAt(overBiased, 12000f)
        val unbiased = gainDbAt(tape15, 12000f)
        assertTrue("over-bias must dull the top ($unbiased dB -> $biasLoss dB)", biasLoss < unbiased - 1f)
    }

    @Test
    fun tapeMachineRecordLevelCompressesAndWowModulatesPitch() {
        val hot = TapeMachineModule(sr)
        hot.configure(slot(PluginKind.TAPE_MACHINE, "driveDb" to 12f, "wowFlutter" to 0f))
        val quietIn = sine(1000f, 24000, 0.05f)
        val loudIn = sine(1000f, 24000, 0.5f)
        val quietGain = db(rms(render(hot, quietIn), 0.5f, 1f)) - db(rms(quietIn, 0.5f, 1f))
        val loudGain = db(rms(render(hot, loudIn), 0.5f, 1f)) - db(rms(loudIn, 0.5f, 1f))
        assertTrue("tape must compress louder material more ($quietGain dB vs $loudGain dB)", loudGain < quietGain - 1f)

        val stable = TapeMachineModule(sr)
        stable.configure(slot(PluginKind.TAPE_MACHINE, "wowFlutter" to 0f))
        val wobbly = TapeMachineModule(sr)
        wobbly.configure(slot(PluginKind.TAPE_MACHINE, "wowFlutter" to 1f))
        val deviationStable = zeroCrossingJitter(render(stable, sine(400f, 48000, 0.3f)))
        val deviationWobbly = zeroCrossingJitter(render(wobbly, sine(400f, 48000, 0.3f)))
        assertTrue("wow/flutter must modulate pitch ($deviationStable vs $deviationWobbly)",
            deviationWobbly > deviationStable * 2f)
    }

    /** Standard deviation of zero-crossing intervals, a cheap pitch-stability probe. */
    private fun zeroCrossingJitter(buffer: FloatArray): Float {
        val intervals = ArrayList<Float>()
        var lastCross = -1
        for (f in 1 until buffer.size / 2) {
            val prev = buffer[(f - 1) * 2]
            val cur = buffer[f * 2]
            if (prev <= 0f && cur > 0f) {
                if (lastCross >= 0) intervals.add((f - lastCross).toFloat())
                lastCross = f
            }
        }
        if (intervals.size < 4) return 0f
        val mean = intervals.average().toFloat()
        var sum = 0.0
        for (v in intervals) sum += (v - mean).toDouble() * (v - mean)
        return sqrt(sum / intervals.size).toFloat()
    }

    // ---------------------------------------------------------------
    // Space
    // ---------------------------------------------------------------

    @Test
    fun plateReverbDecaysDenselyAndRespectsPreDelay() {
        val reverb = AlgorithmicReverbModule(sr, PluginKind.PLATE_REVERB)
        reverb.configure(slot(PluginKind.PLATE_REVERB, "decaySec" to 1.4f, "preDelayMs" to 40f, "mix" to 1f, "hpHz" to 100f, "lpHz" to 9000f))
        val input = silence(96000)
        input[0] = 1f
        input[1] = 1f
        val output = render(reverb, input)
        val early = rms(output, 0f, 0.02f)   // first 20 ms: pre-delay must keep this empty
        assertTrue("pre-delay must suppress the first 20 ms, was $early", early < 1e-4f)
        val mid = rms(output, 0.1f, 0.3f)
        val late = rms(output, 0.5f, 0.7f)
        assertTrue("plate must produce a tail, was $mid", mid > 3e-4f)
        assertTrue("plate tail must decay ($mid -> $late)", late < mid)
    }

    @Test
    fun reverbDecayTimeTracksTheConfiguredRT60() {
        val reverb = AlgorithmicReverbModule(sr, PluginKind.HALL_REVERB)
        reverb.configure(slot(PluginKind.HALL_REVERB, "decaySec" to 2.5f, "preDelayMs" to 0f, "mix" to 1f,
            "hpHz" to 80f, "lpHz" to 8000f, "width" to 0.8f))
        val input = silence(sr.toInt() * 4)
        input[0] = 1f
        input[1] = 1f
        val output = render(reverb, input)
        val windowFrames = sr.toInt() / 4
        val energies = ArrayList<Pair<Float, Float>>() // time (s), level dB
        var t = 0f
        while (t < 2.5f) {
            val from = (t * sr).toInt() / (input.size / 2).toFloat()
            val to = ((t + 0.25f) * sr).toInt() / (input.size / 2).toFloat()
            val level = db(rms(output, from, to))
            energies.add(t to level)
            t += 0.25f
        }
        val usable = energies.filter { it.second > -70f }
        assertTrue("hall must produce measurable energy", usable.size >= 3)
        // Least-squares slope of the decay in dB/s, then T60 = 60 / slope.
        val n = usable.size.toFloat()
        val meanT = usable.sumOf { it.first.toDouble() } / n
        val meanL = usable.sumOf { it.second.toDouble() } / n
        var num = 0.0
        var den = 0.0
        for ((t, level) in usable) {
            num += (t - meanT) * (level - meanL)
            den += (t - meanT) * (t - meanT)
        }
        val slope = if (den == 0.0) 0.0 else num / den
        assertTrue("hall tail must decay, slope was $slope dB/s", slope < -3.0)
        val t60 = 60.0 / -slope
        // An FDN is a statistical decay, so the tolerance is generous.
        assertTrue("measured T60 $t60 s must land near the configured 2.5 s", t60 > 1.4 && t60 < 5.0)
    }

    @Test
    fun plateKeepsMoreHighEndThanAHallAndBothStayStable() {
        fun hfRatio(kind: PluginKind): Float {
            val reverb = AlgorithmicReverbModule(sr, kind)
            reverb.configure(slot(kind, "decaySec" to 1.8f, "preDelayMs" to 0f, "mix" to 1f, "hpHz" to 60f, "lpHz" to 16000f))
            // White-ish noise excitation.
            val frames = 48000
            val input = FloatArray(frames * 2)
            var seed = 12345
            for (i in 0 until frames * 2) {
                seed = seed * 1103515245 + 12345
                input[i] = ((seed ushr 16) and 0x7FFF) / 16384f - 1f
            }
            val out = render(reverb, input)
            val hf = goertzel(out, 8000f, 0, frames / 2, frames)
            val lf = goertzel(out, 500f, 0, frames / 2, frames)
            var maxAbs = 0f
            for (v in out) maxAbs = max(maxAbs, abs(v))
            assertTrue("reverb must never run away, peak was $maxAbs", maxAbs < 4f)
            return hf / lf.coerceAtLeast(1e-6f)
        }
        val plate = hfRatio(PluginKind.PLATE_REVERB)
        val hall = hfRatio(PluginKind.HALL_REVERB)
        assertTrue("plate must be brighter than the hall ($plate vs $hall)", plate > hall)
    }

    @Test
    fun tempoDelayLocksToTheGridAndFiltersItsFeedback() {
        val delay = TempoDelayModule(sr)
        delay.setTempoBpm(120f) // 1/8 = 250 ms
        delay.configure(slot(PluginKind.TEMPO_DELAY, "division" to 1f, "feedback" to 0f, "mix" to 1f,
            "ducking" to 0f, "hpHz" to 200f, "lpHz" to 4000f))
        val frames = sr.toInt()
        val input = FloatArray(frames * 2)
        input[0] = 1f
        input[1] = 1f
        val output = render(delay, input)
        val targetWindow = rms(output, 0.245f, 0.255f)
        val wrongWindow = rms(output, 0.1f, 0.11f)
        assertTrue("1/8 at 120 BPM must echo at 250 ms (hit $targetWindow)", targetWindow > 1e-4f)
        assertTrue("nothing must echo early ($wrongWindow)", wrongWindow < targetWindow * 0.1f)

        // Feedback is high-passed, so a low-frequency burst does not build up.
        val lowDelay = TempoDelayModule(sr)
        lowDelay.setTempoBpm(120f)
        lowDelay.configure(slot(PluginKind.TEMPO_DELAY, "division" to 3f, "feedback" to 0.8f, "mix" to 1f,
            "ducking" to 0f, "hpHz" to 400f, "lpHz" to 4000f))
        val lowInput = FloatArray(96000 * 2)
        val burst = sine(60f, 12000, 0.5f) // 250 ms of 60 Hz, then silence
        System.arraycopy(burst, 0, lowInput, 0, burst.size)
        val lowOut = render(lowDelay, lowInput)
        val lateEnergy = rms(lowOut, 0.8f, 1f)
        assertTrue("HPF in the feedback loop must kill low-frequency build-up, was $lateEnergy", lateEnergy < 0.02f)
    }

    @Test
    fun chorusWidthAddsSideEnergyAndModulatesTheSignal() {
        val width = ChorusWidthModule(sr)
        width.configure(slot(PluginKind.CHORUS_WIDTH, "mode" to 2f, "mix" to 1f, "width" to 0.8f, "depthMs" to 6f, "rateHz" to 0.5f))
        val input = sine(1000f, 48000, 0.4f)
        val output = render(width, input)
        val inputSide = sideEnergy(input)
        val outputSide = sideEnergy(output)
        assertTrue("Haas width must create side energy ($inputSide -> $outputSide)", outputSide > inputSide * 4f + 0.001f)

        val chorus = ChorusWidthModule(sr)
        chorus.configure(slot(PluginKind.CHORUS_WIDTH, "mode" to 1f, "mix" to 1f, "depthMs" to 6f, "rateHz" to 1.5f))
        val modulated = render(chorus, input)
        assertTrue("chorus must modulate the delay line", rms(modulated, 0.5f, 1f) > 0.05f)
    }

    private fun sideEnergy(buffer: FloatArray): Float {
        var sum = 0.0
        val frames = buffer.size / 2
        for (f in frames / 2 until frames) {
            val side = (buffer[f * 2] - buffer[f * 2 + 1]) * 0.5
            sum += side.toDouble() * side
        }
        return sqrt(sum / (frames / 2)).toFloat()
    }

    // ---------------------------------------------------------------
    // Rack behaviour
    // ---------------------------------------------------------------

    @Test
    fun rackProcessesSlotsInOrderAndOrderChangesTheResult() {
        val neve = slot(PluginKind.NEVE_1073, "driveDb" to 0f, "hfDb" to 6f)
        val sat = slot(PluginKind.SATURATION, "driveDb" to 18f, "mix" to 1f, "type" to 3f, "oversample" to 2f)

        val chainA = TrackPluginRack(sr)
        chainA.setChain(PluginChain(listOf(neve, sat)))
        val chainB = TrackPluginRack(sr)
        chainB.setChain(PluginChain(listOf(sat, neve)))

        val input = sine(8000f, 24000, 0.4f)
        val outA = FloatArray(input.size)
        val outB = FloatArray(input.size)
        val frameA = FloatArray(2)
        val frameB = FloatArray(2)
        for (f in 0 until input.size / 2) {
            chainA.processInto(input[f * 2], input[f * 2 + 1], frameA)
            chainB.processInto(input[f * 2], input[f * 2 + 1], frameB)
            outA[f * 2] = frameA[0]; outA[f * 2 + 1] = frameA[1]
            outB[f * 2] = frameB[0]; outB[f * 2 + 1] = frameB[1]
        }
        // Order changes the *signal*, not necessarily its average level: a
        // saturation before an EQ shapes different harmonics than after it.
        // So measure the difference between the two waveforms, relative to the
        // input level.
        var diffSum = 0.0
        for (i in 0 until input.size) {
            val d = (outA[i] - outB[i]).toDouble()
            diffSum += d * d
        }
        val diffRms = sqrt((diffSum / input.size).toFloat())
        val relativeDb = db(diffRms / rms(input, 0.5f, 1f))
        assertTrue("insert order must change the sound, relative difference $relativeDb dB", relativeDb > -20f)
    }

    @Test
    fun disabledSlotsAndEmptyRacksAreTransparent() {
        val rack = TrackPluginRack(sr)
        rack.setChain(PluginChain(listOf(slot(PluginKind.SATURATION, "driveDb" to 24f).copy(enabled = false))))
        val input = sine(1000f, 4800, 0.5f)
        val output = FloatArray(input.size)
        val frame = FloatArray(2)
        for (f in 0 until input.size / 2) {
            rack.processInto(input[f * 2], input[f * 2 + 1], frame)
            output[f * 2] = frame[0]; output[f * 2 + 1] = frame[1]
        }
        assertEquals(0f, db(rms(output, 0.25f, 1f)) - db(rms(input, 0.25f, 1f)), 0.01f)

        val empty = TrackPluginRack(sr)
        empty.processInto(0.5f, -0.25f, frame)
        assertEquals(0.5f, frame[0], 0.0001f)
        assertEquals(-0.25f, frame[1], 0.0001f)
    }

    @Test
    fun rackReconfigureUpdatesParametersWithoutRebuildingTheChain() {
        val rack = TrackPluginRack(sr)
        // Same slot id on purpose: the rack must reconfigure this module in
        // place rather than tearing the chain down and rebuilding it.
        val eqSlot = slot(PluginKind.SSL_CHANNEL_EQ, "lmfHz" to 400f, "lmfDb" to 0f)
        rack.setChain(PluginChain(listOf(eqSlot)))
        val flat = rackGainDb(rack, 400f)

        rack.setChain(PluginChain(listOf(eqSlot.withParam("lmfDb", 8f))))
        val boosted = rackGainDb(rack, 400f)
        assertTrue("reconfigured band must lift 400 Hz ($flat -> $boosted dB)", boosted > flat + 5f)
    }

    private fun rackGainDb(rack: TrackPluginRack, freq: Float): Float {
        val input = sine(freq, 24000, 0.2f)
        val output = FloatArray(input.size)
        val frame = FloatArray(2)
        for (f in 0 until input.size / 2) {
            rack.processInto(input[f * 2], input[f * 2 + 1], frame)
            output[f * 2] = frame[0]; output[f * 2 + 1] = frame[1]
        }
        rack.reset()
        return db(rms(output, 0.6f, 1f)) - db(rms(input, 0.6f, 1f))
    }
}
