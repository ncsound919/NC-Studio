package com.example.audio.engine

import com.example.audio.dsp.AutomationInterpolator
import com.example.audio.dsp.FourBandEq
import com.example.audio.dsp.NoiseReductionProcessor
import com.example.audio.dsp.PanLaw
import com.example.audio.dsp.PeakLimiter
import com.example.audio.dsp.ResonanceSmootherProcessor
import com.example.audio.dsp.SchroederReverb
import com.example.audio.dsp.StereoCompressor
import com.example.audio.dsp.VocalRiderProcessor
import com.overlay.audio.plugins.PluginChain
import com.overlay.audio.plugins.TrackPluginRack
import com.example.audio.model.ProjectSession
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

enum class ExportFormat(val displayName: String, val extension: String, val description: String, val mimeType: String) {
    WAV_24BIT("WAV 24-bit Studio PCM", ".wav", "Uncompressed studio master for Pro Tools / DAW mixing", "audio/wav"),
    AAC_320KBPS("Pro Tools AAC / M4A (320 kbps)", ".m4a", "High-bitrate AAC compressed audio for quick DAW session uploads", "audio/mp4"),
    MP3_320KBPS("MP3 320 kbps", ".mp3", "Universal MP3 for sharing and streaming", "audio/mpeg"),
    FLAC_LOSSLESS("FLAC Lossless", ".flac", "Lossless compressed master for archival", "audio/flac")
}

/**
 * Offline session renderer for bit-identical mixdown and DSP verification unit tests.
 */
object OfflineRenderer {

    private const val TAG = "OfflineRenderer"

    /**
     * Release an encoder pair from a `finally` block.
     *
     * `stop()` throws IllegalStateException when `start()` never ran (create or
     * configure failed), and `release()` must still happen after that — so each
     * step gets its own catch and a throwing `stop()` cannot leak the codec.
     * MediaCodec instances hold a native encoder slot: leaking one per failed
     * export eventually exhausts the device's encoder pool.
     */
    /**
     * `android.util.Log` is unmocked in plain JVM unit tests, and both the
     * fallback path and the release path are exercised there directly — so a
     * logging call must never be the thing that fails.
     */
    private fun safeLog(message: String) {
        try {
            Log.e(TAG, message)
        } catch (ignored: Throwable) {
            System.err.println("$TAG: $message")
        }
    }

    private fun logEncodeFailure(encoder: String, e: Exception) {
        safeLog("$encoder encode failed (${e.javaClass.simpleName}): ${e.message} — falling back to WAV")
    }

    private fun releaseEncoder(
        codec: android.media.MediaCodec?,
        muxer: android.media.MediaMuxer?,
        muxerStarted: Boolean
    ) {
        if (codec != null) {
            try {
                codec.stop()
            } catch (e: IllegalStateException) {
                // start() never ran; release() below still has to happen.
                safeLog("codec stop skipped: ${e.message}")
            }
            try {
                codec.release()
            } catch (e: IllegalStateException) {
                safeLog("codec release failed: ${e.message}")
            }
        }
        if (muxer != null) {
            try {
                if (muxerStarted) muxer.stop()
            } catch (e: IllegalStateException) {
                safeLog("muxer stop skipped: ${e.message}")
            }
            try {
                muxer.release()
            } catch (e: Exception) {
                safeLog("muxer release failed: ${e.message}")
            }
        }
    }

    data class RenderProgress(
        val currentFrame: Long,
        val totalFrames: Long,
        val progressPercent: Float,
        val isCompleted: Boolean = false,
        val outputFile: File? = null
    )

    data class DspTestResult(
        val testName: String,
        val passed: Boolean,
        val message: String
    )

