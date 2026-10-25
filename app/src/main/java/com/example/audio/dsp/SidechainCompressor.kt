package com.example.audio.dsp

/**
 * Sidechain (keyed) compressor: dynamics of the main signal are controlled by
 * an external key input (e.g. kick drum ducking a bass track).
 */
class SidechainCompressor(private val sampleRate: Float) {
    var enabled = true
    var thresholdDb = -18f
    var ratio = 4f
    var attackMs = 10f
    var releaseMs = 100f
    var makeupGainDb = 0f
    var kneeWidthDb = 3f
    private var detectorState = 0f
    private var attackCoeff = kotlin.math.exp(-1.0 / (10.0 * sampleRate / 1000.0)).toFloat()
    private var releaseCoeff = kotlin.math.exp(-1.0 / (100.0 * sampleRate / 1000.0)).toFloat()
    var currentGainReductionDb = 0f
    private val makeupGainLinear get() = Math.pow(10.0, (makeupGainDb / 20.0).toDouble()).toFloat()

    var keyInputL = 0f
    var keyInputR = 0f

    fun setKeyInput(l: Float, r: Float) { keyInputL = l; keyInputR = r }
    fun setSidechainInput(l: Float, r: Float) { setKeyInput(l, r) }
    fun setTrigger(l: Float, r: Float) { setKeyInput(l, r) }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }

    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        processSampleInto(inL, inR, keyInputL, keyInputR, out)
    }

    fun processSampleInto(inL: Float, inR: Float, keyL: Float, keyR: Float, out: FloatArray) {
        if (!enabled) { currentGainReductionDb = 0f; out[0] = inL; out[1] = inR; return }
        val peak = maxOf(kotlin.math.abs(keyL), kotlin.math.abs(keyR)).coerceAtLeast(
            maxOf(kotlin.math.abs(inL), kotlin.math.abs(inR))
        )
        val peakDb = if (peak > 1e-5f) 20f * kotlin.math.log10(peak) else -100f
        val halfKnee = kneeWidthDb * 0.5f
        val delta = peakDb - thresholdDb
        val targetGrDb = when {
            delta <= -halfKnee -> 0f
            delta >= halfKnee -> (1f - 1f / ratio) * delta
            else -> { val kneeTerm = (delta + halfKnee) / kneeWidthDb; (1f - 1f / ratio) * (kneeTerm * kneeTerm * halfKnee) }
        }
        val coeff = if (targetGrDb > detectorState) attackCoeff else releaseCoeff
        detectorState = targetGrDb + coeff * (detectorState - targetGrDb)
        currentGainReductionDb = detectorState
        val gainLinear = Math.pow(10.0, (-detectorState / 20.0).toDouble()).toFloat() * makeupGainLinear
        out[0] = inL * gainLinear; out[1] = inR * gainLinear
    }

    fun processBuffer(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        var i = 0
        while (i + 1 < input.size) {
            val scratch = FloatArray(2)
            processSampleInto(input[i], input[i + 1], input[i], input[i + 1], scratch)
            out[i] = scratch[0]
            out[i + 1] = scratch[1]
            i += 2
        }
        return out
    }

    fun configure(config: com.example.audio.model.CompressorConfig) {
        configure(
            thresholdDb = config.thresholdDb,
            ratio = config.ratio,
            attackMs = config.attackMs,
            releaseMs = config.releaseMs,
            makeupGainDb = config.makeupGainDb,
            kneeWidthDb = config.kneeWidthDb,
            enabled = config.enabled
        )
    }

    fun configure(thresholdDb: Float = this.thresholdDb, ratio: Float = this.ratio,
                  attackMs: Float = this.attackMs, releaseMs: Float = this.releaseMs,
                  makeupGainDb: Float = this.makeupGainDb, kneeWidthDb: Float = this.kneeWidthDb,
                  enabled: Boolean = this.enabled) {
        this.enabled = enabled
        this.thresholdDb = thresholdDb
        this.ratio = ratio
        this.attackMs = attackMs
        this.releaseMs = releaseMs
        this.makeupGainDb = makeupGainDb
        this.kneeWidthDb = kneeWidthDb
        attackCoeff = kotlin.math.exp(-1.0 / (attackMs * sampleRate / 1000.0)).toFloat()
        releaseCoeff = kotlin.math.exp(-1.0 / (releaseMs * sampleRate / 1000.0)).toFloat()
    }

    fun reset() { detectorState = 0f; currentGainReductionDb = 0f }
}

// Aliases
typealias SidechainProcessor = SidechainCompressor
typealias KeyedCompressor = SidechainCompressor
typealias DuckingCompressor = SidechainCompressor