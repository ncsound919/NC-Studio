package com.example.session.take

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.audio.engine.AudioRingBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Records one take to a chunked take folder.
 *
 * Same shape as the app's local recorder — capture thread, lock-free ring
 * buffer, writer thread — but capture is float and 24-bit, the destination is
 * [ChunkedTakeWriter], and every captured block can be tapped for the
 * low-bitrate monitor stream. The tap never touches the take: the writer has
 * already consumed the block by the time the tap sees a copy.
 *
 * The take is valid without any network at all; upload is a separate stage.
 */
class TakeRecorder(
    private val store: TakeStore,
    private val audioManager: AudioManager? = null,
    private val sampleRate: Int = TakeConfig.DEFAULT_SAMPLE_RATE,
) {
    private val running = AtomicBoolean(false)
    private val frames = AtomicLong(0)

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var writerThread: Thread? = null
    private val ringBuffer = AudioRingBuffer(sampleRate * 4)

    @Volatile
    private var writer: ChunkedTakeWriter? = null

    /** Called on the writer thread with a private copy of each captured block. */
    @Volatile
    var monitorTap: ((FloatArray, Int) -> Unit)? = null

    val framesWritten: Long get() = frames.get()

    val isRecording: Boolean get() = running.get()

    val peakLevel: Float get() = writer?.peakLevel ?: 0f

    @SuppressLint("MissingPermission")
    fun start(config: TakeConfig): Boolean {
        if (running.get()) return false
        val takeWriter = ChunkedTakeWriter(store, config)
        if (!takeWriter.begin()) return false

        val inputFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(config.sampleRate)
            .setChannelMask(if (config.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val bufferBytes = AudioRecord.getMinBufferSize(
            config.sampleRate,
            if (config.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(MIN_BUFFER_BYTES)

        var record: AudioRecord? = null
        try {
            record = AudioRecord.Builder()
                .setAudioSource(pickAudioSource())
                .setAudioFormat(inputFormat)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                takeWriter.abort("mic unavailable")
                return false
            }
            record.startRecording()
        } catch (e: Exception) {
            runCatching { record?.release() }
            takeWriter.abort("mic start failed: ${e.message}")
            return false
        }

        audioRecord = record
        writer = takeWriter
        frames.set(0)
        ringBuffer.clear()
        running.set(true)

        recordThread = Thread({
            val block = FloatArray(CAPTURE_BLOCK_FRAMES)
            val recordRef = record
            try {
                while (running.get()) {
                    val read = recordRef.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) ringBuffer.write(block, 0, read)
                }
            } catch (_: Exception) {
                // A device yanking the mic mid-take stops capture but the chunks
                // already written stay valid; the stop path finalizes them.
            } finally {
                runCatching {
                    recordRef.stop()
                    recordRef.release()
                }
            }
        }, "Session-Capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        writerThread = Thread({
            val drain = FloatArray(WRITER_BLOCK_FRAMES)
            while (running.get() || ringBuffer.availableToRead() > 0) {
                val read = ringBuffer.read(drain, 0, drain.size)
                if (read <= 0) {
                    Thread.sleep(5)
                    continue
                }
                // If the writer fail-stops (a chunk could not be stored), stop
                // consuming; the take's promoted chunks stay recoverable.
                val wrote = runCatching { takeWriter.write(drain, read) }.getOrDefault(0)
                if (wrote <= 0) break
                frames.set(takeWriter.framesWritten)
                monitorTap?.let { tap ->
                    runCatching { tap(drain.copyOf(read), read) }
                }
            }
        }, "Session-TakeWriter").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        return true
    }

    /**
     * Stops capture and finalizes the take. Returns the manifest, or null when
     * nothing usable was captured.
     */
    fun stop(): TakeManifest? {
        if (!running.getAndSet(false)) return null
        runCatching {
            recordThread?.join(1_000)
            writerThread?.join(3_000)
        }
        recordThread = null
        writerThread = null
        audioRecord = null
        val takeWriter = writer
        writer = null
        return takeWriter?.finish()
    }

    /** Stops and leaves the promoted chunks for recovery instead of a manifest. */
    fun abort(reason: String) {
        if (!running.getAndSet(false)) return
        runCatching {
            recordThread?.join(1_000)
            writerThread?.join(3_000)
        }
        recordThread = null
        writerThread = null
        audioRecord = null
        val takeWriter = writer
        writer = null
        takeWriter?.abort(reason)
    }

    private fun pickAudioSource(): Int {
        // UNPROCESSED is the honest music capture path where the device allows
        // it (no AGC/NS); MIC is the universally available fallback.
        val supported = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (supported) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
    }

    companion object {
        private const val CAPTURE_BLOCK_FRAMES = 1024
        private const val WRITER_BLOCK_FRAMES = 2048
        private const val MIN_BUFFER_BYTES = 8192
    }
}
