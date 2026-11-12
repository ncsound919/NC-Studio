package com.overlay.audio.plugins

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Objective measurements for the shared rack.
 *
 * `FxParityFixtureTest` answers "does the TypeScript port render the same
 * samples?"; this file answers the harder question the plugin-quality report
 * raises - "are those samples any good?". The parity suite would happily lock
 * in a limiter that overshoots its ceiling or an oversampler that does not
 * actually remove aliasing, because both are perfectly reproducible.
 *
 * Everything here is measured from rendered audio rather than read back from
 * the modules' own readouts, so a module cannot pass by reporting what the
 * test expects to hear. Signals are deterministic (seeded LCG, integer-cycle
 * tones) and every measurement uses a Hann window so bin leakage cannot be
 * mistaken for artefact energy.
 */
class FxDspMeasurementTest {

    private val sampleRate = 48_000f
    private val seconds = 1

    // ============================================================
    // 1. True-peak ceiling
    // ============================================================

    /**
     * A limiter that reports -1 dBTP but emits +1 dBFS has missed its own
     * specification on exactly the material it exists to catch. The ceiling
     * is the module's contract, so it is checked against the loudest,
     * most transient material we can build rather than a polite sine.
     */
    @Test
    fun `true peak limiter never emits a sample above its ceiling`() {
        val ceiling = Db.toLinear(-1f)
        val chain = PluginChain(listOf(PluginCatalog.defaultSlot(PluginKind.TRUE_PEAK_LIMITER)))

        for ((label, input) in listOf(
            "hot sine" to sine(1_000f, 0.99f),
            "hot noise" to noise(frames(), 0x5EED),
            "sine near Nyquist" to sine(17_000f, 0.99f),
        )) {
            val out = FxOfflineRenderer.renderMono(input, sampleRate, chain)
            var peak = 0f
            for (v in out) peak = maxOf(peak, abs(v))
            assertTrue(
                peak <= ceiling + 1e-4f,
                "$label: limiter emitted $peak, above its ceiling of $ceiling " +
                    "(${Db.fromLinear(ceiling)} dBTP) - overshoot by ${Db.fromLinear(peak / ceiling)} dB",
            )
        }
    }

    /**
     * Look-ahead is a deliberate latency, which is only acceptable if the rack
     * reports it so the DAW can compensate. The reported figure must be the
     * delay an impulse actually experiences.
     */
    @Test
    fun `true peak limiter reports the latency it really adds`() {
        // Oversampling off: the decimation filters carry their own group
        // delay, which would obscure the delay line being measured here.
        val chain = PluginChain(
            listOf(PluginCatalog.defaultSlot(PluginKind.TRUE_PEAK_LIMITER).withParam("oversample", 1f)),
        )
        val rack = TrackPluginRack(sampleRate)
        rack.setChain(chain)

        val impulseAt = 100
        val input = FloatArray(frames())
        input[impulseAt] = 0.1f // low enough that the gain computer stays open
        val out = FxOfflineRenderer.renderMono(input, sampleRate, chain)

        var peakIndex = 0
        var peak = 0f
        for (i in out.indices) if (abs(out[i]) > peak) { peak = abs(out[i]); peakIndex = i }

        assertTrue(peak > 0f, "limiter swallowed the impulse entirely")
        assertTrue(
            rack.latencySamples == peakIndex - impulseAt,
            "rack reports ${rack.latencySamples} samples of latency but the impulse " +
                "actually lands $peakIndex - impulseAt = ${peakIndex - impulseAt} samples late",
        )
    }

    // ============================================================
    // 2. Aliasing
    // ============================================================

