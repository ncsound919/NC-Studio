package com.example.session.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.session.calibration.AudioRouteKey
import com.example.session.calibration.LoopbackMeasurer
import com.example.session.calibration.RouteOffsetStore
import com.example.session.monitor.MonitorAudioEngine
import com.example.session.monitor.TalkbackCapture
import com.example.session.net.BackendApi
import com.example.session.net.ConnectionSettings
import com.example.session.net.ConnectionSettingsStore
import com.example.session.net.NetworkQuality
import com.example.session.net.PreflightPolicy
import com.example.session.net.PreflightReport
import com.example.session.net.TransportEvent
import com.example.session.net.WebSocketTransport
import com.example.session.playback.CuePlayback
import com.example.session.protocol.Ack
import com.example.session.protocol.Arm
import com.example.session.protocol.ArtistAuthority
import com.example.session.protocol.Bye
import com.example.session.protocol.Capabilities
import com.example.session.protocol.Command
import com.example.session.protocol.Hello
import com.example.session.protocol.MarkTake
import com.example.session.protocol.MessageCodec
import com.example.session.protocol.Ping
import com.example.session.protocol.PlayBacking
import com.example.session.protocol.Pong
import com.example.session.protocol.Pause
import com.example.session.protocol.Seek
import com.example.session.protocol.SessionLog
import com.example.session.protocol.SessionMessage
import com.example.session.protocol.SessionState
import com.example.session.protocol.SetCueLevel
import com.example.session.protocol.StartTake
import com.example.session.protocol.StateChanged
import com.example.session.protocol.StateSnapshot
import com.example.session.protocol.StopTake
import com.example.session.protocol.TakeAvailable
import com.example.session.protocol.TakeStarted
import com.example.session.protocol.TakeStopped
import com.example.session.protocol.Talkback
import com.example.session.protocol.UploadProgress
import com.example.session.protocol.Warning as WarningMessage
import com.example.session.protocol.WarningCode
import com.example.session.take.TakeConfig
import com.example.session.take.TakeManifest
import com.example.session.take.TakeRecorder
import com.example.session.take.TakeStatus
import com.example.session.take.TakeStore
import com.example.session.take.TakeUploadState
import com.example.session.upload.DownloadOutcome
import com.example.session.upload.HttpStatusException
import com.example.session.upload.TakeDownloader
import com.example.session.upload.UploadApi
import com.example.session.upload.UploadDeps
import com.example.session.upload.UploadScheduler
import com.example.session.upload.SessionBusyFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One take as the session UI sees it. */
data class TakeUiModel(
    val takeId: String,
    val state: String,
    val chunksDone: Int = 0,
    val chunksTotal: Int = 0,
    val frames: Long = 0L,
    val detail: String? = null,
    val localPath: String? = null,
) {
    val seconds: Float get() = frames / 48_000f
}

data class SessionUiState(
    val stage: SessionStage = SessionStage.CHOOSING,
    val role: String? = null,
    val serverHost: String = "192.168.1.20:8787",
    val displayName: String = "",
    val inviteInput: String = "",
    val inviteCode: String? = null,
    val sessionId: String? = null,
    val peerName: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val connected: Boolean = false,
    val rttMs: Long = -1L,
    val jitterMs: Long = 0L,
    val sessionState: String = "LOBBY",
    val armed: Boolean = false,
    val engineerTalkbackOn: Boolean = false,
    val cueLevelDb: Float = -6f,
    val backingTrackName: String? = null,
    val preflight: PreflightReport? = null,
    val calibrationOffsetFrames: Int? = null,
    val lastTakeFrames: Long = 0L,
    val monitorLive: Boolean = false,
    val takes: List<TakeUiModel> = emptyList(),
    val warnings: List<String> = emptyList(),
    val logLines: List<String> = emptyList(),
    val artistStopAvailable: Boolean = false,
)

enum class SessionStage {
    CHOOSING,
    ENGINEER_SETUP,
    ENGINEER_LIVE,
    ARTIST_SETUP,
    ARTIST_PREFLIGHT,
    ARTIST_LIVE,
}

