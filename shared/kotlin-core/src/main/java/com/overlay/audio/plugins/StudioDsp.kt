package com.overlay.audio.plugins

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Shared math and DSP building blocks for the studio plugin rack.
 *
 * Everything here is allocation-free in the audio path: modules own their
 * filter state and scratch buffers, exactly like the rest of the engine.
 */
object Db {
    fun toLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
    fun fromLinear(linear: Float): Float =
        if (linear <= 1e-6f) -120f else 20f * log10(linear)
}

/** One-pole parameter smoother so slider moves never step the audio. */
class SmoothValue(initial: Float, sampleRate: Float, timeConstantMs: Float) {
    private var current = initial
    private var target = initial
    private var running = false
    private val coeff = if (timeConstantMs <= 0f) 0f
        else exp(-1.0 / (timeConstantMs * sampleRate / 1000.0)).toFloat()

    /**
     * Before the first sample the smoother is not running yet, so a configured
     * value must land immediately: otherwise every module would fade in from
     * its constructor default, which is audible on a mix/trim control.
     */
    fun set(value: Float) {
        if (running) target = value else { current = value; target = value }
    }

    fun snap(value: Float) { current = value; target = value; running = true }

    fun next(): Float {
        running = true
        current = target + coeff * (current - target)
        return current
    }
}

/** Direct-form-II transposed biquad with independent mono state. */
class Biquad(private val sampleRate: Float) {
    var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        // A non-finite sample must not stay in the recursion: clearing the
        // state lets the filter recover instead of returning silence forever.
        if (!y.isFinite()) {
            reset()
            return 0f
        }
        // Flush denormals to zero so tails decay to silence instead of burning
        // CPU in the subnormal range. Clear the state too, otherwise the
        // recursion keeps running with subnormal values.
        if (kotlin.math.abs(y) < 1e-30f) {
            z1 = 0f
            z2 = 0f
            return 0f
        }
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }

    fun setBypass() { b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f }

    fun setLowShelf(freq: Float, gainDb: Float, slope: Float = 1f) {
        val f = freq.coerceIn(10f, sampleRate * 0.45f)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) + (a - 1.0) * cw + twoSqrtAAlpha
        b0 = (a * ((a + 1.0) - (a - 1.0) * cw + twoSqrtAAlpha) / a0).toFloat()
        b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cw) / a0).toFloat()
        b2 = (a * ((a + 1.0) - (a - 1.0) * cw - twoSqrtAAlpha) / a0).toFloat()
        a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cw) / a0).toFloat()
        a2 = (((a + 1.0) + (a - 1.0) * cw - twoSqrtAAlpha) / a0).toFloat()
    }

    fun setHighShelf(freq: Float, gainDb: Float, slope: Float = 1f) {
        val f = freq.coerceIn(10f, sampleRate * 0.45f)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) - (a - 1.0) * cw + twoSqrtAAlpha
        b0 = (a * ((a + 1.0) + (a - 1.0) * cw + twoSqrtAAlpha) / a0).toFloat()
        b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cw) / a0).toFloat()
        b2 = (a * ((a + 1.0) + (a - 1.0) * cw - twoSqrtAAlpha) / a0).toFloat()
        a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cw) / a0).toFloat()
        a2 = (((a + 1.0) - (a - 1.0) * cw - twoSqrtAAlpha) / a0).toFloat()
    }

    fun setPeaking(freq: Float, gainDb: Float, q: Float) {
        val f = freq.coerceIn(10f, sampleRate * 0.45f)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2.0 * q.coerceAtLeast(0.05f))
        val a0 = 1.0 + alpha / a
        b0 = ((1.0 + alpha * a) / a0).toFloat()
        b1 = (-2.0 * cw / a0).toFloat()
        b2 = ((1.0 - alpha * a) / a0).toFloat()
        a1 = (-2.0 * cw / a0).toFloat()
        a2 = ((1.0 - alpha / a) / a0).toFloat()
    }

    /**
     * First-order high pass, the real pole of the 3rd-order console HPF.
     * Bilinear transform of H(s) = s / (s + w0): 18 dB/oct needs one 1st-order
     * section, not a second 2nd-order one (that gives 24 dB/oct and a notch).
     */
    fun setFirstOrderHighPass(freq: Float) {
        val f = freq.coerceIn(5f, sampleRate * 0.45f)
        val k = tan(PI * f / sampleRate)
        val norm = 1.0 / (1.0 + k)
        b0 = norm.toFloat()
        b1 = (-norm).toFloat()
        b2 = 0f
        a1 = ((k - 1.0) * norm).toFloat()
        a2 = 0f
    }

    fun setHighPass(freq: Float, q: Float) {
        val f = freq.coerceIn(5f, sampleRate * 0.45f)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2.0 * q.coerceAtLeast(0.05f))
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cw) / 2.0 / a0).toFloat()
        b1 = (-(1.0 + cw) / a0).toFloat()
        b2 = ((1.0 + cw) / 2.0 / a0).toFloat()
        a1 = (-2.0 * cw / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun setLowPass(freq: Float, q: Float) {
        val f = freq.coerceIn(10f, sampleRate * 0.45f)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2.0 * q.coerceAtLeast(0.05f))
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cw) / 2.0 / a0).toFloat()
        b1 = ((1.0 - cw) / a0).toFloat()
        b2 = ((1.0 - cw) / 2.0 / a0).toFloat()
        a1 = (-2.0 * cw / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun setLowShelfTilt(freq: Float, gainDb: Float, slope: Float = 1f) = setLowShelf(freq, gainDb, slope)
    fun setHighShelfTilt(freq: Float, gainDb: Float, slope: Float = 1f) = setHighShelf(freq, gainDb, slope)
}