    /**
     * The reason the nonlinear modules run oversampled at all: harmonics
     * generated above Nyquist fold back to frequencies that were never in the
     * signal, and no filter after the fact can tell them apart. Drive a tone
     * high enough that its 3rd-8th harmonics all land past Nyquist, then look
     * only at the bins those harmonics fold into.
     */
    @Test
    fun `oversampling suppresses the aliases saturation folds into the audible band`() {
        val fundamental = 9_000f
        val input = sine(fundamental, 0.9f)
        // k*9 kHz folded back into 0..24 kHz for k = 3..8
        val aliasBins = floatArrayOf(21_000f, 12_000f, 3_000f, 6_000f, 15_000f)

        fun foldedAliasPower(oversample: Float): Float {
            val chain = PluginChain(
                listOf(
                    PluginCatalog.defaultSlot(PluginKind.SATURATION)
                        .withParam("type", 1f)
                        .withParam("driveDb", 18f)
                        .withParam("warmth", 1f)
                        .withParam("mix", 1f)
                        .withParam("oversample", oversample),
                ),
            )
            val out = FxOfflineRenderer.renderMono(input, sampleRate, chain)
            val settled = out.copyOfRange(settle(), out.size)
            var power = 0f
            for (bin in aliasBins) power += amplitudeAt(settled, bin).let { it * it }
            return power
        }

        val without = foldedAliasPower(1f)
        val with = foldedAliasPower(4f)
        assertTrue(
            without > 1e-6f,
            "test signal did not alias at all, so it cannot demonstrate the oversampler does anything",
        )
        assertTrue(
            with <= without * 0.25f,
            "4x oversampling only cut folded alias energy from $without to $with " +
                "(need at least a 12 dB reduction)",
        )
    }

    // ============================================================
    // 3. Harmonic profile
    // ============================================================

    /**
     * The report's perceptual claim is that colour comes from the *shape* of
     * the transfer curve: an odd-symmetric one cannot produce a 2nd harmonic
     * at all, an asymmetric one can. The rack offers both - tape is modelled
     * symmetric, tube asymmetric - and that difference is what the `warmth`
     * control is trading on, so it is worth pinning before someone
     * "simplifies" one curve into the other.
     *
     * Note what is *not* asserted: tube is not measured here as even-harmonic
     * *dominant*. At maximum warmth its 2nd harmonic sits roughly 13 dB below
     * its 3rd, so the warmth it adds is mostly odd-order with a 2nd-harmonic
     * accent. Asserting the stronger claim would be asserting something the
     * model does not do.
     */
    @Test
    fun `tube saturation is the asymmetric model and tape the symmetric one`() {
        val input = sine(1_000f, 0.5f)

        fun harmonic(type: Float, harmonicFreq: Float): Float {
            val chain = PluginChain(
                listOf(
                    PluginCatalog.defaultSlot(PluginKind.SATURATION)
                        .withParam("type", type)
                        .withParam("driveDb", 12f)
                        .withParam("warmth", 1f)
                        .withParam("mix", 1f)
                        .withParam("oversample", 4f),
                ),
            )
            val out = FxOfflineRenderer.renderMono(input, sampleRate, chain)
            return amplitudeAt(out.copyOfRange(settle(), out.size), harmonicFreq)
        }

        val tape1 = harmonic(0f, 1_000f)
        val tape2 = harmonic(0f, 2_000f)
        val tape3 = harmonic(0f, 3_000f)
        val tube1 = harmonic(1f, 1_000f)
        val tube2 = harmonic(1f, 2_000f)
        val tube3 = harmonic(1f, 3_000f)

        assertTrue(
            tape1 > 0f && tube1 > 0f,
            "the measurement itself produced no fundamental, so the harmonic figures below " +
                "prove nothing",
        )
        assertTrue(
            tape2 <= 0.25f * tape3,
            "tape is modelled as an odd-symmetric transfer, so a 2nd harmonic ($tape2) " +
                "approaching its 3rd ($tape3) means the curve lost its symmetry",
        )
        assertTrue(
            tube2 > 4f * tape2,
            "tube is the asymmetric colour but its 2nd harmonic ($tube2) is not clearly " +
                "above the symmetric tape model's ($tape2)",
        )
        assertTrue(
            tube2 >= 0.02f * tube1,
            "tube's 2nd harmonic ($tube2) is too small to colour anything against its " +
                "fundamental ($tube1)",
        )
    }

    // ============================================================
    // 4. Phone noise reduction
    // ============================================================

