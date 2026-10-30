package com.example.session.calibration

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.example.audio.engine.AudioRingBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays the calibration chirp out of the current route and records it back in,
 * so [LoopbackCalibration] can measure the round-trip offset.
 *
 * The artist should run this with the session headphones connected; the offset
 * is stored per route and re-measured whenever the route changes.
 */
class LoopbackMeasurer(private val sampleRate: Int = 48_000) {

    @SuppressLint("MissingPermission")
    fun measure(): Int? {
        val chirp = LoopbackCalibration.chirp(sampleRate)
        val totalFrames = chirp.size + sampleRate * 2 // 1 s pre-roll, 1 s tail
        val capture = FloatArray(totalFrames)
        val ring = AudioRingBuffer(sampleRate * 4)
        val running = AtomicBoolean(true)

        val minRecordBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(8192)

        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        var captureThread: Thread? = null
        try {
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minRecordBytes)
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return null
            recorder.startRecording()

            captureThread = Thread({
                val block = FloatArray(1024)
                val recordRef = recorder
                while (running.get()) {
                    val read = recordRef.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) ring.write(block, 0, read)
                }
            }, "Session-CalibrationCapture").apply { start() }

            Thread.sleep(300) // let the capture path settle

            val minPlayBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            ).coerceAtLeast(8192)
            player = AudioTrack.Builder()
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
                .setBufferSizeInBytes(minPlayBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            player.play()
            player.write(chirp, 0, chirp.size, AudioTrack.WRITE_BLOCKING)

            // Record the full window (pre-roll + chirp + tail).
            val drain = FloatArray(2048)
            var captured = 0
            val deadline = System.currentTimeMillis() + 4_000
            while (captured < totalFrames && System.currentTimeMillis() < deadline) {
                val read = ring.read(drain, 0, minOf(drain.size, totalFrames - captured))
                if (read > 0) {
                    drain.copyInto(capture, captured, 0, read)
                    captured += read
                } else {
                    Thread.sleep(5)
                }
            }

            val reference = chirp
            return LoopbackCalibration.estimateOffsetFrames(
                reference = reference,
                captured = capture.copyOf(captured.coerceAtMost(totalFrames)),
                sampleRate = sampleRate,
            )
        } catch (e: Exception) {
            return null
        } finally {
            running.set(false)
            runCatching { captureThread?.join(500) }
            runCatching {
                recorder?.stop()
                recorder?.release()
            }
            runCatching {
                player?.stop()
                player?.release()
            }
        }
    }
}
