package com.example.session.monitor

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Push-to-talk capture on the engineer's phone.
 *
 * It exists only while the engineer holds the talkback button: the artist hears
 * the engineer through their headphones, never through a speaker, and the mic
 * is released the moment the button comes up.
 */
class TalkbackCapture(
    private val sampleRate: Int = 48_000,
    private val onSamples: (FloatArray, Int) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    val isRunning: Boolean get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val bufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(8192)

        var record: AudioRecord? = null
        try {
            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                running.set(false)
                record.release()
                return false
            }
            record.startRecording()
        } catch (e: Exception) {
            running.set(false)
            runCatching { record?.release() }
            return false
        }

        val recordRef = record
        thread = Thread({
            val block = FloatArray(1024)
            try {
                while (running.get()) {
                    val read = recordRef.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) onSamples(block.copyOf(read), read)
                }
            } catch (_: Exception) {
            /* best-effort capture loop: a read failure must not kill the thread */ } finally {
                runCatching {
                    recordRef.stop()
                    recordRef.release()
                }
            }
        }, "Session-Talkback").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { thread?.join(500) }
        thread = null
    }
}