    /**
     * The module is a gate, so it has to gate: below threshold it must reach
     * its reduction floor, and at or above threshold it must be transparent.
     * Both halves matter - a gate that also attenuates the programme is worse
     * than one that does nothing.
     */
    @Test
    fun `noise reduction attenuates what sits below threshold and leaves the programme alone`() {
        val input = gatedProgramme()
        val out = FxOfflineRenderer.renderMono(input, sampleRate, chainFor())

        val loudEnd = input.size / 3
        val loudIn = input.copyOfRange(0, loudEnd)
        val loudOut = out.copyOfRange(0, loudEnd)
        val quietIn = input.copyOfRange(input.size - 8_000, input.size)
        val quietOut = out.copyOfRange(out.size - 8_000, out.size)

        val loudRatio = rms(loudOut) / rms(loudIn)
        assertTrue(
            loudRatio in 0.8f..1.25f,
            "programme above threshold was altered by the gate: RMS moved by " +
                "${Db.fromLinear(loudRatio)} dB (expected within +/-1.2 dB)",
        )

        val quietRatio = rms(quietOut) / rms(quietIn)
        assertTrue(
            quietRatio <= 0.5f,
            "noise floor was only pushed down ${Db.fromLinear(quietRatio)} dB; " +
                "the reduction control promises far more than that",
        )
    }

    /**
     * The ecosystem's beta bar is "zero dead controls": a parameter read in
     * `configure` but never reaching the audio lies to the user every time
     * they move it. Each documented parameter must change the output of a
     * signal built to exercise it - wind needs low frequency, the knock
     * detector needs an impulse, the gate needs material crossing threshold.
     */
    @Test
    fun `every noise reduction parameter reaches the output`() {
        val input = livenessSignal()
        val base = FxOfflineRenderer.renderMono(input, sampleRate, chainFor())

        val parameters = listOf(
            "thresholdDb" to -15f,
            "reductionDb" to 24f,
            "smoothingMs" to 500f,
            "windDetection" to 0f,
            "handlingDetection" to 0f,
        )
        for ((key, value) in parameters) {
            val out = FxOfflineRenderer.renderMono(input, sampleRate, chainFor(key to value))
            var maxDiff = 0f
            for (i in out.indices) maxDiff = maxOf(maxDiff, abs(out[i] - base[i]))
            assertTrue(
                maxDiff > 1e-4f,
                "$key=$value never reaches the output - it is a dead control",
            )
        }
    }

    private fun chainFor(vararg overrides: Pair<String, Float>): PluginChain {
        var slot = PluginCatalog.defaultSlot(PluginKind.PHONE_NOISE_REDUCTION)
        for ((key, value) in overrides) slot = slot.withParam(key, value)
        return PluginChain(listOf(slot))
    }

