package com.example.audio.dsp

class ParameterSmoother(initialValue: Float, sampleRate: Float, timeConstantMs: Float) {
    private var current = initialValue
    private var target = initialValue
    private val coeff = kotlin.math.exp(-1.0 / (timeConstantMs * sampleRate / 1000.0)).toFloat()

    fun next(): Float {
        current = target + coeff * (current - target)
        return current
    }

    fun setTarget(value: Float) { target = value }
    fun getCurrent(): Float = current

    fun reset(value: Float = target) { current = value; target = value }
}

object PanLaw {
    fun calculateGains(pan: Float): Pair<Float, Float> {
        val p = pan.coerceIn(-1f, 1f)
        val angle = (p + 1f) * (Math.PI.toFloat() / 4f)
        return Pair(kotlin.math.cos(angle), kotlin.math.sin(angle))
    }
}
