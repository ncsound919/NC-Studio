package com.example.audio.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.example.audio.model.AudioClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Handles audio file import (WAV, MP3, FLAC, AAC, OGG) via MediaExtractor and MediaCodec
 * into standard 48 kHz stereo floating-point audio.
 */
object AudioFileImporter {

    data class ImportResult(
        val clip: AudioClip,
        val samples: FloatArray,
        val sampleRate: Int,
        val originalFileName: String
    )

    suspend fun importAudioUri(
        context: Context,
        uri: Uri,
        targetTrackIndex: Int,
        targetTimelineStartFrame: Long = 0L
    ): ImportResult? = withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri) ?: "imported_track.wav"
        val importsDir = File(context.filesDir, "imported").apply { mkdirs() }
        val destWav = File(importsDir, "track_${targetTrackIndex}_${UUID.randomUUID().toString().take(8)}.wav")
        val targetSampleRate = 48000

        try {
            // First check if it's already a standard WAV file
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read == 12 && String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
                    // Direct copy WAV file
                    context.contentResolver.openInputStream(uri)?.use { fullIn ->
                        FileOutputStream(destWav).use { fullOut ->
                            fullIn.copyTo(fullOut)
                        }
                    }
                    val wavInfo = WavAudioIO.readWavFile(destWav)
                    if (wavInfo != null) {
                        // Normalize to session-rate stereo: prevents 44.1 kHz files from
                        // playing pitch-shifted, and mono files from collapsing to half-width.
                        val normalized = AudioResampler.normalizeToSessionStereo(
                            samples = wavInfo.samples,
                            srcChannels = wavInfo.channels,
                            srcRate = wavInfo.sampleRate,
                            dstRate = targetSampleRate
                        )
                        // Re-write the persisted file in canonical session format so
                        // sessions can be restored without resampling again.
                        WavAudioIO.writeWavFile(destWav, normalized, channels = 2, sampleRate = targetSampleRate, bitsPerSample = 16)
                        val frames = (normalized.size / 2).toLong()
                        val clip = AudioClip(
                            id = UUID.randomUUID().toString(),
                            name = fileName.substringBeforeLast("."),
                            sourcePath = destWav.absolutePath,
                            sourceStartFrame = 0L,
                            lengthFrames = frames,
                            timelineStartFrame = targetTimelineStartFrame,
                            trackIndex = targetTrackIndex
                        )
                        return@withContext ImportResult(
                            clip = clip,
                            samples = normalized,
                            sampleRate = targetSampleRate,
                            originalFileName = fileName
                        )
                    }
                }
            }

            // Decode compressed audio (MP3, M4A, FLAC, OGG) using MediaExtractor + MediaCodec
            val decoded = decodeWithMediaCodec(context, uri) ?: return@withContext null
            // Normalize decoded audio (which arrives at its native rate / layout)
            // to session-standard 48 kHz stereo before persisting.
            val normalized = AudioResampler.normalizeToSessionStereo(
                samples = decoded.samples,
                srcChannels = decoded.channels,
                srcRate = decoded.sampleRate,
                dstRate = targetSampleRate
            )
            WavAudioIO.writeWavFile(destWav, normalized, channels = 2, sampleRate = targetSampleRate, bitsPerSample = 16)

            val totalFrames = (normalized.size / 2).toLong()
            val clip = AudioClip(
                id = UUID.randomUUID().toString(),
                name = fileName.substringBeforeLast("."),
                sourcePath = destWav.absolutePath,
                sourceStartFrame = 0L,
                lengthFrames = totalFrames,
                timelineStartFrame = targetTimelineStartFrame,
                trackIndex = targetTrackIndex
            )

            ImportResult(
                clip = clip,
                samples = normalized,
                sampleRate = targetSampleRate,
                originalFileName = fileName
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Raw decode result at the file's native rate/channel layout.
     * Normalization to session format happens in [importAudioUri].
     */
    data class DecodedAudio(
        val samples: FloatArray,
        val sampleRate: Int,
        val channels: Int
    )

    private fun decodeWithMediaCodec(context: Context, uri: Uri): DecodedAudio? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) return null
            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val floatList = mutableListOf<Float>()
            var isEos = false

            val timeoutUs = 5000L

            while (!isEos) {
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEos = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                while (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val shortBuf = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val shortArray = ShortArray(shortBuf.remaining())
                        shortBuf.get(shortArray)

                        if (channels == 1) {
                            // Keep native mono layout; normalizeToSessionStereo handles upmix.
                            for (s in shortArray) {
                                floatList.add(s / 32768.0f)
                            }
                        } else {
                            for (s in shortArray) {
                                floatList.add(s / 32768.0f)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            return DecodedAudio(floatList.toFloatArray(), sampleRate, channels)
        } catch (e: Exception) {
            extractor.release()
            return null
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }
}
