package com.overlay.audio.sample

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The one sampling contract shared by NC Studio, NC Phone Beat and NC Sound
 * Lab (`shared/contracts/sample-edit.v1.json`).
 *
 * Every function is pure: it returns a new buffer and never mutates its input,
 * so an editor can keep an undo copy without defensive cloning. Audio is
 * interleaved at [channels] channels (2 everywhere in the ecosystem, 1 for a
 * phone-beat pad file).
 *
 * The matching TypeScript implementation is
 * `new dsp/src/lib/sampleEdit.ts`; both read the same parity cases.
 */
object SampleEdit {

    enum class FadeCurve { LINEAR, EQUAL_POWER }

    /** A half-open frame span: `[startFrame, endFrameExclusive)`. */
    data class Span(val startFrame: Int, val endFrameExclusive: Int) {
        val frames: Int get() = (endFrameExclusive - startFrame).coerceAtLeast(0)
    }

    /**
     * Pro Tools-style Strip Silence settings, in the order the Pro Tools dialog
     * presents them: threshold, minimum silence, minimum clip, start/end pad.
     */
    data class StripSettings(
        val thresholdDb: Float = -45f,
        val minSilenceMs: Float = 350f,
        val minKeepMs: Float = 80f,
        val padMs: Float = 10f,
    )

    fun frameCount(samples: FloatArray, channels: Int): Int {
        val ch = channels.coerceAtLeast(1)
        return samples.size / ch
    }

    /** Loudest absolute sample in a range, across every channel. */
    fun peak(
        samples: FloatArray,
        channels: Int,
        startFrame: Int = 0,
        endFrameExclusive: Int = frameCount(samples, channels),
    ): Float {
        val ch = channels.coerceAtLeast(1)
        val frames = frameCount(samples, ch)
        var a = startFrame.coerceIn(0, frames)
        var b = endFrameExclusive.coerceIn(a, frames)
        var peak = 0f
        var i = a * ch
        val end = b * ch
        while (i < end) {
            val v = abs(samples[i])
            if (v > peak) peak = v
            i++
        }
        return peak
    }

