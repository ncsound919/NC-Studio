package com.ncstudio.daw.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ncstudio.daw.R
import com.ncstudio.daw.audio.ai.AudioFeatureExtractor
import com.ncstudio.daw.audio.ai.DiscoveredModelInfo
import com.ncstudio.daw.audio.ai.EndpointProbeResult
import com.ncstudio.daw.audio.ai.LocalModelDetector
import com.ncstudio.daw.audio.ai.LocalModelDetectorState
import com.ncstudio.daw.audio.ai.LocalModelEndpoint
import com.ncstudio.daw.audio.ai.LocalModelProvider
import com.ncstudio.daw.audio.ai.MixAdvisor
import com.ncstudio.daw.audio.ai.MixEngineerBrain
import com.ncstudio.daw.audio.ai.ToolCallDispatcher
import com.ncstudio.daw.audio.ai.VoiceEngineerState
import com.ncstudio.daw.audio.ai.VoiceMixEngineer
import com.ncstudio.daw.audio.engine.AudioEngineCommand
import com.ncstudio.daw.audio.engine.AudioFileImporter
import com.ncstudio.daw.audio.engine.AudioRecorder
import com.ncstudio.daw.audio.dsp.AutoPitchProcessor
import com.ncstudio.daw.audio.dsp.ClipCompositor
import com.ncstudio.daw.audio.engine.AudioResampler
import com.ncstudio.daw.audio.engine.AudioTrackPlaybackEngine
import com.ncstudio.daw.audio.engine.LatencyCalibrator
import com.ncstudio.daw.audio.engine.OfflineRenderer
import com.ncstudio.daw.audio.engine.UsbAudioInterfaceManager
import com.ncstudio.daw.audio.engine.WaveformCache
import com.ncstudio.daw.audio.engine.WavAudioIO
import com.ncstudio.daw.audio.history.NcbeatException
import com.ncstudio.daw.audio.history.NcbeatImporter
import com.ncstudio.daw.audio.history.ProjectPersistence
import com.ncstudio.daw.audio.history.SessionCommand
import com.ncstudio.daw.audio.history.UndoManager
import com.ncstudio.daw.audio.reference.ReferenceMatchReport
import com.ncstudio.daw.audio.reference.ReferenceMatcher
import com.ncstudio.daw.audio.reference.ReferenceMonitor
import com.ncstudio.daw.audio.reference.ReferenceMove
import com.ncstudio.daw.audio.reference.ReferenceProfile
import com.ncstudio.daw.audio.reference.ReferenceProfiler
import com.ncstudio.daw.audio.reference.ReferenceTrackConfig
import com.ncstudio.daw.audio.service.AudioEngineService
import com.ncstudio.daw.audio.model.AiMixProfile
import com.ncstudio.daw.audio.model.AudioClip
import com.ncstudio.daw.audio.model.AudioSourceFormat
import com.ncstudio.daw.audio.model.AudioTelemetry
import com.ncstudio.daw.audio.model.BufferSizePreset
import com.ncstudio.daw.audio.model.ChannelLimiterConfig
import com.ncstudio.daw.audio.model.CompressorConfig
import com.ncstudio.daw.audio.model.DawSettings
import com.ncstudio.daw.audio.model.DuckingDelayConfig
import com.ncstudio.daw.audio.model.EditMode
import com.ncstudio.daw.audio.model.EditSelection
import com.ncstudio.daw.audio.model.EqBand
import com.ncstudio.daw.audio.model.EqConfig
import com.ncstudio.daw.audio.model.FadeCurve
import com.ncstudio.daw.audio.model.FilterType
import com.ncstudio.daw.audio.model.GridResolution
import com.ncstudio.daw.audio.engine.SnapEngine
import com.ncstudio.daw.audio.model.MasterLimiterConfig
import com.ncstudio.daw.audio.model.MeterBallistics
import com.ncstudio.daw.audio.model.MixSuggestion
import com.ncstudio.daw.audio.model.ProjectSession
import com.ncstudio.daw.audio.model.ResonanceSmootherConfig
import com.ncstudio.daw.audio.model.ReverbConfig
import com.ncstudio.daw.audio.model.SaturationConfig
import com.ncstudio.daw.audio.model.StemCategory
import com.ncstudio.daw.audio.model.StripSilenceMode
import com.ncstudio.daw.audio.model.TakeLane
import com.ncstudio.daw.audio.model.TimelineTool
import com.ncstudio.daw.audio.model.timelineEndFrame
import com.ncstudio.daw.audio.model.withCompedRange
import com.overlay.audio.sample.SampleEdit
import com.ncstudio.daw.audio.model.TrackData
import com.ncstudio.daw.audio.model.TrackFeatures
import com.ncstudio.daw.audio.model.UsbAudioDeviceInfo
import com.ncstudio.daw.audio.model.UsbRoutingMode
import com.ncstudio.daw.audio.model.VocalRiderConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class DawViewModel(application: Application) : AndroidViewModel(application) {

    private val sampleRate = 48000
    private val playbackEngine = AudioTrackPlaybackEngine(sampleRate)
    private var audioRecorder = AudioRecorder(sampleRate)
    private val persistence = ProjectPersistence(application)
    private val undoManager = UndoManager(50)

    // Audio focus and headset-disconnect handling. Owned here because this class
    // owns the transport; the foreground service only publishes the notification.
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handleAudioFocusChange(change)
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlayback()
            }
        }
    }

    private val audioManager: AudioManager?
        get() = getApplication<Application>().getSystemService(AudioManager::class.java)

    val usbManager = UsbAudioInterfaceManager(application).apply {
        onDeviceSelected = { dev ->
            playbackEngine.setPreferredOutputDevice(dev)
        }
    }
    val usbDevices: StateFlow<List<UsbAudioDeviceInfo>> = usbManager.usbDevices
    val activeUsbDevice: StateFlow<UsbAudioDeviceInfo?> = usbManager.activeUsbDevice

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

    private val aiStudio: AiStudioController by lazy {
        AiStudioController(
            scope = viewModelScope,
            currentSettings = { _settings.value },
            updateSettings = { transform -> _settings.update(transform) },
            uiState = _uiState,
        )
    }

    private val projectController: ProjectController by lazy {
        ProjectController(
            scope = viewModelScope,
            persistence = persistence,
            prefs = uiPrefs,
            currentSession = { _session.value },
            updateSession = { _session.value = it },
            newBlankSession = { createInitialSession() },
            applySession = { session, persistAsLast ->
                applyLoadedProject(session, persistAsLast = persistAsLast)
            },
            stopTransport = { stop() },
            clearHistory = {
                undoManager.clear()
                originalSessionSnapshot = null
            },
            onStatus = { message, kind -> setStatus(message, kind) },
        )
    }

    private val referenceController: ReferenceController by lazy {
        ReferenceController(
            context = getApplication(),
            scope = viewModelScope,
            sampleRate = sampleRate,
            uiState = _uiState,
            session = _session,
            undoManager = undoManager,
            playbackEngine = playbackEngine,
            trackAudioBuffers = trackAudioBuffers,
            requestJournalWrite = { requestJournalWrite(it) },
            onStatus = { message, kind, action -> setStatus(message, kind, action) },
        )
    }

    private val exportController: ExportController by lazy {
        ExportController(
            context = getApplication(),
            scope = viewModelScope,
            uiState = _uiState,
            currentSession = { _session.value },
            trackBuffers = { trackAudioBuffers },
            prefs = uiPrefs,
        )
    }

    private val timelineEdits: TimelineEditController by lazy {
        TimelineEditController(
            sampleRate = sampleRate,
            session = _session,
            uiState = _uiState,
            settings = _settings,
            undoManager = undoManager,
            prefs = uiPrefs,
            requestJournalWrite = { requestJournalWrite(it) },
            scheduleRebuildTrack = { scheduleRebuildTrack(it) },
            updateTrackChorus = { idx, cfg -> updateTrackChorus(idx, cfg) },
            publishStatus = { message, kind, action -> setStatus(message, kind, action) },
        )
    }

    val projectState: StateFlow<ProjectUiState> get() = projectController.state

    // Speech input and spoken confirmations for the engineer workflow.
    // Assigned at the top of init() so callbacks can safely refer to this field.
    private lateinit var voiceEngineer: VoiceMixEngineer

    private val uiPrefs by lazy {
        getApplication<Application>().getSharedPreferences("pulse_ui_prefs", 0)
    }
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
        // Pause playback when headphones are unplugged, like every other player.
        ContextCompat.registerReceiver(
            application,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Reopen the timeline the way the engineer left it.
        val savedTool = uiPrefs.getString(TIMELINE_TOOL_PREF, null)
        val savedMode = uiPrefs.getString(EDIT_MODE_PREF, null)
        if (savedTool != null || savedMode != null) {
            _settings.update { current ->
                current.copy(
                    timelineTool = savedTool
                        ?.let { name -> TimelineTool.values().firstOrNull { it.name == name } }
                        ?: current.timelineTool,
                    editMode = savedMode
                        ?.let { name -> EditMode.values().firstOrNull { it.name == name } }
                        ?: current.editMode
                )
            }
        }
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
            projectController.startAutoSave(_settings) { sessionReady.isCompleted }
            if (_settings.value.autoDetectLocalModelsOnStartup) {
                scanForLocalModels()
            }
        }
    }

    private suspend fun loadOrInitializeSession() = withContext(Dispatchers.IO) {
        // Loading happens off the editor thread, so the engineer can already be
        // working: a template applied, a clip moved or a parameter touched
        // while the journal is read is newer than the restore in flight. Those
        // edits are the truth, and publishing the snapshot over them would
        // silently throw the work away.
        val sessionAtLoad = _session.value
        val restored = when {
            persistence.hasCrashRecoveryJournal() -> persistence.restoreFromCrashRecoveryJournal()
            else -> persistence.loadLastSession()
        }
        if (_session.value !== sessionAtLoad) return@withContext

        applyLoadedProject(restored ?: sessionAtLoad, guard = sessionAtLoad)
        projectController.markLoaded(_session.value)
    }

    /**
     * Re-hydrates every source and publishes [project] as the live session.
     * [guard] makes a startup restore abandon its write if the engineer edited
     * while the journal was being read.
     */
    private suspend fun applyLoadedProject(
        project: ProjectSession,
        guard: ProjectSession? = null,
        persistAsLast: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        for (track in project.tracks) {
            val pool = trackSourcePools[track.index]
            pool.clear()
            // Alternate takes are audible after switching, so their audio has
            // to be resident too — not just the clips of the active take.
            for (path in track.audioSourcePaths) {
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

        // Migration: previous builds baked synthetic "built_in" demo clips into
        // restored projects. Strip those clips instead of resurrecting stock music.
        val cleanedProj = project.copy(
            tracks = project.tracks.map { track ->
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

        // Publish atomically with the guard check. Checking the guard and then
        // assigning left a window where an edit made on another thread (a clip
        // installed, a fader moved) was overwritten by the restore — the edit
        // simply vanished. `update` closes that window.
        if (guard != null) {
            var superseded = false
            _session.update { current ->
                if (current !== guard) {
                    superseded = true
                    current
                } else cleanedProj
            }
            if (superseded) return@withContext
        } else {
            _session.value = cleanedProj
        }
        playbackEngine.setSessionLength(cleanedProj.totalLengthFrames)
        playbackEngine.syncFromProject(cleanedProj)

        // Render the composited track playlists and push to the engine
        for (i in 0 until 16) {
            rebuildTrackAudioLocked(i)
        }

        // Reference track: reload the user's imported copy for A/B audition.
        restoreReferenceLocked(cleanedProj)

        // Run initial AI analysis
        refreshAudioAnalysis()

        if (persistAsLast) {
            persistence.saveLastSession(cleanedProj)
            projectController.markAutoSaved(System.currentTimeMillis())
        }
    }

    // Project files and auto-save are owned by ProjectController.
    fun showProjectDialog(mode: ProjectDialogMode) = projectController.showDialog(mode)

    fun dismissProjectDialog() = projectController.dismissDialog()

    fun saveCurrentProject() = projectController.saveCurrent()

    fun saveProjectAs(name: String) = projectController.saveAs(name)

    fun openProject(file: File) = projectController.open(file)

    fun newProject() = projectController.newProject()

    private suspend fun rebuildTrackAudioLocked(trackIndex: Int) {
        val session = _session.value
        val track = session.tracks.getOrNull(trackIndex) ?: return
        val pool = trackSourcePools[trackIndex]

        // An empty track is silent, so it must not allocate a song-length
        // buffer. Sizing to the track's own content also stops a 16-second
        // default session from reserving four minutes per lane.
        if (track.clips.isEmpty()) {
            trackAudioBuffers[trackIndex] = FloatArray(0)
            playbackEngine.setTrackAudio(trackIndex, FloatArray(0))
            WaveformCache.refreshClips(track.clips, pool)
            return
        }

        val maxClipEnd = track.clips.maxOfOrNull { it.timelineStartFrame + it.lengthFrames } ?: 0L
        val totalFrames = maxClipEnd.coerceAtLeast(0L)

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
     * Test-only: swaps the microphone for a deterministic recorder. AudioRecord
     * never produces usable frames under Robolectric, so the recording-to-take
     * path is otherwise unreachable from a unit test.
     */
    internal fun useRecorderForTest(recorder: AudioRecorder) {
        audioRecorder = recorder
    }

    /** Test-only wait for the startup restore to finish. */
    internal suspend fun awaitSessionReady() {
        sessionReady.await()
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
        if (!sessionReady.isCompleted) runBlocking { sessionReady.await() } // audit-ignore CONC-002: test-only helper
        // A persisted snapshot from an earlier test can be empty or carry only a
        // subset of lanes (some tests delete tracks). The fixture needs the target
        // lane, so fall back to the shipped 16-track session unless that lane is
        // already present; otherwise the helper returned silently and the test
        // failed later with an empty clip list.
        if (_session.value.tracks.getOrNull(trackIndex) == null) {
            _session.value = createInitialSession()
        }
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
        val totalFrames = updatedTrack.clips.maxOfOrNull {
            it.timelineStartFrame + it.lengthFrames
        } ?: 0L
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
                val enginePlaying = playbackEngine.isEnginePlaying()
                val livePeaks = playbackEngine.trackPeakLevels
                val liveGrs = playbackEngine.trackGainReductions
                val current = _uiState.value

                // Transport stopped and nothing moved since the last frame: skip
                // cloning the meters and writing state, so an idle studio is not
                // recomposing 30 times a second for identical values.
                val idle = enginePlaying == current.isPlaying &&
                    head == current.playheadFrame &&
                    livePeaks.contentEquals(current.trackPeaks) &&
                    liveGrs.contentEquals(current.trackGainReductions)
                if (idle) {
                    delay(100)
                    continue
                }

                val total = _session.value.totalLengthFrames
                val sec = head / sampleRate.toFloat()
                val minutes = (sec / 60).toInt()
                val seconds = (sec % 60).toInt()
                val hundredths = ((sec % 1.0) * 100).toInt()
                val timeStr = String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)

                val peaks = livePeaks.clone()
                val grs = liveGrs.clone()
                val pL = playbackEngine.masterPeakL
                val pR = playbackEngine.masterPeakR
                val rmsDb = playbackEngine.rmsMeter.latestRmsDb
                val momentaryRmsDbValue = playbackEngine.rmsMeter.momentaryRmsDb
                val tel = playbackEngine.latestTelemetry

                _uiState.update { state ->
                    state.copy(
                        isPlaying = enginePlaying,
                        playheadFrame = head,
                        totalFrames = total,
                        timeFormatted = timeStr,
                        trackPeaks = peaks,
                        trackGainReductions = grs,
                        masterPeakL = pL,
                        masterPeakR = pR,
                        momentaryRmsDb = momentaryRmsDbValue,
                        latestRmsDb = rmsDb,
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
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    /**
     * Starts the transport: takes audio focus and promotes the foreground
     * service so playback survives leaving the app.
     */
    private fun startPlayback() {
        acquireAudioFocus()
        // A render thread that died with its output device (USB unplugged, route
        // lost) must come back before a Play command can be heard again.
        if (!playbackEngine.isEngineRunning()) playbackEngine.start()
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.Play)
        _uiState.update { it.copy(isPlaying = true) }
        AudioEngineService.startPlayback(getApplication(), _session.value.projectName)
    }

    /**
     * Pauses the transport and drops focus plus the playback notification.
     * [resumable] marks an interruption (phone call, another player) that should
     * resume playback once focus comes back.
     */
    private fun pausePlayback(resumable: Boolean = false) {
        if (!_uiState.value.isPlaying) return
        resumeOnFocusGain = resumable
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.Pause)
        _uiState.update { it.copy(isPlaying = false) }
        releaseAudioFocus()
        AudioEngineService.stopPlayback(getApplication())
    }

    fun stop() {
        resumeOnFocusGain = false
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.Stop)
        _uiState.update { it.copy(isPlaying = false, playheadFrame = 0L) }
        releaseAudioFocus()
        AudioEngineService.stopPlayback(getApplication())
    }

    private fun acquireAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { audioFocusRequest = it }
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun releaseAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun handleAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pausePlayback(resumable = true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    startPlayback()
                }
            }
        }
    }

    fun seekToFraction(fraction: Float) {
        val total = _session.value.totalLengthFrames
        val target = (fraction.coerceIn(0f, 1f) * total).toLong()
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.Seek(target))
    }

    /**
     * Absolute seek used by the draggable playhead and the rewind/fast-forward
     * buttons. The UI position moves immediately so the marker never lags the
     * finger while the engine drains the seek command.
     */
    fun seekToFrame(frame: Long) {
        val total = _session.value.totalLengthFrames.coerceAtLeast(1L)
        val target = frame.coerceIn(0L, total)
        _uiState.update { it.copy(playheadFrame = target) }
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.Seek(target))
    }

    /** Rewind / fast-forward by whole bars, tempo-aware. */
    fun nudgePlayhead(deltaBars: Int) {
        if (deltaBars == 0) return
        val barFrames = SnapEngine.framesPerStep(
            GridResolution.BAR,
            sampleRate,
            _session.value.tempoBpm
        )
        seekToFrame(_uiState.value.playheadFrame + barFrames * deltaBars)
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

    fun updateTrackChorus(trackIndex: Int, config: com.ncstudio.daw.audio.model.ChorusConfig) {
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

    fun updateTrackAutoPitch(trackIndex: Int, config: com.ncstudio.daw.audio.model.AutoPitchConfig) {
        val before = _session.value.tracks[trackIndex]
        val after = before.copy(autoPitchConfig = config)
        pushTrackParamChange(before, after)
        scheduleRebuildTrack(trackIndex)
    }

    fun updateTrackNoiseReduction(trackIndex: Int, config: com.ncstudio.daw.audio.model.NoiseReductionConfig) {
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

    /**
     * Applies a discrete track edit as its own undo entry. Take switching,
     * rating and deletion are single actions, not a slider drag, so they must
     * not coalesce into the next fader move.
     */
    private fun applyDiscreteTrackEdit(before: TrackData, after: TrackData, description: String) {
        flushPendingParamEdit()
        val updatedList = _session.value.tracks.map { if (it.index == before.index) after else it }
        val newSession = _session.value.copy(tracks = updatedList, revision = _session.value.revision + 1)
        _session.value = newSession
        undoManager.pushAction(
            SessionCommand.UpdateTrackParams(description, before.index, before, after)
        )
        requestJournalWrite(newSession)
    }

    /** Switches which recorded take is audible on [trackIndex]. */
    fun selectTakeLane(trackIndex: Int, laneId: String) {
        val before = _session.value.tracks.getOrNull(trackIndex) ?: return
        val lane = before.takeLanes.firstOrNull { it.id == laneId } ?: return
        val after = before.withActiveTake(laneId)
        if (after == before) return
        applyDiscreteTrackEdit(before, after, "Select ${lane.name}")
        scheduleRebuildTrack(trackIndex)
        _uiState.update { it.copy(statusMessage = "${lane.name} selected on ${before.name}") }
    }

    /** Cycles an unrated take to 1..5 stars, then back to unrated. */
    fun rateTakeLane(trackIndex: Int, laneId: String) {
        val before = _session.value.tracks.getOrNull(trackIndex) ?: return
        val lane = before.takeLanes.firstOrNull { it.id == laneId } ?: return
        val nextRating = if (lane.rating >= 5) 0 else lane.rating + 1
        val after = before.copy(
            takeLanes = before.takeLanes.map { if (it.id == laneId) it.copy(rating = nextRating) else it }
        )
        applyDiscreteTrackEdit(before, after, "Rate ${lane.name}")
    }

    fun renameTakeLane(trackIndex: Int, laneId: String, name: String) {
        val before = _session.value.tracks.getOrNull(trackIndex) ?: return
        val cleaned = name.trim().take(40)
        if (cleaned.isEmpty()) return
        val after = before.copy(
            takeLanes = before.takeLanes.map { if (it.id == laneId) it.copy(name = cleaned) else it }
        )
        applyDiscreteTrackEdit(before, after, "Rename take")
    }

    /**
     * Deletes a take. The active take cannot be deleted while it is the only
     * one: the user would lose the clips they can currently hear.
     */
    fun deleteTakeLane(trackIndex: Int, laneId: String) {
        val before = _session.value.tracks.getOrNull(trackIndex) ?: return
        if (before.takeLanes.size <= 1) {
            _uiState.update {
                it.copy(statusMessage = "Keep at least one take on ${before.name}.", statusKind = StatusKind.ERROR)
            }
            return
        }
        val remaining = before.takeLanes.filterNot { it.id == laneId }
        val wasActive = before.activeTakeId == laneId
        val nextActive = if (wasActive) remaining.first() else null
        val after = before.copy(
            takeLanes = remaining,
            clips = nextActive?.clips ?: before.clips,
            activeTakeId = nextActive?.id ?: before.activeTakeId
        )
        applyDiscreteTrackEdit(before, after, "Delete take")
        if (wasActive) scheduleRebuildTrack(trackIndex)
    }

    /**
     * Comps the [startFrame]..[endFrame] range of [laneId] into the take that is
     * audible now. The borrowed audio replaces whatever the active take has
     * there, which is the tape-style comp move: one phrase from take 3, one
     * phrase from take 5, one performance on the timeline.
     */
    fun compTakeRange(trackIndex: Int, laneId: String, startFrame: Long, endFrame: Long) {
        val before = _session.value.tracks.getOrNull(trackIndex) ?: return
        val lane = before.takeLanes.firstOrNull { it.id == laneId } ?: return
        val activeId = before.activeTakeId
        if (activeId == null) {
            _uiState.update {
                it.copy(
                    statusMessage = "Record a take on ${before.name} before comping.",
                    statusKind = StatusKind.ERROR
                )
            }
            return
        }
        if (activeId == laneId) {
            _uiState.update {
                it.copy(
                    statusMessage = "${lane.name} is already the audible take on ${before.name}.",
                    statusKind = StatusKind.ERROR
                )
            }
            return
        }
        val start = minOf(startFrame, endFrame).coerceAtLeast(0L)
        val end = maxOf(startFrame, endFrame)
        if (end - start < sampleRate / 100) {
            _uiState.update {
                it.copy(
                    statusMessage = "Select a longer range: comps shorter than 10 ms are not audible.",
                    statusKind = StatusKind.ERROR
                )
            }
            return
        }
        val compedClips = before.clips.withCompedRange(lane.clips, start, end)
        if (compedClips == before.clips) {
            _uiState.update {
                it.copy(
                    statusMessage = "${lane.name} has no audio in that range.",
                    statusKind = StatusKind.ERROR
                )
            }
            return
        }
        val after = before.copy(clips = compedClips)
        applyDiscreteTrackEdit(before, after, "Comp ${lane.name}")
        // A take can be longer than the project; the comp must not fall off the
        // end of the timeline or out of the export.
        val compEnd = compedClips.maxOfOrNull { it.timelineEndFrame } ?: 0L
        val oldTotalLength = _session.value.totalLengthFrames
        if (compEnd > oldTotalLength) {
            _session.update { it.copy(totalLengthFrames = compEnd) }
            playbackEngine.setSessionLength(compEnd)
        }
        scheduleRebuildTrack(trackIndex)
        val seconds = String.format(java.util.Locale.US, "%.2f", (end - start).toFloat() / sampleRate)
        _uiState.update {
            it.copy(
                statusMessage = "Comped ${seconds}s from ${lane.name} into ${before.name}",
                statusKind = StatusKind.SUCCESS,
                statusAction = StatusAction("Undo")
            )
        }
    }

    // ---- Studio plugin rack & reference mix templates ----

    fun updateTrackPluginChain(trackIndex: Int, chain: List<com.overlay.audio.plugins.PluginSlot>) {
        val before = _session.value.tracks.getOrNull(trackIndex) ?: return
        val after = before.copy(pluginChain = chain)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(
            AudioEngineCommand.SetTrackPluginChain(trackIndex, com.overlay.audio.plugins.PluginChain(chain))
        )
    }

    /**
     * Replaces the selected track's inserts with the canonical vocal chain.
     * This is a normal rack change, so it is undoable and every slot can still
     * be swapped, bypassed or moved afterwards.
     */
    fun applyVocalPluginChain(trackIndex: Int) {
        val track = _session.value.tracks.getOrNull(trackIndex) ?: return
        updateTrackPluginChain(trackIndex, com.ncstudio.daw.audio.plugins.VocalChainPresets.standard())
        _uiState.update {
            it.copy(
                statusMessage = "Vocal chain loaded on ${track.name}: clean, level, EQ, two compressors, then delay and reverb",
                statusKind = StatusKind.SUCCESS,
                statusAction = StatusAction("Undo")
            )
        }
    }

    fun setTrackOutputBus(trackIndex: Int, busId: String) {
        val before = _session.value.tracks.getOrNull(trackIndex) ?: return
        val after = before.copy(outputBusId = busId)
        pushTrackParamChange(before, after)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetTrackOutputBus(trackIndex, busId))
    }

    /** Per-module gain reduction for the rack meters. */
    fun trackSlotGainReductions(trackIndex: Int): List<Float> =
        playbackEngine.trackSlotGainReductions(trackIndex).toList()

    /**
     * Applies a reference template to the whole session: role chains onto the
     * tracks whose names match the role, group bus routing, and the mix bus
     * chain from the reference mix.
     */
    fun applyMixTemplate(templateId: String) {
        val template = com.ncstudio.daw.audio.plugins.MixReferenceTemplateLibrary.byId(templateId) ?: return
        val before = _session.value
        val applied = com.ncstudio.daw.audio.plugins.TemplateApplier.applyToSession(before, template)
        val after = applied.copy(revision = before.revision + 1)
        if (after == before) {
            setStatus(
                message = "\"${template.name}\" matched no tracks in this session. Rename or import tracks to match a role like Lead Vocal, Kick or Bass.",
                kind = StatusKind.ERROR
            )
            return
        }
        undoManager.pushAction(
            SessionCommand.ReplaceSession("Apply mix template: ${template.name}", before, after)
        )
        _session.value = after
        playbackEngine.syncFromProject(_session.value)
        val matched = com.ncstudio.daw.audio.plugins.TemplateApplier.matchedTrackCount(before, template)
        setStatus(
            message = "${template.name} applied: $matched tracks chained, buses routed, mix bus set. Reference: ${template.referenceTrack}.",
            kind = StatusKind.SUCCESS,
            action = StatusAction("Undo")
        )
    }

    /** Applies one role chain of a template to a single track. */
    fun applyTemplateRoleToTrack(trackIndex: Int, role: com.ncstudio.daw.audio.plugins.ChainRole, templateId: String) {
        val template = com.ncstudio.daw.audio.plugins.MixReferenceTemplateLibrary.byId(templateId) ?: return
        val before = _session.value.tracks.getOrNull(trackIndex) ?: return
        val after = com.ncstudio.daw.audio.plugins.TemplateApplier.applyRoleToTrack(before, role, template)
        if (after == before) return
        pushTrackParamChange(before, after)
        playbackEngine.syncFromProject(_session.value)
        setStatus(
            message = "Applied ${role.displayName} chain from ${template.name} to ${before.name}.",
            kind = StatusKind.SUCCESS,
            action = StatusAction("Undo")
        )
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

    /**
     * Commits a parameter edit that is still inside its coalescing window.
     * Undo calls this first: the user asking to undo means "take that last edit
     * back", not "it never happened", so a chain edit, preset or slider move
     * made a moment ago must be on the undo stack before it is undone.
     */
    private fun flushPendingParamEdit() {
        paramCommitJob?.cancel()
        paramCommitJob = null
        commitPendingParamEdit()
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
        if (!_settings.value.enableCrashRecovery) return
        journalJob?.cancel()
        journalJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            persistence.writeCrashRecoveryJournal(session)
        }
    }

    // Timeline editing lives in TimelineEditController; these delegates keep
    // the ViewModel's public API stable for the UI and tests.

    fun splitClipAtPlayhead(trackIndex: Int) = timelineEdits.splitClipAtPlayhead(trackIndex)

    fun splitClipAtFrame(trackIndex: Int, clipId: String, frame: Long) =
        timelineEdits.splitClipAtFrame(trackIndex, clipId, frame)

    fun beginClipGesture(trackIndex: Int, description: String) =
        timelineEdits.beginClipGesture(trackIndex, description)

    fun endClipGesture() = timelineEdits.endClipGesture()

    fun spotClipAtFrame(
        trackIndex: Int,
        clipId: String,
        frame: Long,
        placeByEnd: Boolean = false,
    ): Boolean = timelineEdits.spotClipAtFrame(trackIndex, clipId, frame, placeByEnd)

    fun setTimelineTool(tool: TimelineTool) = timelineEdits.setTimelineTool(tool)

    fun setEditMode(mode: EditMode) = timelineEdits.setEditMode(mode)

    fun setEditSelection(trackIndex: Int, startFrame: Long, endFrame: Long) =
        timelineEdits.setEditSelection(trackIndex, startFrame, endFrame)

    fun clearEditSelection() = timelineEdits.clearEditSelection()

    fun commitEditSelection() = timelineEdits.commitEditSelection()

    fun healSelectedClip(): Boolean = timelineEdits.healSelectedClip()

    fun separateAtPlayhead(): Int = timelineEdits.separateAtPlayhead()

    fun separateAtSelection(): Int = timelineEdits.separateAtSelection()

    fun separateClipsAtFrame(frame: Long, trackIndex: Int): Int =
        timelineEdits.separateClipsAtFrame(frame, trackIndex)

    fun healSeparation(trackIndex: Int, clipId: String): Boolean =
        timelineEdits.healSeparation(trackIndex, clipId)

    fun deleteSelectedClips(): Int = timelineEdits.deleteSelectedClips()

    fun nudgeSelection(forward: Boolean, byBar: Boolean = false): Int =
        timelineEdits.nudgeSelection(forward, byBar)

    fun crossfadeSelectedClips(): Boolean = timelineEdits.crossfadeSelectedClips()

    fun trimClipEnd(trackIndex: Int, clipId: String, trimFrames: Long) =
        timelineEdits.trimClipEnd(trackIndex, clipId, trimFrames)

    fun trimClipEndToFrame(
        trackIndex: Int,
        clipId: String,
        rawEndFrame: Long,
        snapToleranceFrames: Long = TimelineEditController.DEFAULT_SNAP_TOLERANCE_FRAMES,
    ) = timelineEdits.trimClipEndToFrame(trackIndex, clipId, rawEndFrame, snapToleranceFrames)

    fun trimClipStartToFrame(
        trackIndex: Int,
        clipId: String,
        rawStartFrame: Long,
        snapToleranceFrames: Long = TimelineEditController.DEFAULT_SNAP_TOLERANCE_FRAMES,
    ) = timelineEdits.trimClipStartToFrame(trackIndex, clipId, rawStartFrame, snapToleranceFrames)

    fun duplicateClip(trackIndex: Int, clipId: String) = timelineEdits.duplicateClip(trackIndex, clipId)

    fun renameClip(trackIndex: Int, clipId: String, newName: String) =
        timelineEdits.renameClip(trackIndex, clipId, newName)

    fun setClipGain(trackIndex: Int, clipId: String, gainDb: Float) =
        timelineEdits.setClipGain(trackIndex, clipId, gainDb)

    fun setClipFadeIn(
        trackIndex: Int,
        clipId: String,
        fadeInMs: Float,
        curve: FadeCurve = FadeCurve.EQUAL_POWER,
    ) = timelineEdits.setClipFadeIn(trackIndex, clipId, fadeInMs, curve)

    fun setClipFadeOut(
        trackIndex: Int,
        clipId: String,
        fadeOutMs: Float,
        curve: FadeCurve = FadeCurve.EQUAL_POWER,
    ) = timelineEdits.setClipFadeOut(trackIndex, clipId, fadeOutMs, curve)

    /**
     * Pro Tools-style Strip Silence on the timeline. Detects silent spans with
     * the shared `nc.sample-edit.v1` analyser and rewrites the clip layout:
     * TRIM_EDGES keeps one clip with its head and tail trimmed, SPLIT_REGIONS
     * keeps one clip per loud region and leaves the silence as gaps.
     *
     * Whole-clip analysis happens on the IO dispatcher; the edit itself is one
     * undo entry and one audible rebuild.
     */
    fun stripSilenceSelectedClips(
        settings: SampleEdit.StripSettings = SampleEdit.StripSettings(),
        mode: StripSilenceMode = StripSilenceMode.SPLIT_REGIONS,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = _session.value
            val trackIndex = _uiState.value.selectedTrackIndex
            val track = session.tracks.getOrNull(trackIndex) ?: return@launch
            if (track.clips.isEmpty()) {
                setStatus("Strip silence: track ${trackIndex + 1} has no clips.", StatusKind.INFO)
                return@launch
            }
            val selectedIds = _uiState.value.selectedClipIds
            val targets = if (selectedIds.isEmpty()) {
                track.clips
            } else {
                track.clips.filter { it.id in selectedIds }
            }
            val pool = trackSourcePools.getOrNull(trackIndex)
            val rebuilt = ArrayList<AudioClip>(track.clips.size)
            val editedIds = LinkedHashSet<String>()
            var removedFrames = 0L
            var analysed = 0
            var producedClips = 0

            for (clip in track.clips.sortedBy { it.timelineStartFrame }) {
                if (targets.none { it.id == clip.id }) {
                    rebuilt += clip
                    continue
                }
                val samples = pool?.get(clip.sourcePath) ?: runCatching {
                    val wav = WavAudioIO.readWavFile(File(clip.sourcePath))
                    if (wav != null && wav.channels == 2 && wav.sampleRate == session.sampleRate) {
                        pool?.put(clip.sourcePath, wav.samples)
                        wav.samples
                    } else null
                }.getOrNull()
                if (samples == null || samples.isEmpty()) {
                    rebuilt += clip
                    editedIds += clip.id
                    continue
                }
                val sourceFrames = samples.size / 2
                val clipStart = clip.sourceStartFrame.coerceIn(0L, sourceFrames.toLong()).toInt()
                val clipEnd = (clip.sourceStartFrame + clip.lengthFrames)
                    .coerceIn(clipStart.toLong(), sourceFrames.toLong()).toInt()
                if (clipEnd <= clipStart) {
                    rebuilt += clip
                    editedIds += clip.id
                    continue
                }
                analysed++
                val spans = SampleEdit.keepSpans(
                    samples = samples,
                    channels = 2,
                    sampleRate = session.sampleRate,
                    settings = settings,
                    startFrame = clipStart,
                    endFrameExclusive = clipEnd,
                )
                if (spans.isEmpty()) {
                    // Every frame was below the threshold: the clip is silence.
                    removedFrames += clip.lengthFrames
                    continue
                }
                if (mode == StripSilenceMode.TRIM_EDGES) {
                    val first = spans.first()
                    val last = spans.last()
                    val newStart = first.startFrame.toLong()
                    val newLength = (last.endFrameExclusive - first.startFrame).toLong()
                    val delta = newStart - clip.sourceStartFrame
                    if (delta == 0L && newLength == clip.lengthFrames) {
                        rebuilt += clip
                        editedIds += clip.id
                    } else {
                        removedFrames += (clip.lengthFrames - newLength).coerceAtLeast(0L)
                        val trimmed = clip.copy(
                            sourceStartFrame = newStart,
                            lengthFrames = newLength,
                            timelineStartFrame = (clip.timelineStartFrame + delta).coerceAtLeast(0L),
                            fadeInFrames = clip.fadeInFrames.coerceAtMost(newLength),
                            fadeOutFrames = clip.fadeOutFrames.coerceAtMost(newLength),
                        )
                        rebuilt += trimmed
                        editedIds += trimmed.id
                        producedClips++
                    }
                } else {
                    for (span in spans) {
                        val length = span.frames.toLong()
                        val delta = span.startFrame.toLong() - clip.sourceStartFrame
                        val wholeClip = span.startFrame == clipStart && span.endFrameExclusive == clipEnd
                        val piece = clip.copy(
                            id = if (wholeClip) clip.id else UUID.randomUUID().toString(),
                            sourceStartFrame = span.startFrame.toLong(),
                            lengthFrames = length,
                            timelineStartFrame = (clip.timelineStartFrame + delta).coerceAtLeast(0L),
                            fadeInFrames = clip.fadeInFrames.coerceAtMost(length),
                            fadeOutFrames = clip.fadeOutFrames.coerceAtMost(length),
                        )
                        rebuilt += piece
                        editedIds += piece.id
                        producedClips++
                        removedFrames += (clip.lengthFrames - length).coerceAtLeast(0L)
                    }
                }
            }

            if (analysed == 0) {
                setStatus("Strip silence: no clip audio available to analyse.", StatusKind.INFO)
                return@launch
            }
            val before = track.clips
            val after = rebuilt.sortedBy { it.timelineStartFrame }
            if (before == after) {
                setStatus("Strip silence: nothing below ${settings.thresholdDb.toInt()} dB was found.", StatusKind.INFO)
                return@launch
            }
            timelineEdits.applyClipBatch(
                trackIndex = trackIndex,
                description = "Strip silence ($analysed clips, ${mode.displayName})",
                clips = after,
            )
            _uiState.update {
                it.copy(selectedClipIds = if (selectedIds.isEmpty()) emptySet() else editedIds)
            }
            val removedSeconds = removedFrames.toFloat() / session.sampleRate.coerceAtLeast(1)
            setStatus(
                "Strip silence: ${analysed} clip(s) analysed, $producedClips rewritten, " +
                    "${String.format(Locale.US, "%.2f", removedSeconds)} s of silence removed.",
                StatusKind.SUCCESS,
                StatusAction("Undo"),
            )
        }
    }

    fun pasteClipAsChorusSection(trackIndex: Int, clipId: String, barsAhead: Int = 4) =
        timelineEdits.pasteClipAsChorusSection(trackIndex, clipId, barsAhead)

    fun pasteClipAsDoubledChorus(sourceTrackIndex: Int, clipId: String, targetTrackIndex: Int) =
        timelineEdits.pasteClipAsDoubledChorus(sourceTrackIndex, clipId, targetTrackIndex)

    fun adaptClipToChorusLoop(trackIndex: Int, clipId: String, repeatCount: Int = 3) =
        timelineEdits.adaptClipToChorusLoop(trackIndex, clipId, repeatCount)

    fun showClipBin(show: Boolean) = timelineEdits.showClipBin(show)

    fun moveClipToFrame(
        trackIndex: Int,
        clipId: String,
        rawTargetFrame: Long,
        snapToleranceFrames: Long = TimelineEditController.DEFAULT_SNAP_TOLERANCE_FRAMES,
    ) = timelineEdits.moveClipToFrame(trackIndex, clipId, rawTargetFrame, snapToleranceFrames)

    /** Drops a clip onto another track as one undoable edit. */
    fun moveClipToTrack(sourceTrackIndex: Int, clipId: String, targetTrackIndex: Int) =
        timelineEdits.moveClipToTrack(sourceTrackIndex, clipId, targetTrackIndex)

    fun toggleMagneticSnap() = timelineEdits.toggleMagneticSnap()

    fun cycleGridResolution() = timelineEdits.cycleGridResolution()

    fun nudgeSelectedClip(trackIndex: Int, clipId: String, forward: Boolean) =
        timelineEdits.nudgeSelectedClip(trackIndex, clipId, forward)

    // Undo / Redo
    fun canUndo(): Boolean = undoManager.canUndo()
    fun canRedo(): Boolean = undoManager.canRedo()

    fun undo() {
        flushPendingParamEdit()
        val result = undoManager.undo(_session.value) ?: return
        val (restoredSession, command) = result
        _session.value = restoredSession
        playbackEngine.syncFromProject(restoredSession)
        rebuildAudibleTracksAfterRestore(command)
        requestJournalWrite(restoredSession)
        _uiState.update { it.copy(statusMessage = "Undid: ${command.description}") }
    }

    fun redo() {
        flushPendingParamEdit()
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
                if (command.beforeTrack.autoPitchConfig != command.afterTrack.autoPitchConfig ||
                    command.beforeTrack.clips != command.afterTrack.clips
                ) {
                    scheduleRebuildTrack(command.trackIndex)
                }
            }
            is SessionCommand.MoveClipAcrossTracks -> {
                scheduleRebuildTrack(command.sourceTrackIndex)
                scheduleRebuildTrack(command.targetTrackIndex)
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
            if (!_uiState.value.isPlaying) startPlayback()
            _uiState.update { it.copy(isRecording = true, statusMessage = "Recording to Track ${armedTrack.index + 1} (${armedTrack.name})...") }
        } else {
            _uiState.update { it.copy(statusMessage = "Microphone error. Check permission.", statusKind = StatusKind.ERROR) }
        }
    }

    fun stopRecording() {
        if (!_uiState.value.isRecording) return
        val framesRecorded = audioRecorder.stopRecording()
        _uiState.update { it.copy(isRecording = false) }

        if (framesRecorded < 0L) {
            // The writer is still finalising the WAV. Do not delete or re-read
            // the file while it is open — that discards a valid take.
            _uiState.update {
                it.copy(
                    statusMessage = "Take is still being written to disk. It will appear in the takes folder when complete.",
                    statusKind = StatusKind.ERROR
                )
            }
            return
        }

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

            // Every take keeps its own clips. The first overdub promotes the
            // existing material to "Take 1" so nothing recorded is replaced.
            val takeNumber = armedTrack.takeLanes.size +
                if (armedTrack.activeTakeId == null && armedTrack.clips.isNotEmpty()) 2 else 1
            val newClip = AudioClip(
                name = "Mic Take $takeNumber",
                sourcePath = sourcePath,
                sourceStartFrame = if (punchIn != null) (punchIn - currentRecordingStartFrame + latencyFrames).coerceAtLeast(0L) else 0L,
                lengthFrames = takeLength,
                timelineStartFrame = alignedTimelineStart,
                trackIndex = armedTrack.index
            )

            val lane = TakeLane(
                name = "Take $takeNumber",
                clips = listOf(newClip),
                recordedAtMillis = System.currentTimeMillis()
            )
            val updatedTrack = armedTrack.withNewTake(lane)
            val newTracks = _session.value.tracks.map {
                if (it.index == armedTrack.index) updatedTrack else it
            }
            // A take longer than the current project must extend the timeline,
            // otherwise playback and export stop before the recording ends.
            val takeEnd = alignedTimelineStart + takeLength
            val oldTotalLength = _session.value.totalLengthFrames
            val newTotalLength = max(oldTotalLength, takeEnd)
            _session.update {
                it.copy(
                    tracks = newTracks,
                    totalLengthFrames = newTotalLength,
                    revision = it.revision + 1
                )
            }
            if (newTotalLength > oldTotalLength) {
                playbackEngine.setSessionLength(newTotalLength)
            }
            requestJournalWrite(_session.value)
            scheduleRebuildTrack(armedTrack.index)
            _uiState.update { it.copy(statusMessage = "Take recorded (${String.format(java.util.Locale.US, "%.2f", framesRecorded.toFloat() / sampleRate)}s) with ${String.format(java.util.Locale.US, "%.1f", _session.value.latencyOffsetMs)}ms latency compensation.") }
        } else {
            // Too short to be a usable take: discard it and say why, instead of
            // stopping silently and leaving a stub file in the takes folder.
            lastRecordingFile?.delete()
            lastRecordingFile = null
            _uiState.update {
                it.copy(
                    statusMessage = "Take discarded: recordings under 0.05 s cannot be used.",
                    statusKind = StatusKind.ERROR
                )
            }
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

    // Reference A/B lives in ReferenceController; these delegates keep the
    // public ViewModel API stable for the UI and tests.

    fun loadReferenceTrack(uri: Uri) = referenceController.loadReferenceTrack(uri)

    internal fun installReference(
        samples: FloatArray,
        name: String,
        filePath: String,
        offsetFrames: Long = 0L,
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            referenceController.installReference(samples, name, filePath, offsetFrames)
        }
    }

    internal suspend fun installReferenceNow(
        samples: FloatArray,
        name: String,
        filePath: String,
        offsetFrames: Long = 0L,
    ) = referenceController.installReferenceNow(samples, name, filePath, offsetFrames)

    fun clearReference() = referenceController.clearReference()

    fun setReferenceOffset(offsetFrames: Long) = referenceController.setReferenceOffset(offsetFrames)

    fun toggleAbComparison() = referenceController.toggleAbComparison()

    fun setReferenceAudition(
        enabled: Boolean,
        matchGainDb: Float = referenceController.defaultMatchGain(),
    ) = referenceController.setReferenceAudition(enabled, matchGainDb)

    fun matchToReference() = referenceController.matchToReference()

    internal suspend fun matchToReferenceNow() = referenceController.matchToReferenceNow()

    fun refreshReferenceMatch() = referenceController.refreshReferenceMatch()

    internal suspend fun refreshReferenceMatchNow() = referenceController.refreshReferenceMatchNow()

    fun applyReferenceMoves() = referenceController.applyReferenceMoves()

    internal suspend fun measureMixProfile() = referenceController.measureMixProfile()

    internal suspend fun measureAndMatchReference() = referenceController.measureAndMatchReference()

    private suspend fun restoreReferenceLocked(project: ProjectSession) =
        referenceController.restoreReference(project)

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
            _uiState.update { it.copy(statusMessage = "Latency calibrated: ${String.format(java.util.Locale.US, "%.1f", measuredOffset)} ms (loopback)") }
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

    /** Chooses how the next upload will be interpreted: stems, 2-track or premaster. */
    fun selectImportFormat(format: AudioSourceFormat) {
        _uiState.update {
            it.copy(
                importSourceFormat = format,
                importError = null,
                statusMessage = when {
                    format == AudioSourceFormat.TWO_TRACK_PREMASTER ->
                        "Upload set to 2-track premaster: the engineer will protect the existing master."
                    format == AudioSourceFormat.TWO_TRACK ->
                        "Upload set to 2-track: the engineer will mix from the stereo file."
                    else -> "Upload set to stems: the engineer can process each stem separately."
                },
                statusKind = StatusKind.INFO
            )
        }
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
    fun importAudioFile(
        uri: Uri,
        targetTrackIndex: Int,
        sourceFormat: AudioSourceFormat = AudioSourceFormat.STEMS
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importError = null,
                    statusMessage = if (sourceFormat.isTwoTrack) {
                        "Importing ${sourceFormat.displayName.lowercase()} and reading the stereo file..."
                    } else {
                        "Importing & decoding audio file..."
                    }
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
                        clips = listOf(result.clip),
                        sourceFormat = sourceFormat,
                        // A stereo beat is not a drum, bass or vocal stem. Keeping
                        // it as OTHER stops auto-classification from inviting
                        // surgical stem moves on a finished mix.
                        stemCategory = if (sourceFormat.isTwoTrack) {
                            StemCategory.OTHER
                        } else {
                            track.stemCategory
                        }
                    )

                    val updatedTracks = _session.value.tracks.map { if (it.index == targetTrackIndex) updatedTrack else it }

                    // A stem imported on top of a 2-track beat must not erase the
                    // fact that the backing beat is still stereo.
                    val sessionFormat = when {
                        sourceFormat.isTwoTrack -> sourceFormat
                        updatedTracks.any { it.isTwoTrackBeat } -> _session.value.sourceFormat
                        else -> AudioSourceFormat.STEMS
                    }

                    val newSession = _session.value.copy(
                        tracks = updatedTracks,
                        sourceFormat = sessionFormat,
                        totalLengthFrames = max(_session.value.totalLengthFrames, result.clip.lengthFrames),
                        revision = _session.value.revision + 1
                    )
                    _session.value = newSession
                    requestJournalWrite(newSession)
                    scheduleRebuildTrack(targetTrackIndex)

                    refreshAudioAnalysis()
                    val successMessage = when (sourceFormat) {
                        AudioSourceFormat.TWO_TRACK -> getApplication<Application>().getString(
                            R.string.import_two_track_success,
                            result.originalFileName,
                            targetTrackIndex + 1
                        )
                        AudioSourceFormat.TWO_TRACK_PREMASTER -> getApplication<Application>().getString(
                            R.string.import_premaster_success,
                            result.originalFileName,
                            targetTrackIndex + 1
                        )
                        AudioSourceFormat.STEMS ->
                            "Imported \"${result.originalFileName}\" into Track ${targetTrackIndex + 1}!"
                    }
                    setStatus(successMessage, StatusKind.SUCCESS)
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

    /**
     * Imports an NC Phone Beat `.ncbeat` package: tempo, per-track mix, stem clips and
     * the embedded beat source land in the session natively (see NcbeatImporter).
     * Stems are persisted as canonical 48 kHz stereo WAVs, mirroring importAudioFile.
     */
    fun importNcbeat(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionReady.await()
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importError = null,
                    statusMessage = "Reading NC Phone Beat package..."
                )
            }
            try {
                val app = getApplication<Application>()
                val pkg = app.contentResolver.openInputStream(uri)?.use { NcbeatImporter.read(it) }
                    ?: throw NcbeatException("Could not open the selected file")
                val plan = NcbeatImporter.importPlan(pkg)

                // Persist + decode every stem into the session's canonical format.
                val ncbeatDir = File(app.filesDir, "ncbeat").apply { mkdirs() }
                val decoded = LinkedHashMap<String, Pair<String, FloatArray>>()
                for ((entry, bytes) in pkg.stems) {
                    val fileName = entry.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val file = File(ncbeatDir, fileName)
                    file.writeBytes(bytes)
                    val wav = WavAudioIO.readWavFile(file)
                        ?: throw NcbeatException("$entry is not a readable WAV file")
                    if (wav.channels == 2 && wav.sampleRate == 48000) {
                        decoded[entry] = file.absolutePath to wav.samples
                    } else {
                        val normalized = AudioResampler.normalizeToSessionStereo(
                            samples = wav.samples,
                            srcChannels = wav.channels,
                            srcRate = wav.sampleRate,
                            dstRate = 48000
                        )
                        WavAudioIO.writeWavFile(file, normalized, channels = 2, sampleRate = 48000, bitsPerSample = 16)
                        decoded[entry] = file.absolutePath to normalized
                    }
                }

                // Configure every beat track, then place the clip it owns (if any).
                var newTracks = _session.value.tracks
                var maxLen = _session.value.totalLengthFrames
                var clippedTracks = 0

                fun placeClip(trackIndex: Int, entry: String, name: String): Boolean {
                    val (path, samples) = decoded[entry] ?: return false
                    trackSourcePools[trackIndex][path] = samples
                    val clip = AudioClip(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        sourcePath = path,
                        sourceStartFrame = 0L,
                        lengthFrames = samples.size / 2L,
                        timelineStartFrame = 0L,
                        trackIndex = trackIndex
                    )
                    maxLen = max(maxLen, clip.lengthFrames)
                    // Same semantics as importAudioFile: the target track's clips are
                    // replaced by the newly imported material.
                    newTracks = newTracks.map { t ->
                        if (t.index == trackIndex) t.copy(clips = listOf(clip)) else t
                    }
                    clippedTracks++
                    return true
                }

                for (p in plan.tracks) {
                    newTracks = newTracks.map { t ->
                        if (t.index == p.sessionTrackIndex) t.copy(
                            name = p.name,
                            gainDb = p.gainDb,
                            pan = p.pan,
                            isMuted = p.muted,
                            isSoloed = p.soloed,
                            stemCategory = p.category,
                            sourceFormat = AudioSourceFormat.STEMS
                        ) else t
                    }
                    if (p.stemEntry != null) placeClip(p.sessionTrackIndex, p.stemEntry, p.clipName)
                }
                // No per-track stems: play the full-mix stem once instead.
                plan.masterFallbackEntry?.let { entry ->
                    if (clippedTracks == 0) placeClip(0, entry, plan.beatSource.patternName)
                }

                val before = _session.value
                val newSession = before.copy(
                    tracks = newTracks,
                    tempoBpm = plan.tempoBpm,
                    masterGainDb = plan.masterGainDb,
                    totalLengthFrames = maxLen,
                    beatSource = plan.beatSource,
                    sourceFormat = AudioSourceFormat.STEMS,
                    revision = before.revision + 1
                )
                _session.value = newSession
                requestJournalWrite(newSession)
                for (p in plan.tracks) {
                    if (p.stemEntry != null) scheduleRebuildTrack(p.sessionTrackIndex)
                }
                if (clippedTracks > 0 && plan.tracks.none { it.stemEntry != null }) scheduleRebuildTrack(0)
                playbackEngine.syncFromProject(newSession)
                refreshAudioAnalysis()

                val dropped = if (plan.droppedTracks > 0) " (${plan.droppedTracks} tracks dropped)" else ""
                setStatus(
                    "Imported NC Phone Beat \"${plan.beatSource.patternName}\" - " +
                        "${plan.tracks.size} tracks at ${plan.tempoBpm.toInt()} BPM$dropped",
                    StatusKind.SUCCESS
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val msg = (t as? NcbeatException)?.message ?: t.message ?: "unknown error"
                _uiState.update {
                    it.copy(
                        importError = "Import failed: $msg",
                        statusMessage = "NC Phone Beat import failed: $msg",
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
            // contains audio. Empty tracks keep the category the user assigned,
            // and a 2-track beat stays OTHER: it is a finished stereo mix, not
            // an isolated drum/bass/vocal stem.
            val newTracks = _session.value.tracks.map { trk ->
                val f = feats.find { it.trackIndex == trk.index }
                when {
                    trk.isTwoTrackBeat -> trk.copy(stemCategory = StemCategory.OTHER)
                    f != null && trk.stemCategory == StemCategory.UNKNOWN &&
                        f.rmsDb > -50f && f.peakDb > -50f ->
                        trk.copy(stemCategory = f.classifiedStem)
                    else -> trk
                }
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
        val measuredFeatures = _features.value.ifEmpty {
            (0 until 16).map { t ->
                AudioFeatureExtractor.extractFeatures(t, trackAudioBuffers[t], sampleRate)
            }
        }
        if (_settings.value.enableLocalNeuralModel && active != null && endpointOnline) {
            val raw = try {
                LocalModelDetector.generateWithLocalModel(
                    model = active,
                    prompt = transcript,
                    systemPrompt = MixEngineerBrain.systemPromptFor(_session.value, measuredFeatures)
                ).text.orEmpty()
            } catch (_: Throwable) {
                ""
            }
            val calls = MixEngineerBrain.enforceSourceFormat(
                ToolCallDispatcher.parseToolCalls(raw)
                    .filter { ToolCallDispatcher.isKnownTool(it.name) },
                _session.value
            )
            if (calls.isNotEmpty()) {
                return@withContext MixEngineerBrain.Plan(
                    calls = calls,
                    summary = MixEngineerBrain.summarize(calls, _session.value),
                    source = "Local model · ${active.name}"
                )
            }
        }
        val fallback = MixEngineerBrain.plan(
            transcript = transcript,
            session = _session.value,
            selectedTrackIndex = _uiState.value.selectedTrackIndex
        )
        // If neither the model nor the command grammar produced a move, use the
        // measured features rather than answering "I didn't catch a mix move".
        if (fallback.calls.isEmpty() && fallback.clarification == null) {
            val generic = MixEngineerBrain.genericMix(_session.value, measuredFeatures)
            if (generic.calls.isNotEmpty()) return@withContext generic
        }
        fallback
    }

    private fun applyEngineerPlan(plan: MixEngineerBrain.Plan) {
        val safeCalls = MixEngineerBrain.enforceSourceFormat(plan.calls, _session.value)
        if (safeCalls.isEmpty()) {
            val message = plan.clarification ?: if (
                plan.calls.isNotEmpty() && _session.value.isTwoTrackBeat
            ) {
                "That move needs isolated stems. The beat is a 2-track stereo mix, so import the stem or record it first."
            } else {
                "I didn't catch a mix move. Try again."
            }
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

        val sessionCalls = safeCalls.filter { it.name != "transport" }
        val transportCalls = safeCalls.filter { it.name == "transport" }
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

    // Local-model discovery and inference live in AiStudioController; these
    // methods keep the existing call sites stable.
    fun scanForLocalModels(
        customUrl: String? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        aiStudio.scan(customUrl, dispatcher)
    }

    fun selectLocalModel(model: DiscoveredModelInfo) = aiStudio.select(model)

    fun setCustomLocalEndpointUrl(url: String) = aiStudio.setCustomEndpointUrl(url)

    fun sendPromptToLocalModel(
        prompt: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        aiStudio.send(prompt, dispatcher)
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

    // Export lives in ExportController; these delegates keep the UI API stable.

    fun setExportDestination(uri: Uri) = exportController.setDestination(uri)

    fun reportUiFrameTiming(meanMs: Float, p95Ms: Float) {
        _uiState.update { it.copy(uiFrameMeanMs = meanMs, uiFrameP95Ms = p95Ms) }
    }

    fun dismissOnboarding() {
        uiPrefs.edit { putBoolean(ONBOARDING_PREF, true) }
        _uiState.update { it.copy(showOnboarding = false) }
    }

    fun dismissWhatsNew() {
        uiPrefs.edit { putString(RELEASE_NOTES_PREF, RELEASE_NOTES_VERSION) }
        _uiState.update { it.copy(showWhatsNew = false) }
    }

    fun showWhatsNew(show: Boolean) {
        _uiState.update { it.copy(showWhatsNew = show) }
    }

    fun togglePresentationMode() {
        val next = !_uiState.value.presentationMode
        uiPrefs.edit { putBoolean("presentation_mode", next) }
        _uiState.update {
            it.copy(
                presentationMode = next,
                statusMessage = if (next) "Presentation mode on. Diagnostics hidden." else "Presentation mode off.",
                statusKind = StatusKind.INFO
            )
        }
    }

    fun showExportDialog(show: Boolean) = exportController.showDialog(show)

    fun applyTrackPreset(trackIndex: Int, preset: com.ncstudio.daw.audio.model.TrackPreset) {
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

    fun applyMasterPreset(preset: com.ncstudio.daw.audio.model.MasterPreset) {
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
        format: com.ncstudio.daw.audio.engine.ExportFormat = com.ncstudio.daw.audio.engine.ExportFormat.WAV_24BIT,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = exportController.renderSessionToAudio(format, dispatcher)

    fun renderStemsExport(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = exportController.renderStems(dispatcher)

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

    public override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(becomingNoisyReceiver)
        } catch (_: Exception) {
        /* best-effort cleanup: failure is non-actionable on this path */ }
        releaseAudioFocus()
        AudioEngineService.stopPlayback(getApplication())
        voiceEngineer.shutdown()
        // onCleared runs on the main thread and stop() joins the render thread;
        // joining here could block the UI long enough to ANR during teardown.
        val engine = playbackEngine
        val usb = usbManager
        Thread({
            runCatching { engine.stop() }
            runCatching { usb.release() }
        }, "NCStudio-ViewModelTeardown").apply {
            isDaemon = true
            start()
        }
    }
}
