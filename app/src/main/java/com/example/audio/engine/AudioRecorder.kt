package com.example.audio.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance audio recorder streaming from microphone through an AudioRingBuffer
 * to a background WAV file writer thread.
 */
class AudioRecorder(private val sampleRate: Int = 48000) {

    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var writerThread: Thread? = null

    // Ring buffer capacity: 4 seconds of mono audio
    private val ringBuffer = AudioRingBuffer(sampleRate * 4)
    private var currentOutputFile: File? = null
    /** Written by the writer thread, read by stopRecording's caller. */
    @Volatile
    private var totalRecordedFrames = 0L
    /** Counted down only after the WAV header has been finalised on disk. */
    private var writerDone = CountDownLatch(0)

    fun isCurrentlyRecording(): Boolean = isRecording.get()

    @SuppressLint("MissingPermission")
    fun startRecording(outputFile: File, channelConfig: Int = AudioFormat.CHANNEL_IN_MONO): Boolean {
        if (isRecording.get()) return false

        currentOutputFile = outputFile
        ringBuffer.clear()
        totalRecordedFrames = 0L
        writerDone = CountDownLatch(1)
        isRecording.set(true)

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = Math.max(minBufferSize, 2048)

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                isRecording.set(false)
                audioRecord.release()
                return false
            }

            audioRecord.startRecording()

            // 1. Thread: reads from AudioRecord into ring buffer
            recordThread = Thread({
                val pcmShorts = ShortArray(1024)
                val floatBuffer = FloatArray(1024)

                while (isRecording.get()) {
                    val readShorts = audioRecord.read(pcmShorts, 0, pcmShorts.size)
                    if (readShorts > 0) {
                        for (i in 0 until readShorts) {
                            floatBuffer[i] = pcmShorts[i] / 32768.0f
                        }
                        ringBuffer.write(floatBuffer, 0, readShorts)
                    }
                }

                try {
                    audioRecord.stop()
                    audioRecord.release()
                } catch (_: Exception) {/* best-effort audio cleanup: the resource is already unusable */ }
            }, "NCStudio-AudioRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            // 2. Thread: writes from ring buffer to WAV file
            val channels =
                if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            writerThread = Thread({
                try {
                    writeWavStream(outputFile, channels)
                } finally {
                    // Signals that the file on disk is complete, not merely
                    // that the writer stopped draining the ring.
                    writerDone.countDown()
                }
            }, "NCStudio-WavWriterThread").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }

            return true
        } catch (e: Exception) {
            isRecording.set(false)
            audioRecord?.release()
            return false
        }
    }

    /**
     * Stops capture and returns the number of frames written, or -1 when the
     * writer did not finish in time.
     *
     * The writer drains up to four seconds of ring buffer and then copies the
     * temp PCM into the final WAV. Joining it with a fixed 1 s timeout used to
     * return the stale frame count while the writer was still working, so the
     * caller deleted a valid take (or re-read and rewrote the file) underneath
     * it. Waiting on the writer's completion latch makes that impossible.
     */
    fun stopRecording(): Long {
        if (!isRecording.get()) return 0L
        isRecording.set(false)

        try {
            // Unblocks the capture thread; it is blocked in AudioRecord.read.
            recordThread?.join(1_500)
            val finished = writerDone.await(30, TimeUnit.SECONDS)
            if (!finished) {
                recordThread = null
                writerThread = null
                return -1L
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            recordThread = null
            writerThread = null
            return -1L
        }

        recordThread = null
        writerThread = null
        return totalRecordedFrames
    }

    private fun writeWavStream(file: File, channels: Int) {
        val tempPcmFile = File(file.parentFile, file.name + ".tmp")
        val drainBuffer = FloatArray(2048)
        val shortBuffer = ShortArray(2048)
        val byteBuffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)

        var recordedFramesCount = 0L

        FileOutputStream(tempPcmFile).use { fos ->
            while (isRecording.get() || ringBuffer.availableToRead() > 0) {
                val read = ringBuffer.read(drainBuffer, 0, drainBuffer.size)
                if (read > 0) {
                    byteBuffer.clear()
                    for (i in 0 until read) {
                        val sample = (drainBuffer[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                        shortBuffer[i] = sample
                        byteBuffer.putShort(sample)
                    }
                    fos.write(byteBuffer.array(), 0, read * 2)
                    recordedFramesCount += read
                } else {
                    Thread.sleep(10)
                }
            }
        }

        totalRecordedFrames = recordedFramesCount

        // Finalize standard WAV header with proper file length
        if (tempPcmFile.exists() && recordedFramesCount > 0) {
            finalizeWavFile(tempPcmFile, file, recordedFramesCount, sampleRate, channels)
            tempPcmFile.delete()
        } else {
            // Nothing usable was captured: never leave a stub WAV or temp file behind.
            tempPcmFile.delete()
            file.delete()
        }
    }

    private fun finalizeWavFile(
        pcmFile: File,
        wavFile: File,
        sampleCount: Long,
        sampleRate: Int,
        channels: Int,
    ) {
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        // RIFF stores 32-bit sizes. Cap at what the header can express instead of
        // silently wrapping a long take into a corrupt negative size.
        val maxDataSize = Int.MAX_VALUE.toLong() - 36L
        val dataSize = (sampleCount * bytesPerSample).coerceAtMost(maxDataSize)
        val totalFileSize = 36L + dataSize

        FileOutputStream(wavFile).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalFileSize.toInt())
            header.put("WAVE".toByteArray())

            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort()) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * channels * bytesPerSample)
            header.putShort((channels * bytesPerSample).toShort())
            header.putShort(bitsPerSample.toShort())

            header.put("data".toByteArray())
            header.putInt(dataSize.toInt())
            fos.write(header.array())

            pcmFile.inputStream().use { fis ->
                val buffer = ByteArray(4096)
                var remaining = dataSize
                while (remaining > 0) {
                    val bytesRead = fis.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (bytesRead <= 0) break
                    fos.write(buffer, 0, bytesRead)
                    remaining -= bytesRead
                }
            }
        }
    }
}

/**
 * Loopback latency calibrator: computes round-trip delay so overdubs line up frame-accurately.
 */
object LatencyCalibrator {

    /**
     * Cross-correlates test chirp/impulse with recorded capture to detect delay in samples and ms.
     */
    fun calculateLatencyOffsetMs(
        referenceChirp: FloatArray,
        capturedAudio: FloatArray,
        sampleRate: Int = 48000
    ): Float {
        if (capturedAudio.isEmpty() || referenceChirp.isEmpty()) return 32.5f // Default baseline offset

        var maxCrossCorr = 0.0f
        var bestLag = 0
        val searchWindow = Math.min(capturedAudio.size - referenceChirp.size, (sampleRate * 0.25).toInt()) // up to 250ms

        for (lag in 0 until Math.max(1, searchWindow)) {
            var corr = 0.0f
            val compareLen = Math.min(referenceChirp.size, 512)
            for (i in 0 until compareLen) {
                corr += referenceChirp[i] * capturedAudio[lag + i]
            }
            if (corr > maxCrossCorr) {
                maxCrossCorr = corr
                bestLag = lag
            }
        }

        val latencyMs = (bestLag.toFloat() / sampleRate) * 1000f
        return latencyMs.coerceIn(5.0f, 150.0f)
    }
}
