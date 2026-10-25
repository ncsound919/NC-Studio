package com.example.audio.dsp

class ChannelLimiter(private val sampleRate: Float) {
    var enabled = true
    var ceilingDb = -0.3f
    private var ceilingLinear = Math.pow(10.0, (ceilingDb / 20.0).toDouble()).toFloat()
    private var envelope = 0f
    private var releaseCoeff = kotlin.math.exp(-1.0 / (0.01 * sampleRate)).toFloat()

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        val peak = maxOf(kotlin.math.abs(inL), kotlin.math.abs(inR))
        envelope = maxOf(peak, envelope * releaseCoeff)
        val gain = if (envelope > ceilingLinear) ceilingLinear / envelope else 1f
        out[0] = inL * gain; out[1] = inR * gain
    }
    fun configure(config: com.example.audio.model.ChannelLimiterConfig) {
        enabled = config.enabled
        ceilingDb = config.ceilingDb
        ceilingLinear = Math.pow(10.0, (ceilingDb / 20.0).toDouble()).toFloat()
        releaseCoeff = kotlin.math.exp(-1.0 / (config.releaseMs * sampleRate / 1000.0)).toFloat()
    }
    fun reset() { envelope = 0f }
}