/** Cascade of biquads used for steeper slopes (18 dB/oct HPF, Butterworth LP). */
class BiquadCascade(sampleRate: Float, stages: Int) {
    private val filters = Array(stages) { Biquad(sampleRate) }

    fun process(x: Float): Float {
        var v = x
        for (f in filters) v = f.process(v)
        return v
    }

    fun reset() { filters.forEach { it.reset() } }
    fun bypass() { filters.forEach { it.setBypass() } }

    /** Butterworth cascade of [stages] biquads (stages 1 or 2 => 2nd/4th order). */
    fun setButterworthLowPass(freq: Float) {
        val qs = when (filters.size) {
            1 -> doubleArrayOf(0.7071)
            2 -> doubleArrayOf(0.5412, 1.3066)
            else -> doubleArrayOf(0.5412, 1.3066, 0.7071)
        }
        filters.forEachIndexed { i, f -> if (i < qs.size) f.setLowPass(freq, qs[i].toFloat()) else f.setBypass() }
    }

    /** Third-order (18 dB/oct) high pass, the SSL Black-knob / Neve console slope. */
    fun setThirdOrderHighPass(freq: Float) {
        // Butterworth 3rd order: one real pole plus one complex pair at Q = 1.
        // Cascading two biquads (Q=1 and Q=0.5) is 4th order and notches 6 dB
        // at the dialled frequency, which is what this method used to do.
        if (filters.isNotEmpty()) filters[0].setFirstOrderHighPass(freq)
        if (filters.size > 1) filters[1].setHighPass(freq, 1.0f)
        for (i in 2 until filters.size) filters[i].setBypass()
    }

    fun setSecondOrderHighPass(freq: Float, q: Float = 0.7071f) {
        if (filters.isNotEmpty()) filters[0].setHighPass(freq, q)
        for (i in 1 until filters.size) filters[i].setBypass()
    }

    fun setSecondOrderLowPass(freq: Float, q: Float = 0.7071f) {
        if (filters.isNotEmpty()) filters[0].setLowPass(freq, q)
        for (i in 1 until filters.size) filters[i].setBypass()
    }
}

/**
 * Polyphase-free 2x/4x oversampler built from zero stuffing plus 4th-order
 * Butterworth interpolation/decimation filters. Nonlinear modules (saturation,
 * tape, FET clamp, limiters) run here so their harmonics do not fold back as
 * aliasing - the same reason commercial emulations run 2x-8x internally.
 */
class Oversampler(val factor: Int, private val sampleRate: Float) {
    private val oversampledRate = sampleRate * factor
    private val upL = BiquadCascade(oversampledRate, 2)
    private val upR = BiquadCascade(oversampledRate, 2)
    private val downL = BiquadCascade(oversampledRate, 2)
    private val downR = BiquadCascade(oversampledRate, 2)
    val bufferL = FloatArray(factor)
    val bufferR = FloatArray(factor)

    init {
        val cutoff = sampleRate * 0.45f
        upL.setButterworthLowPass(cutoff); upR.setButterworthLowPass(cutoff)
        downL.setButterworthLowPass(cutoff); downR.setButterworthLowPass(cutoff)
    }

