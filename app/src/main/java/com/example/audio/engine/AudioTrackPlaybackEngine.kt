package com.example.audio.engine

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.example.audio.dsp.ChannelLimiter
import com.example.audio.dsp.DuckingDelayProcessor
import com.example.audio.dsp.FourBandEq
import com.example.audio.dsp.LufsMeter
import com.example.audio.dsp.NoiseReductionProcessor
import com.example.audio.dsp.PanLaw
import com.example.audio.dsp.ParameterSmoother
import com.example.audio.dsp.PeakLimiter
import com.example.audio.dsp.ResonanceSmootherProcessor
import com.example.audio.dsp.SaturationProcessor
import com.example.audio.dsp.SchroederReverb
import com.example.audio.dsp.StereoCompressor
import com.example.audio.dsp.VocalRiderProcessor
import com.example.audio.model.AudioTelemetry
import com.example.audio.model.ChannelLimiterConfig
import com.example.audio.model.CompressorConfig
import com.example.audio.model.DuckingDelayConfig
import com.example.audio.model.EqConfig
import com.example.audio.model.MasterLimiterConfig
import com.example.audio.model.ProjectSession
import com.example.audio.model.ResonanceSmootherConfig
import com.example.audio.model.ReverbConfig
import com.example.audio.model.SaturationConfig
import com.example.audio.model.VocalRiderConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * High-performance 16-track audio playback engine using low-latency AudioTrack.
 * Features parameter smoothing, 4-band EQ, compressor, reverb send, master limiter,
 * and timing telemetry.
 */
