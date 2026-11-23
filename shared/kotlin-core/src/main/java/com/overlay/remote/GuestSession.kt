package com.overlay.remote

/**
 * Guest client for NCSound Remote — the phone-mixer side.
 *
 * Behaviour mirrors `collab/src/guest/GuestSession.ts`, with the two hardening
 * items the web client lacks: ack timeouts (a lost ack triggers a resync
 * instead of a stuck fader) and stale-snapshot rejection (`state.rev` older
 * than the local view is ignored).
 *
 * Local moves are optimistic and coalesced (~30 Hz, last value per
 * track:param). While a control is held, incoming deltas cannot move it; the
 * hold is 700 ms after the last change and 150 ms after release.
 */
enum class GuestStatus { IDLE, CONNECTING, PENDING, LIVE, OFFLINE, DENIED, KICKED, ENDED, ERROR }

data class GuestUiState(
    val status: GuestStatus = GuestStatus.IDLE,
    val role: Role? = null,
    val session: SessionState? = null,
    val meters: Map<String, List<Float>> = emptyMap(),
    val notice: String? = null,
    val canUndoLocally: Boolean = false,
)

class GuestSession(
    private val url: String,
    private val origin: String? = null,
    private val onState: (GuestUiState) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private data class Held(val until: Long)

    private var socket: RemoteSocket? = null
    private var code: String = ""
    private var name: String = "Guest"
    private var resume: GuestResume? = null
    private var terminal = false
    private var flushThread: Thread? = null

    private var sessionId: String? = null
    private var guestId: String? = null
    private var role: Role? = null

    private var model: SessionState? = null
    private val meters = HashMap<String, List<Float>>()
    private val held = HashMap<String, Held>()
    // Optimistic values for fields the user is touching. The overlay must own
    // the value: a late snapshot can overwrite the model, and reading it back
    // there preserved the stale value instead of the finger's.
    private val optimisticFloat = HashMap<String, Float>()
    private val optimisticBool = HashMap<String, Boolean>()
    private val pendingOps = LinkedHashMap<String, Op>()
    private val ackDeadlines = HashMap<Int, Long>()
    private var nextOpId = 1
    private var lastResyncAt = 0L
    @Volatile private var joinSent = false

    @Volatile private var state = GuestUiState()

    fun connect() {
        socket = RemoteSocket(
            url = url,
            origin = origin,
            onOpen = {
                publish(state.copy(status = GuestStatus.CONNECTING))
                // A reconnect with a seat resumes it; a fresh join was already
                // queued by join() for the first connection.
                if (joinSent && resume != null) joinOrResume()
                else if (!joinSent && code.isNotEmpty()) joinOrResume()
            },
            onText = ::handleText,
            onFailure = { t ->
                joinSent = false
                onLog("socket failure: ${t?.message}")
                if (state.status == GuestStatus.LIVE || state.status == GuestStatus.PENDING) {
                    publish(state.copy(status = GuestStatus.OFFLINE))
                }
            },
            onClosed = { code, reason ->
                joinSent = false
                onLog("socket closed $code $reason")
            },
        ).also { it.connect() }
        startFlusher()
    }

    fun join(codeInput: String, name: String) {
        this.code = RemoteCode.normalize(codeInput)
        this.name = name.trim().ifEmpty { "Guest" }
        terminal = false
        joinOrResume()
    }

    private fun joinOrResume() {
        val sock = socket ?: return
        joinSent = true
        publish(state.copy(status = GuestStatus.CONNECTING, notice = null))
        sock.send(
            RemoteCodec.encodeClient(
                ClientFrame.GuestJoin(code, name, resume),
            ),
        )
    }

    fun disconnect() {
        terminal = true
        flushThread?.interrupt()
        flushThread = null
        socket?.close()
        socket = null
        resume = null
        sessionId = null
        guestId = null
        role = null
        model = null
        publish(GuestUiState(status = GuestStatus.IDLE))
    }

    // ---- local gestures ----

    fun setVolume(trackId: String, volume: Float) = mutate(
        trackId,
        "volume",
        Op.Param(trackId, "volume", volume.coerceIn(0f, 1f)),
    )

    fun setPan(trackId: String, pan: Float) = mutate(
        trackId,
        "pan",
        Op.Param(trackId, "pan", pan.coerceIn(-1f, 1f)),
    )

    fun toggle(trackId: String, param: String, value: Boolean) = mutate(
        trackId,
        param,
        Op.Toggle(trackId, param, value),
    )

    /** Transport commands have no local model to update; they are fire-and-forget. */
    fun transport(cmd: String, beats: Double? = null): Boolean {
        val r = role ?: return false
        val op = Op.TransportCmd(cmd, beats)
        if (!RemoteRules.allowed(r, op)) return false
        val id = nextOpId++
        ackDeadlines[id] = System.currentTimeMillis() + 1_500
        socket?.send(RemoteCodec.encodeClient(ClientFrame.Up(GuestMsg.OpMsg(id, op))))
        return true
    }

    fun release(trackId: String, param: String) {
        val key = "$trackId:$param"
        // A release that arrives inside the coalescing window must still send
        // the final value, or a fast gesture loses its last move.
        pendingOps.remove(key)?.let { op ->
            val id = nextOpId++
            ackDeadlines[id] = System.currentTimeMillis() + 1_500
            socket?.send(RemoteCodec.encodeClient(ClientFrame.Up(GuestMsg.OpMsg(id, op))))
        }
        held[key] = Held(System.currentTimeMillis() + 150)
        socket?.send(
            RemoteCodec.encodeClient(ClientFrame.Up(GuestMsg.Release(trackId, param))),
        )
    }

    fun resync() {
        val now = System.currentTimeMillis()
        if (now - lastResyncAt < 1_000) return
        lastResyncAt = now
        socket?.send(RemoteCodec.encodeClient(ClientFrame.Up(GuestMsg.Resync)))
    }

    /**
     * Applies an optimistic local change, marks the field held and queues the
     * op for the next coalesced flush. Returns false when the role may not
     * touch it (the UI should hide such controls; this is the enforcement).
     */
    private fun mutate(trackId: String, param: String, op: Op): Boolean {
        val r = role ?: run { onLog("mutate $trackId:$param — no role yet"); return false }
        if (!RemoteRules.allowed(r, op)) {
            onLog("mutate $trackId:$param — role $r not allowed")
            return false
        }
        val current = model ?: run { onLog("mutate $trackId:$param — no model yet"); return false }
        val updatedTracks = current.tracks.map { t ->
            if (t.id != trackId) t else when (op) {
                is Op.Param -> if (op.param == "volume") t.copy(volume = op.value) else t.copy(pan = op.value)
                is Op.Toggle -> when (op.param) {
                    "mute" -> t.copy(mute = op.value)
                    "solo" -> t.copy(solo = op.value)
                    "armed" -> t.copy(armed = op.value)
                    else -> t
                }
                else -> t
            }
        }
        model = current.copy(tracks = updatedTracks)
        when (op) {
            is Op.Param -> optimisticFloat["$trackId:$param"] = op.value
            is Op.Toggle -> optimisticBool["$trackId:$param"] = op.value
            else -> Unit
        }
        held["$trackId:$param"] = Held(System.currentTimeMillis() + 700)
        pendingOps["$trackId:$param"] = op
        publishState()
        return true
    }

    // ---- flusher: coalesced send + ack timeout ----

    private fun startFlusher() {
        if (flushThread?.isAlive == true) return
        flushThread = Thread {
            while (!terminal) {
                try {
                    Thread.sleep(33)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                flushOps()
                checkAcks()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun flushOps() {
        if (pendingOps.isEmpty()) return
        val sock = socket ?: return
        val now = System.currentTimeMillis()
        val snapshot = pendingOps.entries.toList()
        pendingOps.clear()
        for ((key, op) in snapshot) {
            val h = held[key]
            if (h == null || h.until < now) {
                // Nothing holds the field any more (a gesture ended without a
                // release): send the latest value anyway.
            }
            val id = nextOpId++
            ackDeadlines[id] = now + 1_500
            sock.send(RemoteCodec.encodeClient(ClientFrame.Up(GuestMsg.OpMsg(id, op))))
        }
    }

    private fun checkAcks() {
        val now = System.currentTimeMillis()
        val overdue = ackDeadlines.filterValues { it < now }.keys
        if (overdue.isNotEmpty()) {
            ackDeadlines.keys.removeAll(overdue)
            onLog("ack timeout for ${overdue.size} op(s), resyncing")
            resync()
        }
    }

    // ---- server frames ----

    private fun handleText(text: String) {
        val frame = RemoteCodec.decodeServer(text) ?: return
        when (frame) {
            is ServerFrame.JoinPending -> publish(state.copy(status = GuestStatus.PENDING))
            is ServerFrame.JoinApproved -> {
                sessionId = frame.sessionId
                guestId = frame.guestId
                role = frame.role
                resume = GuestResume(frame.sessionId, frame.guestId, frame.token)
                publish(state.copy(status = GuestStatus.LIVE, role = frame.role, notice = null))
                resync()
            }
            is ServerFrame.JoinDenied -> {
                terminal = true
                socket?.close()
                publish(state.copy(status = GuestStatus.DENIED))
            }
            is ServerFrame.Kicked -> {
                terminal = true
                socket?.close()
                publish(state.copy(status = GuestStatus.KICKED))
            }
            is ServerFrame.SessionEnded -> {
                terminal = true
                socket?.close()
                publish(state.copy(status = GuestStatus.ENDED))
            }
            is ServerFrame.HostOffline -> publish(state.copy(status = GuestStatus.OFFLINE))
            is ServerFrame.HostOnline -> {
                publish(state.copy(status = GuestStatus.LIVE))
                resync()
            }
            is ServerFrame.Msg -> handleHostMsg(frame.msg)
            is ServerFrame.ErrorFrame -> {
                if (frame.code == "session_gone") {
                    terminal = true
                    socket?.close()
                }
                publish(state.copy(status = GuestStatus.ERROR, notice = frame.code))
            }
            else -> Unit
        }
    }

    private fun handleHostMsg(msg: HostMsg) {
        when (msg) {
            is HostMsg.State -> {
                val incoming = msg.state
                val current = model
                // Stale snapshots are ignored; a host rev reset arrives as a
                // lower rev, so accept anything strictly newer or a fresh 0.
                if (incoming.rev < (current?.rev ?: -1L)) return
                model = applyHeld(incoming)
                publishState()
            }
            is HostMsg.DeltaMsg -> {
                val current = model ?: return
                if (msg.rev <= current.rev) return
                if (msg.rev > current.rev + 1) {
                    resync()
                    return
                }
                model = applyHeld(applyDelta(current.copy(rev = msg.rev), msg.delta))
                publishState()
            }
            is HostMsg.Meters -> {
                meters.clear()
                meters.putAll(msg.levels)
                publish(state.copy(meters = HashMap(meters)))
            }
            is HostMsg.Ack -> {
                ackDeadlines.remove(msg.id)
                if (!msg.ok) {
                    publish(state.copy(notice = msg.reason ?: "rejected"))
                    resync()
                }
            }
        }
    }

    private fun applyDelta(state: SessionState, delta: Delta): SessionState {
        val byId = delta.tracks.associateBy { it.id }
        val tracks = state.tracks.map { t ->
            val d = byId[t.id]
            if (d == null) t else t.copy(
                name = d.name ?: t.name,
                color = d.color ?: t.color,
                volume = d.volume ?: t.volume,
                pan = d.pan ?: t.pan,
                mute = d.mute ?: t.mute,
                solo = d.solo ?: t.solo,
                armed = d.armed ?: t.armed,
            )
        }
        return state.copy(tracks = tracks, transport = delta.transport ?: state.transport)
    }

    /** Fields still held by a local gesture win over the incoming state. */
    private fun applyHeld(state: SessionState): SessionState {
        val now = System.currentTimeMillis()
        val tracks = state.tracks.map { t ->
            fun floated(param: String): Float? {
                val key = "${t.id}:$param"
                val h = held[key] ?: return null
                if (h.until <= now) {
                    held.remove(key)
                    optimisticFloat.remove(key)
                    return null
                }
                return optimisticFloat[key]
            }
            fun toggled(param: String): Boolean? {
                val key = "${t.id}:$param"
                val h = held[key] ?: return null
                if (h.until <= now) {
                    held.remove(key)
                    optimisticBool.remove(key)
                    return null
                }
                return optimisticBool[key]
            }
            t.copy(
                volume = floated("volume") ?: t.volume,
                pan = floated("pan") ?: t.pan,
                mute = toggled("mute") ?: t.mute,
                solo = toggled("solo") ?: t.solo,
                armed = toggled("armed") ?: t.armed,
            )
        }
        return state.copy(tracks = tracks)
    }

    private fun publishState() {
        publish(state.copy(session = model, role = role))
    }

    private fun publish(next: GuestUiState) {
        state = next
        onState(next)
    }
}