    /** Stuff [inL]/[inR] with zeros and interpolate, scaled to unity gain. */
    fun upsample(inL: Float, inR: Float) {
        bufferL.fill(0f); bufferR.fill(0f)
        bufferL[0] = inL * factor; bufferR[0] = inR * factor
        for (i in 0 until factor) {
            bufferL[i] = upL.process(bufferL[i])
            bufferR[i] = upR.process(bufferR[i])
        }
    }

    /** Anti-alias filter and keep one of every [factor] samples. */
    fun downsample(out: FloatArray) {
        var lastL = 0f; var lastR = 0f
        for (i in 0 until factor) {
            lastL = downL.process(bufferL[i])
            lastR = downR.process(bufferR[i])
        }
        out[0] = lastL; out[1] = lastR
    }

    fun reset() {
        upL.reset(); upR.reset(); downL.reset(); downR.reset()
        bufferL.fill(0f); bufferR.fill(0f)
    }
}

/**
 * Base class that handles the common "stereo module with optional oversampled
 * nonlinear stage" plumbing and parameter smoothing.
 */
abstract class StereoModule(protected val sampleRate: Float) : RackModule {
    protected var mixSmoother = SmoothValue(1f, sampleRate, 12f)
    protected val wet = FloatArray(2)
    protected var oversampler: Oversampler? = null

    override var enabled: Boolean = true

    protected fun updateOversampling(factor: Int) {
        val desired = factor.coerceIn(1, 4)
        if (desired <= 1) {
            oversampler = null
        } else if (oversampler == null || oversampler?.factor != desired) {
            oversampler = Oversampler(desired, sampleRate)
        }
    }

    /** Nonlinear transfer curve evaluated at the (possibly oversampled) rate. */
    protected open fun shape(x: Float): Float = x

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        val os = oversampler
        if (os == null) {
            out[0] = shape(inL); out[1] = shape(inR)
        } else {
            os.upsample(inL, inR)
            for (i in 0 until os.factor) {
                os.bufferL[i] = shape(os.bufferL[i])
                os.bufferR[i] = shape(os.bufferR[i])
            }
            os.downsample(out)
        }
        applyMix(inL, inR, out)
    }

    private val mixOut = FloatArray(2)

    protected fun applyMix(inL: Float, inR: Float, out: FloatArray) {
        val mix = mixSmoother.next()
        if (mix >= 0.999f) return
        mixOut[0] = out[0]; mixOut[1] = out[1]
        out[0] = inL + (mixOut[0] - inL) * mix
        out[1] = inR + (mixOut[1] - inR) * mix
    }

    override fun reset() { oversampler?.reset() }
}

// ============================================================
// EQ modules
// ============================================================

/** Plain 12 dB/oct high and low pass, the "HPF everything" utility. */
class FilterModule(sampleRate: Float) : StereoModule(sampleRate) {
    override val kind = PluginKind.FILTER
    private val hpL = BiquadCascade(sampleRate, 1)
    private val hpR = BiquadCascade(sampleRate, 1)
    private val lpL = BiquadCascade(sampleRate, 1)
    private val lpR = BiquadCascade(sampleRate, 1)

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        val hp = slot.value("hpHz")
        val lp = slot.value("lpHz")
        if (hp > 15f) {
            hpL.setSecondOrderHighPass(hp); hpR.setSecondOrderHighPass(hp)
        } else {
            // A disabled band must be transparent. A Butterworth low-pass at
            // 0.45 * sampleRate is not: it tilts the top octave and adds phase
            // shift to every chain that merely has the utility slot inserted.
            hpL.bypass(); hpR.bypass()
        }
        // 0 is the documented "off" position and the only value that should
        // bypass. The old > 1000 Hz guard silently disabled every 1–1000 Hz
        // setting (a 500 Hz LPF is an ordinary mix move) and also discarded
        // the top of the advertised range at 44.1 kHz. The biquad clamps the
        // frequency to its own stable ceiling.
        if (lp > 0f) {
            lpL.setSecondOrderLowPass(lp); lpR.setSecondOrderLowPass(lp)
        } else {
            lpL.bypass(); lpR.bypass()
        }
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        out[0] = lpL.process(hpL.process(inL))
        out[1] = lpR.process(hpR.process(inR))
    }

    override fun reset() { hpL.reset(); hpR.reset(); lpL.reset(); lpR.reset() }
}

/**
 * Neve 1073 preamp + EQ. Fixed HF shelf at 12 kHz (the 10/12/16 kHz selector
 * belongs to the 1084), mid centres 360 Hz - 7.2 kHz with Q rising with
 * frequency, LF shelf 35/60/110/220 Hz, 18 dB/oct HPF and a Class-A style
 * preamp stage that clips asymmetrically and thickens low mids.
 */