    /** Scales the buffer so its loudest sample reaches [targetPeakDb]. */
    fun normalize(
        samples: FloatArray,
        channels: Int,
        targetPeakDb: Float = -0.3f,
    ): FloatArray {
        val p = peak(samples, channels)
        if (p <= 1e-9f) return samples.copyOf()
        val target = dbToLinear(targetPeakDb)
        val gain = target / p
        return FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }
    }

    fun applyGainDb(samples: FloatArray, db: Float): FloatArray {
        val gain = dbToLinear(db)
        return FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }
    }

    /**
     * Fade the first [frames] frames from 0 to 1. The first frame is silent and
     * the frame that ends the fade is at unity, so no step is left at either end.
     * [frames] counts frames, not samples: a stereo buffer fades both channels.
     */
    fun fadeIn(
        samples: FloatArray,
        channels: Int,
        frames: Int,
        curve: FadeCurve = FadeCurve.LINEAR,
    ): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val total = frameCount(samples, ch)
        val n = frames.coerceIn(0, total)
        val out = samples.copyOf()
        if (n == 0) return out
        if (n == 1) {
            for (c in 0 until ch) out[c] = 0f
            return out
        }
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            val gain = when (curve) {
                FadeCurve.LINEAR -> t
                FadeCurve.EQUAL_POWER -> sin(t * (PI / 2).toFloat())
            }
            for (c in 0 until ch) out[i * ch + c] *= gain
        }
        return out
    }

    /** Fade the last [frames] frames to silence; the final frame is exactly 0. */
    fun fadeOut(
        samples: FloatArray,
        channels: Int,
        frames: Int,
        curve: FadeCurve = FadeCurve.LINEAR,
    ): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val total = frameCount(samples, ch)
        val n = frames.coerceIn(0, total)
        val out = samples.copyOf()
        if (n == 0) return out
        if (n == 1) {
            for (c in 0 until ch) out[(total - 1) * ch + c] = 0f
            return out
        }
        val start = total - n
        for (i in start until total) {
            val t = (i - start).toFloat() / (n - 1)
            val gain = when (curve) {
                FadeCurve.LINEAR -> 1f - t
                FadeCurve.EQUAL_POWER -> cos(t * (PI / 2).toFloat())
            }
            for (c in 0 until ch) out[i * ch + c] *= gain
        }
        return out
    }

    /** Reverses frames while keeping channel order (L stays L, R stays R). */
    fun reverse(samples: FloatArray, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = frameCount(samples, ch)
        val out = FloatArray(samples.size)
        for (f in 0 until frames) {
            val src = f * ch
            val dst = (frames - 1 - f) * ch
            for (c in 0 until ch) out[dst + c] = samples[src + c]
        }
        return out
    }

    /** Keeps `[startFrame, endFrameExclusive)` and re-bases it to frame 0. */
    fun trim(samples: FloatArray, channels: Int, startFrame: Int, endFrameExclusive: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = frameCount(samples, ch)
        val a = startFrame.coerceIn(0, frames)
        val b = endFrameExclusive.coerceIn(a, frames)
        return samples.copyOfRange(a * ch, b * ch)
    }

    /** Subtracts each channel's own mean. */
    fun removeDcOffset(samples: FloatArray, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = frameCount(samples, ch)
        if (frames == 0) return samples.copyOf()
        val sums = DoubleArray(ch)
        for (f in 0 until frames) {
            for (c in 0 until ch) sums[c] += samples[f * ch + c]
        }
        val means = FloatArray(ch) { (sums[it] / frames).toFloat() }
        return FloatArray(samples.size) { samples[it] - means[it % ch] }
    }

    /**
     * Nearest frame to [frame] where the mono sum changes sign, searching
     * [frame] +/- [windowFrames]. Returns [frame] when there is no crossing.
     */
    fun nearestZeroCrossing(
        samples: FloatArray,
        channels: Int,
        frame: Int,
        windowFrames: Int = 256,
    ): Int {
        val ch = channels.coerceAtLeast(1)
        val frames = frameCount(samples, ch)
        if (frames < 2) return 0
        val requested = frame.coerceIn(0, frames - 1)
        val from = (requested - windowFrames).coerceAtLeast(0)
        val to = (requested + windowFrames).coerceAtMost(frames - 1)
        var best = requested
        var bestDistance = Int.MAX_VALUE
        for (i in from until to) {
            val a = monoAt(samples, ch, i)
            val b = monoAt(samples, ch, i + 1)
            if ((a <= 0f && b > 0f) || (a >= 0f && b < 0f)) {
                val distance = abs(i - requested)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
        }
        return best
    }

    /**
     * Spans of audio to keep after strip silence, in increasing order. A frame
     * is silent when every channel is at or below [StripSettings.thresholdDb].
     * Silent runs shorter than `minSilenceMs` stay audio; spans shorter than
     * `minKeepMs` are dropped; surviving spans get `padMs` on both sides,
     * clamped to the analysed range and merged. Returns an empty list for all
     * silence.
     *
     * [startFrame]/[endFrameExclusive] restrict the analysis to one clip inside
     * a larger source file; returned spans are always absolute source frames.
     */
    fun keepSpans(
        samples: FloatArray,
        channels: Int,
        sampleRate: Int,
        settings: StripSettings,
        startFrame: Int = 0,
        endFrameExclusive: Int = frameCount(samples, channels),
    ): List<Span> {
        val ch = channels.coerceAtLeast(1)
        val frames = frameCount(samples, ch)
        val rangeStart = startFrame.coerceIn(0, frames)
        val rangeEnd = endFrameExclusive.coerceIn(rangeStart, frames)
        if (frames == 0 || sampleRate <= 0 || rangeEnd <= rangeStart) return emptyList()

        val threshold = dbToLinear(settings.thresholdDb)
        val minSilence = msToFrames(settings.minSilenceMs, sampleRate)
        val minKeep = msToFrames(settings.minKeepMs, sampleRate)
        val pad = msToFrames(settings.padMs, sampleRate)

        val loud = BooleanArray(rangeEnd - rangeStart)
        var anyLoud = false
        for (f in rangeStart until rangeEnd) {
            var isLoud = false
            for (c in 0 until ch) {
                if (abs(samples[f * ch + c]) > threshold) {
                    isLoud = true
                    break
                }
            }
            loud[f - rangeStart] = isLoud
            if (isLoud) anyLoud = true
        }
        if (!anyLoud) return emptyList()

        // Raw keep spans: runs of loud frames, in absolute source frames.
        val raw = ArrayList<Span>()
        var i = 0
        while (i < loud.size) {
            if (!loud[i]) {
                i++
                continue
            }
            val start = i
            while (i < loud.size && loud[i]) i++
            raw += Span(rangeStart + start, rangeStart + i)
        }

        // A short silent run is not a strip point: merge across it.
        val merged = ArrayList<Span>()
        for (span in raw) {
            val last = merged.lastOrNull()
            if (last != null && span.startFrame - last.endFrameExclusive < minSilence) {
                merged[merged.lastIndex] = last.copy(endFrameExclusive = span.endFrameExclusive)
            } else {
                merged += span
            }
        }

        // Drop specks, pad, clamp, and merge spans whose padding now overlaps.
        val padded = ArrayList<Span>()
        for (span in merged) {
            if (span.frames < minKeep) continue
            val a = (span.startFrame - pad).coerceAtLeast(rangeStart)
            val b = (span.endFrameExclusive + pad).coerceAtMost(rangeEnd)
            val last = padded.lastOrNull()
            if (last != null && a <= last.endFrameExclusive) {
                padded[padded.lastIndex] = last.copy(endFrameExclusive = maxOf(last.endFrameExclusive, b))
            } else {
                padded += Span(a, b)
            }
        }
        return padded
    }

    fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    fun msToFrames(ms: Float, sampleRate: Int): Int =
        ((ms.coerceAtLeast(0f) / 1000f) * sampleRate).toInt()

    private fun monoAt(samples: FloatArray, channels: Int, frame: Int): Float {
        var sum = 0f
        // Start from the first channel so a mono buffer is a plain read.
        val base = frame * channels
        for (c in 0 until channels) sum += samples[base + c]
        return sum
    }
}
