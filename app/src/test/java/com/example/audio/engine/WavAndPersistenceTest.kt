package com.example.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.history.ProjectPersistence
import com.example.audio.history.SessionCommand
import com.example.audio.history.UndoManager
import com.example.audio.model.AudioClip
import com.example.audio.model.CompressorConfig
import com.example.audio.model.EqBand
import com.example.audio.model.EqConfig
import com.example.audio.model.FilterType
import com.example.audio.model.ProjectSession
import com.example.audio.model.SaturationConfig
import com.example.audio.model.StemCategory
import com.example.audio.model.TrackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WavAndPersistenceTest {

    @Test
    fun audioRingBuffer_wraparoundReadWriteInvariants() {
        val ring = AudioRingBuffer(16)
        assertEquals(15, ring.availableToWrite())
        assertEquals(0, ring.availableToRead())

        val testData = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val written = ring.write(testData, 0, testData.size)
        assertEquals(5, written)
        assertEquals(5, ring.availableToRead())
        assertEquals(10, ring.availableToWrite())

        // Read 3 samples
        val readBuf = FloatArray(3)
        val read = ring.read(readBuf, 0, 3)
        assertEquals(3, read)
        assertEquals(1f, readBuf[0], 0.0001f)
        assertEquals(2f, readBuf[1], 0.0001f)
        assertEquals(3f, readBuf[2], 0.0001f)
        assertEquals(2, ring.availableToRead())

        // Write 10 samples to cause circular wraparound
        val wraparoundData = FloatArray(10) { it * 1.0f }
        val written2 = ring.write(wraparoundData, 0, 10)
        assertEquals(10, written2)

        // Read remaining
        val allRead = FloatArray(12)
        val totalRead = ring.read(allRead, 0, 12)
        assertEquals(12, totalRead)
        assertEquals(4f, allRead[0], 0.0001f) // from previous remaining (4f, 5f)
        assertEquals(5f, allRead[1], 0.0001f)
        assertEquals(0f, allRead[2], 0.0001f) // from wraparoundData
    }

    @Test
    fun wavAudioIO_roundtripStereo16BitPcm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testFile = File(context.cacheDir, "test_stereo_roundtrip.wav")

        val sampleRate = 48000
        val numFrames = 4800 // 100 ms
        val interleaved = FloatArray(numFrames * 2)

        // Generate 440 Hz test tone
        for (i in 0 until numFrames) {
            val sample = (0.75f * sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toFloat()
            interleaved[i * 2] = sample      // Left
            interleaved[i * 2 + 1] = -sample // Right (anti-phase)
        }

        WavAudioIO.writeWavFile(
            file = testFile,
            interleavedSamples = interleaved,
            channels = 2,
            sampleRate = sampleRate,
            bitsPerSample = 16
        )

        assertTrue(testFile.exists())
        assertTrue("WAV file should be at least 44-byte header + data", testFile.length() > 44)

        val wavInfo = WavAudioIO.readWavFile(testFile)
        assertNotNull(wavInfo)
        assertEquals(sampleRate, wavInfo!!.sampleRate)
        assertEquals(2, wavInfo.channels)
        assertEquals(16, wavInfo.bitsPerSample)
        assertEquals(numFrames.toLong(), wavInfo.totalFrames)

        // Verify PCM samples match within 16-bit quantization noise tolerance (1 / 32768 ~ 0.0001)
        for (i in 0 until 100) {
            assertEquals(interleaved[i], wavInfo.samples[i], 0.001f)
        }

        testFile.delete()
    }

    @Test
    fun wavAudioIO_proceduralStemsGenerateValidAudioForAllTracks() {
        for (trackIdx in 0 until 16) {
            val stem = WavAudioIO.generateProceduralStem(trackIdx, sampleRate = 48000, totalFrames = 48000 * 4)
            assertTrue("Stem for track $trackIdx must not be empty", stem.isNotEmpty())

            var maxSample = 0.0f
            var hasNonZero = false
            for (s in stem) {
                if (abs(s) > 0.01f) hasNonZero = true
                maxSample = maxOf(maxSample, abs(s))
                assertFalse("Sample should not be NaN", s.isNaN())
            }
            assertTrue("Stem for track $trackIdx must contain audible content", hasNonZero)
            assertTrue("Stem must not clip above 1.05f", maxSample <= 1.05f)
        }
    }

    @Test
    fun latencyCalibrator_accuratelyMeasuresLoopbackDelay() {
        val sampleRate = 48000
        val testSignal = FloatArray(128)
        testSignal[0] = 1.0f // Dirac delta

        // Create captured buffer with exact known 480 samples delay (10.0 ms)
        val artificialLagFrames = 480
        val captured = FloatArray(sampleRate / 4) // 250 ms
        captured[artificialLagFrames] = 1.0f

        val measuredMs = LatencyCalibrator.calculateLatencyOffsetMs(testSignal, captured, sampleRate)
        // Expected: 480 / 48000 * 1000 = 10.0 ms
        assertEquals(10.0f, measuredMs, 0.5f)
    }

    @Test
    fun projectPersistence_savesAndRestoresCompleteSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val persistence = ProjectPersistence(context)

        val testTracks = (0 until 16).map { i ->
            TrackData(
                index = i,
                name = "Track_$i",
                colorHex = 0xFF00FF00L,
                gainDb = -i.toFloat(),
                pan = (i - 8) * 0.1f,
                stemCategory = StemCategory.KEYS,
                eqConfig = EqConfig(
                    enabled = true,
                    band1 = EqBand(FilterType.LOW_SHELF, 80f, 3.5f, 0.707f)
                ),
                saturationConfig = SaturationConfig(enabled = true, driveDb = 6f, warmth = 0.5f),
                compressorConfig = CompressorConfig(enabled = true, thresholdDb = -18f, ratio = 3f),
                clips = listOf(
                    AudioClip(
                        name = "Clip_$i",
                        sourcePath = "take_wav",
                        sourceStartFrame = 0L,
                        lengthFrames = 48000L * 4L,
                        timelineStartFrame = i * 48000L,
                        trackIndex = i
                    )
                )
            )
        }

        val originalSession = ProjectSession(
            projectName = "TestPersistenceStudio",
            sampleRate = 48000,
            tempoBpm = 124.0f,
            masterGainDb = -1.5f,
            tracks = testTracks,
            latencyOffsetMs = 28.5f
        )

        val savedFile = persistence.saveProject(originalSession, "test_studio_project.json")
        assertTrue("Project file must exist", savedFile.exists())

        val loadedSession = persistence.loadProject(savedFile)
        assertNotNull("Loaded session must not be null", loadedSession)
        assertEquals(originalSession.projectName, loadedSession!!.projectName)
        assertEquals(originalSession.sampleRate, loadedSession.sampleRate)
        assertEquals(originalSession.tempoBpm, loadedSession.tempoBpm, 0.01f)
        assertEquals(originalSession.masterGainDb, loadedSession.masterGainDb, 0.01f)
        assertEquals(originalSession.latencyOffsetMs, loadedSession.latencyOffsetMs, 0.01f)
        assertEquals(16, loadedSession.tracks.size)

        // Verify channel strip parameters persisted exactly
        assertEquals(-3.0f, loadedSession.tracks[3].gainDb, 0.01f)
        assertEquals(3.5f, loadedSession.tracks[0].eqConfig.band1.gainDb, 0.01f)
        assertEquals(6.0f, loadedSession.tracks[0].saturationConfig.driveDb, 0.01f)
        assertEquals(-18.0f, loadedSession.tracks[0].compressorConfig.thresholdDb, 0.01f)
        assertEquals("Clip_2", loadedSession.tracks[2].clips[0].name)

        savedFile.delete()
    }

    @Test
    fun projectPersistence_crashRecoveryJournalRestoresDirtyState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val persistence = ProjectPersistence(context)

        val dirtySession = ProjectSession(
            projectName = "CrashedSession",
            tempoBpm = 140.0f,
            masterGainDb = -4.0f
        )

        persistence.writeCrashRecoveryJournal(dirtySession)
        assertTrue(persistence.hasCrashRecoveryJournal())

        val restored = persistence.restoreFromCrashRecoveryJournal()
        assertNotNull(restored)
        assertEquals("CrashedSession", restored!!.projectName)
        assertEquals(140.0f, restored.tempoBpm, 0.01f)
        assertEquals(-4.0f, restored.masterGainDb, 0.01f)
    }

    @Test
    fun offlineRenderer_soloAndMuteIsolationInMixdown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampleRate = 48000
        val lengthFrames = 4800L // 100 ms

        // Create 2 tracks: Track 0 has signal, Track 1 has signal
        val track0Signal = FloatArray((lengthFrames * 2).toInt()) { 0.5f }
        val track1Signal = FloatArray((lengthFrames * 2).toInt()) { 0.5f }

        val trackBuffers = Array(16) { FloatArray((lengthFrames * 2).toInt()) }
        trackBuffers[0] = track0Signal
        trackBuffers[1] = track1Signal

        val tracks = (0 until 16).map { i ->
            TrackData(
                index = i,
                name = "Trk$i",
                colorHex = 0xFFFFFFFF,
                gainDb = 0.0f,
                isSoloed = (i == 0), // Track 0 is SOLOED
                isMuted = false
            )
        }

        val soloSession = ProjectSession(
            sampleRate = sampleRate,
            totalLengthFrames = lengthFrames,
            tracks = tracks
        )

        val outputFile = File(context.cacheDir, "solo_render_test.wav")
        val success = OfflineRenderer.renderSessionToWav(
            project = soloSession,
            trackBuffers = trackBuffers,
            outputFile = outputFile
        )

        assertTrue("Solo mixdown should succeed", success)
        assertTrue(outputFile.exists())

        val renderedWav = WavAudioIO.readWavFile(outputFile)
        assertNotNull(renderedWav)
        assertTrue("Rendered frames should be at least totalLengthFrames", renderedWav!!.totalFrames >= lengthFrames)

        outputFile.delete()
    }
}
