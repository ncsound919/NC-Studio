package com.overlay.remote

/**
 * Host client for NCSound Remote — the DAW side.
 *
 * The host is authoritative, exactly like `collab/src/host/HostSession.ts`:
 * it re-validates every guest op (shape, role, lock, rate), applies it through
 * [apply], and broadcasts the result. Track identity lives here, so adding or
 * removing tracks is published with a full snapshot via [publishState].
 *
 * [getState] must return the live session (rev included); [apply] must return
 * false when the host refused the change.
 */
enum class HostStatus { IDLE, CONNECTING, LIVE, ENDED, ERROR }

data class HostGuest(val id: String, val name: String, val role: Role)
data class HostJoinRequest(val id: String, val name: String)

data class HostUiState(
    val status: HostStatus = HostStatus.IDLE,
    val sessionId: String? = null,
    val code: String? = null,
    val codeExpiresAt: Long = 0L,
    val guests: List<HostGuest> = emptyList(),
    val requests: List<HostJoinRequest> = emptyList(),
    val notice: String? = null,
)

class HostSession(
    private val url: String,
    private val origin: String? = null,
    private val getState: () -> SessionState,
    private val apply: (Op) -> Boolean,
    private val onState: (HostUiState) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private class Lock(val guestId: String, var until: Long)

    private var socket: RemoteSocket? = null
    private val locks = HashMap<String, Lock>()
    private val opWindows = HashMap<String, MutableList<Long>>()
    private var terminal = false

    /** Persist these with the project so a process death can resume the seat. */
    @Volatile var sessionId: String? = null
        private set
    @Volatile var hostToken: String? = null
        private set

    @Volatile private var state = HostUiState()

    fun connect() {
        socket = RemoteSocket(
            url = url,
            origin = origin,
            onOpen = { publish(state.copy(status = HostStatus.CONNECTING)) },
            onText = ::handleText,
            onFailure = { t -> onLog("host socket failure: ${t?.message}") },
            onClosed = { code, reason -> onLog("host socket closed $code $reason") },
        ).also { it.connect() }
    }

    /** Create a fresh session (a second create on a live socket orphans the first). */
    fun create() {
        if (terminal) return
        socket?.send(RemoteCodec.encodeClient(ClientFrame.HostCreate))
    }

    fun resume(sessionId: String, token: String) {
        this.sessionId = sessionId
        this.hostToken = token
        socket?.send(RemoteCodec.encodeClient(ClientFrame.HostResume(sessionId, token)))
    }

    fun approve(guestId: String, role: Role) {
        socket?.send(RemoteCodec.encodeClient(ClientFrame.HostApprove(guestId, role)))
    }

    fun deny(guestId: String) {
        socket?.send(RemoteCodec.encodeClient(ClientFrame.HostDeny(guestId)))
    }

    fun kick(guestId: String) {
        socket?.send(RemoteCodec.encodeClient(ClientFrame.HostKick(guestId)))
        locks.entries.removeIf { it.value.guestId == guestId }
    }

    fun newCode() {
        socket?.send(RemoteCodec.encodeClient(ClientFrame.HostNewCode))
    }

    fun end() {
        terminal = true
        socket?.send(RemoteCodec.encodeClient(ClientFrame.HostEnd))
    }

    fun disconnect() {
        terminal = true
        socket?.close()
        socket = null
        publish(state.copy(status = HostStatus.IDLE))
    }

    // ---- publishing ----

    /** Full snapshot: use for track add/remove/reorder and project loads. */
    fun publishState() {
        if (terminal) return
        socket?.send(RemoteCodec.encodeClient(ClientFrame.To(null, HostMsg.State(getState()))))
    }

    /** Partial update from a local (host-side) change. */
    fun publishDelta(delta: Delta) {
        if (terminal) return
        val rev = getState().rev
        socket?.send(RemoteCodec.encodeClient(ClientFrame.To(null, HostMsg.DeltaMsg(rev, delta))))
    }

    fun publishMeters(levels: Map<String, List<Float>>) {
        if (terminal) return
        socket?.send(RemoteCodec.encodeClient(ClientFrame.To(null, HostMsg.Meters(levels))))
    }

    // ---- frames ----

    private fun handleText(text: String) {
        val frame = RemoteCodec.decodeServer(text) ?: return
        when (frame) {
            is ServerFrame.HostCreated -> {
                sessionId = frame.sessionId
                hostToken = frame.token
                publish(
                    state.copy(
                        status = HostStatus.LIVE,
                        sessionId = frame.sessionId,
                        code = frame.code,
                        codeExpiresAt = frame.codeExpiresAt,
                        notice = null,
                    ),
                )
                publishState()
            }
            is ServerFrame.HostResumed -> publish(
                state.copy(
                    status = HostStatus.LIVE,
                    sessionId = frame.sessionId,
                    code = frame.code,
                    codeExpiresAt = frame.codeExpiresAt,
                ),
            )
            is ServerFrame.CodeUpdated -> publish(
                state.copy(code = frame.code, codeExpiresAt = frame.codeExpiresAt),
            )
            is ServerFrame.JoinRequest -> publish(
                state.copy(
                    requests = (state.requests + HostJoinRequest(frame.guestId, frame.name))
                        .distinctBy { it.id },
                ),
            )
            is ServerFrame.GuestOnline -> {
                val guests = (state.guests + HostGuest(frame.guestId, frame.name, frame.role))
                    .distinctBy { it.id }
                publish(state.copy(guests = guests, requests = state.requests.filterNot { it.id == frame.guestId }))
                // A joining guest needs the current picture immediately.
                socket?.send(
                    RemoteCodec.encodeClient(
                        ClientFrame.To(frame.guestId, HostMsg.State(getState())),
                    ),
                )
            }
            is ServerFrame.GuestLeft -> {
                locks.entries.removeIf { it.value.guestId == frame.guestId }
                opWindows.remove(frame.guestId)
                publish(
                    state.copy(
                        guests = state.guests.filterNot { it.id == frame.guestId },
                        requests = state.requests.filterNot { it.id == frame.guestId },
                    ),
                )
            }
            is ServerFrame.From -> handleGuestMsg(frame.guestId, frame.role, frame.msg)
            is ServerFrame.SessionEnded -> {
                terminal = true
                publish(state.copy(status = HostStatus.ENDED, guests = emptyList(), requests = emptyList()))
            }
            is ServerFrame.ErrorFrame -> publish(state.copy(status = HostStatus.ERROR, notice = frame.code))
            else -> Unit
        }
    }

    private fun handleGuestMsg(guestId: String, role: Role, msg: GuestMsg) {
        when (msg) {
            is GuestMsg.Resync -> socket?.send(
                RemoteCodec.encodeClient(ClientFrame.To(guestId, HostMsg.State(getState()))),
            )
            is GuestMsg.Release -> {
                val key = "${msg.track}:${msg.param}"
                if (locks[key]?.guestId == guestId) locks.remove(key)
            }
            is GuestMsg.OpMsg -> handleOp(guestId, role, msg)
        }
    }

    private fun handleOp(guestId: String, role: Role, msg: GuestMsg.OpMsg) {
        fun ack(ok: Boolean, reason: String? = null) {
            socket?.send(
                RemoteCodec.encodeClient(
                    ClientFrame.To(guestId, HostMsg.Ack(msg.id, ok, reason)),
                ),
            )
        }

        val now = System.currentTimeMillis()
        val window = opWindows.getOrPut(guestId) { mutableListOf() }
        window.removeAll { now - it > 1_000 }
        if (window.size >= RemoteLimits.GUEST_OPS_PER_SECOND) {
            ack(false, "rate_limited")
            return
        }
        window.add(now)

        val session = getState()
        val reason = RemoteRules.validate(msg.op) { id -> session.tracks.any { it.id == id } }
        if (reason != null) {
            ack(false, reason)
            return
        }
        if (!RemoteRules.allowed(role, msg.op)) {
            ack(false, "forbidden")
            return
        }
        val key = RemoteRules.lockKey(msg.op)
        if (key != null) {
            val lock = locks[key]
            if (lock != null && lock.guestId != guestId && lock.until > now) {
                ack(false, "locked")
                return
            }
        }
        if (!apply(msg.op)) {
            ack(false, "rejected")
            return
        }
        if (key != null) locks[key] = Lock(guestId, now + RemoteLimits.LOCK_LEASE_MS)

        // Param/toggle changes go out as a delta; structural changes (name,
        // tempo, transport) need the full picture.
        when (msg.op) {
            is Op.Param -> publishDelta(
                Delta(
                    tracks = listOf(
                        if (msg.op.param == "volume") {
                            TrackDelta(msg.op.track, volume = msg.op.value)
                        } else {
                            TrackDelta(msg.op.track, pan = msg.op.value)
                        },
                    ),
                ),
            )
            is Op.Toggle -> publishDelta(
                Delta(
                    tracks = listOf(
                        when (msg.op.param) {
                            "mute" -> TrackDelta(msg.op.track, mute = msg.op.value)
                            "solo" -> TrackDelta(msg.op.track, solo = msg.op.value)
                            else -> TrackDelta(msg.op.track, armed = msg.op.value)
                        },
                    ),
                ),
            )
            else -> publishState()
        }
        ack(true)
    }

    private fun publish(next: HostUiState) {
        state = next
        onState(next)
    }
}