class Neve1073Module(sampleRate: Float) : StereoModule(sampleRate) {
    override val kind = PluginKind.NEVE_1073
    private val hpL = BiquadCascade(sampleRate, 2)
    private val hpR = BiquadCascade(sampleRate, 2)
    private val lfL = Biquad(sampleRate); private val lfR = Biquad(sampleRate)
    private val midL = Biquad(sampleRate); private val midR = Biquad(sampleRate)
    private val hfL = Biquad(sampleRate); private val hfR = Biquad(sampleRate)
    private val outSmoother = SmoothValue(1f, sampleRate, 20f)
    private val driveSmoother = SmoothValue(0f, sampleRate, 25f)
    private val transformerL = SmoothValue(0f, sampleRate, 1.1f)
    private val transformerR = SmoothValue(0f, sampleRate, 1.1f)

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        val hpf = slot.value("hpfHz")
        hpL.setThirdOrderHighPass(hpf.coerceAtLeast(20f)); hpR.setThirdOrderHighPass(hpf.coerceAtLeast(20f))
        lfL.setLowShelf(slot.value("lfHz"), slot.value("lfDb")); lfR.setLowShelf(slot.value("lfHz"), slot.value("lfDb"))
        val midHz = slot.value("midHz")
        // Fixed-Q design whose selectivity rises with the centre frequency.
        val q = (0.6f + (midHz / 7200f) * 1.1f).coerceIn(0.6f, 1.8f)
        midL.setPeaking(midHz, slot.value("midDb"), q); midR.setPeaking(midHz, slot.value("midDb"), q)
        hfL.setHighShelf(12000f, slot.value("hfDb")); hfR.setHighShelf(12000f, slot.value("hfDb"))
        outSmoother.set(Db.toLinear(slot.value("outDb")))
        driveSmoother.set(slot.value("driveDb"))
        updateOversampling(2)
    }

    override fun shape(x: Float): Float {
        val driveDb = driveSmoother.next()
        if (driveDb <= 0.01f) return x
        val drive = Db.toLinear(driveDb)
        val driven = x * drive
        // Asymmetric Class-A-ish transfer: more second harmonic than third.
        val shaped = if (driven >= 0f) driven / (1f + driven * 0.45f)
        else driven / (1f - driven * 0.32f)
        return shaped / (1f + (drive - 1f) * 0.06f)
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        val os = oversampler
        var l: Float
        var r: Float
        if (os != null) {
            os.upsample(inL, inR)
            for (i in 0 until os.factor) {
                os.bufferL[i] = shape(os.bufferL[i])
                os.bufferR[i] = shape(os.bufferR[i])
            }
            os.downsample(out)
            l = out[0]; r = out[1]
        } else {
            l = shape(inL); r = shape(inR)
        }
        l = lfL.process(midL.process(hfL.process(l)))
        r = lfR.process(midR.process(hfR.process(r)))
        l = hpL.process(l); r = hpR.process(r)
        // Marinair transformer weight: the low-mid bloom is a low-frequency
        // lift, not a broadband loss, so it must not take level out of the
        // passband the way a long one-pole mix would.
        transformerL.set(l); transformerR.set(r)
        val bloomL = transformerL.next(); val bloomR = transformerR.next()
        l += bloomL * 0.05f
        r += bloomR * 0.05f
        val gain = outSmoother.next()
        out[0] = l * gain; out[1] = r * gain
    }

    override fun reset() {
        super.reset(); hpL.reset(); hpR.reset(); lfL.reset(); lfR.reset()
        midL.reset(); midR.reset(); hfL.reset(); hfR.reset()
    }
}

/**
 * SSL 4000 E channel EQ: LF/HF switchable shelf or bell, variable-Q LMF/HMF,
 * 18 dB/oct Black-knob high pass and the forward, punchy midrange character.
 */
