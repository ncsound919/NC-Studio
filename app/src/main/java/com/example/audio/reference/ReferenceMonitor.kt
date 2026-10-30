package com.example.audio.reference

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The A/B monitor: audition of a reference track against the live mix.
 *
 * Three things have to be true for a reference comparison to mean anything, and
 * all three are this class's job:
 *
 * 1. **Same position.** The reference plays at the transport's position, so the
 *    chorus of your mix meets the chorus of the reference. With a loop active
 *    the reference loops with it, so a four-bar loop can be compared over and
 *    over without the two drifting apart.
 * 2. **Matched level.** The reference plays at the gain that makes its loudness
 *    equal to the mix's (measured LUFS, not peak), because a louder mix always
 *    sounds better and the comparison must not be decided by level. Both are
 *    crossfaded with equal power so switching does not dip or bump.
 * 3. **No clicks.** Gain never jumps: the crossfade moves by a limited step per
 *    sample (a full switch takes [SWITCH_SECONDS]). This is the design of
 *    Lemon Studio's gain glide (`crates/os-dsp/src/glide.rs`: "gains do not
 *    jump, they move to a new target by a limited step per sample, planned once
 *    per block"), applied to a monitor bus instead of a fader.
 *
 * The reference bypasses the session's master chain on purpose: a commercial
 * master has already been mastered, and running it through this session's bus
 * compressor and limiter would compare two different things.
 *
 * Called from the render thread only, after the master stage. When the monitor
 * is idle (no reference loaded, crossfade at the mix end) [process] returns
 * immediately, so a session that never uses a reference pays nothing for it.
 */
class ReferenceMonitor(private val sampleRate: Int) {

    /** Interleaved stereo at the engine sample rate, or null when none is loaded. */
    @Volatile
    private var referenceSamples: FloatArray? = null

    @Volatile
    private var referenceFrames: Long = 0L

    /** Frames into the reference file that line up with the session's frame 0. */
    @Volatile
    private var offsetFrames: Long = 0L

    @Volatile
    private var auditionRequested = false

    @Volatile
    private var matchGainDb = 0f

    @Volatile
    var isAuditioning: Boolean = false
        private set

    /** Gain actually applied to the reference, for the UI to report. */
    @Volatile
    var appliedMatchGainDb: Float = 0f
        private set

    private var glide = 0f
    private var gainCurrent = 1f

    private val glideStep = (1.0 / (SWITCH_SECONDS * sampleRate)).toFloat()
    private val gainCoeff = (1.0 - kotlin.math.exp(-1.0 / (0.020 * sampleRate))).toFloat()

    val hasReference: Boolean get() = referenceFrames > 0L

    fun setReference(samples: FloatArray?, offset: Long = 0L) {
        referenceSamples = samples
        referenceFrames = if (samples == null) 0L else (samples.size / 2).toLong()
        offsetFrames = offset.coerceAtLeast(0L)
        if (samples == null) {
            auditionRequested = false
            glide = 0f
            isAuditioning = false
        }
    }

    fun setOffset(offset: Long) {
        offsetFrames = offset.coerceAtLeast(0L)
    }

    fun setAudition(enabled: Boolean) {
        auditionRequested = enabled && hasReference
    }

    fun setMatchGainDb(db: Float) {
        matchGainDb = db.coerceIn(-48f, 48f)
    }

    /** Current crossfade position: 0 = mix only, 1 = reference only. Visible for tests/UI. */
    val crossfade: Float get() = glide

    /**
     * Mixes the reference into [left] and [right] in place.
     *
     * [songStartFrame] is the transport position of the first frame of the
     * block. While [loopEnabled], the reference follows the loop: with the
     * transport inside `[loopStartFrame, loopEndFrame)` the reference plays
     * `offsetFrames + (position - loopStartFrame)`, so a looping section is
     * compared against the same length of the reference starting at the
     * configured offset.
     */
    fun process(
        left: FloatArray,
        right: FloatArray,
        frameCount: Int,
        songStartFrame: Long,
        loopEnabled: Boolean,
        loopStartFrame: Long,
        loopEndFrame: Long
    ) {
        val target = if (auditionRequested) 1f else 0f
        if (glide == target && glide == 0f) {
            isAuditioning = false
            return
        }
        val samples = referenceSamples
        if (samples == null || referenceFrames <= 0L) {
            glide = 0f
            isAuditioning = false
            return
        }

        val loopLength = loopEndFrame - loopStartFrame
        val looping = loopEnabled && loopLength > 0
        var frame = songStartFrame
        var i = 0
        while (i < frameCount) {
            // 1. Move the crossfade by at most one step (never a jump).
            if (glide < target) glide = (glide + glideStep).coerceAtMost(target)
            else if (glide > target) glide = (glide - glideStep).coerceAtLeast(target)

            // 2. Smoothed level match, so a new match gain never clicks either.
            val gainTarget = if (matchGainDb <= -48f) 0.001f else dbToLinear(matchGainDb)
            gainCurrent += (gainTarget - gainCurrent) * gainCoeff

            // 3. Equal-power split: the two sides sum to constant power, so the
            //    switch has no dip in the middle. Fully switched (the common
            //    case while auditioning) skips the trigonometry entirely.
            val refGain: Float
            val mixGain: Float
            if (glide >= 1f) {
                refGain = gainCurrent
                mixGain = 0f
            } else {
                refGain = sin(glide * HALF_PI) * gainCurrent
                mixGain = cos(glide * HALF_PI)
            }

            if (mixGain < 1f) {
                left[i] *= mixGain
                right[i] *= mixGain
            }

            if (glide > 0f) {
                // While looping, the reference window is [offset, offset+loopLength)
                // and advances with the transport's position inside the loop, so a
                // looped section is always compared against the same length of the
                // reference. Without a loop, positions line up one to one.
                val relative = if (looping) positiveMod(frame - loopStartFrame, loopLength) else frame
                val refFrame = offsetFrames + relative
                if (refFrame in 0 until referenceFrames) {
                    val idx = (refFrame * 2).toInt()
                    left[i] += samples[idx] * refGain
                    right[i] += samples[idx + 1] * refGain
                }
            }

            frame++
            i++
        }

        appliedMatchGainDb = matchGainDb
        isAuditioning = glide > 0f
    }

    /** Kotlin's `%` keeps the sign; a loop needs the positive remainder. */
    private fun positiveMod(value: Long, modulus: Long): Long {
        val r = value % modulus
        return if (r < 0L) r + modulus else r
    }

    companion object {
        /**
         * A full switch takes this long. Long enough to be click-free, short
         * enough to feel instant when hitting the button.
         */
        const val SWITCH_SECONDS = 0.045

        private const val HALF_PI = (PI / 2.0).toFloat()

        /**
         * How long a reference the engine keeps in memory for auditioning.
         * Long references are analysed in full; auditioning covers this window
         * from the configured offset (a phone is not a place for a 200 MB
         * float buffer).
         */
        const val MAX_AUDITION_SECONDS = 360
    }
}
