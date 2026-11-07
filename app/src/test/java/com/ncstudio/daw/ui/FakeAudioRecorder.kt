package com.ncstudio.daw.ui

import com.ncstudio.daw.audio.engine.AudioRecorder
import com.ncstudio.daw.audio.engine.WavAudioIO
import java.io.File

/**
 * Deterministic microphone stand-in. Robolectric's AudioRecord never delivers
 * usable frames, so every test of the record -> take path needs this instead of
 * hoping the shadow recorder produces audio.
 */
class FakeAudioRecorder(
    private val frames: Long = 96_000L,
    private val sampleRate: Int = 48_000
) : AudioRecorder(sampleRate) {

    var isStarted = false
        private set

    override fun startRecording(outputFile: File, channelConfig: Int): Boolean {
        isStarted = true
        outputFile.parentFile?.mkdirs()
        WavAudioIO.writeWavFile(
            file = outputFile,
            interleavedSamples = FloatArray((frames * 2).toInt()),
            channels = 2,
            sampleRate = sampleRate,
            bitsPerSample = 16
        )
        return true
    }

    override fun stopRecording(): Long = frames

    override fun isCurrentlyRecording(): Boolean = false
}
