package com.example.session.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The artist's backing track, played locally straight off the phone.
 *
 * Nothing about this path depends on the network: the backing track is copied
 * to the device before the session, playback is local, and the engineer's cue
 * level changes are just volume commands applied here.
 *
 * Playback position in frames is the session timeline the engineer schedules
 * against; the audio engine on this phone stays the timing authority.
 */
class CuePlayback(
    private val context: Context,
    private val sampleRate: Int = 48_000,
    private val countInBeats: Int = 4,
    private val beatFrames: Long = 24_000L, // 120 BPM at 48 kHz
) {
    private var player: MediaPlayer? = null
    private var clickTrack: AudioTrack? = null
    private var cueDb: Float = -6f
    private var timelineStartFrame: Long = 0L
    private var timelineStartedAtMs: Long = 0L
    private var clockRunning = false

    val countInFrames: Long get() = beatFrames * countInBeats

    fun load(uri: Uri): String? {
        release()
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(context, uri)
                prepare()
            }
            applyCueVolume()
            uri.lastPathSegment
        } catch (e: Exception) {
            release()
            null
        }
    }

    fun setCueLevelDb(db: Float) {
        cueDb = db.coerceIn(-60f, 6f)
        applyCueVolume()
    }

    /**
     * Starts the session clock at [frame]. The clock runs even when no backing
     * track is loaded: the count-in clicks and the engineer's scheduled start
     * both depend on a timeline, and a take must be possible with clicks only.
     */
    fun playFrom(frame: Long) {
        val start = frame.coerceAtLeast(0)
        timelineStartFrame = start
        timelineStartedAtMs = System.currentTimeMillis()
        clockRunning = true
        val media = player ?: return
        runCatching {
            media.seekTo(((start * 1000L) / sampleRate).toInt())
            media.start()
        }
    }

    fun pause() {
        clockRunning = false
        runCatching { player?.pause() }
        stopClicks()
    }

    fun stop() {
        clockRunning = false
        runCatching { player?.stop() }
        runCatching { player?.reset() }
        stopClicks()
    }

    fun release() {
        stop()
        runCatching { player?.release() }
        player = null
        runCatching { clickTrack?.release() }
        clickTrack = null
        clockRunning = false
        timelineStartFrame = 0L
        timelineStartedAtMs = 0L
    }

    /** Current timeline position in frames; runs on the wall clock, not media state. */
    fun positionFrames(): Long {
        if (!clockRunning) return 0L
        val elapsed = (System.currentTimeMillis() - timelineStartedAtMs).coerceAtLeast(0L)
        return timelineStartFrame + ((elapsed * sampleRate) / 1000L)
    }

    /** Plays [countInBeats] clicks as a pre-roll; safe to call without a track. */
    fun playCountIn() {
        val beatSamples = beatFrames.toInt()
        val click = FloatArray(beatSamples)
        val clickLength = (sampleRate * 0.03).toInt()
        for (i in 0 until clickLength) {
            val t = i.toDouble() / sampleRate
            click[i] = (sin(2.0 * PI * 1000.0 * t) * (1.0 - i.toDouble() / clickLength) * 0.35).toFloat()
        }
        val frames = beatSamples * countInBeats
        val buffer = FloatArray(frames)
        for (beat in 0 until countInBeats) {
            click.copyInto(buffer, beat * beatSamples, 0, clickLength)
        }
        val existing = clickTrack
        if (existing != null) {
            // A paused/flushed AudioTrack stays paused; without play() the
            // second and later count-ins would be silent.
            runCatching {
                existing.pause()
                existing.flush()
                existing.play()
                existing.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
            return
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(frames * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                clickTrack = it
                it.play()
            }
        runCatching { track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING) }
    }

    private fun applyCueVolume() {
        val linear = dbToLinear(cueDb)
        runCatching { player?.setVolume(linear, linear) }
    }

    private fun stopClicks() {
        runCatching { clickTrack?.pause()?.let { clickTrack?.flush() } }
    }

    companion object {
        fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
    }
}
