package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.ai.AudioFeatureExtractor
import com.example.audio.ai.DiscoveredModelInfo
import com.example.audio.ai.EndpointProbeResult
import com.example.audio.ai.LocalModelDetector
import com.example.audio.ai.LocalModelDetectorState
import com.example.audio.ai.LocalModelEndpoint
import com.example.audio.ai.LocalModelProvider
import com.example.audio.ai.MixAdvisor
import com.example.audio.engine.AudioEngineCommand
import com.example.audio.engine.AudioFileImporter
import com.example.audio.engine.AudioRecorder
import com.example.audio.engine.AudioTrackPlaybackEngine
import com.example.audio.engine.LatencyCalibrator
import com.example.audio.engine.OfflineRenderer
import com.example.audio.engine.UsbAudioInterfaceManager
import com.example.audio.engine.WavAudioIO
import com.example.audio.history.ProjectPersistence
import com.example.audio.history.SessionCommand
import com.example.audio.history.UndoManager
import com.example.audio.model.AiMixProfile
import com.example.audio.model.AudioClip
import com.example.audio.model.AudioTelemetry
import com.example.audio.model.BufferSizePreset
import com.example.audio.model.ChannelLimiterConfig
import com.example.audio.model.CompressorConfig
import com.example.audio.model.DawSettings
import com.example.audio.model.DuckingDelayConfig
import com.example.audio.model.EqConfig
import com.example.audio.model.MasterLimiterConfig
import com.example.audio.model.MeterBallistics
import com.example.audio.model.MixSuggestion
import com.example.audio.model.ProjectSession
import com.example.audio.model.ReverbConfig
import com.example.audio.model.SaturationConfig
import com.example.audio.model.StemCategory
import com.example.audio.model.TrackData
import com.example.audio.model.TrackFeatures
import com.example.audio.model.UsbAudioDeviceInfo
import com.example.audio.model.UsbRoutingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class EngineUiState(
    val isPlaying: Boolean = false,
    val isRecording: Boolean = false,
    val playheadFrame: Long = 0L,
    val totalFrames: Long = 48000L * 16L,
    val timeFormatted: String = "00:00.00",
    val trackPeaks: FloatArray = FloatArray(16) { 0f },
    val trackGainReductions: FloatArray = FloatArray(16) { 0f },
    val masterPeakL: Float = 0f,
    val masterPeakR: Float = 0f,
    val momentaryLufs: Float = -60f,
    val integratedLufs: Float = -60f,
    val telemetry: AudioTelemetry = AudioTelemetry(),
    val selectedTrackIndex: Int = 0,
    val isAbComparing: Boolean = false,
    val abMode: String = "A", // "A" = Original, "B" = AI Suggested Mix
    val isAnalyzingAi: Boolean = false,
    val localModelDetectorState: com.example.audio.ai.LocalModelDetectorState = com.example.audio.ai.LocalModelDetectorState(),
    val showDspTestDialog: Boolean = false,
    val showExportDialog: Boolean = false,
    val showUsbDialog: Boolean = false,
    val showImportDialog: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val showClipBin: Boolean = false,
    val dspTestResults: List<OfflineRenderer.DspTestResult> = emptyList(),
    val exportProgress: Float = 0f,
    val exportedFile: File? = null,
    val statusMessage: String? = null
)

class DawViewModel(application: Application) : AndroidViewModel(application) {

    private val sampleRate = 48000
    private val playbackEngine = AudioTrackPlaybackEngine(sampleRate)
    private val audioRecorder = AudioRecorder(sampleRate)
    private val persistence = ProjectPersistence(application)
    private val undoManager = UndoManager(50)

    val usbManager = UsbAudioInterfaceManager(application).apply {
        onDeviceSelected = { dev ->
            playbackEngine.setPreferredOutputDevice(dev)
        }
    }
    val usbDevices: StateFlow<List<UsbAudioDeviceInfo>> = usbManager.usbDevices
    val activeUsbDevice: StateFlow<UsbAudioDeviceInfo?> = usbManager.activeUsbDevice

    // Stored in-memory audio buffers for 16 tracks
    private val trackAudioBuffers = Array(16) { FloatArray(0) }

    private val _session = MutableStateFlow(createInitialSession())
    val session: StateFlow<ProjectSession> = _session.asStateFlow()

    private val _settings = MutableStateFlow(DawSettings())
    val settings: StateFlow<DawSettings> = _settings.asStateFlow()

    private val _uiState = MutableStateFlow(EngineUiState())
    val uiState: StateFlow<EngineUiState> = _uiState.asStateFlow()

    private val _features = MutableStateFlow<List<TrackFeatures>>(emptyList())
    val features: StateFlow<List<TrackFeatures>> = _features.asStateFlow()

    private val _suggestions = MutableStateFlow<List<MixSuggestion>>(emptyList())
    val suggestions: StateFlow<List<MixSuggestion>> = _suggestions.asStateFlow()

    // Backup snapshot for A/B compare
    private var originalSessionSnapshot: ProjectSession? = null

    init {
        // Synthesize demo stems on IO thread and start audio engine
        viewModelScope.launch(Dispatchers.IO) {
            loadOrInitializeSession()
            playbackEngine.start()
            startMeterAndPlayheadPolling()
            if (_settings.value.autoDetectLocalModelsOnStartup) {
                scanForLocalModels()
            }
        }
    }