class SslChannelEqModule(sampleRate: Float) : StereoModule(sampleRate) {
    override val kind = PluginKind.SSL_CHANNEL_EQ
    private val hpL = BiquadCascade(sampleRate, 2); private val hpR = BiquadCascade(sampleRate, 2)
    private val lfL = Biquad(sampleRate); private val lfR = Biquad(sampleRate)
    private val lmfL = Biquad(sampleRate); private val lmfR = Biquad(sampleRate)
    private val hmfL = Biquad(sampleRate); private val hmfR = Biquad(sampleRate)
    private val hfL = Biquad(sampleRate); private val hfR = Biquad(sampleRate)
    private val outSmoother = SmoothValue(1f, sampleRate, 20f)
    private val driveSmoother = SmoothValue(0f, sampleRate, 30f)

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        val hp = slot.value("hpHz")
        if (hp >= 15f) { hpL.setThirdOrderHighPass(hp); hpR.setThirdOrderHighPass(hp) }
        // No hidden anti-image filter when the HPF is off: at 0.45 * sr it
        // tilts the top octave and adds phase shift, so an "open" channel EQ
        // would not be a wire.
        else { hpL.bypass(); hpR.bypass() }

        if (slot.value("lfBell") >= 0.5f) {
            lfL.setPeaking(slot.value("lfHz"), slot.value("lfDb"), 0.9f); lfR.setPeaking(slot.value("lfHz"), slot.value("lfDb"), 0.9f)
        } else {
            lfL.setLowShelf(slot.value("lfHz"), slot.value("lfDb"), 0.85f); lfR.setLowShelf(slot.value("lfHz"), slot.value("lfDb"), 0.85f)
        }
        lmfL.setPeaking(slot.value("lmfHz"), slot.value("lmfDb"), slot.value("lmfQ"))
        lmfR.setPeaking(slot.value("lmfHz"), slot.value("lmfDb"), slot.value("lmfQ"))
        hmfL.setPeaking(slot.value("hmfHz"), slot.value("hmfDb"), slot.value("hmfQ"))
        hmfR.setPeaking(slot.value("hmfHz"), slot.value("hmfDb"), slot.value("hmfQ"))
        if (slot.value("hfBell") >= 0.5f) {
            hfL.setPeaking(slot.value("hfHz"), slot.value("hfDb"), 0.9f); hfR.setPeaking(slot.value("hfHz"), slot.value("hfDb"), 0.9f)
        } else {
            hfL.setHighShelf(slot.value("hfHz"), slot.value("hfDb"), 0.85f); hfR.setHighShelf(slot.value("hfHz"), slot.value("hfDb"), 0.85f)
        }
        outSmoother.set(Db.toLinear(slot.value("outDb")))
        driveSmoother.set(slot.value("hmfDb").coerceAtLeast(0f))
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        var l = hfL.process(hmfL.process(lmfL.process(lfL.process(hpL.process(inL)))))
        var r = hfR.process(hmfR.process(lmfR.process(lfR.process(hpR.process(inR)))))
        // The E-series op-amp character: proportional harmonic edge as the
        // high-mid band is pushed. Subtle by design, like the console.
        val drive = driveSmoother.next()
        if (drive > 1f) {
            val amt = ((drive - 1f) / 18f).coerceIn(0f, 1f) * 0.35f
            l *= 1f + amt * (l * l - 0.33f)
            r *= 1f + amt * (r * r - 0.33f)
        }
        val gain = outSmoother.next()
        out[0] = l * gain; out[1] = r * gain
    }

    override fun reset() {
        super.reset(); hpL.reset(); hpR.reset(); lfL.reset(); lfR.reset()
        lmfL.reset(); lmfR.reset(); hmfL.reset(); hmfR.reset(); hfL.reset(); hfR.reset()
    }
}

/**
 * Pultec EQP-1A. The low section is boost + attenuation at adjacent turnovers,
 * which sums to a low shelf lift with a low-mid dip rather than a narrow
 * resonant notch; the high boost is an inductor-style broad bell with a
 * separate high shelf attenuation. The passive network loses roughly 20 dB, so
 * a tube make-up stage restores level and adds gentle color.
 */
