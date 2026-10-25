package com.example.audio.engine

import com.example.audio.model.ChannelLimiterConfig
import com.example.audio.model.CompressorConfig
import com.example.audio.model.DuckingDelayConfig
import com.example.audio.model.EqConfig
import com.example.audio.model.MasterLimiterConfig
import com.example.audio.model.ReverbConfig
import com.example.audio.model.SaturationConfig
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Typed commands sent from UI/Worker threads to the real-time audio engine.
 */
sealed interface AudioEngineCommand {
    data object Play : AudioEngineCommand
    data object Pause : AudioEngineCommand
    data object Stop : AudioEngineCommand
    data class Seek(val targetFrame: Long) : AudioEngineCommand
    data class SetGain(val trackIndex: Int, val gainDb: Float) : AudioEngineCommand
    data class SetPan(val trackIndex: Int, val pan: Float) : AudioEngineCommand
    data class SetMute(val trackIndex: Int, val isMuted: Boolean) : AudioEngineCommand
    data class SetSolo(val trackIndex: Int, val isSoloed: Boolean) : AudioEngineCommand
    data class SetEq(val trackIndex: Int, val eqConfig: EqConfig) : AudioEngineCommand
    data class SetSaturation(val trackIndex: Int, val config: SaturationConfig) : AudioEngineCommand
    data class SetCompressor(val trackIndex: Int, val compressorConfig: CompressorConfig) : AudioEngineCommand
    data class SetChorus(val trackIndex: Int, val config: com.example.audio.model.ChorusConfig) : AudioEngineCommand
    data class SetDuckingDelay(val trackIndex: Int, val config: DuckingDelayConfig) : AudioEngineCommand
    data class SetChannelLimiter(val trackIndex: Int, val config: ChannelLimiterConfig) : AudioEngineCommand
    data class SetReverbSend(val trackIndex: Int, val sendLevel: Float) : AudioEngineCommand
    data class SetMasterGain(val gainDb: Float) : AudioEngineCommand
    data class SetMasterLimiter(val config: MasterLimiterConfig) : AudioEngineCommand
    data class SetMasterReverb(val config: ReverbConfig) : AudioEngineCommand
    data class SetLoopRange(val startFrame: Long, val endFrame: Long, val enabled: Boolean) : AudioEngineCommand
    data class SetLatencyOffset(val offsetMs: Float) : AudioEngineCommand
    data class BatchParameterSnapshot(
        val masterGainDb: Float,
        val tracksGainDb: FloatArray,
        val tracksPan: FloatArray,
        val tracksMuted: BooleanArray,
        val tracksSoloed: BooleanArray,
        val tracksSend: FloatArray
    ) : AudioEngineCommand
}

/**
 * Lock-free typed command queue for communicating with the audio render thread.
 */
class AudioCommandQueue {
    private val queue = ConcurrentLinkedQueue<AudioEngineCommand>()

    fun enqueue(command: AudioEngineCommand) {
        queue.offer(command)
    }

    fun poll(): AudioEngineCommand? {
        return queue.poll()
    }

    fun clear() {
        queue.clear()
    }
}