class AudioTrackPlaybackEngine(
    val sampleRate: Int = 48000,
    configuredBufferSizeFrames: Int = 512
) {

    companion object {
        const val MAX_TRACKS = 16
    }

    val commandQueue = AudioCommandQueue()

    // Configured engine block size (applied when the render loop initializes AudioTrack)
    @Volatile
    var configuredBufferSizeFrames: Int = configuredBufferSizeFrames
        private set

    val metronome = MetronomeProcessor(sampleRate.toFloat())

    private val isRunning = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val currentPlayheadFrame = AtomicLong(0L)
    private var renderThread: Thread? = null

    // Track buffers: 16 tracks, each holds interleaved stereo samples (or mono)
    private val trackCount = MAX_TRACKS
    private val trackBuffers = Array(trackCount) { FloatArray(0) }
    private val trackFrameCounts = LongArray(trackCount) { 0L }

    // DSP components per track
    private val trackGainsDb = FloatArray(trackCount) { 0.0f }
    private val trackPans = FloatArray(trackCount) { 0.0f }
    private val trackMuted = BooleanArray(trackCount) { false }
    private val trackSoloed = BooleanArray(trackCount) { false }
    private val trackReverbSends = FloatArray(trackCount) { 0.0f }

    private val gainSmoothers = Array(trackCount) { ParameterSmoother(1.0f, sampleRate.toFloat(), 15f) }
    private val panSmoothersL = Array(trackCount) { ParameterSmoother(0.707f, sampleRate.toFloat(), 15f) }
    private val panSmoothersR = Array(trackCount) { ParameterSmoother(0.707f, sampleRate.toFloat(), 15f) }
    private val sendSmoothers = Array(trackCount) { ParameterSmoother(0.0f, sampleRate.toFloat(), 15f) }

    private val trackEqs = Array(trackCount) { FourBandEq(sampleRate.toFloat()) }
    private val trackSaturators = Array(trackCount) { SaturationProcessor() }
    private val trackCompressors = Array(trackCount) { StereoCompressor(sampleRate.toFloat()) }
    private val trackChoruses = Array(trackCount) { com.example.audio.dsp.StereoChorusProcessor(sampleRate.toFloat()) }
    private val trackDelays = Array(trackCount) { DuckingDelayProcessor(sampleRate.toFloat()) }
    private val trackLimiters = Array(trackCount) { ChannelLimiter(sampleRate.toFloat()) }
    private val trackNoiseReductions = Array(trackCount) { NoiseReductionProcessor(sampleRate.toFloat()) }
    private val trackResonanceSmoothers = Array(trackCount) { ResonanceSmootherProcessor(sampleRate.toFloat()) }
    private val trackVocalRiders = Array(trackCount) { VocalRiderProcessor(sampleRate.toFloat()) }

    // Master bus DSP
    private var masterGainDb = 0.0f
    private val masterGainSmoother = ParameterSmoother(1.0f, sampleRate.toFloat(), 15f)
    private val masterReverb = SchroederReverb(sampleRate.toFloat())
    private val masterLimiter = PeakLimiter(sampleRate.toFloat())
    val lufsMeter = LufsMeter(sampleRate.toFloat())

    // Telemetry and meters
    val trackPeakLevels = FloatArray(trackCount) { 0.0f }
    val trackGainReductions = FloatArray(trackCount) { 0.0f }
    var masterPeakL: Float = 0.0f
    var masterPeakR: Float = 0.0f

    // Loop settings
    private var loopEnabled = true
    private var loopStartFrame = 0L
    private var loopEndFrame = sampleRate.toLong() * 16L
    private var totalSessionLengthFrames = sampleRate.toLong() * 16L

    // Click track state (mirrors metronome config for UI visibility)
    var clickBpm: Float = 120f
        private set
    var clickEnabled: Boolean = false
        private set
    var clickVolumeDb: Float = -6f
        private set

    // Performance telemetry
    private var underrunCount = 0
    private val callbackTimesMs = FloatArray(100)
    private var callbackTimeIdx = 0
    var latestTelemetry = AudioTelemetry()
        private set

    fun setTrackAudio(trackIndex: Int, samples: FloatArray) {
        if (trackIndex in 0 until trackCount) {
            trackBuffers[trackIndex] = samples
            trackFrameCounts[trackIndex] = (samples.size / 2).toLong()
            totalSessionLengthFrames = max(totalSessionLengthFrames, trackFrameCounts[trackIndex])
            if (loopEndFrame <= 0) loopEndFrame = totalSessionLengthFrames
        }
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        renderThread = Thread({
            runAudioLoop()
        }, "PulseDaw-RenderThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        isRunning.set(false)
        isPlaying.set(false)
        try {
            renderThread?.join(500)
        } catch (_: InterruptedException) {}
        renderThread = null
    }

    fun isEnginePlaying(): Boolean = isPlaying.get()
    fun getPlayheadPosition(): Long = currentPlayheadFrame.get()

    private fun buildAudioTrack(bufferFrames: Int): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val audioTrackBufferSize = max(minBufferSize, bufferFrames * 2 * 2 * 4)

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(audioTrackBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = trackBuilder.build()

        if (preferredDevice != null &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
        ) {
            track.preferredDevice = preferredDevice
        }
        return track
    }

    private fun runAudioLoop() {
        val bufferSizeFrames = configuredBufferSizeFrames.coerceIn(64, 4096)
        val audioTrackBufferSize = max(
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            bufferSizeFrames * 2 * 2 * 4
        )

        var audioTrack: AudioTrack? = null
        try {
            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(audioTrackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            audioTrack = trackBuilder.build()

            audioTrack.play()
            activeAudioTrack = audioTrack
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                preferredDevice?.let { audioTrack.preferredDevice = it }
            }

            // Pre-allocated scratch buffers to guarantee zero GC in callback
            val mixBufferL = FloatArray(bufferSizeFrames)
            val mixBufferR = FloatArray(bufferSizeFrames)
            val reverbSendBufferL = FloatArray(bufferSizeFrames)
            val reverbSendBufferR = FloatArray(bufferSizeFrames)
            val pcmOutputBytes = ShortArray(bufferSizeFrames * 2)
            val stageOut = FloatArray(2) // reusable stereo stage buffer

            var lastRenderTimeNs = System.nanoTime()

            while (isRunning.get()) {
                val cycleStartNs = System.nanoTime()

                // 1. Drain typed commands from queue
                drainCommands()

                // Check for underruns
                val currentUnderruns = audioTrack.underrunCount
                if (currentUnderruns > underrunCount) {
                    underrunCount = currentUnderruns
                }

                if (!isPlaying.get()) {
                    // Output silence smoothly
                    pcmOutputBytes.fill(0)
                    audioTrack.write(pcmOutputBytes, 0, pcmOutputBytes.size, AudioTrack.WRITE_BLOCKING)
                    Thread.sleep(8)
                    continue
                }

                // 2. Clear mix buffers
                mixBufferL.fill(0f)
                mixBufferR.fill(0f)
                reverbSendBufferL.fill(0f)
                reverbSendBufferR.fill(0f)

                val playhead = currentPlayheadFrame.get()
                var anySolo = false
                for (s in trackSoloed) {
                    if (s) {
                        anySolo = true
                        break
                    }
                }

                // 3. Render 16 tracks
                for (t in 0 until trackCount) {
                    val trackSamples = trackBuffers[t]
                    val trackFrames = trackFrameCounts[t]
                    val isMuted = trackMuted[t]
                    val isSoloed = trackSoloed[t]
                    val shouldPlay = if (anySolo) isSoloed else !isMuted

                    val gainSmoother = gainSmoothers[t]
                    val panSmootherL = panSmoothersL[t]
                    val panSmootherR = panSmoothersR[t]
                    val sendSmoother = sendSmoothers[t]
                    val eq = trackEqs[t]
                    val saturator = trackSaturators[t]
                    val comp = trackCompressors[t]
                    val chorus = trackChoruses[t]
                    val delay = trackDelays[t]
                    val limiter = trackLimiters[t]
                    val noiseReduction = trackNoiseReductions[t]
                    val resonanceSmoother = trackResonanceSmoothers[t]
                    val vocalRider = trackVocalRiders[t]

                    var trackPeak = 0.0f

                    for (i in 0 until bufferSizeFrames) {
                        val framePos = playhead + i
                        val gain = gainSmoother.next()
                        val panL = panSmootherL.next()
                        val panR = panSmootherR.next()
                        val sendLvl = sendSmoother.next()

                        var rawL = 0.0f
                        var rawR = 0.0f

                        if (trackFrames > 0 && framePos < trackFrames) {
                            val sampleIdx = (framePos * 2).toInt()
                            if (sampleIdx + 1 < trackSamples.size) {
                                rawL = trackSamples[sampleIdx]
                                rawR = trackSamples[sampleIdx + 1]
                            }
                        }

                        // 1. 4-Band Parametric EQ
                        eq.processSampleInto(rawL, rawR, stageOut)

                        // 2. Noise reduction / gate
                        noiseReduction.processSampleInto(stageOut[0], stageOut[1], stageOut)

                        // 3. Dynamic resonance suppression (de-harsh / de-ess band)
                        resonanceSmoother.processSampleInto(stageOut[0], stageOut[1], stageOut)

                        // 4. Tape / Tube Saturation
                        saturator.processSampleInto(stageOut[0], stageOut[1], stageOut)

                        // 5. Stereo Dynamic Compressor
                        comp.processSampleInto(stageOut[0], stageOut[1], stageOut)

                        // 6. Vocal rider / automatic leveler
                        vocalRider.processSampleInto(stageOut[0], stageOut[1], stageOut)

                        // 7. Stereo Chorus / Vocal Doubler
                        chorus.processSampleInto(stageOut[0], stageOut[1], stageOut)

                        // 8. Stereo Ducking Delay
                        delay.processSampleInto(stageOut[0], stageOut[1], stageOut)

                        // 9. Channel Brickwall Limiter
                        limiter.processSampleInto(stageOut[0], stageOut[1], stageOut)

                        val processedL = stageOut[0] * gain * panL
                        val processedR = stageOut[1] * gain * panR

                        val samplePeak = max(Math.abs(processedL), Math.abs(processedR))
                        if (samplePeak > trackPeak) trackPeak = samplePeak

                        if (shouldPlay) {
                            mixBufferL[i] += processedL
                            mixBufferR[i] += processedR

                            // Mute/Solo rule: sends are cut if track is muted or not soloed
                            reverbSendBufferL[i] += processedL * sendLvl
                            reverbSendBufferR[i] += processedR * sendLvl
                        }
                    }

                    trackPeakLevels[t] = trackPeak
                    trackGainReductions[t] = comp.currentGainReductionDb
                }

                // 4. Process Master Reverb Send & Return (+ sample-locked metronome click)
                for (i in 0 until bufferSizeFrames) {
                    masterReverb.processSampleInto(reverbSendBufferL[i], reverbSendBufferR[i], stageOut)
                    var finalL = mixBufferL[i] + stageOut[0]
                    var finalR = mixBufferR[i] + stageOut[1]

                    val click = metronome.processFrame(playhead + i)
                    if (click != 0f) {
                        finalL += click
                        finalR += click
                    }

                    mixBufferL[i] = finalL
                    mixBufferR[i] = finalR
                }

                // 5. Apply Master Fader & Master Limiter & LUFS metering
                var localMasterPeakL = 0.0f
                var localMasterPeakR = 0.0f

                for (i in 0 until bufferSizeFrames) {
                    val masterGain = masterGainSmoother.next()
                    val preLimitL = mixBufferL[i] * masterGain
                    val preLimitR = mixBufferR[i] * masterGain

                    masterLimiter.processSampleInto(preLimitL, preLimitR, stageOut)
                    val postLimitL = stageOut[0]
                    val postLimitR = stageOut[1]

                    lufsMeter.addSample(postLimitL, postLimitR)

                    val absL = Math.abs(postLimitL)
                    val absR = Math.abs(postLimitR)
                    if (absL > localMasterPeakL) localMasterPeakL = absL
                    if (absR > localMasterPeakR) localMasterPeakR = absR

                    // Convert to 16-bit PCM with clipping prevention
                    val pcmL = (postLimitL.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                    val pcmR = (postLimitR.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()

                    pcmOutputBytes[i * 2] = pcmL
                    pcmOutputBytes[i * 2 + 1] = pcmR
                }

                masterPeakL = localMasterPeakL
                masterPeakR = localMasterPeakR

                // 6. Write to AudioTrack
                audioTrack.write(pcmOutputBytes, 0, pcmOutputBytes.size, AudioTrack.WRITE_BLOCKING)

                // 7. Update playhead & loop
                var nextPlayhead = playhead + bufferSizeFrames
                if (loopEnabled && loopEndFrame > loopStartFrame && nextPlayhead >= loopEndFrame) {
                    nextPlayhead = loopStartFrame
                } else if (!loopEnabled && totalSessionLengthFrames > 0 && nextPlayhead >= totalSessionLengthFrames) {
                    nextPlayhead = 0L
                    isPlaying.set(false)
                }
                currentPlayheadFrame.set(nextPlayhead)

                // Telemetry timing calculation
                val cycleEndNs = System.nanoTime()
                val renderMs = (cycleEndNs - cycleStartNs) / 1_000_000f
                callbackTimesMs[callbackTimeIdx] = renderMs
                callbackTimeIdx = (callbackTimeIdx + 1) % callbackTimesMs.size

                if (callbackTimeIdx == 0) {
                    updateTelemetry(bufferSizeFrames)
                }
            }

            audioTrack.stop()
            audioTrack.release()
        } catch (e: Exception) {
            audioTrack?.release()
        }
    }

    private fun drainCommands() {
        var cmd = commandQueue.poll()
        while (cmd != null) {
            when (cmd) {
                is AudioEngineCommand.Play -> isPlaying.set(true)
                is AudioEngineCommand.Pause -> isPlaying.set(false)
                is AudioEngineCommand.Stop -> {
                    isPlaying.set(false)
                    currentPlayheadFrame.set(0L)
                }
                is AudioEngineCommand.Seek -> {
                    currentPlayheadFrame.set(cmd.targetFrame.coerceAtLeast(0L))
                }
                is AudioEngineCommand.SetGain -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) {
                        trackGainsDb[t] = cmd.gainDb
                        val linear = if (cmd.gainDb <= -60f) 0f else 10.0.pow(cmd.gainDb / 20.0).toFloat()
                        gainSmoothers[t].setTarget(linear)
                    }
                }
                is AudioEngineCommand.SetPan -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) {
                        trackPans[t] = cmd.pan
                        val (gainL, gainR) = PanLaw.calculateGains(cmd.pan)
                        panSmoothersL[t].setTarget(gainL)
                        panSmoothersR[t].setTarget(gainR)
                    }
                }
                is AudioEngineCommand.SetMute -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackMuted[t] = cmd.isMuted
                }
                is AudioEngineCommand.SetSolo -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackSoloed[t] = cmd.isSoloed
                }
                is AudioEngineCommand.SetReverbSend -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) {
                        trackReverbSends[t] = cmd.sendLevel
                        sendSmoothers[t].setTarget(cmd.sendLevel)
                    }
                }
                is AudioEngineCommand.SetEq -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackEqs[t].updateConfig(cmd.eqConfig)
                }
                is AudioEngineCommand.SetSaturation -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackSaturators[t].configure(cmd.config)
                }
                is AudioEngineCommand.SetCompressor -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackCompressors[t].configure(cmd.compressorConfig)
                }
                is AudioEngineCommand.SetChorus -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackChoruses[t].configure(cmd.config)
                }
                is AudioEngineCommand.SetDuckingDelay -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackDelays[t].configure(cmd.config)
                }
                is AudioEngineCommand.SetChannelLimiter -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackLimiters[t].configure(cmd.config)
                }
                is AudioEngineCommand.SetNoiseReduction -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) {
                        trackNoiseReductions[t].configure(
                            cmd.config.enabled,
                            cmd.config.thresholdDb,
                            cmd.config.reductionDb
                        )
                    }
                }
                is AudioEngineCommand.SetResonanceSmoother -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackResonanceSmoothers[t].configure(cmd.config)
                }
                is AudioEngineCommand.SetVocalRider -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackVocalRiders[t].configure(cmd.config)
                }
                is AudioEngineCommand.SetMasterGain -> {
                    masterGainDb = cmd.gainDb
                    val linear = if (cmd.gainDb <= -60f) 0f else 10.0.pow(cmd.gainDb / 20.0).toFloat()
                    masterGainSmoother.setTarget(linear)
                }
                is AudioEngineCommand.SetMasterLimiter -> masterLimiter.configure(cmd.config)
                is AudioEngineCommand.SetMasterReverb -> masterReverb.configure(cmd.config)
                is AudioEngineCommand.SetClickTrack -> {
                    metronome.configure(cmd.enabled, cmd.bpm, cmd.volumeDb, cmd.beatsPerBar)
                    clickBpm = cmd.bpm
                    clickEnabled = cmd.enabled
                    clickVolumeDb = cmd.volumeDb
                }
                is AudioEngineCommand.SetCountIn -> {
                    // Count-in uses the same click voice; transport-level scheduling
                    // (how many beats of pre-roll) is decided by the ViewModel layer.
                    clickBpm = cmd.bpm
                    metronome.configure(enabled = true, bpm = cmd.bpm, volumeDb = cmd.volumeDb)
                }
                is AudioEngineCommand.SetBufferSize -> {
                    if (cmd.frames in 64..4096 && cmd.frames != configuredBufferSizeFrames) {
                        configuredBufferSizeFrames = cmd.frames
                        // Rebuild AudioTrack on the render thread with the new size
                        try {
                            (activeAudioTrack)?.let { current ->
                                current.pause()
                                current.flush()
                                current.release()
                            }
                            activeAudioTrack = buildAudioTrack(configuredBufferSizeFrames)
                            activeAudioTrack?.play()
                        } catch (_: Exception) {
                            // Keep running with old buffer size on hardware failure
                        }
                    }
                }
                is AudioEngineCommand.SetLoopRange -> {
                    loopStartFrame = cmd.startFrame
                    loopEndFrame = cmd.endFrame
                    loopEnabled = cmd.enabled
                }
                is AudioEngineCommand.SetLatencyOffset -> {
                    // Handled in calibration telemetry
                }
                is AudioEngineCommand.BatchParameterSnapshot -> {
                    val mLinear = if (cmd.masterGainDb <= -60f) 0f else 10.0.pow(cmd.masterGainDb / 20.0).toFloat()
                    masterGainSmoother.setTarget(mLinear)
                    for (t in 0 until trackCount) {
                        val gLinear = if (cmd.tracksGainDb[t] <= -60f) 0f else 10.0.pow(cmd.tracksGainDb[t] / 20.0).toFloat()
                        gainSmoothers[t].setTarget(gLinear)
                        val (pL, pR) = PanLaw.calculateGains(cmd.tracksPan[t])
                        panSmoothersL[t].setTarget(pL)
                        panSmoothersR[t].setTarget(pR)
                        trackMuted[t] = cmd.tracksMuted[t]
                        trackSoloed[t] = cmd.tracksSoloed[t]
                        sendSmoothers[t].setTarget(cmd.tracksSend[t])
                    }
                }
            }
            cmd = commandQueue.poll()
        }
    }

    private fun updateTelemetry(bufferSizeFrames: Int) {
        val sorted = callbackTimesMs.clone().apply { sort() }
        val p50 = sorted[(sorted.size * 0.50).toInt()]
        val p95 = sorted[(sorted.size * 0.95).toInt()]
        val p99 = sorted[(sorted.size * 0.99).toInt()]
        val mean = sorted.average().toFloat()

        val bufferDurationMs = (bufferSizeFrames.toFloat() / sampleRate) * 1000f
        val cpuLoad = (mean / bufferDurationMs * 100f).coerceIn(0f, 100f)

        latestTelemetry = AudioTelemetry(
            callbackMeanMs = mean,
            callbackP50Ms = p50,
            callbackP95Ms = p95,
            callbackP99Ms = p99,
            bufferUnderruns = underrunCount,
            cpuLoadPercent = cpuLoad,
            activeVoices = trackCount,
            sampleRate = sampleRate,
            bufferSizeFrames = bufferSizeFrames
        )
    }

    fun syncFromProject(project: ProjectSession) {
        commandQueue.enqueue(AudioEngineCommand.SetMasterGain(project.masterGainDb))
        commandQueue.enqueue(AudioEngineCommand.SetMasterLimiter(project.masterLimiter))
        commandQueue.enqueue(AudioEngineCommand.SetMasterReverb(project.masterReverb))
        commandQueue.enqueue(
            AudioEngineCommand.SetLoopRange(
                project.loopStartFrame,
                project.loopEndFrame,
                project.loopEnabled
            )
        )
        for (track in project.tracks) {
            commandQueue.enqueue(AudioEngineCommand.SetGain(track.index, track.gainDb))
            commandQueue.enqueue(AudioEngineCommand.SetPan(track.index, track.pan))
            commandQueue.enqueue(AudioEngineCommand.SetMute(track.index, track.isMuted))
            commandQueue.enqueue(AudioEngineCommand.SetSolo(track.index, track.isSoloed))
            commandQueue.enqueue(AudioEngineCommand.SetReverbSend(track.index, track.reverbSendLevel))
            commandQueue.enqueue(AudioEngineCommand.SetEq(track.index, track.eqConfig))
            commandQueue.enqueue(AudioEngineCommand.SetSaturation(track.index, track.saturationConfig))
            commandQueue.enqueue(AudioEngineCommand.SetCompressor(track.index, track.compressorConfig))
            commandQueue.enqueue(AudioEngineCommand.SetChorus(track.index, track.chorusConfig))
            commandQueue.enqueue(AudioEngineCommand.SetDuckingDelay(track.index, track.delayConfig))
            commandQueue.enqueue(AudioEngineCommand.SetChannelLimiter(track.index, track.limiterConfig))
            commandQueue.enqueue(AudioEngineCommand.SetNoiseReduction(track.index, track.noiseReductionConfig))
            commandQueue.enqueue(AudioEngineCommand.SetResonanceSmoother(track.index, track.resonanceSmootherConfig))
            commandQueue.enqueue(AudioEngineCommand.SetVocalRider(track.index, track.vocalRiderConfig))
        }
    }

    private var preferredDevice: AudioDeviceInfo? = null
    private var activeAudioTrack: AudioTrack? = null

    fun setPreferredOutputDevice(deviceInfo: AudioDeviceInfo?) {
        preferredDevice = deviceInfo
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            activeAudioTrack?.preferredDevice = deviceInfo
        }
    }
}