class PultecEqp1aModule(sampleRate: Float) : StereoModule(sampleRate) {
    override val kind = PluginKind.PULTEC_EQP1A
    private val lowBoostL = Biquad(sampleRate); private val lowBoostR = Biquad(sampleRate)
    private val lowAttenL = Biquad(sampleRate); private val lowAttenR = Biquad(sampleRate)
    private val highBoostL = Biquad(sampleRate); private val highBoostR = Biquad(sampleRate)
    private val highAttenL = Biquad(sampleRate); private val highAttenR = Biquad(sampleRate)
    private val outSmoother = SmoothValue(1f, sampleRate, 20f)

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        val lowBoost = slot.value("lowBoostDb")
        val lowAtten = slot.value("lowAttenDb")
        if (slot.value("lowBoostHz") > 0f) {
            lowBoostL.setLowShelf(slot.value("lowBoostHz"), lowBoost)
            lowBoostR.setLowShelf(slot.value("lowBoostHz"), lowBoost)
        } else {
            lowBoostL.setBypass(); lowBoostR.setBypass()
        }
        // Attenuation starts about half an octave above the boost turnover,
        // producing the classic tight boost-plus-dip low contour.
        if (lowAtten > 0.01f) {
            val attenFreq = (slot.value("lowAttenHz") * 1.5f).coerceAtMost(sampleRate * 0.35f)
            lowAttenL.setPeaking(attenFreq, -lowAtten, 0.7f)
            lowAttenR.setPeaking(attenFreq, -lowAtten, 0.7f)
        } else {
            lowAttenL.setBypass(); lowAttenR.setBypass()
        }
        if (slot.value("highBoostDb") > 0.01f) {
            highBoostL.setPeaking(slot.value("highBoostHz"), slot.value("highBoostDb"), 0.8f)
            highBoostR.setPeaking(slot.value("highBoostHz"), slot.value("highBoostDb"), 0.8f)
        } else {
            highBoostL.setBypass(); highBoostR.setBypass()
        }
        if (slot.value("highAttenDb") > 0.01f) {
            highAttenL.setHighShelf(slot.value("highAttenHz"), -slot.value("highAttenDb"), 0.7f)
            highAttenR.setHighShelf(slot.value("highAttenHz"), -slot.value("highAttenDb"), 0.7f)
        } else {
            highAttenL.setBypass(); highAttenR.setBypass()
        }
        outSmoother.set(Db.toLinear(slot.value("outDb") + 20f))
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        var l = lowAttenL.process(lowBoostL.process(inL))
        var r = lowAttenR.process(lowBoostR.process(inR))
        l = highAttenL.process(highBoostL.process(l))
        r = highAttenR.process(highBoostR.process(r))
        // Tube make-up: gentle asymmetric color, not a distortion box.
        l = l / (1f + abs(l) * 0.06f)
        r = r / (1f + abs(r) * 0.06f)
        val gain = outSmoother.next()
        out[0] = l * gain; out[1] = r * gain
    }

    override fun reset() {
        super.reset(); lowBoostL.reset(); lowBoostR.reset(); lowAttenL.reset(); lowAttenR.reset()
        highBoostL.reset(); highBoostR.reset(); highAttenL.reset(); highAttenR.reset()
    }
}

/** Simple air shelf for the 10-12 kHz vocal lift. */
class AirShelfModule(sampleRate: Float) : StereoModule(sampleRate) {
    override val kind = PluginKind.AIR_SHELF
    private val l = Biquad(sampleRate); private val r = Biquad(sampleRate)

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        val freq = slot.value("freqHz")
        val gain = slot.value("gainDb")
        if (gain == 0f) { l.setBypass(); r.setBypass() } else {
            l.setHighShelf(freq, gain); r.setHighShelf(freq, gain)
        }
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        out[0] = l.process(inL); out[1] = r.process(inR)
    }

    override fun reset() { l.reset(); r.reset() }
}

/**
 * Mid-side EQ / width: cut lows in the sides (keeps bass mono and focused)
 * and optionally lift side air, with a width control for the stereo image.
 */
class MidSideEqModule(sampleRate: Float) : StereoModule(sampleRate) {
    override val kind = PluginKind.MID_SIDE_EQ
    private val sideHpL = Biquad(sampleRate); private val sideHpR = Biquad(sampleRate)
    private val sideAirL = Biquad(sampleRate); private val sideAirR = Biquad(sampleRate)
    private var width = 1f

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        val hp = slot.value("sideHpHz")
        if (hp > 15f) {
            sideHpL.setHighPass(hp, 0.707f); sideHpR.setHighPass(hp, 0.707f)
        } else { sideHpL.setBypass(); sideHpR.setBypass() }
        val air = slot.value("sideAirDb")
        if (air != 0f) {
            sideAirL.setHighShelf(9000f, air); sideAirR.setHighShelf(9000f, air)
        } else { sideAirL.setBypass(); sideAirR.setBypass() }
        width = slot.value("width").coerceIn(0.25f, 2f)
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        val mid = (inL + inR) * 0.5f
        var side = (inL - inR) * 0.5f
        side = sideAirL.process(sideHpL.process(side))
        side *= width
        out[0] = mid + side
        out[1] = mid - side
    }

    override fun reset() {
        sideHpL.reset(); sideHpR.reset(); sideAirL.reset(); sideAirR.reset()
    }
}