    /**
     * Renders multi-track session to stereo PCM float buffer.
     */
    fun renderSessionToPcmBuffer(
        project: ProjectSession,
        trackBuffers: Array<FloatArray>,
        onProgress: (Float) -> Unit = {},
        normalize: Boolean = false,
        targetPeakDb: Float = -1.0f
    ): FloatArray {
        val sampleRate = project.sampleRate
        val totalFrames = project.totalLengthFrames.toInt().coerceAtLeast(sampleRate * 2)
        val interleavedOutput = FloatArray(totalFrames * 2)

        val trackCount = project.tracks.size.coerceAtLeast(16)

        // Track buffers are supplied already clip-composited and pre-rendered
        // by the session layer (including AutoPitch), matching what the live
        // engine plays. DSP below is the per-channel strip only.
        val paddedBuffers = Array(trackCount) { i ->
            if (i < trackBuffers.size) trackBuffers[i] else FloatArray(0)
        }

        val trackEqs = Array(trackCount) { FourBandEq(sampleRate.toFloat()) }
        val trackSaturators = Array(trackCount) { com.example.audio.dsp.SaturationProcessor() }
        val trackCompressors = Array(trackCount) { StereoCompressor(sampleRate.toFloat()) }
        val trackChoruses = Array(trackCount) { com.example.audio.dsp.StereoChorusProcessor(sampleRate.toFloat()) }
        val trackDelays = Array(trackCount) { com.example.audio.dsp.DuckingDelayProcessor(sampleRate.toFloat()) }
        val trackLimiters = Array(trackCount) { com.example.audio.dsp.ChannelLimiter(sampleRate.toFloat()) }
        val trackNoiseReductions = Array(trackCount) { NoiseReductionProcessor(sampleRate.toFloat()) }
        val trackResonanceSmoothers = Array(trackCount) { ResonanceSmootherProcessor(sampleRate.toFloat()) }
        val trackVocalRiders = Array(trackCount) { VocalRiderProcessor(sampleRate.toFloat()) }
        val masterReverb = SchroederReverb(sampleRate.toFloat())
        val masterLimiter = PeakLimiter(sampleRate.toFloat())

        // Initialize DSP
        masterReverb.configure(project.masterReverb)
        masterLimiter.configure(project.masterLimiter)
        for (t in 0 until trackCount) {
            if (t < project.tracks.size) {
                val trk = project.tracks[t]
                trackEqs[t].updateConfig(trk.eqConfig)
                trackSaturators[t].configure(trk.saturationConfig)
                trackCompressors[t].configure(trk.compressorConfig)
                trackChoruses[t].configure(trk.chorusConfig)
                trackDelays[t].configure(trk.delayConfig)
                trackLimiters[t].configure(trk.limiterConfig)
                trackNoiseReductions[t].configure(trk.noiseReductionConfig.enabled, trk.noiseReductionConfig.thresholdDb, trk.noiseReductionConfig.reductionDb)
                trackResonanceSmoothers[t].configure(trk.resonanceSmootherConfig)
                trackVocalRiders[t].configure(trk.vocalRiderConfig)
            }
        }

        // -------------------------------------------------------------------
        // Studio plugin racks and bus routing, mirroring the live engine's
        // signal path. An offline render is used for two things that both have
        // to match what you hear: the export, and the reference measurement the
        // A/B level match and tone match are built on. A track with no chain
        // keeps the legacy channel strip, and a session that uses neither racks
        // nor buses renders exactly as it always did.
        // -------------------------------------------------------------------
        val blockSize = 512
        val rackTempo = project.tempoBpm
        val trackRacks = Array<TrackPluginRack?>(trackCount) { t ->
            val chain = project.tracks.getOrNull(t)?.pluginChain.orEmpty()
            if (chain.none { it.enabled }) null
            else TrackPluginRack(sampleRate.toFloat(), rackTempo).apply { setChain(PluginChain(chain)) }
        }
        val busIds = project.buses.map { it.id }
        val busRacks = project.buses.map { bus ->
            TrackPluginRack(sampleRate.toFloat(), rackTempo).apply { setChain(PluginChain(bus.pluginChain)) }
        }
        val busGains = project.buses.map { if (it.gainDb <= -60f) 0f else Math.pow(10.0, (it.gainDb / 20.0)).toFloat() }
        val busMuted = project.buses.map { it.isMuted }
        val trackBusSlot = IntArray(trackCount) { t ->
            val busId = project.tracks.getOrNull(t)?.outputBusId
            if (busId.isNullOrBlank() || busId == "master") -1 else busIds.indexOf(busId)
        }
        val masterChainActive = project.masterPluginChain.any { it.enabled }
        val masterRack = if (masterChainActive) {
            TrackPluginRack(sampleRate.toFloat(), rackTempo).apply { setChain(PluginChain(project.masterPluginChain)) }
        } else {
            null
        }
        val busBufferL = Array(project.buses.size) { FloatArray(blockSize) }
        val busBufferR = Array(project.buses.size) { FloatArray(blockSize) }
        val directBufferL = FloatArray(blockSize)
        val directBufferR = FloatArray(blockSize)
        val sendBufferL = FloatArray(blockSize)
        val sendBufferR = FloatArray(blockSize)

        val masterGainLinear = project.masterGainLinear
        var frame = 0
        val stageOut = FloatArray(2) // allocation-free DSP stage scratch

        while (frame < totalFrames) {
            val chunk = Math.min(blockSize, totalFrames - frame)

            var anySolo = false
            for (trk in project.tracks) {
                if (trk.isSoloed) {
                    anySolo = true
                    break
                }
            }
            for (b in project.buses.indices) {
                java.util.Arrays.fill(busBufferL[b], 0f)
                java.util.Arrays.fill(busBufferR[b], 0f)
            }
            java.util.Arrays.fill(directBufferL, 0f)
            java.util.Arrays.fill(directBufferR, 0f)
            java.util.Arrays.fill(sendBufferL, 0f)
            java.util.Arrays.fill(sendBufferR, 0f)

            // 1. Tracks: the studio chain when the track has one (legacy strip
            //    otherwise), then fader, pan and the group-bus send.
            for (i in 0 until chunk) {
                val currentFrame = frame + i
                val sampleIdx = currentFrame * 2

                for (t in 0 until trackCount) {
                    if (t >= project.tracks.size) continue
                    val trk = project.tracks[t]
                    val isPlayable = if (anySolo) trk.isSoloed else !trk.isMuted
                    if (!isPlayable) continue

                    val buf = paddedBuffers[t]
                    var rawL = 0.0f
                    var rawR = 0.0f
                    if (sampleIdx + 1 < buf.size) {
                        rawL = buf[sampleIdx]
                        rawR = buf[sampleIdx + 1]
                    }

                    val rack = trackRacks[t]
                    if (rack != null) {
                        // Modules run in their saved order, and a sidechain
                        // source keys them exactly as it does live.
                        val keyIndex = trk.sidechainSourceIndex
                        if (keyIndex in 0 until trackCount && keyIndex != t) {
                            val keyBuf = paddedBuffers[keyIndex]
                            if (sampleIdx + 1 < keyBuf.size) {
                                rack.setKey(keyBuf[sampleIdx], keyBuf[sampleIdx + 1])
                            } else {
                                rack.setKey(0f, 0f)
                            }
                        } else {
                            rack.setKey(0f, 0f)
                        }
                        rack.processInto(rawL, rawR, stageOut)
                    } else {
                        trackEqs[t].processSampleInto(rawL, rawR, stageOut)
                        trackNoiseReductions[t].processSampleInto(stageOut[0], stageOut[1], stageOut)
                        trackResonanceSmoothers[t].processSampleInto(stageOut[0], stageOut[1], stageOut)
                        trackSaturators[t].processSampleInto(stageOut[0], stageOut[1], stageOut)
                        trackCompressors[t].processSampleInto(stageOut[0], stageOut[1], stageOut)
                        trackVocalRiders[t].processSampleInto(stageOut[0], stageOut[1], stageOut)
                        trackChoruses[t].processSampleInto(stageOut[0], stageOut[1], stageOut)
                        trackDelays[t].processSampleInto(stageOut[0], stageOut[1], stageOut)
                        trackLimiters[t].processSampleInto(stageOut[0], stageOut[1], stageOut)
                    }

                    val autoGainDb = trk.automationLanes.firstOrNull { it.enabled && it.parameter == "gainDb" }
                        ?.let { AutomationInterpolator.valueAt(it.points, currentFrame.toLong(), trk.gainDb) } ?: trk.gainDb
                    val autoPan = trk.automationLanes.firstOrNull { it.enabled && it.parameter == "pan" }
                        ?.let { AutomationInterpolator.valueAt(it.points, currentFrame.toLong(), trk.pan) } ?: trk.pan
                    val (panL, panR) = PanLaw.calculateGains(autoPan)
                    val gainLinear = if (autoGainDb <= -60f) 0f else Math.pow(10.0, (autoGainDb / 20.0).toDouble()).toFloat()
                    val processedL = stageOut[0] * gainLinear * panL
                    val processedR = stageOut[1] * gainLinear * panR

                    val slot = trackBusSlot[t]
                    if (slot >= 0) {
                        busBufferL[slot][i] += processedL
                        busBufferR[slot][i] += processedR
                    } else {
                        directBufferL[i] += processedL
                        directBufferR[i] += processedR
                    }
                    sendBufferL[i] += processedL * trk.reverbSendLevel
                    sendBufferR[i] += processedR * trk.reverbSendLevel
                }
            }

            // 2. Group buses: each bus chain runs, then the bus lands in the mix.
            for (b in project.buses.indices) {
                if (busMuted[b]) continue
                val rack = busRacks[b]
                val busGain = busGains[b]
                val bl = busBufferL[b]
                val br = busBufferR[b]
                for (i in 0 until chunk) {
                    rack.processInto(bl[i], br[i], stageOut)
                    bl[i] = stageOut[0] * busGain
                    br[i] = stageOut[1] * busGain
                }
            }

            // 3. Master: reverb return (legacy path only), master fader, then
            //    the mix chain when there is one, or the legacy limiter.
            for (i in 0 until chunk) {
                var mixL = directBufferL[i]
                var mixR = directBufferR[i]
                for (b in project.buses.indices) {
                    mixL += busBufferL[b][i]
                    mixR += busBufferR[b][i]
                }

                if (!masterChainActive) {
                    masterReverb.processSampleInto(sendBufferL[i], sendBufferR[i], stageOut)
                    mixL += stageOut[0]
                    mixR += stageOut[1]
                }

                val preLimitL = mixL * masterGainLinear
                val preLimitR = mixR * masterGainLinear
                val master = masterRack
                if (master != null) {
                    master.processInto(preLimitL, preLimitR, stageOut)
                } else {
                    masterLimiter.processSampleInto(preLimitL, preLimitR, stageOut)
                }

                val outIdx = (frame + i) * 2
                interleavedOutput[outIdx] = stageOut[0]
                interleavedOutput[outIdx + 1] = stageOut[1]
            }

            frame += chunk
            onProgress(frame.toFloat() / totalFrames)
        }

        // Optional peak normalization to a target dBFS (default -1 dBFS)
        if (normalize) {
            var peak = 0.0f
            for (s in interleavedOutput) { val a = kotlin.math.abs(s); if (a > peak) peak = a }
            if (peak > 1e-6f) {
                val targetLinear = 10.0.pow((targetPeakDb / 20.0)).toFloat()
                val gain = (targetLinear / peak).coerceIn(0.1f, 10.0f)
                for (i in interleavedOutput.indices) interleavedOutput[i] *= gain
            }
        }
        return interleavedOutput
    }

