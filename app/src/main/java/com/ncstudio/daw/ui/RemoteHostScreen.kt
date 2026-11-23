package com.ncstudio.daw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncstudio.daw.ui.theme.ElectricLime
import com.ncstudio.daw.ui.theme.PanelInset
import com.ncstudio.daw.ui.theme.TextMuted
import com.ncstudio.daw.ui.theme.TextPrimary
import com.ncstudio.daw.ui.theme.TextSecondary
import com.overlay.remote.HostSession
import com.overlay.remote.HostStatus
import com.overlay.remote.HostUiState
import com.overlay.remote.Op
import com.overlay.remote.RemoteRelay
import com.overlay.remote.Role
import com.overlay.remote.SessionState
import com.overlay.remote.TrackState
import com.overlay.remote.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.pow

/**
 * Hosts an NCSound Remote session from NC Studio: an embedded relay on the LAN
 * plus the authoritative host client. Guests (Phone Beat, or the desktop DAW's
 * guest client) join with the code and drive the mixer within their role.
 *
 * The host owns `rev`; every accepted guest op bumps it and broadcasts a delta.
 * Local host edits publish a fresh snapshot so guests never drift.
 */
class RemoteHostController(private val vm: DawViewModel) {
    private val relay = RemoteRelay(port = 8787)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow(HostUiState())
    val state: StateFlow<HostUiState> = _state.asStateFlow()

    @Volatile private var rev = 1L
    private var host: HostSession? = null
    private var started = false
    private var sessionWatch: Job? = null

    /** LAN address guests should type, resolved when hosting starts. */
    @Volatile var address: String = ""

    fun start() {
        if (started) return
        started = true
        val bound = runCatching { relay.start() }
        if (bound.isFailure) {
            _state.value = HostUiState(status = HostStatus.ERROR, notice = "port 8787 is busy")
            started = false
            return
        }
        address = RemoteRelay.localAddresses().firstOrNull() ?: "127.0.0.1"
        rev = vm.session.value.revision.coerceAtLeast(1L)
        val h = HostSession(
            url = "ws://127.0.0.1:8787",
            getState = { buildState() },
            apply = { op -> applyOp(op) },
            onState = { s -> _state.value = s },
            onLog = {},
        )
        h.connect()
        h.create()
        host = h
        // Local edits on this device also reach the guests.
        sessionWatch = scope.launch {
            vm.session
                .map { it.revision }
                .distinctUntilChanged()
                .collect { h.publishState() }
        }
    }

    fun stop() {
        sessionWatch?.cancel()
        sessionWatch = null
        host?.end()
        host?.disconnect()
        host = null
        relay.stop()
        scope.cancel()
        started = false
        _state.value = HostUiState()
    }

    fun approve(guestId: String, role: Role) {
        host?.approve(guestId, role)
    }

    fun deny(guestId: String) = host?.deny(guestId) ?: Unit
    fun kick(guestId: String) = host?.kick(guestId) ?: Unit
    fun newCode() = host?.newCode() ?: Unit

    private fun buildState(): SessionState {
        val session = vm.session.value
        val ui = vm.uiState.value
        return SessionState(
            title = session.projectName,
            transport = TransportState(
                playing = ui.isPlaying,
                recording = ui.isRecording,
                beats = ui.playheadFrame.toDouble() / 48_000.0 * (session.tempoBpm / 60.0),
                bpm = session.tempoBpm.toDouble(),
            ),
            tracks = session.tracks.map { t ->
                TrackState(
                    id = "t${t.index}",
                    name = t.name,
                    color = "#%06X".format(t.colorHex and 0xFFFFFF),
                    volume = dbToVolume(t.gainDb),
                    pan = t.pan,
                    mute = t.isMuted,
                    solo = t.isSoloed,
                    armed = t.isArmedForRecording,
                )
            },
            rev = rev,
        )
    }

    private fun applyOp(op: Op): Boolean {
        fun index(trackId: String): Int? =
            trackId.removePrefix("t").toIntOrNull()?.takeIf { it in 0 until vm.session.value.tracks.size }

        when (op) {
            is Op.Param -> {
                val i = index(op.track) ?: return false
                when (op.param) {
                    "volume" -> vm.setTrackGain(i, volumeToDb(op.value))
                    "pan" -> vm.setTrackPan(i, op.value)
                    else -> return false
                }
                rev++
                return true
            }
            is Op.Toggle -> {
                val i = index(op.track) ?: return false
                val track = vm.session.value.tracks[i]
                when (op.param) {
                    "mute" -> if (track.isMuted != op.value) vm.toggleTrackMute(i)
                    "solo" -> if (track.isSoloed != op.value) vm.toggleTrackSolo(i)
                    "armed" -> if (track.isArmedForRecording != op.value) vm.toggleTrackArm(i)
                    else -> return false
                }
                rev++
                return true
            }
            is Op.TransportCmd -> {
                when (op.cmd) {
                    "play" -> if (!vm.uiState.value.isPlaying) vm.togglePlayPause()
                    "stop" -> vm.stop()
                    "locate" -> {
                        val beats = op.beats ?: return false
                        val frames = (beats * 60.0 / vm.session.value.tempoBpm * 48_000.0).toLong()
                        vm.seekToFrame(frames.coerceAtLeast(0L))
                    }
                    else -> return false
                }
                rev++
                return true
            }
            is Op.Rename, is Op.Tempo -> return false
        }
    }

    private fun dbToVolume(db: Float): Float =
        if (db <= -60f) 0f else 10f.pow(db / 20f)

    private fun volumeToDb(v: Float): Float =
        if (v <= 0.001f) -60f else (20f * log10(v)).coerceIn(-60f, 6f)
}

/** Host dialog: code, LAN address, approval cards, guest list, stop. */
@Composable
fun RemoteHostDialog(controller: RemoteHostController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mixer remote", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.status != HostStatus.LIVE) {
                    Text(
                        state.notice ?: "Starting the relay…",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .testTag("remote_code_panel"),
                    ) {
                        Text("JOIN CODE", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(
                            com.overlay.remote.RemoteCode.display(state.code ?: ""),
                            color = ElectricLime,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("remote_code"),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Open Phone Beat → REMOTE, enter ${controller.address} and this code.",
                            color = TextSecondary,
                            fontSize = 10.sp,
                        )
                    }
                }

                if (state.requests.isNotEmpty()) {
                    Text("WAITING FOR APPROVAL", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    state.requests.forEach { req ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(req.name, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { controller.approve(req.id, Role.MIXER) }) {
                                Text("MIXER", color = ElectricLime, fontSize = 11.sp)
                            }
                            TextButton(onClick = { controller.approve(req.id, Role.ENGINEER) }) {
                                Text("ENGINEER", color = ElectricLime, fontSize = 11.sp)
                            }
                            TextButton(onClick = { controller.deny(req.id) }) {
                                Text("DENY", color = Color(0xFFFF5A4A), fontSize = 11.sp)
                            }
                        }
                    }
                }

                if (state.guests.isNotEmpty()) {
                    Text("GUESTS", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    state.guests.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${g.name} · ${g.role.wire}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { controller.kick(g.id) }) {
                                Text("KICK", color = Color(0xFFFF5A4A), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE", color = TextSecondary) }
        },
        dismissButton = {
            TextButton(onClick = { controller.newCode() }) { Text("NEW CODE", color = TextMuted) }
        },
        containerColor = PanelInset,
    )
}
