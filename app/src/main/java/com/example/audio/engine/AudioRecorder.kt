package com.example.audio.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private var totalRecordedFrames = 0L

    fun isCurrentlyRecording(): Boolean = isRecording.get()

    @SuppressLint("MissingPermission")
    fun startRecording(outputFile: File, channelConfig: Int = AudioFormat.CHANNEL_IN_MONO): Boolean {
        if (isRecording.get()) return false

        currentOutputFile = outputFile
        ringBuffer.clear()
        totalRecordedFrames = 0L
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
                } catch (_: Exception) {}
            }, "PulseDaw-AudioRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            // 2. Thread: writes from ring buffer to WAV file
            writerThread = Thread({
                writeWavStream(outputFile)
            }, "PulseDaw-WavWriterThread").apply {
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

    fun stopRecording(): Long {
        if (!isRecording.get()) return 0L
        isRecording.set(false)

        try {
            recordThread?.join(500)
            writerThread?.join(1000)
        } catch (_: InterruptedException) {}

        recordThread = null
        writerThread = null
        return totalRecordedFrames
    }

    private fun writeWavStream(file: File) {
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
            finalizeWavFile(tempPcmFile, file, recordedFramesCount, sampleRate)
            tempPcmFile.delete()
        }
    }

    private fun finalizeWavFile(pcmFile: File, wavFile: File, frames: Long, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val dataSize = frames * channels * bytesPerSample
        val totalFileSize = 36 + dataSize

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
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
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