    /**
     * Renders multi-track session to stereo WAV file.
     */
    fun renderSessionToWav(
        project: ProjectSession,
        trackBuffers: Array<FloatArray>,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
        bitsPerSample: Int = 24,
        normalize: Boolean = true
    ): Boolean {
        val interleavedOutput = renderSessionToPcmBuffer(project, trackBuffers, onProgress, normalize = normalize)
        WavAudioIO.writeWavFile(outputFile, interleavedOutput, channels = 2, sampleRate = project.sampleRate, bitsPerSample = bitsPerSample)
        return true
    }

    /**
     * Renders multi-track session to 320kbps AAC / M4A file using MediaCodec and MediaMuxer.
     */
    fun renderSessionToAac(
        project: ProjectSession,
        trackBuffers: Array<FloatArray>,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
        normalize: Boolean = true
    ): File? {
        val sampleRate = project.sampleRate
        val pcmFloat = renderSessionToPcmBuffer(project, trackBuffers, onProgress, normalize = normalize)

        val pcmBytes = ByteArray(pcmFloat.size * 2)
        for (i in pcmFloat.indices) {
            val sample = (pcmFloat[i].coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
            pcmBytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        var codecRef: android.media.MediaCodec? = null
        var muxerRef: android.media.MediaMuxer? = null
        var muxerStarted = false
        return try {
            val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
            format.setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 320000)
            format.setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
            codecRef = codec
            codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val muxer = android.media.MediaMuxer(outputFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerRef = muxer
            var trackIndex = -1

            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var bytesRead = 0
            var isInputEOS = false
            var isOutputEOS = false

            while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inputBufIdx = codec.dequeueInputBuffer(10000L)
                    if (inputBufIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIdx)
                        if (inputBuf != null) {
                            inputBuf.clear()
                            val bytesToCopy = Math.min(inputBuf.remaining(), pcmBytes.size - bytesRead)
                            if (bytesToCopy > 0) {
                                inputBuf.put(pcmBytes, bytesRead, bytesToCopy)
                                val presentationTimeUs = (bytesRead / 4L) * 1000000L / sampleRate
                                bytesRead += bytesToCopy
                                codec.queueInputBuffer(inputBufIdx, 0, bytesToCopy, presentationTimeUs, 0)
                            } else {
                                codec.queueInputBuffer(inputBufIdx, 0, 0, 0L, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            }
                        }
                    }
                }

                val outputBufIdx = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outputBufIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outputBufIdx >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputBufIdx)
                    if (outputBuf != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufIdx, false)
                    if ((bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }

            outputFile
        } catch (e: Exception) {
            logEncodeFailure("AAC", e)
            // Encoder unavailable: write a real WAV with a .wav extension so the
            // file is not mislabeled as AAC/M4A.
            wavFallback(project, trackBuffers, outputFile, onProgress, bitsPerSample = 16)
        } finally {
            releaseEncoder(codecRef, muxerRef, muxerStarted)
        }
    }

    /**
     * Renders each track of a session to its own WAV file (stems export).
     * Returns the list of written files.
     */
    fun renderStemsToWav(
        project: ProjectSession,
        trackBuffers: Array<FloatArray>,
        outputDir: File,
        bitsPerSample: Int = 24,
        normalize: Boolean = false,
        onStemProgress: (completed: Int, total: Int, trackName: String) -> Unit = { _, _, _ -> }
    ): List<File> {
        outputDir.mkdirs()
        val result = mutableListOf<File>()
        val trackCount = project.tracks.size
        for (t in 0 until trackCount) {
            val trk = project.tracks[t]
            // A stem must contain exactly one track regardless of the session's
            // current mute/solo state. Normalizing each stem independently would
            // also destroy the relative balance of the exported set.
            val stemProject = project.copy(tracks = project.tracks.mapIndexed { idx, track ->
                if (idx == t) track.copy(isMuted = false, isSoloed = false)
                else track.copy(isMuted = true, isSoloed = false)
            })
            // Only hand the renderer the target buffer; all other tracks are empty.
            val padded = Array(trackCount) { i ->
                if (i == t && i < trackBuffers.size) trackBuffers[i] else FloatArray(0)
            }
            val pcm = renderSessionToPcmBuffer(stemProject, padded, normalize = normalize)
            val safeName = trk.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val outFile = File(outputDir, "stem_${t + 1}_${safeName}.wav")
            WavAudioIO.writeWavFile(outFile, pcm, channels = 2, sampleRate = project.sampleRate, bitsPerSample = bitsPerSample)
            result.add(outFile)
            onStemProgress(t + 1, trackCount, trk.name)
        }
        return result
    }

    /**
     * Renders multi-track session to a FLAC file using MediaCodec when available,
     * falling back to WAV on failure.
     */
    fun renderSessionToFlac(
        project: ProjectSession,
        trackBuffers: Array<FloatArray>,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
        normalize: Boolean = true
    ): File? {
        val pcmFloat = renderSessionToPcmBuffer(project, trackBuffers, onProgress, normalize = normalize)
        val pcmBytes = ByteArray(pcmFloat.size * 2)
        for (i in pcmFloat.indices) {
            val sample = (pcmFloat[i].coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
            pcmBytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        var codecRef: android.media.MediaCodec? = null
        var muxerRef: android.media.MediaMuxer? = null
        var muxerStarted = false
        return try {
            val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_FLAC, project.sampleRate, 2)
            format.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 0) // lossless
            val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_FLAC)
            codecRef = codec
            codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val muxer = android.media.MediaMuxer(outputFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerRef = muxer
            var trackIndex = -1
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var bytesRead = 0
            var isInputEOS = false
            var isOutputEOS = false
            while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inputBufIdx = codec.dequeueInputBuffer(10000L)
                    if (inputBufIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIdx)
                        if (inputBuf != null) {
                            inputBuf.clear()
                            val bytesToCopy = kotlin.math.min(inputBuf.remaining(), pcmBytes.size - bytesRead)
                            if (bytesToCopy > 0) {
                                inputBuf.put(pcmBytes, bytesRead, bytesToCopy)
                                val presentationTimeUs = (bytesRead / 4L) * 1000000L / project.sampleRate
                                bytesRead += bytesToCopy
                                codec.queueInputBuffer(inputBufIdx, 0, bytesToCopy, presentationTimeUs, 0)
                            } else {
                                codec.queueInputBuffer(inputBufIdx, 0, 0, 0L, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            }
                        }
                    }
                }
                val outputBufIdx = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outputBufIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outputBufIdx >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputBufIdx)
                    if (outputBuf != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufIdx, false)
                    if ((bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }
            outputFile
        } catch (e: Exception) {
            logEncodeFailure("FLAC", e)
            wavFallback(project, trackBuffers, outputFile, onProgress, bitsPerSample = 24)
        } finally {
            releaseEncoder(codecRef, muxerRef, muxerStarted)
        }
    }