/**
 * Orchestrates one end of a Mode 2 session.
 *
 * Artist side: preflight, scheduled take recording through [ArtistAuthority]
 * (the phone is the timing and safety authority), monitor streaming from the
 * recorder's own tap, and upload driven by WorkManager with progress mirrored
 * over the control channel.
 *
 * Engineer side: create/join, send commands, follow the artist's events, listen
 * to the monitor stream, push to talk, and download/verify takes.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val rootDir = File(app.filesDir, ROOT_DIR)
    private val takeStore = TakeStore(rootDir)
    private val connectionStore = ConnectionSettingsStore(rootDir)
    private val routeOffsets = RouteOffsetStore(rootDir)
    private val codec = MessageCodec()
    private val http = UploadDeps.httpClient

    private val _ui = MutableStateFlow(SessionUiState())
    val ui: StateFlow<SessionUiState> = _ui.asStateFlow()

    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val cuePlayback = CuePlayback(app)
    private val recorder = TakeRecorder(takeStore, audioManager)

    private var transport: WebSocketTransport? = null
    private var monitorEngine: MonitorAudioEngine? = null
    private var talkbackCapture: TalkbackCapture? = null
    private var authority: ArtistAuthority? = null
    private var log: SessionLog? = null
    private var api: UploadApi? = null
    private var settings: ConnectionSettings? = null
    private var eventJob: Job? = null
    private var uploadJob: Job? = null

    // Written by the transport collector, the UI thread and the upload watcher.
    private val manifests = ConcurrentHashMap<String, TakeManifest>()
    private val lastSentUploadState = ConcurrentHashMap<String, String>()
    // The watcher fills this on its own thread; teardown clears it from main.
    private val announcedTakes: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile
    private var announcedGeneration = -1L

    /** True once the engineer has completed the Hello handshake on this link. */
    @Volatile
    private var peerPresent = false

    @Volatile
    private var activeTakeId: String? = null
    private var takeStartJob: Job? = null
    private val stopInFlight = AtomicBoolean(false)
    private val logLines = ArrayDeque<String>()

    init {
        // Rebuild any take the app was recording when it died.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                takeStore.recoverAll().forEach { recovery ->
                    logLine("recovered ${recovery.takeId}: ${recovery.chunksRecovered} chunks")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }

    // ------------------------------------------------------------- setup

    fun chooseRole(role: String) {
        _ui.update {
            it.copy(
                role = role,
                stage = if (role == ConnectionSettings.ROLE_ENGINEER) SessionStage.ENGINEER_SETUP else SessionStage.ARTIST_SETUP,
                error = null,
            )
        }
    }

    fun updateServerHost(host: String) = _ui.update { it.copy(serverHost = host) }
    fun updateDisplayName(name: String) = _ui.update { it.copy(displayName = name) }
    fun updateInviteInput(code: String) = _ui.update { it.copy(inviteInput = code.uppercase().take(8)) }
    fun clearError() = _ui.update { it.copy(error = null) }
    fun dismissWarning(index: Int) = _ui.update { state ->
        state.copy(warnings = state.warnings.filterIndexed { i, _ -> i != index })
    }

    fun createSession() {
        val host = _ui.value.serverHost
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val credentials = BackendApi(
                    ConnectionSettings.fromHost(host, "", "", "").baseUrl,
                    http,
                ).createSession(ConnectionSettings.ROLE_ENGINEER, displayNameOrNull())
                startSession(host, credentials, inviteCode = credentials.inviteCode)
                _ui.update {
                    it.copy(
                        busy = false,
                        stage = SessionStage.ENGINEER_LIVE,
                        inviteCode = credentials.inviteCode,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    fun joinAsArtist() {
        val host = _ui.value.serverHost
        val invite = _ui.value.inviteInput
        if (invite.isBlank()) {
            _ui.update { it.copy(error = "Enter the invite code from the engineer") }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val credentials = BackendApi(
                    ConnectionSettings.fromHost(host, "", "", "").baseUrl,
                    http,
                ).joinSession(invite, displayNameOrNull())
                startSession(host, credentials, inviteCode = invite)
                _ui.update {
                    it.copy(
                        busy = false,
                        stage = SessionStage.ARTIST_PREFLIGHT,
                        peerName = credentials.peerName,
                    )
                }
                refreshPreflight()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    private fun displayNameOrNull(): String? = _ui.value.displayName.trim().ifBlank { null }

    private fun startSession(host: String, credentials: com.example.session.net.SessionCredentials, inviteCode: String?) {
        val connection = ConnectionSettings.fromHost(
            hostInput = host,
            sessionId = credentials.sessionId,
            role = credentials.role,
            token = credentials.token,
            inviteCode = inviteCode,
            displayName = _ui.value.displayName,
        )
        settings = connection
        connectionStore.write(connection)
        api = UploadApi(connection.baseUrl, http, tokenProvider = { connection.token })
        log = SessionLog(sink = { line ->
            takeStore.appendSessionLog(connection.sessionId, line)
            logLine(line)
        })
        // One transport per session lifetime; monitor engine kind depends on role.
        val role = credentials.role
        authority = ArtistAuthority(
            sid = connection.sessionId,
            currentFrame = { cuePlayback.positionFrames() },
            displayName = { displayNameOrNull() },
        )
        monitorEngine = MonitorAudioEngine(
            encodeKind = if (role == ConnectionSettings.ROLE_ARTIST) {
                com.example.session.monitor.MonitorFrame.KIND_MONITOR
            } else {
                com.example.session.monitor.MonitorFrame.KIND_TALKBACK
            },
        ).also { engine ->
            engine.onEncodedFrame = { frame -> transport?.sendBinary(frame) }
            // The engineer plays the monitor stream as soon as they are in the
            // session; the artist starts theirs when preflight passes.
            if (role == ConnectionSettings.ROLE_ENGINEER) {
                engine.acceptTalkback = true
                engine.start()
            }
        }
        transport = WebSocketTransport(
            settings = connection,
            http = http,
            scope = viewModelScope,
            codec = codec,
        )
        _ui.update { it.copy(sessionId = connection.sessionId, role = role) }
        // Subscribe before opening the socket: TransportEvent.Connected is a
        // live event, not replayed, and it carries the generation the Hello
        // handshake depends on.
        collectTransport()
        transport?.start()
        if (role == ConnectionSettings.ROLE_ENGINEER) {
            checkArtistPresence()
        } else {
            // Also covers takes recovered after an app kill: the watcher uploads
            // them and re-announces verified takes once the engineer says Hello.
            startUploadWatcher()
        }
    }

    // -------------------------------------------------------- transport

    private fun collectTransport() {
        eventJob?.cancel()
        val transport = transport ?: return
        eventJob = viewModelScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Connected -> {
                        // The authority tracks its own transport generations; it
                        // must advance on every new socket or the engineer's
                        // Hello (and every command after it) is unauthorized.
                        authority?.newTransportGeneration()
                        // Re-send the monitor codec config on every new socket:
                        // a peer that joins or reconnects mid-stream otherwise
                        // has no csd and silently decodes nothing.
                        monitorEngine?.requestConfigResend()
                        _ui.update { it.copy(connected = true) }
                        log?.record(SessionLog.Direction.LOCAL, "link-up", note = "generation ${event.generation}")
                        sendHello()
                    }

                    is TransportEvent.Disconnected -> {
                        peerPresent = false
                        // A talkback left on by a crashed engineer must not keep
                        // the artist's monitor open to a dead link.
                        monitorEngine?.acceptTalkback = false
                        _ui.update {
                            it.copy(
                                connected = false,
                                monitorLive = false,
                                engineerTalkbackOn = false,
                            )
                        }
                        authority?.onTransportLost()
                        log?.record(SessionLog.Direction.LOCAL, "link-down", note = event.reason)
                        addWarning("Link lost: ${event.reason}. The take keeps recording locally.")
                    }

                    is TransportEvent.Received -> handleIncoming(event.envelope, event.message)

                    is TransportEvent.BinaryReceived -> monitorEngine?.onRemotePayload(event.payload)

                    is TransportEvent.Rtt -> _ui.update { it.copy(rttMs = event.rttMs, jitterMs = event.jitterMs) }

                    is TransportEvent.Degraded -> addWarning("Link degraded: ${event.missedPings} missed pings")
                }
            }
        }
    }

    private fun sendHello() {
        val role = _ui.value.role ?: return
        val hello = Hello(
            protocolVersion = com.example.session.protocol.PROTOCOL_VERSION,
            role = role,
            capabilities = Capabilities.all,
            lastStateRevision = authority?.revision ?: -1L,
            displayName = displayNameOrNull(),
        )
        send(hello, telemetry = true)
    }

    private fun send(message: SessionMessage, telemetry: Boolean = false): Boolean {
        val transport = transport ?: return false
        val sent = if (telemetry) transport.sendTelemetry(message) else transport.send(message)
        if (sent) {
            log?.record(SessionLog.Direction.OUT, message.type, seq = transport.currentSequence())
        }
        return sent
    }

    private fun handleIncoming(envelope: com.example.session.protocol.Envelope, message: SessionMessage) {
        val role = _ui.value.role
        log?.record(SessionLog.Direction.IN, message.type, seq = envelope.seq, state = currentSessionState())
        if (role == ConnectionSettings.ROLE_ARTIST) {
            handleArtistIncoming(envelope, message)
        } else {
            handleEngineerIncoming(message)
        }
    }

    private fun currentSessionState(): SessionState? =
        authority?.state ?: runCatching { SessionState.valueOf(_ui.value.sessionState) }.getOrNull()

    private fun handleArtistIncoming(envelope: com.example.session.protocol.Envelope, message: SessionMessage) {
        val authority = authority ?: return
        val generation = transport?.currentGeneration ?: return
        when (message) {
            is Hello -> {
                val result = authority.onHello(message, generation)
                if (!result.accepted) {
                    result.events.forEach { send(it) }
                    addWarning("Engineer protocol mismatch; ask them to update the app")
                    return
                }
                peerPresent = true
                // The snapshot is one of these events; pushing a second one here
                // only duplicated handshake traffic.
                result.events.forEach { send(it) }
                _ui.update {
                    it.copy(
                        sessionState = authority.state.name,
                        armed = authority.armed,
                        artistStopAvailable = authority.activeTakeId != null,
                    )
                }
            }

            is Ping -> send(Pong(message.sentAtMs, authority.revision), telemetry = true)

            // Link traffic from the transport is not a command. Treating a Pong
            // as unsupported made the artist reject every heartbeat and put an
            // error in the engineer's UI once a second.
            is Pong, is Ack -> Unit

            is Bye -> addWarning("Engineer closed the session: ${message.reason}")

            is Command -> {
                val result = authority.handleCommand(envelope, message, generation)
                send(result.ack)
                if (!result.ack.accepted) {
                    log?.recordAck(SessionLog.Direction.OUT, result.ack, authority.state)
                    _ui.update { it.copy(error = "Engineer command rejected: ${result.ack.reason}") }
                }
                result.events.forEach { send(it) }
                applyAcceptedCommand(message, result.ack, result.events)
            }

            else -> {
                // Anything else from the engineer that is not a command or link
                // message is explicitly refused, never silently ignored.
                send(Ack(ackSeq = envelope.seq, accepted = false, reason = "UNSUPPORTED"))
            }
        }
    }

    private fun applyAcceptedCommand(
        command: Command,
        ack: Ack,
        events: List<SessionMessage>,
    ) {
        val authority = authority ?: return
        if (!ack.accepted) {
            if (command is StartTake) {
                // Nothing started; make sure we are still honest about state.
                pushArtistState()
            }
            return
        }
        when (command) {
            is StartTake -> {
                if (events.any { it is StateChanged && it.state == SessionState.COUNT_IN.name } ||
                    authority.state == SessionState.COUNT_IN
                ) {
                    scheduleTake(command.takeId, command.atFrame)
                }
            }

            is StopTake -> stopTake("engineer stop")

            is SetCueLevel -> {
                cuePlayback.setCueLevelDb(command.levelDb)
                _ui.update { it.copy(cueLevelDb = command.levelDb) }
            }

            is Talkback -> {
                monitorEngine?.acceptTalkback = command.on
            }

            is PlayBacking -> {
                cuePlayback.playFrom(command.atFrame)
                if (authority.state == SessionState.COUNT_IN) cuePlayback.playCountIn()
            }

            is Seek -> _ui.update { it.copy(lastTakeFrames = command.atFrame) }

            is Pause -> cuePlayback.pause()

            is MarkTake -> logLine("marker on take ${command.takeId ?: activeTakeId ?: "current"}")

            is Arm -> _ui.update { it.copy(armed = command.armed) }
        }
        _ui.update {
            it.copy(
                sessionState = authority.state.name,
                armed = authority.armed,
                artistStopAvailable = authority.activeTakeId != null,
            )
        }
    }

    private fun handleEngineerIncoming(message: SessionMessage) {
        when (message) {
            is StateSnapshot -> {
                _ui.update {
                    it.copy(
                        sessionState = message.state,
                        armed = message.armed,
                        cueLevelDb = message.cueLevelDb,
                        peerName = message.displayName ?: it.peerName,
                        monitorLive = message.monitoring,
                    )
                }
            }

            is StateChanged -> _ui.update {
                it.copy(
                    sessionState = message.state,
                    // The authority only ever arms in ARMED; deriving it here
                    // stops a stale "armed" switch after a take starts.
                    armed = message.state == SessionState.ARMED.name,
                )
            }

            is TakeStarted -> {
                logLine("take started ${message.takeId} at frame ${message.actualStartFrame}")
                upsertTake(message.takeId) { it.copy(state = "recording") }
            }

            is TakeStopped -> {
                _ui.update { it.copy(lastTakeFrames = message.frames) }
                upsertTake(message.takeId) { it.copy(state = "recorded", frames = message.frames) }
            }

            is UploadProgress -> upsertTake(message.takeId) {
                it.copy(
                    state = message.state,
                    chunksDone = message.chunksDone,
                    chunksTotal = message.chunksTotal,
                )
            }

            is TakeAvailable -> {
                manifests[message.takeId] = message.manifest
                upsertTake(message.takeId) {
                    it.copy(state = "available", frames = message.manifest.totalFrames, chunksTotal = message.manifest.chunks.size)
                }
            }

            is WarningMessage -> addWarning("${message.code}: ${message.detail}")

            is Ack -> if (!message.accepted) {
                _ui.update { it.copy(error = "Artist refused: ${message.reason ?: "unknown"} ${message.detail ?: ""}".trim()) }
            }

            is Hello -> sendHello()

            // The artist's heartbeat needs an answer too. Without this, the
            // artist's transport counts every ping as lost and marks a healthy
            // link degraded.
            is Ping -> send(Pong(message.sentAtMs, -1L), telemetry = true)

            is Bye -> addWarning("Artist closed the session: ${message.reason}")

            else -> Unit
        }
    }

    private fun upsertTake(takeId: String, transform: (TakeUiModel) -> TakeUiModel) {
        _ui.update { state ->
            val existing = state.takes.firstOrNull { it.takeId == takeId }
                ?: TakeUiModel(takeId = takeId, state = "unknown")
            val updated = transform(existing)
            state.copy(takes = state.takes.filterNot { it.takeId == takeId } + updated)
        }
    }

    private fun pushArtistState() {
        authority?.let { send(it.snapshot()) }
        _ui.update {
            val authority = authority
            it.copy(
                sessionState = authority?.state?.name ?: it.sessionState,
                armed = authority?.armed ?: it.armed,
                artistStopAvailable = authority?.activeTakeId != null,
            )
        }
    }

    private fun checkArtistPresence() {
        // Engineer asks for a snapshot on connect; if the artist is not there
        // the snapshot simply never arrives and the UI keeps showing waiting.
        viewModelScope.launch {
            delay(500)
            if (transport?.connected?.value == true) sendHello()
        }
    }

    // ------------------------------------------------------- artist flow

    fun setBackingTrack(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = cuePlayback.load(uri)
            _ui.update { it.copy(backingTrackName = name) }
        }
    }

    fun refreshPreflight() {
        val app = getApplication<Application>()
        val micOk = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val headphones = hasWiredOrUsbOutput()
        val free = app.filesDir.usableSpace
        val route = currentRouteKey()
        val offset = routeOffsets.offsetFor(route)
        val quality = NetworkQuality(
            rttMs = transport?.rttMs?.value ?: -1L,
            jitterMs = transport?.jitterMs?.value ?: 0L,
            lossPercent = 0f,
            uploadKbps = _ui.value.preflight?.network?.uploadKbps ?: -1,
        )
        val report = PreflightPolicy.evaluate(micOk, headphones, free, quality, offset)
        _ui.update { it.copy(preflight = report, calibrationOffsetFrames = offset) }
    }

    fun runSpeedProbe() {
        // Capture the settings snapshot before switching threads: a concurrent
        // teardown can null the field between the null check and the `!!`.
        val baseUrl = settings?.baseUrl ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val kbps = runCatching { BackendApi(baseUrl, http).probe().uploadKbps }.getOrDefault(-1)
            _ui.update { state ->
                val current = state.preflight
                val quality = (current?.network ?: NetworkQuality()).copy(uploadKbps = kbps)
                val report = PreflightPolicy.evaluate(
                    micPermission = current?.micPermission ?: true,
                    headphones = current?.headphones ?: hasWiredOrUsbOutput(),
                    freeBytes = getApplication<Application>().filesDir.usableSpace,
                    network = quality,
                    calibrationOffsetFrames = current?.calibrationOffsetFrames,
                )
                state.copy(preflight = report)
            }
        }
    }

    fun runCalibration() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(busy = true, error = null) }
            val offset = LoopbackMeasurer().measure()
            if (offset != null) {
                routeOffsets.save(currentRouteKey(), offset)
                logLine("calibrated ${currentRouteKey()}: $offset frames (${"% .1f".format(offset / 48f)} ms)")
            }
            _ui.update { it.copy(busy = false) }
            refreshPreflight()
        }
    }

    fun artistGoLive() {
        val authority = authority ?: return
        val report = _ui.value.preflight
        if (report?.passed != true) {
            _ui.update { it.copy(error = "Preflight still has blockers") }
            return
        }
        val free = getApplication<Application>().filesDir.usableSpace
        authority.preflightPassed(free)
        startMonitor()
        SessionBusyFlag.setBusy(rootDir, false)
        _ui.update { it.copy(stage = SessionStage.ARTIST_LIVE, monitorLive = true) }
        pushArtistState()
        startUploadWatcher()
        logLine("session live: ${authority.state}")
    }

    private fun startMonitor() {
        val engine = monitorEngine ?: return
        engine.acceptTalkback = false
        engine.start()
        recorder.monitorTap = { samples, count -> engine.submitCapture(samples, count) }
    }

    private fun scheduleTake(takeId: String, atFrame: Long) {
        if (takeStartJob?.isActive == true) return
        takeStartJob = viewModelScope.launch(Dispatchers.Default) {
            val authority = authority ?: return@launch
            val offset = routeOffsets.offsetFor(currentRouteKey()) ?: 0
            val startFrame = atFrame.coerceAtLeast(0)
            // Count-in first: the backing track is positioned so that the take
            // starts exactly at the requested frame.
            cuePlayback.playFrom((startFrame - cuePlayback.countInFrames).coerceAtLeast(0))
            cuePlayback.playCountIn()
            while (cuePlayback.positionFrames() < startFrame) {
                if (authority.state != SessionState.COUNT_IN) return@launch
                delay(5)
            }
            val config = TakeConfig(
                takeId = takeId,
                sessionId = authority.run { settings?.sessionId ?: "" },
                trackId = 0L,
                startFrame = startFrame,
                calibrationOffsetFrames = offset,
            )
            if (!recorder.start(config)) {
                val events = authority.engineTakeFinalized(0L, "recorder failed to start")
                events.forEach { send(it) }
                addWarning("Recorder could not start; the worker refused the take")
                return@launch
            }
            activeTakeId = takeId
            SessionBusyFlag.setBusy(rootDir, true)
            val actualFrame = (startFrame - offset).coerceAtLeast(0)
            val events = authority.engineTakeStarted(takeId, actualFrame)
            events.forEach { send(it) }
            _ui.update { it.copy(sessionState = authority.state.name, artistStopAvailable = true) }
        }
    }

    /** The artist's own stop button: always wins over the engineer. */
    fun artistStop() {
        val events = authority?.artistStop("artist stopped") ?: emptyList()
        events.forEach { send(it) }
        stopTake("artist stop")
    }

    private fun stopTake(reason: String) {
        val authority = authority ?: return
        if (!stopInFlight.compareAndSet(false, true)) return
        val startJob = takeStartJob
        takeStartJob = null
        startJob?.cancel()
        cuePlayback.pause()

        // Joining the capture/writer threads blocks for up to seconds; doing it
        // on the caller's (main) thread could ANR. Waiting for the scheduled
        // start first also closes the race where a take is started just as the
        // stop arrives.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                startJob?.join()
                // Capture the live count before stopping; a stop during count-in
                // has no recorded frames (and must not report the last take's).
                val liveFrames = if (recorder.isRecording) recorder.framesWritten else 0L
                val manifest = recorder.stop()
                activeTakeId = null
                SessionBusyFlag.setBusy(rootDir, false)
                val frames = manifest?.totalFrames ?: liveFrames
                val events = authority.engineTakeFinalized(frames, reason)
                events.forEach { send(it) }
                _ui.update {
                    it.copy(
                        sessionState = authority.state.name,
                        artistStopAvailable = false,
                        lastTakeFrames = frames,
                    )
                }
                if (manifest != null) {
                    manifests[manifest.takeId] = manifest
                    upsertTake(manifest.takeId) {
                        it.copy(
                            state = TakeUploadState.RECORDED.name.lowercase(),
                            frames = manifest.totalFrames,
                            chunksTotal = manifest.chunks.size,
                            localPath = takeStore.takeDir(manifest.takeId).absolutePath,
                        )
                    }
                    startUpload(manifest)
                } else {
                    addWarning("Take had no usable audio and was not uploaded")
                }
                startUploadWatcher()
            } finally {
                stopInFlight.set(false)
            }
        }
    }

    private fun startUpload(manifest: TakeManifest) {
        // WorkManager owns the upload: it survives process death and reboots,
        // and the watcher below mirrors its state onto the control channel.
        UploadScheduler.enqueue(getApplication(), manifest.takeId, unmeteredOnly = false)
        if (uploadJob?.isActive != true) startUploadWatcher()
    }

    private fun startUploadWatcher() {
        if (uploadJob?.isActive == true) return
        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val generation = transport?.currentGeneration ?: -1L
                if (generation != announcedGeneration) {
                    // A new link means the engineer may have missed the last
                    // announcement; allow every verified take to be sent again.
                    announcedGeneration = generation
                    announcedTakes.clear()
                    lastSentUploadState.clear()
                }

                // The disk is the source of truth, not the in-memory map: this
                // is what lets takes recovered after an app kill upload and
                // announce themselves on the next session.
                for (takeId in takeStore.listTakeIds()) {
                    val manifest = manifests[takeId] ?: takeStore.readManifest(takeId) ?: continue
                    manifests.putIfAbsent(takeId, manifest)
                    val state = takeStore.readState(takeId)
                    when (state.state) {
                        TakeUploadState.RECORDED,
                        TakeUploadState.REGISTERED,
                        TakeUploadState.UPLOADING,
                        TakeUploadState.COMPLETE,
                        -> UploadScheduler.enqueue(getApplication(), takeId, unmeteredOnly = false)

                        else -> Unit
                    }

                    val key = "${state.state}:${state.chunksDone}/${state.chunksTotal}"
                    if (lastSentUploadState[takeId] != key && peerPresent) {
                        val wireState = stateWireName(state.state)
                        val progressEvents = authority?.uploadProgress(
                            takeId,
                            wireState,
                            state.chunksDone,
                            state.chunksTotal,
                        ).orEmpty()
                        if (sendAll(progressEvents)) {
                            lastSentUploadState[takeId] = key
                            upsertTake(takeId) {
                                it.copy(
                                    state = wireState,
                                    chunksDone = state.chunksDone,
                                    chunksTotal = state.chunksTotal,
                                    detail = state.lastError,
                                )
                            }
                        }
                    }

                    if (state.state == TakeUploadState.VERIFIED &&
                        peerPresent &&
                        takeId !in announcedTakes
                    ) {
                        val events = authority?.takeAvailable(manifest).orEmpty() +
                            authority?.engineTakeVerified(takeId).orEmpty()
                        if (sendAll(events)) {
                            announcedTakes += takeId
                            _ui.update {
                                it.copy(sessionState = authority?.state?.name ?: it.sessionState)
                            }
                        }
                    }
                }
                delay(UPLOAD_WATCH_MS)
            }
        }
    }

    private fun sendAll(events: List<SessionMessage>): Boolean {
        var allSent = true
        for (event in events) {
            if (!send(event)) allSent = false
        }
        return allSent
    }

    private fun stateWireName(state: TakeUploadState): String = when (state) {
        TakeUploadState.RECORDED -> "recorded"
        TakeUploadState.REGISTERED -> TakeStatus.STATE_REGISTERED
        TakeUploadState.UPLOADING -> TakeStatus.STATE_UPLOADING
        TakeUploadState.COMPLETE -> "complete"
        TakeUploadState.VERIFIED -> TakeStatus.STATE_VERIFIED
        TakeUploadState.FAILED -> TakeStatus.STATE_FAILED
        TakeUploadState.EMPTY -> "empty"
    }

    // ----------------------------------------------------- engineer flow

    fun engineerArm(armed: Boolean) = send(Arm(armed))

    fun engineerStartTake() {
        val ui = _ui.value
        // The artist is the authority and will reject this anyway; failing here
        // gives the engineer an explanation instead of a silent no-op.
        if (!ui.armed) {
            _ui.update { it.copy(error = "Arm the artist before starting a take") }
            return
        }
        if (ui.sessionState != SessionState.ARMED.name) {
            _ui.update { it.copy(error = "The artist is not armed (state ${ui.sessionState})") }
            return
        }
        val takeId = UUID.randomUUID().toString()
        val atFrame = (ui.lastTakeFrames + START_LEAD_FRAMES).coerceAtLeast(START_LEAD_FRAMES)
        send(StartTake(takeId, atFrame))
        upsertTake(takeId) { it.copy(state = "requested") }
    }

    fun engineerStopTake() = send(StopTake)

    fun engineerMarkTake() = send(MarkTake())

    fun engineerSetCueLevel(db: Float) {
        _ui.update { it.copy(cueLevelDb = db) }
        send(SetCueLevel(db))
    }

    fun engineerTalkback(on: Boolean) {
        _ui.update { it.copy(engineerTalkbackOn = on) }
        send(Talkback(on))
        val engine = monitorEngine ?: return
        if (on) {
            engine.start()
            talkbackCapture?.stop()
            talkbackCapture = TalkbackCapture { samples, count -> engine.submitCapture(samples, count) }.also { it.start() }
        } else {
            talkbackCapture?.stop()
            talkbackCapture = null
        }
    }

    fun engineerSeek(frame: Long) = send(Seek(frame))
    fun engineerPlayBacking(frame: Long) = send(PlayBacking(frame))
    fun engineerPause() = send(Pause)

    fun downloadTake(takeId: String) {
        val manifest = manifests[takeId] ?: return
        val api = api ?: return
        upsertTake(takeId) { it.copy(state = "downloading") }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = TakeDownloader(api, takeStore).download(manifest) { done, total ->
                upsertTake(takeId) { it.copy(chunksDone = done, chunksTotal = total, state = "downloading") }
            }
            when (outcome) {
                is DownloadOutcome.Verified -> upsertTake(takeId) {
                    it.copy(
                        state = "verified",
                        chunksDone = outcome.chunks,
                        chunksTotal = outcome.chunks,
                        localPath = takeStore.takeDir(takeId).absolutePath,
                    )
                }

                is DownloadOutcome.Retry -> {
                    upsertTake(takeId) { it.copy(state = "retry", detail = outcome.reason) }
                    addWarning("Download interrupted (${outcome.reason}); press verify again to resume")
                }

                is DownloadOutcome.Fatal -> {
                    upsertTake(takeId) { it.copy(state = "failed", detail = outcome.reason) }
                    addWarning("Take download failed permanently: ${outcome.reason}")
                }
            }
        }
    }

    // ------------------------------------------------------------ teardown

    fun stopSession() {
        send(Bye("session closed"))
        peerPresent = false
        announcedTakes.clear()
        lastSentUploadState.clear()
        val pendingStart = takeStartJob
        takeStartJob = null
        pendingStart?.cancel()
        uploadJob?.cancel()
        eventJob?.cancel()
        talkbackCapture?.stop()
        talkbackCapture = null
        monitorEngine?.stop()
        monitorEngine = null
        recorder.monitorTap = null
        // recorder.abort joins the capture and writer threads (up to seconds).
        // This runs from onCleared, so it must not block the main thread; and
        // it must wait for a scheduled start to finish before aborting, or a
        // take could begin after teardown already passed the recorder.
        if (pendingStart != null || recorder.isRecording) {
            Thread(
                {
                    if (pendingStart != null) {
                        runCatching { runBlocking { pendingStart.join() } } // audit-ignore CONC-002: dedicated abort thread
                    }
                    if (recorder.isRecording) recorder.abort("session closed")
                },
                "Session-TakeAbort",
            ).apply { isDaemon = true }.start()
        }
        cuePlayback.release()
        transport?.stop()
        transport = null
        SessionBusyFlag.setBusy(rootDir, false)
        _ui.update { it.copy(connected = false, stage = SessionStage.CHOOSING, monitorLive = false) }
    }

    // ------------------------------------------------------------- helpers

    private fun hasWiredOrUsbOutput(): Boolean {
        val manager = audioManager ?: return false
        val devices = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return false
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    }

    private fun currentRouteKey(): String {
        val manager = audioManager
        val devices: List<AudioDeviceInfo> = manager
            ?.let { runCatching { it.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrNull() }
            ?: emptyList()
        val usb = devices.any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
        val wired = devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        val bluetooth = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        return AudioRouteKey.of(wired, usb, bluetooth)
    }

    private fun addWarning(text: String) {
        _ui.update { it.copy(warnings = (it.warnings + text).takeLast(6)) }
    }

    private fun logLine(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            _ui.update { it.copy(logLines = logLines.toList()) }
        }
    }

    private fun friendly(e: Exception): String = when {
        e is HttpStatusException && e.code == 404 -> "No session with that invite code on this server"
        e is HttpStatusException && e.code == 410 -> "That invite code has expired; ask for a new one"
        e is HttpStatusException -> "Server said ${e.code}: ${e.bodyText.take(120)}"
        else -> e.message ?: "Connection failed"
    }

    companion object {
        const val ROOT_DIR = "session"
        private const val START_LEAD_FRAMES = 4L * 48_000L // 4 s of lead time
        private const val UPLOAD_WATCH_MS = 1_500L
        private const val MAX_LOG_LINES = 200
    }
}