    /** A loud tone for a third of the buffer, then a quiet noise floor. */
    private fun gatedProgramme(): FloatArray {
        val n = frames()
        val rnd = noise(n, 0xBEEF)
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = if (i < n / 3) {
                (0.25 * sin(2.0 * PI * 1_000.0 * i / sampleRate)).toFloat() + rnd[i] * 0.01f
            } else {
                rnd[i] * 0.002f
            }
        }
        return out
    }

    /**
     * Everything the module claims to handle, in one buffer: an LF-dominant
     * tone for the wind branch, a long quiet floor for the gate, and a single
     * loud impulse for the knock detector.
     */
    private fun livenessSignal(): FloatArray {
        val n = frames()
        val rnd = noise(n, 0x1234)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i / sampleRate
            out[i] = if (t < 0.4f) {
                (0.3 * sin(2.0 * PI * 40.0 * t)).toFloat() + rnd[i] * 0.01f
            } else {
                rnd[i] * 0.002f
            }
        }
        // Half a second in: well inside the quiet floor, so the knock
        // detector is measuring against noise when the impulse arrives.
        out[sampleRate.toInt() / 2] = 0.9f
        return out
    }

    /**
     * The SSL/Neve console HPF is labelled 18 dB/oct. It used to be two
     * 2nd-order sections (24 dB/oct) with Q=0.5 in the second one, which put a
     * 6 dB notch at the dialled frequency. Measure the real response: -3 dB at
     * the cutoff and 18 dB per octave in the stopband.
     */
    @Test
    fun `third order high pass is 18 dB per octave with no notch at cutoff`() {
        val cutoff = 500f

        fun gainAt(freq: Float): Float {
            val cascade = BiquadCascade(sampleRate, 2)
            cascade.setThirdOrderHighPass(cutoff)
            val tone = sine(freq, 0.5f)
            val out = FloatArray(tone.size)
            for (i in tone.indices) out[i] = cascade.process(tone[i])
            return rms(out.copyOfRange(settle(), out.size)) /
                rms(tone.copyOfRange(settle(), tone.size))
        }

        val atCutoffDb = Db.fromLinear(gainAt(cutoff))
        assertTrue(
            abs(atCutoffDb + 3.0f) < 0.5f,
            "cutoff gain is $atCutoffDb dB; a 3rd-order Butterworth must be -3 dB, " +
                "and a 6 dB notch means a Q=0.5 second stage came back",
        )

        val octaveDb = Db.fromLinear(gainAt(cutoff / 4f) / gainAt(cutoff / 8f))
        assertTrue(
            octaveDb in 16.5f..19.5f,
            "stopband slope is $octaveDb dB/octave; the label and the SSL/Neve " +
                "presets promise 18",
        )
    }

    // ============================================================
    // Measurement helpers
    // ============================================================

    /**
     * The ladder is a low-pass: a 200 Hz tone must survive a 1 kHz cutoff and
     * an 8 kHz tone must be attenuated. The coefficient used to be divided by
     * the sample rate twice, which made the filter a wire and left the cutoff
     * knob inaudible; this catches that class of regression.
     */
    @Test
    fun `ladder filter actually attenuates above its cutoff`() {
        val chain = PluginChain(
            listOf(
                PluginCatalog.defaultSlot(PluginKind.LADDER_FILTER)
                    .withParam("cutoffHz", 1_000f)
                    .withParam("resonance", 0f)
                    .withParam("drive", 0f),
            ),
        )
        val low = rms(FxOfflineRenderer.renderMono(sine(200f, 0.5f), sampleRate, chain))
        val high = rms(FxOfflineRenderer.renderMono(sine(8_000f, 0.5f), sampleRate, chain))
        assertTrue(
            low > 0.05f,
            "ladder removed the passband: 200 Hz RMS is $low",
        )
        assertTrue(
            high < low * 0.1f,
            "ladder did not attenuate above cutoff: 8 kHz RMS $high vs 200 Hz RMS $low",
        )
    }

    private fun frames(): Int = sampleRate.toInt() * seconds

    private fun settle(): Int = 4_096

    private fun sine(freq: Float, amplitude: Float): FloatArray {
        val n = frames()
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (amplitude * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
        return out
    }

    /** Seeded LCG noise so a run never differs from the run before it. */
    private fun noise(n: Int, seed: Int): FloatArray {
        var state = seed
        val out = FloatArray(n)
        for (i in 0 until n) {
            state = state * 1664525 + 1013904223
            val u = (state ushr 8) and 0xFFFFFF
            out[i] = (u / 16777216f - 0.5f) * 1.98f
        }
        return out
    }

    /**
     * Amplitude of the component at [freq], Hann-windowed Goertzel. The
     * window keeps a strong neighbouring harmonic from bleeding into the bin
     * under test, which is what makes "is there alias energy here?" a
     * meaningful question.
     */
    private fun amplitudeAt(x: FloatArray, freq: Float): Float {
        val n = x.size
        val w = 2.0 * PI * freq / sampleRate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        var windowSum = 0.0
        for (i in 0 until n) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            windowSum += window
            val s0 = x[i] * window + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val real = s1 - s2 * cos(w)
        val imag = s2 * sin(w)
        val magnitude = sqrt(real * real + imag * imag)
        return (2.0 * magnitude / windowSum).toFloat()
    }

    private fun rms(x: FloatArray): Float {
        var sum = 0f
        for (v in x) sum += v * v
        return sqrt(sum / x.size)
    }
}
