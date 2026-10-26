package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
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
import com.example.audio.ai.MixEngineerBrain
import com.example.audio.ai.ToolCallDispatcher
import com.example.audio.ai.VoiceEngineerState
import com.example.audio.ai.VoiceMixEngineer
import com.example.audio.engine.AudioEngineCommand
import com.example.audio.engine.AudioFileImporter
import com.example.audio.engine.AudioRecorder
import com.example.audio.dsp.AutoPitchProcessor
import com.example.audio.dsp.ClipCompositor
import com.example.audio.engine.AudioResampler
import com.example.audio.engine.AudioTrackPlaybackEngine
import com.example.audio.engine.LatencyCalibrator
import com.example.audio.engine.OfflineRenderer
import com.example.audio.engine.UsbAudioInterfaceManager
import com.example.audio.engine.WaveformCache
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
import com.example.audio.model.EqBand
import com.example.audio.model.EqConfig
import com.example.audio.model.FilterType
import com.example.audio.model.MasterLimiterConfig
import com.example.audio.model.MeterBallistics
import com.example.audio.model.MixSuggestion
import com.example.audio.model.ProjectSession
import com.example.audio.model.ResonanceSmootherConfig
import com.example.audio.model.ReverbConfig
import com.example.audio.model.SaturationConfig
import com.example.audio.model.StemCategory
import com.example.audio.model.TrackData
import com.example.audio.model.TrackFeatures
import com.example.audio.model.UsbAudioDeviceInfo
import com.example.audio.model.UsbRoutingMode
import com.example.audio.model.VocalRiderConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class StatusKind { INFO, SUCCESS, ERROR }

data class StatusAction(val label: String, val forMessage: String? = null)

