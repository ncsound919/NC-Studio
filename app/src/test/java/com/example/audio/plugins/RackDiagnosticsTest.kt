package com.example.audio.plugins

import com.overlay.audio.plugins.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Temporary diagnostics harness. Deleted once the numbers are known. */
class RackDiagnosticsTest {

    private val sr = 48000f

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
            out[i] = frame[0]; out[i + 1] = frame[1]; i += 2
        }
        return out
    }

    private fun goertzel(buffer: FloatArray, freq: Float, fromFrame: Int, toFrame: Int): Float {
        val k = 2.0 * PI * freq / sr
        val coeff = 2.0 * kotlin.math.cos(k)
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (f in fromFrame until toFrame) {
            s0 = buffer[f * 2] + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        val real = s1 - s2 * kotlin.math.cos(k)
        val imag = s2 * sin(k)
        return sqrt(real * real + imag * imag).toFloat()
    }

    private fun sine(freq: Float, frames: Int, amplitude: Float = 0.4f): FloatArray {
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = amplitude * sin(2.0 * PI * freq * i / sr).toFloat()
            out[i * 2] = v; out[i * 2 + 1] = v
        }
        return out
    }

    private fun rms(buffer: FloatArray, fromFraction: Float = 0f, toFraction: Float = 1f): Float {
        val first = ((buffer.size / 2) * fromFraction).toInt()
        val last = ((buffer.size / 2) * toFraction).toInt()
        var sum = 0.0; var count = 0
        for (f in first until last) { val v = buffer[f * 2]; sum += v.toDouble() * v; count++ }
        return if (count == 0) 0f else sqrt(sum / count).toFloat()
    }

    private fun db(v: Float): Float = if (v <= 1e-9f) -180f else 20f * log10(v)

    private fun biquadGainDb(filter: Biquad, freq: Float): Float {
        val fresh = Biquad(sr)
        fresh.b0 = filter.b0; fresh.b1 = filter.b1; fresh.b2 = filter.b2
        fresh.a1 = filter.a1; fresh.a2 = filter.a2
        val input = sine(freq, 24000, 0.2f)
        val out = FloatArray(input.size)
        for (i in 0 until input.size / 2) {
            out[i * 2] = fresh.process(input[i * 2])
            out[i * 2 + 1] = fresh.process(input[i * 2 + 1])
        }
        return db(rms(out, 0.5f, 1f)) - db(rms(input, 0.5f, 1f))
    }

    private fun gainDbAt(module: RackModule, freq: Float): Float {
        val input = sine(freq, 24000, 0.2f)
        val output = render(module, input)
        return db(rms(output, 0.5f, 1f)) - db(rms(input, 0.5f, 1f))
    }

    @Test
    fun printDiagnostics() {
        // --- Neve EQ ---
        val neve = Neve1073Module(sr)
        neve.configure(slot(PluginKind.NEVE_1073, "driveDb" to 0f, "hpfHz" to 50f,
            "lfHz" to 60f, "lfDb" to 6f, "midHz" to 1600f, "midDb" to -6f, "hfDb" to 4f))
        println("NEVE full: 60Hz=${gainDbAt(neve, 60f)} 110Hz=${gainDbAt(neve, 110f)} 1k=${gainDbAt(neve, 1000f)} 1.6k=${gainDbAt(neve, 1600f)} 12k=${gainDbAt(neve, 12000f)}")

        // Clean A/B of the LF shelf: same HPF, only the shelf gain differs.
        val neveFlat = Neve1073Module(sr)
        neveFlat.configure(slot(PluginKind.NEVE_1073, "driveDb" to 0f, "hpfHz" to 50f, "lfHz" to 60f, "lfDb" to 0f))
        val neveBoost = Neve1073Module(sr)
        neveBoost.configure(slot(PluginKind.NEVE_1073, "driveDb" to 0f, "hpfHz" to 50f, "lfHz" to 60f, "lfDb" to 6f))
        println("NEVE shelf delta: 30Hz=${gainDbAt(neveBoost, 30f) - gainDbAt(neveFlat, 30f)} 60Hz=${gainDbAt(neveBoost, 60f) - gainDbAt(neveFlat, 60f)} 110Hz=${gainDbAt(neveBoost, 110f) - gainDbAt(neveFlat, 110f)} 220Hz=${gainDbAt(neveBoost, 220f) - gainDbAt(neveFlat, 220f)}")
        val neveHpOnly = Neve1073Module(sr)
        neveHpOnly.configure(slot(PluginKind.NEVE_1073, "driveDb" to 0f, "hpfHz" to 50f, "lfHz" to 220f, "lfDb" to 0f))
        println("NEVE hpf-only 60Hz=${gainDbAt(neveHpOnly, 60f)} 1000Hz=${gainDbAt(neveHpOnly, 1000f)} 20Hz=${gainDbAt(neveHpOnly, 20f)}")

        // --- Raw biquad shelf check ---
        for (gain in listOf(6f, 12f)) {
            val b = Biquad(sr)
            b.setLowShelf(60f, gain)
            println("BIQUAD lowShelf 60Hz +${gain}: 30Hz=${biquadGainDb(b, 30f)} 60Hz=${biquadGainDb(b, 60f)} 110Hz=${biquadGainDb(b, 110f)} 1k=${biquadGainDb(b, 1000f)}")
        }
        val hs = Biquad(sr)
        hs.setHighShelf(12000f, 4f)
        println("BIQUAD highShelf 12k +4: 1k=${biquadGainDb(hs, 1000f)} 12k=${biquadGainDb(hs, 12000f)} 16k=${biquadGainDb(hs, 16000f)}")

        // --- Reverb decay sweep ---
        for (dampingProbe in listOf("as-is", "no-damp", "no-mod")) {
            val rev = AlgorithmicReverbModule(sr, PluginKind.HALL_REVERB)
            rev.configure(slot(PluginKind.HALL_REVERB, "decaySec" to 2.5f, "preDelayMs" to 0f, "mix" to 1f,
                "hpHz" to 40f, "lpHz" to 16000f, "width" to 1f))
            val input = FloatArray(96000 * 2)
            input[0] = 1f; input[1] = 1f
            val out = render(rev, input)
            val a = rms(out, 0.05f, 0.15f)
            val b2 = rms(out, 0.25f, 0.35f)
            val c2 = rms(out, 0.55f, 0.65f)
            println("REVERB SWEEP $dampingProbe: 0.05-0.15s=${db(a)} 0.25-0.35s=${db(b2)} 0.55-0.65s=${db(c2)} slope=${(db(b2) - db(a)) / 0.2f}dB/s -> T60=${if (db(b2) - db(a) < 0f) 60f / (-(db(b2) - db(a)) / 0.2f) else 0f}s")
        }

        // --- Air shelf ---
        val air = AirShelfModule(sr)
        air.configure(slot(PluginKind.AIR_SHELF, "freqHz" to 11000f, "gainDb" to 3f))
        println("AIR: 1k=${gainDbAt(air, 1000f)} 8k=${gainDbAt(air, 8000f)} 11k=${gainDbAt(air, 11000f)} 16k=${gainDbAt(air, 16000f)}")

        // --- De-esser ---
        val de = DeEsserModule(sr)
        de.configure(slot(PluginKind.DE_ESSER, "freqHz" to 6500f, "thresholdDb" to -30f, "amountDb" to 8f, "split" to 1f))
        val sib = render(de, sine(6500f, 24000, 0.5f))
        println("DE-ESS 6.5k: reduced=${db(rms(sib, 0.5f, 1f)) - db(rms(sine(6500f, 24000, 0.5f), 0.5f, 1f))} gr=${de.gainReductionDb}")

        // --- FET program-dependent release ---
        val fet = Fet1176Module(sr)
        fet.configure(slot(PluginKind.FET_1176, "inputDb" to 12f, "ratio" to 8f, "attackUs" to 20f, "releaseMs" to 100f))
        render(fet, sine(1000f, 2400, 0.6f))
        val transientGr = fet.gainReductionDb
        render(fet, FloatArray(2400 * 2))
        println("FET transient: gr=$transientGr recoverAfter50ms=${transientGr - fet.gainReductionDb}")

        val fet2 = Fet1176Module(sr)
        fet2.configure(slot(PluginKind.FET_1176, "inputDb" to 12f, "ratio" to 8f, "attackUs" to 20f, "releaseMs" to 100f))
        render(fet2, sine(1000f, 96000, 0.6f))
        val sustainedGr = fet2.gainReductionDb
        render(fet2, FloatArray(2400 * 2))
        println("FET sustained: gr=$sustainedGr recoverAfter50ms=${sustainedGr - fet2.gainReductionDb}")

        val fet3 = Fet1176Module(sr)
        fet3.configure(slot(PluginKind.FET_1176, "inputDb" to 12f, "ratio" to 8f, "attackUs" to 20f, "releaseMs" to 100f))
        render(fet3, sine(1000f, 96000, 0.6f))
        println("FET steady gr=${fet3.gainReductionDb}")

        // --- Bus compressor ---
        val bus = VcaBusCompModule(sr)
        bus.configure(slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -14f, "ratio" to 2f, "attackMs" to 10f,
            "autoRelease" to 1f, "makeupDb" to 0f, "mix" to 1f))
        render(bus, sine(120f, 96000, 0.8f))
        val autoGr = bus.gainReductionDb
        render(bus, FloatArray(9600 * 2))
        println("BUS auto: steady=$autoGr after200ms=${bus.gainReductionDb}")

        val fixed = VcaBusCompModule(sr)
        fixed.configure(slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -14f, "ratio" to 2f, "attackMs" to 10f,
            "autoRelease" to 0f, "releaseMs" to 100f, "makeupDb" to 0f, "mix" to 1f))
        render(fixed, sine(120f, 96000, 0.8f))
        val fixedGr = fixed.gainReductionDb
        render(fixed, FloatArray(9600 * 2))
        println("BUS fixed: steady=$fixedGr after200ms=${fixed.gainReductionDb}")

        // --- Reverb ---
        for (kind in listOf(PluginKind.PLATE_REVERB, PluginKind.HALL_REVERB)) {
            val rev = AlgorithmicReverbModule(sr, kind)
            val isPlate = kind == PluginKind.PLATE_REVERB
            rev.configure(
                if (isPlate) slot(kind, "decaySec" to 1.4f, "preDelayMs" to 40f, "mix" to 1f, "hpHz" to 100f, "lpHz" to 9000f)
                else slot(kind, "decaySec" to 2.5f, "preDelayMs" to 0f, "mix" to 1f, "hpHz" to 80f, "lpHz" to 8000f, "width" to 0.8f)
            )
            val input = FloatArray(96000 * 2)
            input[0] = 1f; input[1] = 1f
            val out = render(rev, input)
            var nan = 0; var peak = 0f
            for (v in out) { if (v.isNaN() || v.isInfinite()) nan++; peak = maxOf(peak, abs(v)) }
            val windows = StringBuilder()
            var t = 0f
            while (t < 2.5f) {
                windows.append(String.format("%.2fs=%.1fdB ", t, db(rms(out, t / 2.5f * 0.999f, (t + 0.25f) / 2.5f * 0.999f))))
                t += 0.25f
            }
            println("REVERB $kind: early20ms=${rms(out, 0f, 0.02f)} nan=$nan peak=$peak")
            println("   windows: $windows")
        }

        // --- Reverb steady-state level and plate/hall brightness ---
        for (kind in listOf(PluginKind.PLATE_REVERB, PluginKind.HALL_REVERB)) {
            val wet = AlgorithmicReverbModule(sr, kind)
            wet.configure(slot(kind, "decaySec" to 1.8f, "preDelayMs" to 0f, "mix" to 1f, "hpHz" to 60f, "lpHz" to 16000f))
            val probe = sine(500f, 96000, 0.2f)
            val wetOut = render(wet, probe)
            println("REVERB $kind steady wet level=${db(rms(wetOut, 0.8f, 1f)) - db(rms(probe, 0.8f, 1f))} dB")

            val noise = FloatArray(48000 * 2)
            var seed = 12345
            for (i in 0 until noise.size) {
                seed = seed * 1103515245 + 12345
                noise[i] = ((seed ushr 16) and 0x7FFF) / 16384f - 1f
            }
            val res = render(wet, noise)
            println("   hf/lf ratio=${goertzel(res, 8000f, 24000, 48000) / maxOf(goertzel(res, 500f, 24000, 48000), 1e-6f)} peak=${res.maxOf { abs(it) }}")
        }

        // --- Delay feedback HPF with a burst ---
        val delay = TempoDelayModule(sr)
        delay.setTempoBpm(120f)
        delay.configure(slot(PluginKind.TEMPO_DELAY, "division" to 3f, "feedback" to 0.8f, "mix" to 1f,
            "ducking" to 0f, "hpHz" to 400f, "lpHz" to 4000f))
        val burst = FloatArray(96000 * 2)
        val tone = sine(60f, 12000, 0.5f)
        System.arraycopy(tone, 0, burst, 0, tone.size)
        val burstOut = render(delay, burst)
        println("DELAY burst tail 0.8-1.0s=${rms(burstOut, 0.8f, 1f)} (input rms=${rms(tone)})")

        // --- Rack order ---
        val neveSlot = slot(PluginKind.NEVE_1073, "driveDb" to 6f, "hfDb" to 6f, "lfDb" to 4f)
        val satSlot = slot(PluginKind.SATURATION, "driveDb" to 18f, "mix" to 1f, "type" to 3f, "oversample" to 2f)
        val chainA = TrackPluginRack(sr); chainA.setChain(PluginChain(listOf(neveSlot, satSlot)))
        val chainB = TrackPluginRack(sr); chainB.setChain(PluginChain(listOf(satSlot, neveSlot)))
        val probe = sine(8000f, 24000, 0.4f)
        val outA = FloatArray(probe.size); val outB = FloatArray(probe.size)
        val fa = FloatArray(2); val fb = FloatArray(2)
        for (f in 0 until probe.size / 2) {
            chainA.processInto(probe[f * 2], probe[f * 2 + 1], fa)
            chainB.processInto(probe[f * 2], probe[f * 2 + 1], fb)
            outA[f * 2] = fa[0]; outA[f * 2 + 1] = fa[1]
            outB[f * 2] = fb[0]; outB[f * 2 + 1] = fb[1]
        }
        var diffSum = 0.0
        for (i in 0 until probe.size) { val d = (outA[i] - outB[i]).toDouble(); diffSum += d * d }
        val diffRms = sqrt(diffSum / probe.size).toFloat()
        println("RACK order: rmsA=${rms(outA, 0.5f, 1f)} rmsB=${rms(outB, 0.5f, 1f)} diffRms=$diffRms rel=${db(diffRms / rms(probe, 0.5f, 1f))}dB")
    }
}