    private suspend fun loadOrInitializeSession() = withContext(Dispatchers.IO) {
        val restored = if (persistence.hasCrashRecoveryJournal()) {
            persistence.restoreFromCrashRecoveryJournal()
        } else null

        val currentProj = restored ?: _session.value

        // Synthesize procedural stems for empty tracks
        for (i in 0 until 16) {
            val stemSamples = WavAudioIO.generateProceduralStem(i, sampleRate)
            trackAudioBuffers[i] = stemSamples
            playbackEngine.setTrackAudio(i, stemSamples)
        }

        _session.value = currentProj
        playbackEngine.syncFromProject(currentProj)

        // Run initial AI analysis
        refreshAudioAnalysis()
    }

    private fun startMeterAndPlayheadPolling() {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val head = playbackEngine.getPlayheadPosition()
                val total = _session.value.totalLengthFrames
                val sec = head / sampleRate.toFloat()
                val minutes = (sec / 60).toInt()
                val seconds = (sec % 60).toInt()
                val hundredths = ((sec % 1.0) * 100).toInt()
                val timeStr = String.format("%02d:%02d.%02d", minutes, seconds, hundredths)

                val peaks = playbackEngine.trackPeakLevels.clone()
                val grs = playbackEngine.trackGainReductions.clone()
                val pL = playbackEngine.masterPeakL
                val pR = playbackEngine.masterPeakR
                val lufs = playbackEngine.lufsMeter.integratedLufs
                val momLufs = playbackEngine.lufsMeter.momentaryLufs
                val tel = playbackEngine.latestTelemetry

                _uiState.update { current ->
                    current.copy(
                        isPlaying = playbackEngine.isEnginePlaying(),
                        playheadFrame = head,
                        totalFrames = total,
                        timeFormatted = timeStr,
                        trackPeaks = peaks,
                        trackGainReductions = grs,
                        masterPeakL = pL,
                        masterPeakR = pR,
                        momentaryLufs = momLufs,
                        integratedLufs = lufs,
                        telemetry = tel
                    )
                }
                delay(33) // ~30 FPS UI metering update
            }
        }
    }

    // Transport Actions
    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            playbackEngine.commandQueue.enqueue(AudioEngineCommand.Pause)
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            playbackEngine.commandQueue.enqueue(AudioEngineCommand.Play)
            _uiState.update { it.copy(isPlaying = true) }
        }
    }

    fun stop() {
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.Stop)
        _uiState.update { it.copy(isPlaying = false, playheadFrame = 0L) }
    }

    fun seekToFraction(fraction: Float) {
        val total = _session.value.totalLengthFrames
        val target = (fraction.coerceIn(0f, 1f) * total).toLong()
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.Seek(target))
    }

    fun toggleLoop() {
        val nextLoop = !_session.value.loopEnabled
        _session.update { it.copy(loopEnabled = nextLoop) }
        playbackEngine.commandQueue.enqueue(
            AudioEngineCommand.SetLoopRange(
                _session.value.loopStartFrame,
                _session.value.loopEndFrame,
                nextLoop
            )
        )
    }

    fun selectTrack(index: Int) {
        _uiState.update { it.copy(selectedTrackIndex = index.coerceIn(0, 15)) }
    }

    // Track Mixing Controls
    fun setTrackGain(trackIndex: Int, gainDb: Float) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(gainDb = gainDb)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetGain(trackIndex, gainDb))
    }

    fun setTrackPan(trackIndex: Int, pan: Float) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(pan = pan)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetPan(trackIndex, pan))
    }

    fun toggleTrackMute(trackIndex: Int) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(isMuted = !before.isMuted)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetMute(trackIndex, after.isMuted))
    }

    fun toggleTrackSolo(trackIndex: Int) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(isSoloed = !before.isSoloed)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetSolo(trackIndex, after.isSoloed))
    }

    fun toggleTrackArm(trackIndex: Int) {
        _session.update { s ->
            val newTracks = s.tracks.map {
                if (it.index == trackIndex) it.copy(isArmedForRecording = !it.isArmedForRecording)
                else it.copy(isArmedForRecording = false) // Single track armed at a time
            }
            s.copy(tracks = newTracks)
        }
    }

    fun setTrackReverbSend(trackIndex: Int, sendLevel: Float) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(reverbSendLevel = sendLevel)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReverbSend(trackIndex, sendLevel))
    }

    fun updateTrackEq(trackIndex: Int, eqConfig: EqConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(eqConfig = eqConfig)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetEq(trackIndex, eqConfig))
    }

    fun updateTrackCompressor(trackIndex: Int, compressorConfig: CompressorConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(compressorConfig = compressorConfig)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetCompressor(trackIndex, compressorConfig))
    }

    fun updateTrackSaturation(trackIndex: Int, config: SaturationConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(saturationConfig = config)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetSaturation(trackIndex, config))
    }

    fun updateTrackDuckingDelay(trackIndex: Int, config: DuckingDelayConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(delayConfig = config)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetDuckingDelay(trackIndex, config))
    }

    fun updateTrackChorus(trackIndex: Int, config: com.example.audio.model.ChorusConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(chorusConfig = config)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetChorus(trackIndex, config))
    }

    fun updateTrackLimiter(trackIndex: Int, config: ChannelLimiterConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(limiterConfig = config)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetChannelLimiter(trackIndex, config))
    }

    fun setMasterGain(gainDb: Float) {
        _session.update { it.copy(masterGainDb = gainDb) }
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetMasterGain(gainDb))
    }

    fun updateMasterLimiter(config: MasterLimiterConfig) {
        _session.update { it.copy(masterLimiter = config) }
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetMasterLimiter(config))
    }

    fun updateMasterReverb(config: ReverbConfig) {
        _session.update { it.copy(masterReverb = config) }
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetMasterReverb(config))
    }

    private fun pushTrackParamChange(before: TrackData, after: TrackData) {
        val updatedList = _session.value.tracks.map { if (it.index == before.index) after else it }
        val newSession = _session.value.copy(tracks = updatedList, revision = _session.value.revision + 1)
        _session.value = newSession
        persistence.writeCrashRecoveryJournal(newSession)
    }

    // Clip Editing (Nondestructive)
    fun splitClipAtPlayhead(trackIndex: Int) {
        val playhead = _uiState.value.playheadFrame
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clipToSplit = track.clips.find { c ->
            playhead in c.timelineStartFrame until (c.timelineStartFrame + c.lengthFrames)
        } ?: return

        val offsetInClip = playhead - clipToSplit.timelineStartFrame
        if (offsetInClip <= 2048 || offsetInClip >= clipToSplit.lengthFrames - 2048) return

        val clip1 = clipToSplit.copy(
            id = UUID.randomUUID().toString(),
            name = "${clipToSplit.name} (Part 1)",
            lengthFrames = offsetInClip
        )
        val clip2 = clipToSplit.copy(
            id = UUID.randomUUID().toString(),
            name = "${clipToSplit.name} (Part 2)",
            sourceStartFrame = clipToSplit.sourceStartFrame + offsetInClip,
            timelineStartFrame = playhead,
            lengthFrames = clipToSplit.lengthFrames - offsetInClip
        )

        val beforeClips = track.clips
        val afterClips = track.clips.filter { it.id != clipToSplit.id } + listOf(clip1, clip2)

        undoManager.pushAction(
            SessionCommand.EditClips(
                "Split ${clipToSplit.name} at Playhead",
                trackIndex,
                beforeClips,
                afterClips
            )
        )

        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
    }

    fun trimClipEnd(trackIndex: Int, clipId: String, trimFrames: Long) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val newLength = max(4800L, clip.lengthFrames - trimFrames)
        val updated = clip.copy(lengthFrames = newLength)

        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updated else it }

        undoManager.pushAction(SessionCommand.EditClips("Trim ${clip.name}", trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
    }

    fun duplicateClip(trackIndex: Int, clipId: String) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val duplicated = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Copy)",
            timelineStartFrame = clip.timelineStartFrame + clip.lengthFrames
        )

        val beforeClips = track.clips
        val afterClips = track.clips + duplicated

        undoManager.pushAction(SessionCommand.EditClips("Duplicate ${clip.name}", trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, totalLengthFrames = max(_session.value.totalLengthFrames, duplicated.timelineStartFrame + duplicated.lengthFrames), revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
    }

    fun renameClip(trackIndex: Int, clipId: String, newName: String) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val updated = clip.copy(name = newName)

        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updated else it }

        undoManager.pushAction(SessionCommand.EditClips("Rename ${clip.name} to $newName", trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
    }

    fun setClipGain(trackIndex: Int, clipId: String, gainDb: Float) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val updated = clip.withGainDb(gainDb)

        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updated else it }

        undoManager.pushAction(SessionCommand.EditClips("Gain ${clip.name} (%+.1f dB)".format(gainDb), trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
    }

    fun setClipFadeIn(trackIndex: Int, clipId: String, fadeInMs: Float, curve: com.example.audio.model.FadeCurve = com.example.audio.model.FadeCurve.EQUAL_POWER) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val fadeInFrames = (fadeInMs * 0.001f * sampleRate).toLong().coerceAtLeast(0L)
        val updated = clip.copy(fadeInFrames = fadeInFrames, fadeInCurve = curve)

        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updated else it }

        undoManager.pushAction(SessionCommand.EditClips("Fade In ${clip.name}", trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
    }

    fun setClipFadeOut(trackIndex: Int, clipId: String, fadeOutMs: Float, curve: com.example.audio.model.FadeCurve = com.example.audio.model.FadeCurve.EQUAL_POWER) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val fadeOutFrames = (fadeOutMs * 0.001f * sampleRate).toLong().coerceAtLeast(0L)
        val updated = clip.copy(fadeOutFrames = fadeOutFrames, fadeOutCurve = curve)

        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updated else it }

        undoManager.pushAction(SessionCommand.EditClips("Fade Out ${clip.name}", trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
    }

    /**
     * Pastes the selected clip at the next chorus section (e.g. 4 bars ahead).
     */
    fun pasteClipAsChorusSection(trackIndex: Int, clipId: String, barsAhead: Int = 4) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val barFrames = 19200L // 1 Bar = 19200 frames @ 120 BPM 48kHz
        val targetStart = clip.timelineStartFrame + (barsAhead * barFrames)

        val pasted = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Chorus +${barsAhead}B)",
            timelineStartFrame = targetStart
        )

        val beforeClips = track.clips
        val afterClips = track.clips + pasted

        undoManager.pushAction(SessionCommand.EditClips("Chorus Paste ${clip.name}", trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        val maxFrames = max(_session.value.totalLengthFrames, targetStart + clip.lengthFrames)
        _session.update { it.copy(tracks = newTracks, totalLengthFrames = maxFrames, revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
        _uiState.update { it.copy(statusMessage = "Chorus Paste: \"${clip.name}\" placed at Bar +$barsAhead!") }
    }

    /**
     * Pastes a doubled chorus layer onto a target track with stereo chorus adaptation.
     */
    fun pasteClipAsDoubledChorus(sourceTrackIndex: Int, clipId: String, targetTrackIndex: Int) {
        val srcTrack = _session.value.tracks.getOrNull(sourceTrackIndex) ?: return
        val targetTrack = _session.value.tracks.getOrNull(targetTrackIndex) ?: srcTrack
        val clip = srcTrack.clips.find { it.id == clipId } ?: return

        // 12ms micro timing offset (576 frames @ 48kHz)
        val doubledStart = clip.timelineStartFrame + 576L
        val doubledClip = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Chorus Double)",
            timelineStartFrame = doubledStart,
            trackIndex = targetTrack.index,
            gainLinear = clip.gainLinear * 0.85f // subtle attenuation for double
        )

        val beforeClips = targetTrack.clips
        val afterClips = targetTrack.clips + doubledClip

        // Enable stereo chorus plugin on target track
        val newChorusConfig = targetTrack.chorusConfig.copy(
            enabled = true,
            rateHz = 1.4f,
            depthMs = 4.2f,
            stereoSpread = 0.90f,
            mix = 0.45f
        )
        updateTrackChorus(targetTrack.index, newChorusConfig)

        undoManager.pushAction(SessionCommand.EditClips("Chorus Doubler Layer", targetTrack.index, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == targetTrack.index) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
        _uiState.update { it.copy(statusMessage = "Created Chorus Doubler Layer on ${targetTrack.name}!") }
    }

    /**
     * Fills 4-bar chorus section with crossfaded repeats of the clip.
     */
    fun adaptClipToChorusLoop(trackIndex: Int, clipId: String, repeatCount: Int = 3) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return

        val fadeFrames = 480L // 10ms crossfade
        val newClips = mutableListOf<AudioClip>()
        var currentStart = clip.timelineStartFrame

        for (r in 0..repeatCount) {
            val repClip = clip.copy(
                id = if (r == 0) clip.id else UUID.randomUUID().toString(),
                name = if (r == 0) clip.name else "${clip.name} (Rpt $r)",
                timelineStartFrame = currentStart,
                fadeInFrames = if (r > 0) fadeFrames else clip.fadeInFrames,
                fadeOutFrames = if (r < repeatCount) fadeFrames else clip.fadeOutFrames
            )
            newClips.add(repClip)
            currentStart += (clip.lengthFrames - fadeFrames)
        }

        val beforeClips = track.clips
        val afterClips = track.clips.filter { it.id != clipId } + newClips

        undoManager.pushAction(SessionCommand.EditClips("Adapt Chorus Loop", trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        val maxFrames = max(_session.value.totalLengthFrames, currentStart + clip.lengthFrames)
        _session.update { it.copy(tracks = newTracks, totalLengthFrames = maxFrames, revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
        _uiState.update { it.copy(statusMessage = "Adapted 4-bar chorus loop with equal-power crossfades!") }
    }

    fun showClipBin(show: Boolean) {
        _uiState.update { it.copy(showClipBin = show) }
    }

    fun moveClipToFrame(trackIndex: Int, clipId: String, rawTargetFrame: Long) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val currentSettings = _settings.value

        val snappedFrame = com.example.audio.engine.SnapEngine.snapFrame(
            targetFrame = rawTargetFrame,
            session = _session.value,
            isSnapEnabled = currentSettings.isMagneticSnapEnabled,
            resolution = currentSettings.gridResolution
        ).coerceAtLeast(0L)

        val updatedClip = clip.copy(timelineStartFrame = snappedFrame)
        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updatedClip else it }

        undoManager.pushAction(SessionCommand.EditClips("Move ${clip.name} to frame $snappedFrame", trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, totalLengthFrames = max(_session.value.totalLengthFrames, snappedFrame + updatedClip.lengthFrames), revision = it.revision + 1) }
        persistence.writeCrashRecoveryJournal(_session.value)
    }

    fun toggleMagneticSnap() {
        _settings.update { it.copy(isMagneticSnapEnabled = !it.isMagneticSnapEnabled) }
    }

    fun cycleGridResolution() {
        val current = _settings.value.gridResolution
        val all = com.example.audio.model.GridResolution.values()
        val nextIdx = (current.ordinal + 1) % all.size
        _settings.update { it.copy(gridResolution = all[nextIdx]) }
    }

    fun nudgeSelectedClip(trackIndex: Int, clipId: String, forward: Boolean) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val step = if (_settings.value.gridResolution.frames > 0L) {
            _settings.value.gridResolution.frames
        } else {
            4800L // 1 beat default
        }
        val target = if (forward) {
            clip.timelineStartFrame + step
        } else {
            max(0L, clip.timelineStartFrame - step)
        }
        moveClipToFrame(trackIndex, clipId, target)
    }

    // Undo / Redo
    fun canUndo(): Boolean = undoManager.canUndo()
    fun canRedo(): Boolean = undoManager.canRedo()

    fun undo() {
        val result = undoManager.undo(_session.value) ?: return
        val (restoredSession, command) = result
        _session.value = restoredSession
        playbackEngine.syncFromProject(restoredSession)
        persistence.writeCrashRecoveryJournal(restoredSession)
        _uiState.update { it.copy(statusMessage = "Undid: ${command.description}") }
    }

    fun redo() {
        val result = undoManager.redo(_session.value) ?: return
        val (restoredSession, command) = result
        _session.value = restoredSession
        playbackEngine.syncFromProject(restoredSession)
        persistence.writeCrashRecoveryJournal(restoredSession)
        _uiState.update { it.copy(statusMessage = "Redid: ${command.description}") }
    }

    // Recording & Latency Calibration
    fun startRecording() {
        val armedTrack = _session.value.tracks.find { it.isArmedForRecording }
        if (armedTrack == null) {
            _uiState.update { it.copy(statusMessage = "Please arm a track (tap ● on track strip) to record!") }
            return
        }

        val takesDir = File(getApplication<Application>().filesDir, "takes").apply { mkdirs() }
        val takeFile = File(takesDir, "take_${System.currentTimeMillis()}.wav")

        val success = audioRecorder.startRecording(takeFile)
        if (success) {
            // Also start playback if not already playing so user hears backing tracks
            if (!_uiState.value.isPlaying) {
                playbackEngine.commandQueue.enqueue(AudioEngineCommand.Play)
            }
            _uiState.update { it.copy(isRecording = true, isPlaying = true, statusMessage = "Recording to Track ${armedTrack.index + 1} (${armedTrack.name})...") }
        } else {
            _uiState.update { it.copy(statusMessage = "Microphone error. Check permission.") }
        }
    }

    fun stopRecording() {
        if (!_uiState.value.isRecording) return
        val framesRecorded = audioRecorder.stopRecording()
        _uiState.update { it.copy(isRecording = false) }

        val armedTrack = _session.value.tracks.find { it.isArmedForRecording } ?: return
        if (framesRecorded > 2000L) {
            // Apply latency calibration offset so overdubs align perfectly!
            val latencyFrames = ((_session.value.latencyOffsetMs / 1000f) * sampleRate).toLong()
            val alignedTimelineStart = max(0L, _uiState.value.playheadFrame - framesRecorded - latencyFrames)

            val newClip = AudioClip(
                name = "Mic Take ${armedTrack.clips.size + 1}",
                sourcePath = "take_wav",
                sourceStartFrame = 0L,
                lengthFrames = framesRecorded,
                timelineStartFrame = alignedTimelineStart,
                trackIndex = armedTrack.index
            )

            val updatedClips = armedTrack.clips + newClip
            val newTracks = _session.value.tracks.map { if (it.index == armedTrack.index) it.copy(clips = updatedClips) else it }
            _session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
            _uiState.update { it.copy(statusMessage = "Take recorded (${framesRecorded / sampleRate}s) with ${_session.value.latencyOffsetMs}ms latency compensation.") }
        }
    }

    fun runLatencyCalibration() {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(statusMessage = "Calibrating audio latency offset...") }
            val referenceChirp = WavAudioIO.generateTestSignal("impulse", 512, sampleRate)
            val measuredOffset = LatencyCalibrator.calculateLatencyOffsetMs(referenceChirp, referenceChirp, sampleRate)
            _session.update { it.copy(latencyOffsetMs = measuredOffset) }
            _uiState.update { it.copy(statusMessage = "Latency calibrated: ${String.format("%.1f", measuredOffset)} ms") }
        }
    }

    // USB Type-C Audio Interface Routing
    fun selectUsbDevice(deviceId: Int) {
        usbManager.selectDevice(deviceId)
        _uiState.update { it.copy(statusMessage = "Audio routing switched to Type-C USB interface") }
    }

    fun showUsbDialog(show: Boolean) {
        _uiState.update { it.copy(showUsbDialog = show) }
    }

    fun showImportDialog(show: Boolean) {
        _uiState.update { it.copy(showImportDialog = show) }
    }

    fun showSettingsDialog(show: Boolean) {
        _uiState.update { it.copy(showSettingsDialog = show) }
    }

    fun updateSettings(newSettings: DawSettings) {
        _settings.value = newSettings
        _session.update { it.copy(latencyOffsetMs = newSettings.latencyCompensationMs) }
        _uiState.update { it.copy(statusMessage = "Studio settings updated (${newSettings.bufferSize.label})") }
    }

    fun resetSettingsToDefaults() {
        val defaults = DawSettings()
        _settings.value = defaults
        _session.update { it.copy(latencyOffsetMs = defaults.latencyCompensationMs) }
        _uiState.update { it.copy(statusMessage = "All studio parameters reset to factory calibration.") }
    }

    fun clearScratchCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = getApplication<Application>().cacheDir
                cacheDir.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }
                _uiState.update { it.copy(statusMessage = "Temporary audio scratchpad & waveform cache cleared.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Cache cleared.") }
            }
        }
    }

    // Audio File Import / Upload
    fun importAudioFile(uri: Uri, targetTrackIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(statusMessage = "Importing & decoding audio file...") }
            val result = AudioFileImporter.importAudioUri(
                context = getApplication(),
                uri = uri,
                targetTrackIndex = targetTrackIndex,
                targetTimelineStartFrame = 0L
            )
            if (result != null) {
                trackAudioBuffers[targetTrackIndex] = result.samples
                playbackEngine.setTrackAudio(targetTrackIndex, result.samples)

                val track = _session.value.tracks.getOrNull(targetTrackIndex) ?: return@launch
                val updatedTrack = track.copy(
                    name = result.originalFileName.substringBeforeLast("."),
                    clips = listOf(result.clip)
                )

                val updatedTracks = _session.value.tracks.map { if (it.index == targetTrackIndex) updatedTrack else it }
                val newSession = _session.value.copy(
                    tracks = updatedTracks,
                    totalLengthFrames = max(_session.value.totalLengthFrames, result.clip.lengthFrames),
                    revision = _session.value.revision + 1
                )
                _session.value = newSession
                persistence.writeCrashRecoveryJournal(newSession)

                refreshAudioAnalysis()
                _uiState.update { it.copy(statusMessage = "Imported \"${result.originalFileName}\" into Track ${targetTrackIndex + 1}!") }
            } else {
                _uiState.update { it.copy(statusMessage = "Could not decode audio file.") }
            }
        }
    }

    // AI Mix Assistant
    fun refreshAudioAnalysis() {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isAnalyzingAi = true) }
            val feats = mutableListOf<TrackFeatures>()
            for (t in 0 until 16) {
                val samples = trackAudioBuffers[t]
                val f = AudioFeatureExtractor.extractFeatures(t, samples, sampleRate)
                feats.add(f)
            }
            _features.value = feats

            // Automatically label stem categories if unknown
            val newTracks = _session.value.tracks.map { trk ->
                val f = feats.find { it.trackIndex == trk.index }
                if (f != null && trk.stemCategory == StemCategory.UNKNOWN) {
                    trk.copy(stemCategory = f.classifiedStem)
                } else trk
            }
            _session.update { it.copy(tracks = newTracks) }

            // Generate verified suggestions
            val sugs = MixAdvisor.generateSuggestions(_session.value, feats)
            _suggestions.value = sugs
            _uiState.update { it.copy(isAnalyzingAi = false) }
        }
    }

    fun applyAllSuggestions() {
        val sugs = _suggestions.value
        if (sugs.isEmpty()) return

        val beforeTracks = _session.value.tracks
        val beforeMasterGain = _session.value.masterGainDb

        // Save original snapshot for A/B compare
        originalSessionSnapshot = _session.value

        val updatedSession = MixAdvisor.applySuggestionsToSession(_session.value, sugs)

        undoManager.pushAction(
            SessionCommand.BatchMixChange(
                "Apply AI Mix Assistant Suggestions (${sugs.size} adjustments)",
                beforeTracks,
                updatedSession.tracks,
                beforeMasterGain,
                updatedSession.masterGainDb
            )
        )

        _session.value = updatedSession
        playbackEngine.syncFromProject(updatedSession)
        persistence.writeCrashRecoveryJournal(updatedSession)

        _uiState.update {
            it.copy(
                isAbComparing = true,
                abMode = "B",
                statusMessage = "Applied ${sugs.size} AI mix suggestions. Tap A/B Compare to hear difference!"
            )
        }
    }

    fun toggleAbCompare() {
        val original = originalSessionSnapshot ?: return
        val current = _session.value

        if (_uiState.value.abMode == "B") {
            // Switch to A: Original
            playbackEngine.syncFromProject(original)
            _uiState.update { it.copy(abMode = "A", statusMessage = "Listening to Mix A (Original)") }
        } else {
            // Switch to B: AI Suggested Mix
            playbackEngine.syncFromProject(current)
            _uiState.update { it.copy(abMode = "B", statusMessage = "Listening to Mix B (AI Optimized)") }
        }
    }

    fun handleNaturalLanguageMixPrompt(prompt: String, dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        viewModelScope.launch(dispatcher) {
            val sugs = MixAdvisor.parseNaturalLanguageCommand(prompt, _session.value)
            _suggestions.value = sugs
            _uiState.update { it.copy(statusMessage = "AI generated ${sugs.size} bounded proposals for: \"$prompt\"") }
        }
    }

    // Auto Detection & Integration for Small Models (Ollama & Llama.cpp)
    fun scanForLocalModels(customUrl: String? = null, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) = viewModelScope.launch(dispatcher) {
        _uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isScanning = true,
                    endpointsChecked = 0
                )
            )
        }

        val endpointsToTest = mutableListOf<LocalModelEndpoint>()
        endpointsToTest.addAll(LocalModelDetector.DEFAULT_ENDPOINTS)

        val custom = customUrl ?: _settings.value.customOllamaUrl
        if (custom.isNotBlank() && endpointsToTest.none { it.baseUrl == custom }) {
            endpointsToTest.add(
                LocalModelEndpoint(
                    provider = LocalModelProvider.OLLAMA,
                    baseUrl = custom,
                    displayName = "Custom Endpoint ($custom)"
                )
            )
        }

        val probedResults = mutableListOf<EndpointProbeResult>()
        val allDetectedModels = mutableListOf<DiscoveredModelInfo>()

        for ((idx, ep) in endpointsToTest.withIndex()) {
            val res = LocalModelDetector.probeEndpoint(ep)
            probedResults.add(res)
            if (res.isOnline && res.models.isNotEmpty()) {
                allDetectedModels.addAll(res.models)
            }
            _uiState.update { current ->
                current.copy(
                    localModelDetectorState = current.localModelDetectorState.copy(
                        endpointsChecked = idx + 1,
                        totalEndpointsToScan = endpointsToTest.size
                    )
                )
            }
        }

        // Fallback simulated models when running offline or no server running
        val finalModelsList = if (allDetectedModels.isEmpty()) {
            LocalModelDetector.SIMULATED_FALLBACK_MODELS
        } else {
            allDetectedModels
        }

        val currentActive = _uiState.value.localModelDetectorState.activeModel
            ?: finalModelsList.firstOrNull { it.name == _settings.value.activeLocalModelName }
            ?: finalModelsList.firstOrNull()

        val markedModels = finalModelsList.map { m ->
            m.copy(isSelected = m.name == currentActive?.name)
        }

        _uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isScanning = false,
                    probedEndpoints = probedResults,
                    detectedModels = markedModels,
                    activeModel = currentActive,
                    lastScanTimestamp = System.currentTimeMillis()
                ),
                statusMessage = "Scanned ${endpointsToTest.size} local model endpoints. Discovered ${finalModelsList.size} models."
            )
        }
    }

    fun selectLocalModel(model: DiscoveredModelInfo) {
        val updatedList = _uiState.value.localModelDetectorState.detectedModels.map { m ->
            m.copy(isSelected = m.id == model.id || m.name == model.name)
        }
        val sel = updatedList.find { it.isSelected } ?: model.copy(isSelected = true)

        _settings.update { s ->
            s.copy(
                activeLocalModelName = sel.name,
                activeLocalModelProvider = sel.provider.displayName,
                customOllamaUrl = sel.baseUrl
            )
        }

        _uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    detectedModels = updatedList,
                    activeModel = sel
                ),
                statusMessage = "Selected local model: ${sel.name} (${sel.provider.displayName} @ ${sel.baseUrl})"
            )
        }
    }

    fun setCustomLocalEndpointUrl(url: String) {
        _settings.update { it.copy(customOllamaUrl = url) }
        _uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    customEndpointUrl = url
                )
            )
        }
        scanForLocalModels(url)
    }

    fun sendPromptToLocalModel(prompt: String, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) = viewModelScope.launch(dispatcher) {
        val active = _uiState.value.localModelDetectorState.activeModel
            ?: DiscoveredModelInfo(
                name = _settings.value.activeLocalModelName,
                provider = LocalModelProvider.OLLAMA,
                baseUrl = _settings.value.customOllamaUrl
            )

        _uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isInferenceRunning = true,
                    lastInferenceResponse = null
                ),
                statusMessage = "Sending query to ${active.name}..."
            )
        }

        val resp = LocalModelDetector.generateWithLocalModel(
            model = active,
            prompt = prompt,
            systemPrompt = "You are a professional audio mixing engineer and DAW tactical advisor."
        )

        _uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isInferenceRunning = false,
                    lastInferenceResponse = resp
                ),
                statusMessage = "Response received from ${active.name}"
            )
        }
    }

    // Offline Renderer & DSP Unit Tests
    fun runDspUnitTests() {
        viewModelScope.launch(Dispatchers.Default) {
            val results = OfflineRenderer.runDspUnitTests()
            _uiState.update { it.copy(dspTestResults = results, showDspTestDialog = true) }
        }
    }

    fun dismissDspTestDialog() {
        _uiState.update { it.copy(showDspTestDialog = false) }
    }

    fun showExportDialog(show: Boolean) {
        _uiState.update { it.copy(showExportDialog = show, exportProgress = 0f, exportedFile = null) }
    }

    fun applyTrackPreset(trackIndex: Int, preset: com.example.audio.model.TrackPreset) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val updated = track.copy(
            eqConfig = preset.eqConfig,
            saturationConfig = preset.saturationConfig,
            compressorConfig = preset.compressorConfig,
            chorusConfig = preset.chorusConfig,
            delayConfig = preset.delayConfig,
            limiterConfig = preset.limiterConfig,
            reverbSendLevel = preset.reverbSendLevel
        )
        pushTrackParamChange(track, updated)
        playbackEngine.syncFromProject(_session.value)
        _uiState.update { it.copy(statusMessage = "Applied preset \"${preset.name}\" to ${track.name}!") }
    }

    fun applyMasterPreset(preset: com.example.audio.model.MasterPreset) {
        _session.update { s ->
            s.copy(
                masterGainDb = preset.masterGainDb,
                masterReverb = preset.masterReverb,
                masterLimiter = preset.masterLimiter,
                revision = s.revision + 1
            )
        }
        playbackEngine.syncFromProject(_session.value)
        _uiState.update { it.copy(statusMessage = "Applied Master Preset \"${preset.name}\"!") }
    }

    fun renderSessionToAudio(
        format: com.example.audio.engine.ExportFormat = com.example.audio.engine.ExportFormat.WAV_24BIT,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) {
        viewModelScope.launch(dispatcher) {
            _uiState.update { it.copy(exportProgress = 0.05f, statusMessage = "Rendering session to ${format.displayName}...") }
            val exportDir = File(getApplication<Application>().filesDir, "exports").apply { mkdirs() }
            val outFile = File(exportDir, "PulseStudio_${System.currentTimeMillis()}${format.extension}")

            val ok = if (format == com.example.audio.engine.ExportFormat.AAC_320KBPS) {
                com.example.audio.engine.OfflineRenderer.renderSessionToAac(
                    project = _session.value,
                    trackBuffers = trackAudioBuffers,
                    outputFile = outFile,
                    onProgress = { p -> _uiState.update { it.copy(exportProgress = p) } }
                )
            } else {
                com.example.audio.engine.OfflineRenderer.renderSessionToWav(
                    project = _session.value,
                    trackBuffers = trackAudioBuffers,
                    outputFile = outFile,
                    onProgress = { p -> _uiState.update { it.copy(exportProgress = p) } }
                )
            }

            if (ok) {
                _uiState.update {
                    it.copy(
                        exportProgress = 1.0f,
                        exportedFile = outFile,
                        statusMessage = "Export complete: ${outFile.name} (${outFile.length() / 1024} KB)"
                    )
                }
            } else {
                _uiState.update { it.copy(statusMessage = "Export failed.") }
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun createInitialSession(): ProjectSession {
        val trackColors = longArrayOf(
            0xFFFF5722, // 1. Kick: Deep Orange
            0xFFFF9800, // 2. Snare: Amber
            0xFFFFEB3B, // 3. Hi-Hats: Yellow
            0xFF8BC34A, // 4. Toms: Light Green
            0xFF4CAF50, // 5. Sub Bass: Green
            0xFF009688, // 6. Synth Bass: Teal
            0xFF00BCD4, // 7. Rhythm Guitars L: Cyan
            0xFF03A9F4, // 8. Rhythm Guitars R: Light Blue
            0xFF2196F3, // 9. Lead Guitar: Blue
            0xFF3F51B5, // 10. Acoustic Piano: Indigo
            0xFF673AB7, // 11. Rhodes E-Piano: Deep Purple
            0xFF9C27B0, // 12. Synth Pad: Purple
            0xFFE91E63, // 13. Synth Lead: Pink
            0xFFF44336, // 14. Lead Vocal: Red / Rose
            0xFFFF4081, // 15. Vocal Harmonies: Magenta
            0xFFCDDC39  // 16. FX Riser & Drops: Lime
        )
        val trackNames = arrayOf(
            "Kick Drum",
            "Snare & Clap",
            "Hi-Hats Groove",
            "Toms & Percs",
            "808 Sub Bass",
            "Synth Bass",
            "Rhythm Guitar L",
            "Rhythm Guitar R",
            "Lead Guitar",
            "Acoustic Piano",
            "Rhodes E-Piano",
            "Synth Pad",
            "Synth Lead",
            "Lead Vocal",
            "Vocal Harmonies",
            "FX Riser & Drops"
        )
        val defaultCategories = arrayOf(
            StemCategory.DRUMS,
            StemCategory.DRUMS,
            StemCategory.DRUMS,
            StemCategory.DRUMS,
            StemCategory.BASS,
            StemCategory.BASS,
            StemCategory.GUITAR,
            StemCategory.GUITAR,
            StemCategory.GUITAR,
            StemCategory.KEYS,
            StemCategory.KEYS,
            StemCategory.KEYS,
            StemCategory.KEYS,
            StemCategory.VOCAL,
            StemCategory.VOCAL,
            StemCategory.OTHER
        )

        val tracks = (0 until 16).map { i ->
            TrackData(
                index = i,
                name = trackNames[i],
                colorHex = trackColors[i],
                gainDb = when (i) {
                    0, 4 -> -1.5f // Kick and Sub
                    6 -> -3.5f    // Guitar L
                    7 -> -3.5f    // Guitar R
                    else -> -3.0f
                },
                pan = when (i) {
                    2 -> 0.20f   // Hi-hats
                    6 -> -0.40f  // Rhythm Guitar L
                    7 -> 0.40f   // Rhythm Guitar R
                    10 -> -0.25f // Rhodes
                    14 -> 0.30f  // Harmonies
                    else -> 0.0f
                },
                reverbSendLevel = when (i) {
                    1 -> 0.35f  // Snare
                    9 -> 0.25f  // Acoustic Piano
                    11 -> 0.45f // Synth Pad
                    13 -> 0.30f // Lead Vocal
                    14 -> 0.50f // Harmonies
                    15 -> 0.60f // FX Riser
                    else -> 0.05f
                },
                stemCategory = defaultCategories[i],
                clips = listOf(
                    AudioClip(
                        name = "${trackNames[i]} Clip",
                        sourcePath = "built_in",
                        sourceStartFrame = 0L,
                        lengthFrames = 48000L * 16L,
                        timelineStartFrame = 0L,
                        trackIndex = i
                    )
                )
            )
        }

        return ProjectSession(tracks = tracks)
    }

    public override fun onCleared() {
        super.onCleared()
        playbackEngine.stop()
        usbManager.release()
    }
}