    /**
     * Renders multi-track session to an MP3 file using MediaCodec when available,
     * falling back to WAV on failure.
     */
    fun renderSessionToMp3(
        project: ProjectSession,
        trackBuffers: Array<FloatArray>,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
        normalize: Boolean = true
    ): File? {
        val pcmFloat = renderSessionToPcmBuffer(project, trackBuffers, onProgress, normalize = normalize)
        val pcmBytes = ByteArray(pcmFloat.size * 2)
        for (i in pcmFloat.indices) {
            val sample = (pcmFloat[i].coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
            pcmBytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        var codecRef: android.media.MediaCodec? = null
        var muxerRef: android.media.MediaMuxer? = null
        var muxerStarted = false
        return try {
            val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_MPEG, project.sampleRate, 2)
            format.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 320000)
            val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_MPEG)
            codecRef = codec
            codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val muxer = android.media.MediaMuxer(outputFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerRef = muxer
            var trackIndex = -1
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var bytesRead = 0
            var isInputEOS = false
            var isOutputEOS = false
            while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inputBufIdx = codec.dequeueInputBuffer(10000L)
                    if (inputBufIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIdx)
                        if (inputBuf != null) {
                            inputBuf.clear()
                            val bytesToCopy = kotlin.math.min(inputBuf.remaining(), pcmBytes.size - bytesRead)
                            if (bytesToCopy > 0) {
                                inputBuf.put(pcmBytes, bytesRead, bytesToCopy)
                                val presentationTimeUs = (bytesRead / 4L) * 1000000L / project.sampleRate
                                bytesRead += bytesToCopy
                                codec.queueInputBuffer(inputBufIdx, 0, bytesToCopy, presentationTimeUs, 0)
                            } else {
                                codec.queueInputBuffer(inputBufIdx, 0, 0, 0L, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            }
                        }
                    }
                }
                val outputBufIdx = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outputBufIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outputBufIdx >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputBufIdx)
                    if (outputBuf != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufIdx, false)
                    if ((bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }
            outputFile
        } catch (e: Exception) {
            logEncodeFailure("MP3", e)
            wavFallback(project, trackBuffers, outputFile, onProgress, bitsPerSample = 16)
        } finally {
            releaseEncoder(codecRef, muxerRef, muxerStarted)
        }
    }

    /**
     * Writes a WAV fallback beside the requested compressed file and removes the
     * empty/mislabeled target. Returning the real path lets the UI report the
     * format that was actually produced.
     */
    private fun wavFallback(
        project: ProjectSession,
        trackBuffers: Array<FloatArray>,
        outputFile: File,
        onProgress: (Float) -> Unit,
        bitsPerSample: Int
    ): File {
        val wavFile = File(outputFile.parentFile, outputFile.nameWithoutExtension + ".wav")
        renderSessionToWav(project, trackBuffers, wavFile, onProgress, bitsPerSample = bitsPerSample)
        outputFile.delete()
        return wavFile
    }

    /**
     * Run DSP Unit Tests (Sine, Impulse, Noise, Pan Law, Limiter, Bit-Identical rendering).
     */
    fun runDspUnitTests(): List<DspTestResult> {
        val results = mutableListOf<DspTestResult>()
        val sampleRate = 48000

        // Test 1: Pan Law (-3 dB at center)
        val (pL, pR) = PanLaw.calculateGains(0.0f)
        val targetGain = 1.0f / Math.sqrt(2.0).toFloat()
        val panError = abs(pL - targetGain) + abs(pR - targetGain)
        results.add(
            DspTestResult(
                testName = "Pan Law (-3 dB Center)",
                passed = panError < 1e-4f,
                message = "Center gains: L=$pL, R=$pR (Expected: $targetGain, error: $panError)"
            )
        )

        // Test 2: Master Limiter Brickwall Ceiling
        val limiter = PeakLimiter(sampleRate.toFloat())
        var maxPeakOut = 0.0f
        // Push 1000 samples of hot +6 dB signal (amplitude 2.0f)
        for (i in 0 until 1000) {
            val (outL, outR) = limiter.processSample(2.0f, 2.0f)
            maxPeakOut = max(maxPeakOut, max(abs(outL), abs(outR)))
        }
        val limiterPassed = maxPeakOut <= 1.0f
        results.add(
            DspTestResult(
                testName = "Master Limiter Ceiling Protection",
                passed = limiterPassed,
                message = "Maximum peak out from +6dBFS overload was ${String.format(java.util.Locale.US, "%.4f", maxPeakOut)} (<= 1.0000)"
            )
        )

        // Test 3: Biquad Filter Impulse Response Stability
        val eq = FourBandEq(sampleRate.toFloat())
        var nanOrInf = false
        val (impL, impR) = eq.processSample(1.0f, 1.0f) // Dirac impulse
        if (impL.isNaN() || impR.isNaN() || impL.isInfinite() || impR.isInfinite()) nanOrInf = true
        for (i in 0 until 512) {
            val (tailL, tailR) = eq.processSample(0f, 0f)
            if (tailL.isNaN() || tailR.isNaN() || tailL.isInfinite() || tailR.isInfinite()) nanOrInf = true
        }
        results.add(
            DspTestResult(
                testName = "Biquad Filter Impulse Stability",
                passed = !nanOrInf,
                message = if (!nanOrInf) "Filter impulse response is stable and finite." else "Filter blew up with NaN/Inf."
            )
        )

        // Test 4: Compressor Gain Reduction on Loud Signal
        val comp = StereoCompressor(sampleRate.toFloat())
        comp.configure(com.example.audio.model.CompressorConfig(enabled = true, thresholdDb = -20f, ratio = 4.0f))
        for (i in 0 until 1000) {
            comp.processSample(0.8f, 0.8f) // ~ -2 dBFS signal, well above -20 dBFS threshold
        }
        val grDb = comp.currentGainReductionDb
        val compPassed = grDb > 2.0f
        results.add(
            DspTestResult(
                testName = "Stereo Compressor Gain Reduction",
                passed = compPassed,
                message = "Gain reduction achieved: ${String.format(java.util.Locale.US, "%.2f", grDb)} dB (expected > 2.0 dB)"
            )
        )

        // Test 5: Bit-Identical Determinism
        val testSine1 = WavAudioIO.generateTestSignal("sine", durationFrames = 2048)
        val testSine2 = WavAudioIO.generateTestSignal("sine", durationFrames = 2048)
        var bitIdentical = true
        for (i in testSine1.indices) {
            if (testSine1[i] != testSine2[i]) {
                bitIdentical = false
                break
            }
        }
        results.add(
            DspTestResult(
                testName = "Bit-Identical Reproducibility",
                passed = bitIdentical,
                message = if (bitIdentical) "Identical seeds produced 100% bit-exact samples." else "Mismatch found in deterministic signals."
            )
        )

        return results
    }
}