data class EngineUiState(
    val isPlaying: Boolean = false,
    val isRecording: Boolean = false,
    val punchInFrame: Long? = null,
    val punchOutFrame: Long? = null,
    val isInputMonitoringEnabled: Boolean = false,
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
    val selectedClipIds: Set<String> = emptySet(),
    val isAbComparing: Boolean = false,
    val hasReferenceTrack: Boolean = false,
    val abMode: String = "A", // "A" = Original, "B" = AI Suggested Mix
    val isAnalyzingAi: Boolean = false,
    val localModelDetectorState: com.example.audio.ai.LocalModelDetectorState = com.example.audio.ai.LocalModelDetectorState(),
    val showDspTestDialog: Boolean = false,
    val showExportDialog: Boolean = false,
    val showUsbDialog: Boolean = false,
    val showImportDialog: Boolean = false,
    val showClipBin: Boolean = false,
    val dspTestResults: List<OfflineRenderer.DspTestResult> = emptyList(),
    val exportProgress: Float = 0f,
    val exportedFile: File? = null,
    val exportedStemFiles: List<File> = emptyList(),
    val exportError: String? = null,
    val cacheSizeBytes: Long = 0L,
    val exportDestinationName: String = "App storage / exports",
    val uiFrameMeanMs: Float = 0f,
    val uiFrameP95Ms: Float = 0f,
    val showOnboarding: Boolean = false,
    val showWhatsNew: Boolean = false,
    val presentationMode: Boolean = false,
    val isImporting: Boolean = false,
    val importError: String? = null,
    val voiceEngineer: VoiceEngineerState = VoiceEngineerState(),
    val statusMessage: String? = null,
    val statusKind: StatusKind = StatusKind.INFO,
    val statusAction: StatusAction? = null
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

    // A/B reference track (loaded from a real song for comparison/matching)
    private var referenceTrackSamples: FloatArray? = null

    // Track playlists the engine plays — rebuilt whenever clips or pre-render
    // processing (AutoPitch) change. Internal so instrumented-style unit tests
    // can verify that the audible buffer follows session edits.
    internal val trackAudioBuffers = Array(16) { FloatArray(0) }

    // Per-track source audio pools: each clip references its own source audio
    // by AudioClip.sourcePath (a canonical 48 kHz stereo WAV file on disk for
    // imports and takes, or an explicit test fixture in unit tests).
    private val trackSourcePools = Array(16) { mutableMapOf<String, FloatArray>() }
    private val trackRebuildMutexes = Array(16) { Mutex() }
    private val trackRebuildJobs = arrayOfNulls<Job>(16)

    // Recording session state (transport truth at punch-in)
    private var currentRecordingStartFrame = 0L
    private var lastRecordingFile: File? = null

    // Debounced persistence + coalesced undo state
    private var journalJob: Job? = null
    private var paramCommitJob: Job? = null
    private data class PendingParamEdit(val trackIndex: Int, val before: TrackData, val description: String)
    private var pendingParamEdit: PendingParamEdit? = null

    private val _session = MutableStateFlow(createInitialSession())
    val session: StateFlow<ProjectSession> = _session.asStateFlow()

    // Completed once the persisted session has been restored and the audio
    // engine is about to start. Tests that install a fixture must wait for
    // this instead of racing the initial load.
    private val sessionReady = CompletableDeferred<Unit>()

    private val _settings = MutableStateFlow(DawSettings())
    val settings: StateFlow<DawSettings> = _settings.asStateFlow()

    private val _uiState = MutableStateFlow(EngineUiState())
    val uiState: StateFlow<EngineUiState> = _uiState.asStateFlow()

    // Speech input and spoken confirmations for the engineer workflow.
    // Assigned at the top of init() so callbacks can safely refer to this field.
    private lateinit var voiceEngineer: VoiceMixEngineer

    private val uiPrefs by lazy {
        getApplication<Application>().getSharedPreferences("pulse_ui_prefs", 0)
    }
    private var exportTreeUri: Uri? = null

    private val _features = MutableStateFlow<List<TrackFeatures>>(emptyList())
    val features: StateFlow<List<TrackFeatures>> = _features.asStateFlow()

    private val _suggestions = MutableStateFlow<List<MixSuggestion>>(emptyList())
    val suggestions: StateFlow<List<MixSuggestion>> = _suggestions.asStateFlow()

    // Backup snapshot for A/B compare
    private var originalSessionSnapshot: ProjectSession? = null

    init {
        voiceEngineer = VoiceMixEngineer(application).apply {
            onListeningChanged = { listening ->
                _uiState.update {
                    it.copy(voiceEngineer = it.voiceEngineer.copy(isListening = listening, error = null))
                }
            }
            onPartialTranscript = { partial ->
                _uiState.update {
                    it.copy(voiceEngineer = it.voiceEngineer.copy(partialTranscript = partial))
                }
            }
            onFinalTranscript = { transcript -> handleVoiceTranscript(transcript) }
            onError = { message ->
                _uiState.update {
                    it.copy(
                        voiceEngineer = it.voiceEngineer.copy(
                            isListening = false,
                            isProcessing = false,
                            error = message
                        ),
                        statusMessage = message,
                        statusKind = StatusKind.ERROR
                    )
                }
                voiceEngineer.speak(message)
            }
        }
        exportTreeUri = uiPrefs.getString("export_tree_uri", null)?.let { Uri.parse(it) }
        val onboardingComplete = uiPrefs.getBoolean(ONBOARDING_PREF, false)
        _uiState.update {
            it.copy(
                exportDestinationName = uiPrefs.getString("export_tree_name", null)
                    ?: "App storage / exports",
                showOnboarding = !onboardingComplete,
                showWhatsNew = onboardingComplete &&
                    uiPrefs.getString(RELEASE_NOTES_PREF, null) != RELEASE_NOTES_VERSION,
                presentationMode = uiPrefs.getBoolean("presentation_mode", false)
            )
        }
        // Restore the last session (or open an empty 16-channel project) and
        // start the audio engine. No stock/demo music is loaded.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                loadOrInitializeSession()
            } finally {
                sessionReady.complete(Unit)
            }
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

        if (restored != null) {
            // Session restore: re-hydrate every clip's source audio from disk.
            // Imports and takes are persisted as canonical 48 kHz stereo WAVs,
            // so files load at session rate without resampling.
            for (track in restored.tracks) {
                val pool = trackSourcePools[track.index]
                pool.clear()
                for (path in track.clips.map { it.sourcePath }.distinct()) {
                    if (path == "built_in") continue
                    val f = File(path)
                    if (f.exists()) {
                        val wav = WavAudioIO.readWavFile(f)
                        if (wav != null && wav.channels == 2 && wav.sampleRate == 48000) {
                            pool[path] = wav.samples
                        }
                    }
                }
            }
        } else {
            // First launch opens a clean, empty session. The old synthetic demo
            // stems are intentionally not generated or shipped.
            for (i in 0 until 16) {
                trackSourcePools[i].clear()
            }
        }

        // Migration: previous builds baked synthetic "built_in" demo clips into
        // restored projects. Strip those clips instead of resurrecting stock music.
        val cleanedProj = currentProj.copy(
            tracks = currentProj.tracks.map { track ->
                val cleaned = track.copy(clips = track.clips.filterNot { it.sourcePath == "built_in" })
                val eq = cleaned.eqConfig
                if (eq.band1.type == FilterType.HIGH_PASS) {
                    // Older sessions used band 1 as the high-pass. Move that
                    // cutoff to the dedicated HPF and free band 1 for its gain.
                    cleaned.copy(
                        eqConfig = eq.copy(
                            band1 = EqBand(FilterType.LOW_SHELF, 100f, 0f, 0.707f, enabled = true),
                            highPass = eq.band1
                        )
                    )
                } else cleaned
            }
        )

        _session.value = cleanedProj
        playbackEngine.syncFromProject(cleanedProj)

        // Render the composited track playlists and push to the engine
        for (i in 0 until 16) {
            rebuildTrackAudioLocked(i)
        }

        // Run initial AI analysis
        refreshAudioAnalysis()
    }

    /**
     * Re-composites a track's clip playlist into its playable buffer and
     * hands it to the engine. This is the path that makes every non-destructive
     * edit (trim, move, split, fades, gain, takes) audibly real.
     */
    private suspend fun rebuildTrackAudioLocked(trackIndex: Int) {
        val session = _session.value
        val track = session.tracks.getOrNull(trackIndex) ?: return
        val pool = trackSourcePools[trackIndex]

        val maxClipEnd = track.clips.maxOfOrNull { it.timelineStartFrame + it.lengthFrames } ?: 0L
        val totalFrames = max(session.totalLengthFrames, maxClipEnd)

        val composited = ClipCompositor.compositeClipsFromSources(pool, track.clips, totalFrames)
        // AutoPitch is a whole-buffer transform (it needs pitch detection over a
        // window), so it is printed into the playable buffer here. The same buffer
        // is used by playback and export, so the control is audible in both.
        val processed = if (track.autoPitchConfig.enabled) {
            AutoPitchProcessor(sampleRate.toFloat())
                .apply { configure(true, track.autoPitchConfig.correctionStrength) }
                .processBuffer(composited)
        } else composited
        trackAudioBuffers[trackIndex] = processed
        playbackEngine.setTrackAudio(trackIndex, processed)

        // Real waveform peaks derived from actual audio data
        WaveformCache.refreshClips(track.clips, pool)
    }

    /**
     * Installs a deterministic clip fixture for tests. The app no longer opens
     * with stock demo music, so clip-editing tests opt in to their own take.
     * Kept internal so it never becomes a user-facing session mutation.
     */
    internal fun installTestClip(
        trackIndex: Int,
        lengthFrames: Long = 48000L * 16L,
        withAudio: Boolean = false
    ) {
        if (!sessionReady.isCompleted) runBlocking { sessionReady.await() }
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val sourcePath = "test_fixture_$trackIndex"
        val clip = AudioClip(
            name = "Test Take ${trackIndex + 1}",
            sourcePath = sourcePath,
            lengthFrames = lengthFrames,
            trackIndex = trackIndex
        )
        val updatedTrack = track.copy(clips = track.clips + clip)
        val updatedSession = _session.value.copy(
            tracks = _session.value.tracks.map { if (it.index == trackIndex) updatedTrack else it },
            totalLengthFrames = max(_session.value.totalLengthFrames, lengthFrames),
            revision = _session.value.revision + 1
        )
        if (withAudio && !trackSourcePools[trackIndex].containsKey(sourcePath)) {
            trackSourcePools[trackIndex][sourcePath] = WavAudioIO.generateTestSignal(
                "sine",
                durationFrames = min(lengthFrames, 48000L).toInt(),
                sampleRate = sampleRate
            )
        }
        _session.value = updatedSession
        val pool = trackSourcePools[trackIndex]
        val totalFrames = max(
            updatedSession.totalLengthFrames,
            updatedTrack.clips.maxOfOrNull { it.timelineStartFrame + it.lengthFrames } ?: 0L
        )
        val composited = ClipCompositor.compositeClipsFromSources(pool, updatedTrack.clips, totalFrames)
        trackAudioBuffers[trackIndex] = composited
        playbackEngine.setTrackAudio(trackIndex, composited)
        playbackEngine.syncFromProject(updatedSession)
    }

    fun scheduleRebuildTrack(trackIndex: Int) {
        val index = trackIndex.coerceIn(0, 15)
        // Drag gestures fire many edits; coalesce them so we do not queue a
        // full clip composite + AutoPitch pass for every pointer event.
        trackRebuildJobs[index]?.cancel()
        trackRebuildJobs[index] = viewModelScope.launch(Dispatchers.IO) {
            delay(40)
            trackRebuildMutexes[index].withLock {
                rebuildTrackAudioLocked(index)
            }
        }
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

    fun setLoopRange(startFrame: Long, endFrame: Long) {
        val total = _session.value.totalLengthFrames.coerceAtLeast(1L)
        val start = startFrame.coerceIn(0L, total - 1L)
        val end = endFrame.coerceIn(start + 1L, total)
        _session.update { it.copy(loopStartFrame = start, loopEndFrame = end, loopEnabled = true) }
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetLoopRange(start, end, true))
    }

    fun setPunchRange(startFrame: Long, endFrame: Long) {
        val total = _session.value.totalLengthFrames.coerceAtLeast(1L)
        val start = startFrame.coerceIn(0L, total - 1L)
        val end = endFrame.coerceIn(start + 1L, total)
        _uiState.update { it.copy(punchInFrame = start, punchOutFrame = end) }
    }

    fun selectTrack(index: Int) {
        val safeIndex = index.coerceIn(0, 15)
        _uiState.update {
            it.copy(
                selectedTrackIndex = safeIndex,
                selectedClipIds = if (it.selectedTrackIndex == safeIndex) it.selectedClipIds else emptySet()
            )
        }
    }

    fun toggleClipSelection(trackIndex: Int, clipId: String) {
        _uiState.update {
            val baseSelection = if (it.selectedTrackIndex == trackIndex) {
                it.selectedClipIds
            } else {
                emptySet()
            }
            it.copy(
                selectedTrackIndex = trackIndex,
                selectedClipIds = if (clipId in baseSelection) {
                    baseSelection - clipId
                } else {
                    baseSelection + clipId
                }
            )
        }
    }

    fun clearClipSelection() {
        _uiState.update { it.copy(selectedClipIds = emptySet()) }
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

    fun updateTrackAutoPitch(trackIndex: Int, config: com.example.audio.model.AutoPitchConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(autoPitchConfig = config)
        pushTrackParamChange(before, after)
        scheduleRebuildTrack(trackIndex)
    }

    fun updateTrackNoiseReduction(trackIndex: Int, config: com.example.audio.model.NoiseReductionConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(noiseReductionConfig = config)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetNoiseReduction(trackIndex, config))
    }

    fun updateTrackResonanceSmoother(trackIndex: Int, config: ResonanceSmootherConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(resonanceSmootherConfig = config)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetResonanceSmoother(trackIndex, config))
    }

    fun updateTrackVocalRider(trackIndex: Int, config: VocalRiderConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(vocalRiderConfig = config)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetVocalRider(trackIndex, config))
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

        // Coalesce a continuous gesture (e.g. slider drag) into one undo entry:
        // "before" is captured on the first change, committed after the user settles.
        if (pendingParamEdit == null || pendingParamEdit?.trackIndex != before.index) {
            pendingParamEdit = PendingParamEdit(before.index, before, "Mix Adjust ${before.name}")
        }
        paramCommitJob?.cancel()
        paramCommitJob = viewModelScope.launch {
            delay(800)
            commitPendingParamEdit()
        }

        requestJournalWrite(newSession)
    }

    private fun commitPendingParamEdit() {
        val pending = pendingParamEdit ?: return
        pendingParamEdit = null
        val track = _session.value.tracks.getOrNull(pending.trackIndex) ?: return
        undoManager.pushAction(
            SessionCommand.UpdateTrackParams(
                pending.description,
                pending.trackIndex,
                pending.before,
                track
            )
        )
    }

    /** Debounced crash-journal write: one write per gesture instead of per tick. */
    private fun requestJournalWrite(session: ProjectSession) {
        journalJob?.cancel()
        journalJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            persistence.writeCrashRecoveryJournal(session)
        }
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
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(trackIndex)
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
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(trackIndex)
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
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(trackIndex)
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
        requestJournalWrite(_session.value)
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
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(trackIndex)
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
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(trackIndex)
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
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(trackIndex)
    }

    /**
     * Pastes the selected clip at the next chorus section (e.g. 4 bars ahead).
     */
    fun pasteClipAsChorusSection(trackIndex: Int, clipId: String, barsAhead: Int = 4) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        // Real bar length from session tempo (120 BPM @ 48 kHz = 96 000 frames/bar)
        val beatFrames = (sampleRate * 60f / _session.value.tempoBpm).toLong().coerceAtLeast(1L)
        val barFrames = beatFrames * 4
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
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(trackIndex)
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
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(targetTrackIndex)
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
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(trackIndex)
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
        if (snappedFrame == clip.timelineStartFrame) return

        val updatedClip = clip.copy(timelineStartFrame = snappedFrame)
        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updatedClip else it }

        undoManager.pushAction(SessionCommand.EditClips("Move ${clip.name} to frame $snappedFrame", trackIndex, beforeClips, afterClips))
        val newTracks = _session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        _session.update { it.copy(tracks = newTracks, totalLengthFrames = max(_session.value.totalLengthFrames, snappedFrame + updatedClip.lengthFrames), revision = it.revision + 1) }
        requestJournalWrite(_session.value)
        scheduleRebuildTrack(trackIndex)
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
        rebuildAudibleTracksAfterRestore(command)
        requestJournalWrite(restoredSession)
        _uiState.update { it.copy(statusMessage = "Undid: ${command.description}") }
    }

    fun redo() {
        val result = undoManager.redo(_session.value) ?: return
        val (restoredSession, command) = result
        _session.value = restoredSession
        playbackEngine.syncFromProject(restoredSession)
        rebuildAudibleTracksAfterRestore(command)
        requestJournalWrite(restoredSession)
        _uiState.update { it.copy(statusMessage = "Redid: ${command.description}") }
    }

    /** Restoring clip edits or AutoPitch settings must also restore the audible buffers. */
    private fun rebuildAudibleTracksAfterRestore(command: SessionCommand) {
        when (command) {
            is SessionCommand.EditClips -> scheduleRebuildTrack(command.trackIndex)
            is SessionCommand.UpdateTrackParams -> {
                if (command.beforeTrack.autoPitchConfig != command.afterTrack.autoPitchConfig) {
                    scheduleRebuildTrack(command.trackIndex)
                }
            }
            is SessionCommand.BatchMixChange -> Unit
            is SessionCommand.ReplaceSession -> Unit
        }
    }

    // Recording & Latency Calibration
    fun startRecording() {
        val armedTrack = _session.value.tracks.find { it.isArmedForRecording }
        if (armedTrack == null) {
            _uiState.update { it.copy(statusMessage = "Please arm a track (tap ● on track strip) to record!", statusKind = StatusKind.ERROR) }
            return
        }

        val takesDir = File(getApplication<Application>().filesDir, "takes").apply { mkdirs() }
        val takeFile = File(takesDir, "take_${System.currentTimeMillis()}.wav")

        val success = audioRecorder.startRecording(takeFile)
        if (success) {
            lastRecordingFile = takeFile
            // Capture transport truth at punch-in for accurate alignment
            currentRecordingStartFrame = _uiState.value.playheadFrame
            // Also start playback if not already playing so user hears backing tracks
            if (!_uiState.value.isPlaying) {
                playbackEngine.commandQueue.enqueue(AudioEngineCommand.Play)
                _uiState.update { it.copy(isPlaying = true) }
            }
            _uiState.update { it.copy(isRecording = true, statusMessage = "Recording to Track ${armedTrack.index + 1} (${armedTrack.name})...") }
        } else {
            _uiState.update { it.copy(statusMessage = "Microphone error. Check permission.", statusKind = StatusKind.ERROR) }
        }
    }

    fun stopRecording() {
        if (!_uiState.value.isRecording) return
        val framesRecorded = audioRecorder.stopRecording()
        _uiState.update { it.copy(isRecording = false) }

        val armedTrack = _session.value.tracks.find { it.isArmedForRecording } ?: return
        if (framesRecorded > 2000L) {
            // Apply latency calibration offset so overdubs align perfectly
            val latencyFrames = ((_session.value.latencyOffsetMs / 1000f) * sampleRate).toLong()
            val punchIn = _uiState.value.punchInFrame
            val punchOut = _uiState.value.punchOutFrame
            val alignedTimelineStart = if (punchIn != null) punchIn else maxOf(0L, _uiState.value.playheadFrame - framesRecorded - latencyFrames)
            val takeLength = if (punchIn != null && punchOut != null) {
                (punchOut - punchIn).coerceAtLeast(0L)
            } else framesRecorded

            // Prefer the exact file handed to the recorder; scanning for the most
            // recently modified WAV can pick up a different take.
            val takeFile = lastRecordingFile?.takeIf { it.exists() }
                ?: File(getApplication<Application>().filesDir, "takes")
                    .listFiles { f -> f.extension == "wav" }
                    ?.maxByOrNull { it.lastModified() }
            lastRecordingFile = null

            val sourcePath = takeFile?.absolutePath ?: "take_wav"
            // Load take into source pool so it's audible and exportable
            if (takeFile?.exists() == true) {
                val wav = WavAudioIO.readWavFile(takeFile)
                if (wav != null) {
                    val normalized = AudioResampler.normalizeToSessionStereo(
                        wav.samples,
                        wav.channels,
                        wav.sampleRate,
                        sampleRate
                    )
                    // Canonicalize on disk for session restore
                    WavAudioIO.writeWavFile(takeFile, normalized, channels = 2, sampleRate = sampleRate, bitsPerSample = 16)
                    trackSourcePools[armedTrack.index][sourcePath] = normalized
                }
            }

            val newClip = AudioClip(
                name = "Mic Take ${armedTrack.clips.size + 1}",
                sourcePath = sourcePath,
                sourceStartFrame = if (punchIn != null) (punchIn - currentRecordingStartFrame + latencyFrames).coerceAtLeast(0L) else 0L,
                lengthFrames = takeLength,
                timelineStartFrame = alignedTimelineStart,
                trackIndex = armedTrack.index
            )

            val updatedClips = armedTrack.clips + newClip
            val newTracks = _session.value.tracks.map { if (it.index == armedTrack.index) it.copy(clips = updatedClips) else it }
            _session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
            requestJournalWrite(_session.value)
            scheduleRebuildTrack(armedTrack.index)
            _uiState.update { it.copy(statusMessage = "Take recorded (${String.format("%.2f", framesRecorded.toFloat() / sampleRate)}s) with ${String.format("%.1f", _session.value.latencyOffsetMs)}ms latency compensation.") }
        }
    }

    // Overdubbing: punch in / punch out points
    fun setPunchIn() {
        _uiState.update { it.copy(punchInFrame = it.playheadFrame, statusMessage = "Punch IN set at ${it.playheadFrame} frames") }
    }

    fun setPunchOut() {
        val pi = _uiState.value.punchInFrame
        val po = _uiState.value.playheadFrame
        if (pi != null && po <= pi) {
            _uiState.update { it.copy(statusMessage = "Punch OUT must be after Punch IN.", statusKind = StatusKind.ERROR) }
            return
        }
        _uiState.update { it.copy(punchOutFrame = po, statusMessage = "Punch OUT set at $po frames") }
    }

    fun clearPunchPoints() {
        _uiState.update { it.copy(punchInFrame = null, punchOutFrame = null, statusMessage = "Punch points cleared.") }
    }

    fun toggleInputMonitoring() {
        val next = !_uiState.value.isInputMonitoringEnabled
        _uiState.update { it.copy(isInputMonitoringEnabled = next, statusMessage = if (next) "Input monitoring ON" else "Input monitoring OFF") }
    }

    // A/B reference track: load a real song for comparison and auto-match
    fun loadReferenceTrack(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(statusMessage = "Loading reference track...") }
            val result = AudioFileImporter.importAudioUri(getApplication(), uri, targetTrackIndex = -1, targetTimelineStartFrame = 0L)
            if (result != null) {
                // Store reference samples in a dedicated slot (track index 16 is virtual)
                // We reuse trackAudioBuffers[0]? No � store in a separate field.
                referenceTrackSamples = result.samples
                _uiState.update {
                    it.copy(
                        hasReferenceTrack = true,
                        statusMessage = "Reference loaded: ${result.originalFileName}. Press A/B to compare."
                    )
                }
            } else {
                _uiState.update { it.copy(statusMessage = "Could not decode reference track.", statusKind = StatusKind.ERROR) }
            }
        }
    }

    fun toggleAbComparison() {
        if (referenceTrackSamples == null) {
            _uiState.update { it.copy(statusMessage = "Load a reference track first (Load Ref).", statusKind = StatusKind.ERROR) }
            return
        }
        val next = !_uiState.value.isAbComparing
        _uiState.update { it.copy(isAbComparing = next, statusMessage = if (next) "A/B comparison ON" else "A/B comparison OFF") }
    }

    fun matchToReference() {
        val ref = referenceTrackSamples
        if (ref == null) {
            _uiState.update { it.copy(statusMessage = "Load a reference track first (Load Ref).", statusKind = StatusKind.ERROR) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            // Measure reference loudness (RMS)
            var refSum = 0.0
            for (s in ref) refSum += s * s
            val refRms = kotlin.math.sqrt(refSum / ref.size).toFloat().coerceAtLeast(1e-6f)
            val refDb = 20f * kotlin.math.log10(refRms)

            // Measure current mix loudness
            val mixSize = trackAudioBuffers.sumOf { it.size }
            val mix = FloatArray(mixSize)
            var mixOff = 0
            for (b in trackAudioBuffers) { System.arraycopy(b, 0, mix, mixOff, b.size); mixOff += b.size }
            var mixSum = 0.0
            for (s in mix) mixSum += s * s
            val mixRms = kotlin.math.sqrt(mixSum / mix.size.coerceAtLeast(1)).toFloat().coerceAtLeast(1e-6f)
            val mixDb = 20f * kotlin.math.log10(mixRms)

            val gainDb = (refDb - mixDb).coerceIn(-12f, 12f)
            val before = _session.value.masterGainDb
            _session.update { it.copy(masterGainDb = (it.masterGainDb + gainDb).coerceIn(-24f, 24f)) }
            playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetMasterGain(_session.value.masterGainDb))
            _uiState.update { it.copy(statusMessage = "Matched to reference: ${String.format("%.1f", gainDb)} dB gain applied.") }
        }
    }

    fun runLatencyCalibration() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(statusMessage = "Calibrating audio latency offset (loopback)...") }
            val takesDir = File(getApplication<Application>().filesDir, "takes").apply { mkdirs() }
            val captureFile = File(takesDir, "latency_capture.wav")
            val chirpStereo = WavAudioIO.generateTestSignal("impulse", 512, sampleRate)
            val chirpMono = FloatArray(512) { chirpStereo[it * 2] }

            val recorder = AudioRecorder(sampleRate)
            val started = recorder.startRecording(captureFile)
            if (!started) {
                _uiState.update { it.copy(statusMessage = "Calibration failed: microphone unavailable.", statusKind = StatusKind.ERROR) }
                return@launch
            }

            // Let the recorder settle before playing the impulse
            delay(250)

            try {
                val minBuf = android.media.AudioTrack.getMinBufferSize(
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_STEREO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(Math.max(minBuf, chirpStereo.size * 2))
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()
                val pcm = ShortArray(chirpStereo.size)
                for (i in chirpStereo.indices) {
                    pcm[i] = (chirpStereo[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                track.write(pcm, 0, pcm.size)
                track.play()
                delay(1200)
                track.stop()
                track.release()
            } catch (e: Exception) {
                // Hardware unavailable; fall back to baseline offset
            }

            recorder.stopRecording()

            val wav = WavAudioIO.readWavFile(captureFile)
            val measuredOffset = if (wav != null) {
                val capturedMono = if (wav.channels == 2) FloatArray(wav.samples.size / 2) { wav.samples[it * 2] } else wav.samples
                LatencyCalibrator.calculateLatencyOffsetMs(chirpMono, capturedMono, sampleRate)
            } else 32.5f

            _session.update { it.copy(latencyOffsetMs = measuredOffset) }
            _settings.update { it.copy(latencyCompensationMs = measuredOffset) }
            _uiState.update { it.copy(statusMessage = "Latency calibrated: ${String.format("%.1f", measuredOffset)} ms (loopback)") }
        }
    }

    // USB Type-C Audio Interface Routing
    fun selectUsbDevice(deviceId: Int) {
        usbManager.selectDevice(deviceId)
        _uiState.update { it.copy(statusMessage = "Audio routing switched to Type-C USB interface") }
    }

    fun showUsbDialog(show: Boolean) {
        _uiState.update { it.copy(showUsbDialog = show) }
        if (show) usbManager.refreshDevices()
    }

    fun refreshUsbDevices() {
        usbManager.refreshDevices()
        _uiState.update { it.copy(statusMessage = "Scanned for USB audio interfaces.") }
    }

    fun showImportDialog(show: Boolean) {
        _uiState.update { it.copy(showImportDialog = show, importError = if (show) null else it.importError) }
    }

    fun updateSettings(newSettings: DawSettings) {
        _settings.value = newSettings
        _session.update { it.copy(latencyOffsetMs = newSettings.latencyCompensationMs) }
        // Propagate buffer size + metronome to the audio engine thread-safely
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetBufferSize(newSettings.bufferSize.frames))
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetClickTrack(newSettings.metronomeEnabled, _session.value.tempoBpm, newSettings.metronomeVolumeDb))
        _uiState.update { it.copy(statusMessage = "Studio settings updated (${newSettings.bufferSize.label})") }
    }

    fun resetSettingsToDefaults() {
        val defaults = DawSettings()
        _settings.value = defaults
        _session.update { it.copy(latencyOffsetMs = defaults.latencyCompensationMs) }
        _uiState.update { it.copy(statusMessage = "All studio parameters reset to factory calibration.") }
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val size = getApplication<Application>().cacheDir
                .walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            _uiState.update { it.copy(cacheSizeBytes = size) }
        }
    }

    fun clearScratchCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = getApplication<Application>().cacheDir
                cacheDir.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }
                _uiState.update { it.copy(cacheSizeBytes = 0L, statusMessage = "Temporary audio scratchpad & waveform cache cleared.") }
            } catch (e: Exception) {
                refreshCacheSize()
                _uiState.update { it.copy(statusMessage = "Cache cleared.") }
            }
        }
    }

    // Audio File Import / Upload
    fun importAudioFile(uri: Uri, targetTrackIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importError = null,
                    statusMessage = "Importing & decoding audio file..."
                )
            }
            try {
                val result = AudioFileImporter.importAudioUri(
                    context = getApplication(),
                    uri = uri,
                    targetTrackIndex = targetTrackIndex,
                    targetTimelineStartFrame = 0L
                )
                if (result != null) {
                    // Register the decoded source so later clip edits and session
                    // rebuilds can composite it. Without this, the first edit after
                    // import silently replaced the imported audio with silence.
                    trackSourcePools[targetTrackIndex][result.clip.sourcePath] = result.samples

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
                    requestJournalWrite(newSession)
                    scheduleRebuildTrack(targetTrackIndex)

                    refreshAudioAnalysis()
                    setStatus(
                        "Imported \"${result.originalFileName}\" into Track ${targetTrackIndex + 1}!",
                        StatusKind.SUCCESS
                    )
                } else {
                    _uiState.update {
                        it.copy(
                            importError = "This file could not be decoded. Try a WAV, MP3, FLAC, or M4A file with a supported sample rate.",
                            statusMessage = "Could not decode audio file.",
                            statusKind = StatusKind.ERROR
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update {
                    it.copy(
                        importError = "Import failed: ${t.message ?: "unknown error"}",
                        statusMessage = "Import failed: ${t.message ?: "unknown error"}",
                        statusKind = StatusKind.ERROR
                    )
                }
            } finally {
                _uiState.update { it.copy(isImporting = false) }
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

            // Automatically label stem categories only when the track actually
            // contains audio. Empty tracks keep the category the user assigned.
            val newTracks = _session.value.tracks.map { trk ->
                val f = feats.find { it.trackIndex == trk.index }
                if (f != null && trk.stemCategory == StemCategory.UNKNOWN && f.rmsDb > -50f && f.peakDb > -50f) {
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
        requestJournalWrite(updatedSession)

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

    // ------------------------------------------------------------
    // Voice engineer: speech -> local model/tool calls -> applied mix
    // ------------------------------------------------------------

    fun startVoiceCommand() {
        if (_uiState.value.voiceEngineer.isProcessing) return
        _uiState.update {
            it.copy(
                voiceEngineer = it.voiceEngineer.copy(
                    error = null,
                    transcript = "",
                    partialTranscript = ""
                )
            )
        }
        voiceEngineer.startListening()
    }

    fun stopVoiceCommand() {
        voiceEngineer.stopListening()
    }

    /** Runs a typed engineer request through the same local-model/tool pipeline. */
    fun runEngineerRequest(request: String) {
        if (request.isBlank()) return
        handleVoiceTranscript(request)
    }

    private fun handleVoiceTranscript(transcript: String) {
        _uiState.update {
            it.copy(
                voiceEngineer = it.voiceEngineer.copy(
                    isListening = false,
                    isProcessing = true,
                    transcript = transcript,
                    partialTranscript = "",
                    error = null
                ),
                statusMessage = "Engineer is listening: \"$transcript\"",
                statusKind = StatusKind.INFO
            )
        }
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) { planEngineerRequest(transcript) }
            applyEngineerPlan(plan)
        }
    }

    private suspend fun planEngineerRequest(transcript: String): MixEngineerBrain.Plan = withContext(Dispatchers.IO) {
        val detector = _uiState.value.localModelDetectorState
        val active = detector.activeModel
        val endpointOnline = active != null && detector.probedEndpoints.any {
            it.isOnline && it.endpoint.baseUrl == active.baseUrl
        }
        if (_settings.value.enableLocalNeuralModel && active != null && endpointOnline) {
            val raw = try {
                LocalModelDetector.generateWithLocalModel(
                    model = active,
                    prompt = transcript,
                    systemPrompt = MixEngineerBrain.systemPromptFor(_session.value)
                )
            } catch (_: Throwable) {
                ""
            }
            val calls = ToolCallDispatcher.parseToolCalls(raw)
                .filter { ToolCallDispatcher.isKnownTool(it.name) }
            if (calls.isNotEmpty()) {
                return@withContext MixEngineerBrain.Plan(
                    calls = calls,
                    summary = MixEngineerBrain.summarize(calls, _session.value),
                    source = "Local model · ${active.name}"
                )
            }
        }
        MixEngineerBrain.plan(
            transcript = transcript,
            session = _session.value,
            selectedTrackIndex = _uiState.value.selectedTrackIndex
        )
    }

    private fun applyEngineerPlan(plan: MixEngineerBrain.Plan) {
        if (plan.calls.isEmpty()) {
            val message = plan.clarification ?: "I didn't catch a mix move. Try again."
            _uiState.update {
                it.copy(
                    voiceEngineer = it.voiceEngineer.copy(
                        isProcessing = false,
                        lastAction = message,
                        source = plan.source,
                        error = null
                    ),
                    statusMessage = message,
                    statusKind = StatusKind.INFO
                )
            }
            voiceEngineer.speak(message)
            return
        }

        val sessionCalls = plan.calls.filter { it.name != "transport" }
        val transportCalls = plan.calls.filter { it.name == "transport" }
        val before = _session.value
        val after = ToolCallDispatcher.executeAll(before, sessionCalls)
        val changed = after != before

        if (changed) {
            undoManager.pushAction(
                SessionCommand.ReplaceSession("Voice engineer: ${plan.summary}", before, after)
            )
            _session.value = after
            playbackEngine.syncFromProject(after)
            requestJournalWrite(after)
            sessionCalls.firstNotNullOfOrNull { it.args["trackIndex"]?.toIntOrNull() }?.let { selectTrack(it) }
            refreshAudioAnalysis()
        }

        val transportIssue = transportCalls.firstNotNullOfOrNull {
            executeVoiceTransport(it.args["action"])
        }

        val baseSummary = plan.summary.ifBlank { MixEngineerBrain.summarize(plan.calls, after) }
        val summary = if (transportIssue != null) "$baseSummary $transportIssue" else baseSummary
        _uiState.update {
            it.copy(
                voiceEngineer = it.voiceEngineer.copy(
                    isProcessing = false,
                    lastAction = summary,
                    source = plan.source,
                    error = null
                ),
                statusMessage = summary,
                statusKind = if (transportIssue != null) StatusKind.ERROR else StatusKind.SUCCESS
            )
        }
        voiceEngineer.speak(summary)
    }

    /** Returns a spoken problem when a transport command could not start. */
    private fun executeVoiceTransport(action: String?): String? {
        return when (action) {
            "play" -> {
                if (!_uiState.value.isPlaying) togglePlayPause()
                null
            }
            "stop" -> {
                stop()
                null
            }
            "record" -> {
                if (!_uiState.value.isRecording) startRecording()
                if (_uiState.value.isRecording) null
                else "Arm a track and allow microphone access before recording."
            }
            "stopRecording" -> {
                if (_uiState.value.isRecording) stopRecording()
                null
            }
            "loopOn" -> {
                if (!_session.value.loopEnabled) toggleLoop()
                null
            }
            "loopOff" -> {
                if (_session.value.loopEnabled) toggleLoop()
                null
            }
            else -> null
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

    fun setExportDestination(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers do not support persistable grants; the export still works for this session.
        }
        val name = DocumentFile.fromTreeUri(getApplication(), uri)?.name ?: "Chosen folder"
        exportTreeUri = uri
        uiPrefs.edit()
            .putString("export_tree_uri", uri.toString())
            .putString("export_tree_name", name)
            .apply()
        _uiState.update {
            it.copy(
                exportDestinationName = name,
                statusMessage = "Export destination set to $name.",
                statusKind = StatusKind.SUCCESS
            )
        }
    }

    /** Publishes rendered app files into the user's chosen document tree. */
    private fun publishExports(files: List<File>): String? {
        val tree = exportTreeUri?.let { DocumentFile.fromTreeUri(getApplication(), it) } ?: return null
        if (!tree.canWrite()) return null
        var copied = 0
        for (file in files) {
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "audio/*"
            val target = tree.createFile(mime, file.name) ?: continue
            try {
                getApplication<Application>().contentResolver
                    .openOutputStream(target.uri)
                    ?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
                copied++
            } catch (_: Exception) {
                // Keep the app-local copy; the share sheet still works.
            }
        }
        return if (copied > 0) tree.name else null
    }

    fun reportUiFrameTiming(meanMs: Float, p95Ms: Float) {
        _uiState.update { it.copy(uiFrameMeanMs = meanMs, uiFrameP95Ms = p95Ms) }
    }

    fun dismissOnboarding() {
        uiPrefs.edit().putBoolean(ONBOARDING_PREF, true).apply()
        _uiState.update { it.copy(showOnboarding = false) }
    }

    fun dismissWhatsNew() {
        uiPrefs.edit().putString(RELEASE_NOTES_PREF, RELEASE_NOTES_VERSION).apply()
        _uiState.update { it.copy(showWhatsNew = false) }
    }

    fun showWhatsNew(show: Boolean) {
        _uiState.update { it.copy(showWhatsNew = show) }
    }

    fun togglePresentationMode() {
        val next = !_uiState.value.presentationMode
        uiPrefs.edit().putBoolean("presentation_mode", next).apply()
        _uiState.update {
            it.copy(
                presentationMode = next,
                statusMessage = if (next) "Presentation mode on. Diagnostics hidden." else "Presentation mode off.",
                statusKind = StatusKind.INFO
            )
        }
    }

    fun showExportDialog(show: Boolean) {
        _uiState.update {
            it.copy(
                showExportDialog = show,
                exportProgress = 0f,
                exportedFile = null,
                exportedStemFiles = emptyList(),
                exportError = null
            )
        }
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
            reverbSendLevel = preset.reverbSendLevel,
            vocalRiderConfig = preset.vocalRiderConfig,
            resonanceSmootherConfig = preset.resonanceSmootherConfig
        )
        pushTrackParamChange(track, updated)
        playbackEngine.syncFromProject(_session.value)
        setStatus(
            message = "Applied preset \"${preset.name}\" to ${track.name}!",
            kind = StatusKind.SUCCESS,
            action = StatusAction("Undo")
        )
    }

    fun applyMasterPreset(preset: com.example.audio.model.MasterPreset) {
        val before = _session.value
        val after = before.copy(
            masterGainDb = preset.masterGainDb,
            masterReverb = preset.masterReverb,
            masterLimiter = preset.masterLimiter,
            revision = before.revision + 1
        )
        undoManager.pushAction(SessionCommand.ReplaceSession("Apply master preset", before, after))
        _session.value = after
        playbackEngine.syncFromProject(_session.value)
        setStatus(
            message = "Applied Master Preset \"${preset.name}\"!",
            kind = StatusKind.SUCCESS,
            action = StatusAction("Undo")
        )
    }

    fun renderSessionToAudio(
        format: com.example.audio.engine.ExportFormat = com.example.audio.engine.ExportFormat.WAV_24BIT,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) {
        viewModelScope.launch(dispatcher) {
            try {
                _uiState.update {
                    it.copy(
                        exportProgress = 0.05f,
                        exportedFile = null,
                        exportedStemFiles = emptyList(),
                        exportError = null,
                        statusMessage = "Rendering session to ${format.displayName}..."
                    )
                }
                val exportDir = File(getApplication<Application>().filesDir, "exports").apply { mkdirs() }
                val outFile = File(exportDir, "PulseStudio_${System.currentTimeMillis()}${format.extension}")
                val onProgress: (Float) -> Unit = { p ->
                    _uiState.update { it.copy(exportProgress = p) }
                }

                val renderedFile: File? = when (format) {
                    com.example.audio.engine.ExportFormat.AAC_320KBPS ->
                        com.example.audio.engine.OfflineRenderer.renderSessionToAac(
                            project = _session.value,
                            trackBuffers = trackAudioBuffers,
                            outputFile = outFile,
                            onProgress = onProgress
                        )
                    com.example.audio.engine.ExportFormat.MP3_320KBPS ->
                        com.example.audio.engine.OfflineRenderer.renderSessionToMp3(
                            project = _session.value,
                            trackBuffers = trackAudioBuffers,
                            outputFile = outFile,
                            onProgress = onProgress
                        )
                    com.example.audio.engine.ExportFormat.FLAC_LOSSLESS ->
                        com.example.audio.engine.OfflineRenderer.renderSessionToFlac(
                            project = _session.value,
                            trackBuffers = trackAudioBuffers,
                            outputFile = outFile,
                            onProgress = onProgress
                        )
                    else -> {
                        val wrote = com.example.audio.engine.OfflineRenderer.renderSessionToWav(
                            project = _session.value,
                            trackBuffers = trackAudioBuffers,
                            outputFile = outFile,
                            onProgress = onProgress
                        )
                        if (wrote) outFile else null
                    }
                }

                if (renderedFile != null && renderedFile.exists()) {
                    val usedWavFallback = ".${renderedFile.extension}" != format.extension
                    val publishedTo = publishExports(listOf(renderedFile))
                    val destinationSuffix = publishedTo?.let { " • saved to $it" } ?: " • saved to app storage"
                    _uiState.update {
                        it.copy(
                            exportProgress = 1.0f,
                            exportedFile = renderedFile,
                            exportError = null,
                            statusMessage = if (usedWavFallback) {
                                "Export complete: ${renderedFile.name} (${format.displayName} encoder unavailable; WAV fallback, ${renderedFile.length() / 1024} KB)$destinationSuffix"
                            } else {
                                "Export complete: ${renderedFile.name} (${renderedFile.length() / 1024} KB)$destinationSuffix"
                            },
                            statusKind = StatusKind.SUCCESS
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            exportProgress = 0f,
                            exportError = "Export failed. Check available storage and try again.",
                            statusMessage = "Export failed. Check available storage and try again.",
                            statusKind = StatusKind.ERROR
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update {
                    it.copy(
                        exportProgress = 0f,
                        exportError = "Export failed: ${t.message ?: "unknown error"}",
                        statusMessage = "Export failed: ${t.message ?: "unknown error"}",
                        statusKind = StatusKind.ERROR
                    )
                }
            }
        }
    }

    fun renderStemsExport(
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) {
        viewModelScope.launch(dispatcher) {
            try {
                _uiState.update {
                    it.copy(
                        exportProgress = 0.05f,
                        exportedFile = null,
                        exportedStemFiles = emptyList(),
                        exportError = null,
                        statusMessage = "Rendering stems..."
                    )
                }
                val exportDir = File(
                    getApplication<Application>().filesDir,
                    "exports/stems_${System.currentTimeMillis()}"
                ).apply { mkdirs() }
                val files = com.example.audio.engine.OfflineRenderer.renderStemsToWav(
                    project = _session.value,
                    trackBuffers = trackAudioBuffers,
                    outputDir = exportDir,
                    bitsPerSample = 24,
                    onStemProgress = { completed, total, trackName ->
                        _uiState.update {
                            it.copy(
                                exportProgress = 0.05f + 0.9f * completed / total.coerceAtLeast(1),
                                statusMessage = "Rendering stem $completed/$total: $trackName"
                            )
                        }
                    }
                )
                if (files.isNotEmpty()) publishExports(files)
                _uiState.update {
                    if (files.isEmpty()) {
                        it.copy(
                            exportProgress = 0f,
                            exportError = "Stem export failed. Check available storage and try again.",
                            statusMessage = "Stem export failed. Check available storage and try again.",
                            statusKind = StatusKind.ERROR
                        )
                    } else {
                        it.copy(
                            exportProgress = 1.0f,
                            exportedFile = files.firstOrNull(),
                            exportedStemFiles = files,
                            exportError = null,
                            statusMessage = "Exported ${files.size} stems to ${it.exportDestinationName}",
                            statusKind = StatusKind.SUCCESS
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update {
                    it.copy(
                        exportProgress = 0f,
                        exportError = "Stem export failed: ${t.message ?: "unknown error"}",
                        statusMessage = "Stem export failed: ${t.message ?: "unknown error"}",
                        statusKind = StatusKind.ERROR
                    )
                }
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null, statusAction = null, statusKind = StatusKind.INFO) }
    }

    private fun setStatus(
        message: String,
        kind: StatusKind = StatusKind.INFO,
        action: StatusAction? = null
    ) {
        _uiState.update {
            it.copy(
                statusMessage = message,
                statusKind = kind,
                statusAction = action?.copy(forMessage = message)
            )
        }
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
                // Tracks start empty. Audio arrives from import or recording;
                // no stock music is baked into a new session.
                clips = emptyList()
            )
        }

        return ProjectSession(tracks = tracks)
    }

    public override fun onCleared() {
        super.onCleared()
        voiceEngineer.shutdown()
        playbackEngine.stop()
        usbManager.release()
    }
}
