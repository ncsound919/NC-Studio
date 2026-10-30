package com.example.session.take

import kotlin.math.roundToInt

/**
 * Little-endian PCM packing helpers.
 *
 * 24-bit is the take format for remote sessions (spec: 48 kHz / 24-bit mono).
 * Capture arrives as float, so conversion happens once, on the writer thread,
 * outside the audio callback.
 */
object PcmCodec {

    /** Bytes per sample for a supported bit depth. */
    fun bytesPerSample(bitDepth: Int): Int = when (bitDepth) {
        16 -> 2
        24 -> 3
        32 -> 4
        else -> throw IllegalArgumentException("Unsupported bit depth: $bitDepth")
    }

    /**
     * Converts [count] float samples to little-endian PCM in [out] starting at
     * [outOffset]. Returns the number of bytes written.
     */
    fun writeFloatPcm(
        samples: FloatArray,
        count: Int,
        bitDepth: Int,
        out: ByteArray,
        outOffset: Int = 0,
    ): Int {
        var cursor = outOffset
        when (bitDepth) {
            16 -> {
                for (i in 0 until count) {
                    // Double math keeps the rounding error below one LSB.
                    val value = (samples[i].toDouble().coerceIn(-1.0, 1.0) * 32767.0)
                        .roundToInt().coerceIn(-32768, 32767)
                    out[cursor++] = (value and 0xFF).toByte()
                    out[cursor++] = ((value shr 8) and 0xFF).toByte()
                }
            }

            24 -> {
                for (i in 0 until count) {
                    val value = (samples[i].toDouble().coerceIn(-1.0, 1.0) * 8388607.0)
                        .roundToInt().coerceIn(-8388608, 8388607)
                    out[cursor++] = (value and 0xFF).toByte()
                    out[cursor++] = ((value shr 8) and 0xFF).toByte()
                    out[cursor++] = ((value shr 16) and 0xFF).toByte()
                }
            }

            32 -> {
                // 32-bit is float on the wire, matching AudioRecord's float capture.
                for (i in 0 until count) {
                    val value = samples[i].coerceIn(-1f, 1f)
                    val bits = java.lang.Float.floatToIntBits(value)
                    out[cursor++] = (bits and 0xFF).toByte()
                    out[cursor++] = ((bits shr 8) and 0xFF).toByte()
                    out[cursor++] = ((bits shr 16) and 0xFF).toByte()
                    out[cursor++] = ((bits shr 24) and 0xFF).toByte()
                }
            }

            else -> throw IllegalArgumentException("Unsupported bit depth: $bitDepth")
        }
        return cursor - outOffset
    }

    /** Decodes [count] PCM samples starting at [offset] back to float. */
    fun readFloatPcm(bytes: ByteArray, offset: Int, count: Int, bitDepth: Int): FloatArray {
        val out = FloatArray(count)
        var cursor = offset
        when (bitDepth) {
            16 -> for (i in 0 until count) {
                val lo = bytes[cursor++].toInt() and 0xFF
                val hi = bytes[cursor++].toInt()
                out[i] = (((hi shl 8) or lo).toShort().toInt() / 32768.0).toFloat()
            }

            24 -> for (i in 0 until count) {
                val b0 = bytes[cursor++].toInt() and 0xFF
                val b1 = bytes[cursor++].toInt() and 0xFF
                val b2 = bytes[cursor++].toInt()
                val value = (b2 shl 16) or (b1 shl 8) or b0
                out[i] = (value / 8388608.0).toFloat()
            }

            32 -> for (i in 0 until count) {
                val b0 = bytes[cursor++].toInt() and 0xFF
                val b1 = bytes[cursor++].toInt() and 0xFF
                val b2 = bytes[cursor++].toInt() and 0xFF
                val b3 = bytes[cursor++].toInt() and 0xFF
                val bits = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                out[i] = java.lang.Float.intBitsToFloat(bits)
            }

            else -> throw IllegalArgumentException("Unsupported bit depth: $bitDepth")
        }
        return out
    }
}
