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
import com.overlay.audio.plugins.PluginChain
import com.overlay.audio.plugins.TrackPluginRack
import com.example.audio.reference.ReferenceMonitor
import com.example.audio.model.BusData
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
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
        const val MAX_BUSES = 8
        const val MAX_ENGINE_BUFFER_FRAMES = 4096
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

    // Track buffers: 16 tracks, each holds interleaved stereo samples (or mono).
    // Samples and frame count are published together as one immutable object so
    // the render thread can never pair a new buffer with a stale length.
    private class TrackSource(val samples: FloatArray, val frames: Long)

    private val trackCount = MAX_TRACKS
    private val trackSources = AtomicReferenceArray<TrackSource?>(trackCount)
    private val emptySamples = FloatArray(0)

    // Buffer-size change requested from another thread. The render loop applies
    // it itself, so nothing ever writes to an AudioTrack it has already released.
    @Volatile
    private var pendingBufferSizeFrames: Int = -1

    // DSP components per track
    private val trackGainsDb = FloatArray(trackCount)
    private val trackPans = FloatArray(trackCount)
    private val trackMuted = BooleanArray(trackCount)
    private val trackSoloed = BooleanArray(trackCount)
    private val trackReverbSends = FloatArray(trackCount)

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

    /**
     * Studio plugin racks. When a track has an ordered chain, the rack replaces
     * the fixed legacy strip for that track; when it is empty the legacy strip
     * still runs, so old sessions and configs keep working unchanged.
     */
    private val trackRacks = Array(trackCount) { TrackPluginRack(sampleRate.toFloat()) }
    private val trackSidechainSource = IntArray(trackCount) { -1 }
    private val trackOutputBusIds = arrayOfNulls<String>(trackCount)
    private val masterRack = TrackPluginRack(sampleRate.toFloat())

    @Volatile
    private var masterChainActive = false

    /** A group bus: summed tracks, its own ordered chain, then the mix bus. */
    private inner class BusState(val id: String, val name: String) {
        var gainLinear = 1f
        var muted = false
        val rack = TrackPluginRack(sampleRate.toFloat())
        val bufferL = FloatArray(MAX_ENGINE_BUFFER_FRAMES)
        val bufferR = FloatArray(MAX_ENGINE_BUFFER_FRAMES)
    }

    private val busStates = ArrayList<BusState>(MAX_BUSES)
    private val trackBusSlot = IntArray(trackCount) { -1 }
    private val busProcessScratch = FloatArray(2)

    private fun refreshBusMapping() {
        for (t in 0 until trackCount) {
            val busId = trackOutputBusIds[t]
            trackBusSlot[t] = if (busId == null || busId == "master") -1
            else busStates.indexOfFirst { it.id == busId }
        }
    }

    // Master bus DSP
    private var masterGainDb = 0.0f
    private val masterGainSmoother = ParameterSmoother(1.0f, sampleRate.toFloat(), 15f)
    private val masterReverb = SchroederReverb(sampleRate.toFloat())
    private val masterLimiter = PeakLimiter(sampleRate.toFloat())
    val lufsMeter = LufsMeter(sampleRate.toFloat())

    // Telemetry and meters
    val trackPeakLevels = FloatArray(trackCount)
    val trackGainReductions = FloatArray(trackCount)
    /** Gain reduction of each group bus rack, indexed by bus slot. */
    val busGainReductions = FloatArray(MAX_BUSES)
    /** Gain reduction of the active mix bus chain (0 when the legacy limiter runs). */
    @Volatile
    var masterGainReductionDb: Float = 0f
    @Volatile
    var masterPeakL: Float = 0.0f

    @Volatile
    var masterPeakR: Float = 0.0f

    /**
     * Reference A/B audition. Runs after the master stage and is driven entirely
     * by commands, so nothing about it is touched when no reference is loaded.
     */
    private val referenceMonitor = ReferenceMonitor(sampleRate)

    // Loop settings
    private var loopEnabled = true
    private var loopStartFrame = 0L
    private var loopEndFrame = sampleRate.toLong() * 16L
    private val totalSessionLengthFrames = AtomicLong(sampleRate.toLong() * 16L)

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
    private val telemetryScratch = FloatArray(100)
    private var callbackTimeIdx = 0
    var latestTelemetry = AudioTelemetry()
        private set

    fun setTrackAudio(trackIndex: Int, samples: FloatArray) {
        if (trackIndex in 0 until trackCount) {
            val frames = (samples.size / 2).toLong()
            trackSources.set(trackIndex, TrackSource(samples, frames))
            totalSessionLengthFrames.accumulateAndGet(frames, ::maxOf)
        }
    }

    /**
     * Resets the playable length when a different project is opened. Track
     * pushes still grow it to the longest clip, but a shorter project must not
     * inherit the previous project's tail.
     */
    fun setSessionLength(frames: Long) {
        totalSessionLengthFrames.set(frames.coerceAtLeast(0L))
    }

    fun start() {
        if (isRunning.get()) return
        pendingBufferSizeFrames = -1
        isRunning.set(true)

        renderThread = Thread({
            runAudioLoop()
        }, "NCStudio-RenderThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        isRunning.set(false)
        isPlaying.set(false)
        try {
            renderThread?.join(500)
        } catch (_: InterruptedException) {/* best-effort audio cleanup: the track is already unusable */ }
        renderThread = null
    }

    /** Per-slot gain reduction for the rack UI (empty when no chain is loaded). */
    fun trackSlotGainReductions(trackIndex: Int): FloatArray {
        if (trackIndex !in 0 until trackCount) return FloatArray(0)
        val rack = trackRacks[trackIndex]
        if (rack.isEmpty) return FloatArray(0)
        return FloatArray(rack.size) { i -> rack.slotGainReductions().getOrElse(i) { 0f } }
    }

    fun isEnginePlaying(): Boolean = isPlaying.get()

    /** True while the render thread is alive (false after [stop] or a render failure). */
    fun isEngineRunning(): Boolean = isRunning.get()

    fun getPlayheadPosition(): Long = currentPlayheadFrame.get()

    /**
     * Opens and starts an AudioTrack for [bufferFrames]. Called on the render
     * thread only, which owns the instance and is the only code that releases it.
     */
    private fun openAudioTrack(bufferFrames: Int): AudioTrack {
        val track = buildAudioTrack(bufferFrames)
        track.play()
        activeAudioTrack = track
        return track
    }

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

        preferredDevice?.let { track.preferredDevice = it }
        return track
    }

    private fun runAudioLoop() {
        var bufferSizeFrames = configuredBufferSizeFrames.coerceIn(64, 4096)
        var audioTrack: AudioTrack? = null
        try {
            audioTrack = openAudioTrack(bufferSizeFrames)

            // Pre-allocated scratch buffers to guarantee zero GC in callback.
            // Only re-allocated when the block size changes.
            var mixBufferL = FloatArray(bufferSizeFrames)
            var mixBufferR = FloatArray(bufferSizeFrames)
            var reverbSendBufferL = FloatArray(bufferSizeFrames)
            var reverbSendBufferR = FloatArray(bufferSizeFrames)
            var pcmOutputBytes = ShortArray(bufferSizeFrames * 2)
            val stageOut = FloatArray(2) // reusable stereo stage buffer

            while (isRunning.get()) {
                val cycleStartNs = System.nanoTime()

                // 1. Drain typed commands from queue
                drainCommands()

                // 1b. Apply a pending buffer-size change here, on the only thread
                // that writes to the AudioTrack, so playback never writes into a
                // released instance.
                val requestedFrames = pendingBufferSizeFrames
                if (requestedFrames > 0 && requestedFrames != bufferSizeFrames) {
                    pendingBufferSizeFrames = -1
                    val rebuilt = try {
                        openAudioTrack(requestedFrames)
                    } catch (_: Exception) {
                        null
                    }
                    if (rebuilt != null) {
                        // Clear the published instance before releasing it, so a
                        // device callback cannot call setPreferredDevice on a
                        // released AudioTrack (that throws IllegalStateException
                        // on a binder thread and crashes the process).
                        activeAudioTrack = null
                        try {
                            audioTrack?.release()
                        } catch (_: Exception) {
                            // Old instance is unusable either way; keep the new one.
                        }
                        audioTrack = rebuilt
                        bufferSizeFrames = requestedFrames
                        mixBufferL = FloatArray(bufferSizeFrames)
                        mixBufferR = FloatArray(bufferSizeFrames)
                        reverbSendBufferL = FloatArray(bufferSizeFrames)
                        reverbSendBufferR = FloatArray(bufferSizeFrames)
                        pcmOutputBytes = ShortArray(bufferSizeFrames * 2)
                    }
                }

                // Check for underruns
                val currentUnderruns = audioTrack?.underrunCount ?: 0
                if (currentUnderruns > underrunCount) {
                    underrunCount = currentUnderruns
                }

                if (!isPlaying.get()) {
                    // Output silence smoothly
                    pcmOutputBytes.fill(0)
                    audioTrack?.write(pcmOutputBytes, 0, pcmOutputBytes.size, AudioTrack.WRITE_BLOCKING)
                    Thread.sleep(8)
                    continue
                }

                // 2. Clear mix buffers
                mixBufferL.fill(0f)
                mixBufferR.fill(0f)
                reverbSendBufferL.fill(0f)
                reverbSendBufferR.fill(0f)
                for (bus in busStates) {
                    java.util.Arrays.fill(bus.bufferL, 0, bufferSizeFrames, 0f)
                    java.util.Arrays.fill(bus.bufferR, 0, bufferSizeFrames, 0f)
                }

                val playhead = currentPlayheadFrame.get()
                val loopLength = loopEndFrame - loopStartFrame
                val loopActive = loopEnabled && loopLength > 0
                // Map a block-relative frame into the loop. The block may
                // straddle loopEndFrame, and the previous code kept rendering
                // past it and then reset the playhead: the overshoot was heard
                // twice at the loop head, so every pass was longer than the
                // loop and drifted against the metronome.
                fun positionAt(absolute: Long): Long =
                    if (loopActive && absolute >= loopEndFrame) {
                        loopStartFrame + ((absolute - loopEndFrame) % loopLength)
                    } else absolute
                var anySolo = false
                for (s in trackSoloed) {
                    if (s) {
                        anySolo = true
                        break
                    }
                }

                // 3. Render 16 tracks
                for (t in 0 until trackCount) {
                    val source = trackSources.get(t)
                    val trackSamples = source?.samples ?: emptySamples
                    val trackFrames = source?.frames ?: 0L
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
                    val rack = if (trackRacks[t].isEmpty) null else trackRacks[t]
                    val busSlot = trackBusSlot[t]
                    val bus = if (busSlot in 0 until busStates.size) busStates[busSlot] else null
                    val keyTrack = trackSidechainSource[t]

                    var trackPeak = 0.0f

                    for (i in 0 until bufferSizeFrames) {
                        val framePos = positionAt(playhead + i)
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

                        if (rack != null) {
                            // Studio insert chain: modules run in their saved order,
                            // which is what makes the chain sound like the reference.
                            if (keyTrack in 0 until trackCount) {
                                val keySource = trackSources.get(keyTrack)
                                if (keySource != null && framePos < keySource.frames) {
                                    val keyIdx = (framePos * 2).toInt()
                                    if (keyIdx + 1 < keySource.samples.size) {
                                        rack.setKey(keySource.samples[keyIdx], keySource.samples[keyIdx + 1])
                                    } else {
                                        rack.setKey(0f, 0f)
                                    }
                                } else {
                                    rack.setKey(0f, 0f)
                                }
                            }
                            rack.processInto(rawL, rawR, stageOut)
                        } else {
                            // Legacy fixed channel strip (sessions with no chain).
                            eq.processSampleInto(rawL, rawR, stageOut)
                            noiseReduction.processSampleInto(stageOut[0], stageOut[1], stageOut)
                            resonanceSmoother.processSampleInto(stageOut[0], stageOut[1], stageOut)
                            saturator.processSampleInto(stageOut[0], stageOut[1], stageOut)
                            comp.processSampleInto(stageOut[0], stageOut[1], stageOut)
                            vocalRider.processSampleInto(stageOut[0], stageOut[1], stageOut)
                            chorus.processSampleInto(stageOut[0], stageOut[1], stageOut)
                            delay.processSampleInto(stageOut[0], stageOut[1], stageOut)
                            limiter.processSampleInto(stageOut[0], stageOut[1], stageOut)
                        }

                        val processedL = stageOut[0] * gain * panL
                        val processedR = stageOut[1] * gain * panR

                        val samplePeak = max(Math.abs(processedL), Math.abs(processedR))
                        if (samplePeak > trackPeak) trackPeak = samplePeak

                        if (shouldPlay) {
                            if (bus != null) {
                                bus.bufferL[i] += processedL
                                bus.bufferR[i] += processedR
                            } else {
                                mixBufferL[i] += processedL
                                mixBufferR[i] += processedR
                            }

                            // Mute/Solo rule: sends are cut if track is muted or not soloed
                            reverbSendBufferL[i] += processedL * sendLvl
                            reverbSendBufferR[i] += processedR * sendLvl
                        }
                    }

                    trackPeakLevels[t] = trackPeak
                    trackGainReductions[t] = rack?.gainReductionDb ?: comp.currentGainReductionDb
                }

                // 3b. Group buses: run each bus chain, then sum it into the mix.
                for (busSlot in busStates.indices) {
                    val bus = busStates[busSlot]
                    if (bus.muted) continue
                    val busGain = bus.gainLinear
                    for (i in 0 until bufferSizeFrames) {
                        bus.rack.processInto(bus.bufferL[i], bus.bufferR[i], busProcessScratch)
                        mixBufferL[i] += busProcessScratch[0] * busGain
                        mixBufferR[i] += busProcessScratch[1] * busGain
                    }
                    busGainReductions[busSlot] = bus.rack.gainReductionDb
                }

                // 4. Process Master Reverb Send & Return (+ sample-locked metronome click)
                // With a mix bus chain active, the chain owns the bus processing
                // (reverb included), so the legacy send return steps aside.
                for (i in 0 until bufferSizeFrames) {
                    if (masterChainActive) {
                        stageOut[0] = 0f
                        stageOut[1] = 0f
                    } else {
                        masterReverb.processSampleInto(reverbSendBufferL[i], reverbSendBufferR[i], stageOut)
                    }
                    var finalL = mixBufferL[i] + stageOut[0]
                    var finalR = mixBufferR[i] + stageOut[1]

                    val click = metronome.processFrame(positionAt(playhead + i))
                    if (click != 0f) {
                        finalL += click
                        finalR += click
                    }

                    mixBufferL[i] = finalL
                    mixBufferR[i] = finalR
                }

                // 5. Apply Master Fader & Master Limiter
                for (i in 0 until bufferSizeFrames) {
                    val masterGain = masterGainSmoother.next()
                    val preLimitL = mixBufferL[i] * masterGain
                    val preLimitR = mixBufferR[i] * masterGain

                    if (masterChainActive) {
                        masterRack.processInto(preLimitL, preLimitR, stageOut)
                    } else {
                        masterLimiter.processSampleInto(preLimitL, preLimitR, stageOut)
                    }
                    mixBufferL[i] = stageOut[0]
                    mixBufferR[i] = stageOut[1]
                }

                // 5b. Reference A/B audition. The reference bypasses the master
                // chain (a commercial master has already been mastered); the mix
                // is crossfaded out as the reference fades in, level matched.
                referenceMonitor.process(
                    mixBufferL,
                    mixBufferR,
                    bufferSizeFrames,
                    playhead,
                    loopEnabled,
                    loopStartFrame,
                    loopEndFrame
                )

                // 5c. Metering and PCM conversion, on what actually reaches the output
                var localMasterPeakL = 0.0f
                var localMasterPeakR = 0.0f

                for (i in 0 until bufferSizeFrames) {
                    val postLimitL = mixBufferL[i]
                    val postLimitR = mixBufferR[i]

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
                masterGainReductionDb = if (masterChainActive) masterRack.gainReductionDb else 0f

                // 6. Write to AudioTrack
                val out = audioTrack ?: break
                out.write(pcmOutputBytes, 0, pcmOutputBytes.size, AudioTrack.WRITE_BLOCKING)

                // 7. Update playhead & loop
                val sessionFrames = totalSessionLengthFrames.get()
                var nextPlayhead = playhead + bufferSizeFrames
                if (loopActive && nextPlayhead >= loopEndFrame) {
                    nextPlayhead = loopStartFrame + ((nextPlayhead - loopEndFrame) % loopLength)
                } else if (!loopEnabled && sessionFrames > 0 && nextPlayhead >= sessionFrames) {
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

        } catch (_: Exception) {
            // A dead output device must not leave the engine wedged: the finally
            // block clears isRunning so a later start() can rebuild the output.
        } finally {
            // Unpublish before teardown: setPreferredOutputDevice reads this
            // field from the device-callback thread.
            activeAudioTrack = null
            try {
                audioTrack?.stop()
            } catch (_: Exception) {
            /* best-effort audio cleanup: the track is already unusable */ }
            try {
                audioTrack?.release()
            } catch (_: Exception) {
            /* best-effort audio cleanup: the track is already unusable */ }
            isRunning.set(false)
            isPlaying.set(false)
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
                is AudioEngineCommand.SetTrackPluginChain -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) {
                        trackRacks[t].setTempoBpm(clickBpm)
                        trackRacks[t].setChain(cmd.chain)
                    }
                }
                is AudioEngineCommand.SetTrackSidechainSource -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) trackSidechainSource[t] = cmd.sourceTrackIndex
                }
                is AudioEngineCommand.SetTrackOutputBus -> {
                    val t = cmd.trackIndex
                    if (t in 0 until trackCount) {
                        trackOutputBusIds[t] = cmd.busId
                        refreshBusMapping()
                    }
                }
                is AudioEngineCommand.SetMasterPluginChain -> {
                    masterRack.setTempoBpm(clickBpm)
                    masterRack.setChain(cmd.chain)
                    masterChainActive = cmd.chain.isActive
                }
                is AudioEngineCommand.SetBuses -> {
                    busStates.clear()
                    for (bus in cmd.buses.take(MAX_BUSES)) {
                        val state = BusState(bus.id, bus.name)
                        state.gainLinear = if (bus.gainDb <= -60f) 0f else 10.0.pow(bus.gainDb / 20.0).toFloat()
                        state.muted = bus.isMuted
                        state.rack.setTempoBpm(clickBpm)
                        cmd.busChains[bus.id]?.let { state.rack.setChain(it) }
                        busStates.add(state)
                    }
                    refreshBusMapping()
                }
                is AudioEngineCommand.SetMasterGain -> {
                    masterGainDb = cmd.gainDb
                    val linear = if (cmd.gainDb <= -60f) 0f else 10.0.pow(cmd.gainDb / 20.0).toFloat()
                    masterGainSmoother.setTarget(linear)
                }
                is AudioEngineCommand.SetReferenceTrack -> {
                    referenceMonitor.setReference(cmd.samples, cmd.offsetFrames)
                }
                is AudioEngineCommand.SetReferenceAudition -> {
                    referenceMonitor.setMatchGainDb(cmd.matchGainDb)
                    referenceMonitor.setAudition(cmd.enabled)
                }
                is AudioEngineCommand.SetReferenceOffset -> {
                    referenceMonitor.setOffset(cmd.offsetFrames)
                }
                is AudioEngineCommand.SetMasterLimiter -> masterLimiter.configure(cmd.config)
                is AudioEngineCommand.SetMasterReverb -> masterReverb.configure(cmd.config)
                is AudioEngineCommand.SetClickTrack -> {
                    metronome.configure(cmd.enabled, cmd.bpm, cmd.volumeDb, cmd.beatsPerBar)
                    clickBpm = cmd.bpm
                    clickEnabled = cmd.enabled
                    clickVolumeDb = cmd.volumeDb
                    // Grid-locked modules (delays) follow the session tempo.
                    for (rack in trackRacks) rack.setTempoBpm(cmd.bpm)
                    masterRack.setTempoBpm(cmd.bpm)
                    for (bus in busStates) bus.rack.setTempoBpm(cmd.bpm)
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
                        // Applied by the render loop, which owns the AudioTrack.
                        pendingBufferSizeFrames = cmd.frames
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
        // Runs on the render thread, so it reuses one scratch array and sums by
        // hand instead of allocating a copy and boxing an iterator.
        System.arraycopy(callbackTimesMs, 0, telemetryScratch, 0, callbackTimesMs.size)
        telemetryScratch.sort()
        val p50 = telemetryScratch[(telemetryScratch.size * 0.50).toInt()]
        val p95 = telemetryScratch[(telemetryScratch.size * 0.95).toInt()]
        val p99 = telemetryScratch[(telemetryScratch.size * 0.99).toInt()]
        var sum = 0f
        for (v in telemetryScratch) sum += v
        val mean = sum / telemetryScratch.size

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
            commandQueue.enqueue(AudioEngineCommand.SetTrackPluginChain(track.index, PluginChain(track.pluginChain)))
            commandQueue.enqueue(AudioEngineCommand.SetTrackSidechainSource(track.index, track.sidechainSourceIndex))
            commandQueue.enqueue(AudioEngineCommand.SetTrackOutputBus(track.index, track.outputBusId))
        }
        commandQueue.enqueue(
            AudioEngineCommand.SetBuses(
                project.buses,
                project.buses.associate { it.id to PluginChain(it.pluginChain) }
            )
        )
        commandQueue.enqueue(AudioEngineCommand.SetMasterPluginChain(PluginChain(project.masterPluginChain)))
    }

    // Written by the UI/USB thread and read by the render thread (and vice versa
    // when the render thread opens a new AudioTrack), so publish them safely.
    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    fun setPreferredOutputDevice(deviceInfo: AudioDeviceInfo?) {
        preferredDevice = deviceInfo
        // The render thread can release the track between the read and the
        // setter when a buffer-size change or teardown lands at the same time.
        // This runs on the system's AudioDeviceCallback thread with no outer
        // try/catch, so swallow the "not initialized" race here; the render
        // thread applies `preferredDevice` when it builds the next track.
        try {
            activeAudioTrack?.preferredDevice = deviceInfo
        } catch (_: IllegalStateException) {
            /* track was released concurrently; next build picks it up */
        }
    }
}
