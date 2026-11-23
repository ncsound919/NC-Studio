package com.overlay.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for the NCSound Remote protocol (see `protocol.ts` in
 * ncsound-daw/collab). Uses org.json, which Android provides at runtime and
 * kotlin-core gets as a compile-only dependency, so neither app gains a new
 * serialization stack.
 */
sealed class ClientFrame {
    data object HostCreate : ClientFrame()
    data class HostResume(val sessionId: String, val token: String) : ClientFrame()
    data class HostApprove(val guestId: String, val role: Role) : ClientFrame()
    data class HostDeny(val guestId: String) : ClientFrame()
    data class HostKick(val guestId: String) : ClientFrame()
    data object HostNewCode : ClientFrame()
    data object HostEnd : ClientFrame()
    data class GuestJoin(
        val code: String,
        val name: String,
        val resume: GuestResume? = null,
    ) : ClientFrame()
    data class Up(val msg: GuestMsg) : ClientFrame()
    data class To(val guestId: String?, val msg: HostMsg) : ClientFrame()
}

data class GuestResume(val sessionId: String, val guestId: String, val token: String)

sealed class ServerFrame {
    data class HostCreated(
        val sessionId: String,
        val token: String,
        val code: String,
        val codeExpiresAt: Long,
    ) : ServerFrame()
    data class HostResumed(val sessionId: String, val code: String, val codeExpiresAt: Long) : ServerFrame()
    data class CodeUpdated(val code: String, val codeExpiresAt: Long) : ServerFrame()
    data class JoinRequest(val guestId: String, val name: String) : ServerFrame()
    data object JoinPending : ServerFrame()
    data class JoinApproved(
        val sessionId: String,
        val guestId: String,
        val token: String,
        val role: Role,
    ) : ServerFrame()
    data object JoinDenied : ServerFrame()
    data object Kicked : ServerFrame()
    data object SessionEnded : ServerFrame()
    data object HostOffline : ServerFrame()
    data object HostOnline : ServerFrame()
    data class GuestOnline(val guestId: String, val name: String, val role: Role) : ServerFrame()
    data class GuestLeft(val guestId: String) : ServerFrame()
    data class From(val guestId: String, val role: Role, val msg: GuestMsg) : ServerFrame()
    data class Msg(val msg: HostMsg) : ServerFrame()
    data class ErrorFrame(val code: String) : ServerFrame()
}

object RemoteCodec {

    // ---- client → relay ----

    fun encodeClient(frame: ClientFrame): String = when (frame) {
        is ClientFrame.HostCreate -> JSONObject().put("t", "host.create").toString()
        is ClientFrame.HostResume -> JSONObject()
            .put("t", "host.resume")
            .put("sessionId", frame.sessionId)
            .put("token", frame.token)
            .toString()
        is ClientFrame.HostApprove -> JSONObject()
            .put("t", "host.approve")
            .put("guestId", frame.guestId)
            .put("role", frame.role.wire)
            .toString()
        is ClientFrame.HostDeny -> JSONObject()
            .put("t", "host.deny")
            .put("guestId", frame.guestId)
            .toString()
        is ClientFrame.HostKick -> JSONObject()
            .put("t", "host.kick")
            .put("guestId", frame.guestId)
            .toString()
        is ClientFrame.HostNewCode -> JSONObject().put("t", "host.newCode").toString()
        is ClientFrame.HostEnd -> JSONObject().put("t", "host.end").toString()
        is ClientFrame.GuestJoin -> JSONObject()
            .put("t", "guest.join")
            .put("code", frame.code)
            .put("name", frame.name)
            .apply {
                frame.resume?.let {
                    put(
                        "resume",
                        JSONObject()
                            .put("sessionId", it.sessionId)
                            .put("guestId", it.guestId)
                            .put("token", it.token),
                    )
                }
            }
            .toString()
        is ClientFrame.Up -> JSONObject()
            .put("t", "up")
            .put("msg", encodeGuestMsg(frame.msg))
            .toString()
        is ClientFrame.To -> JSONObject()
            .put("t", "to")
            .apply { frame.guestId?.let { put("guestId", it) } }
            .put("msg", encodeHostMsg(frame.msg))
            .toString()
    }

    fun decodeClient(text: String): ClientFrame? {
        val o = try { JSONObject(text) } catch (_: Exception) { return null }
        val t = o.optString("t", "")
        val role = Role.fromWire(o.optString("role"))
        return when (t) {
            "host.create" -> ClientFrame.HostCreate
            "host.resume" -> ClientFrame.HostResume(
                o.optString("sessionId"),
                o.optString("token"),
            )
            "host.approve" -> role?.let {
                ClientFrame.HostApprove(o.optString("guestId"), it)
            }
            "host.deny" -> ClientFrame.HostDeny(o.optString("guestId"))
            "host.kick" -> ClientFrame.HostKick(o.optString("guestId"))
            "host.newCode" -> ClientFrame.HostNewCode
            "host.end" -> ClientFrame.HostEnd
            "guest.join" -> {
                val resume = o.optJSONObject("resume")?.let {
                    GuestResume(
                        it.optString("sessionId"),
                        it.optString("guestId"),
                        it.optString("token"),
                    )
                }
                ClientFrame.GuestJoin(
                    code = o.optString("code"),
                    name = o.optString("name"),
                    resume = resume,
                )
            }
            "up" -> o.optJSONObject("msg")?.let { decodeGuestMsg(it) }?.let { ClientFrame.Up(it) }
            "to" -> o.optJSONObject("msg")?.let { decodeHostMsg(it) }?.let {
                ClientFrame.To(o.optString("guestId").takeIf { g -> g.isNotEmpty() }, it)
            }
            else -> null
        }
    }

    // ---- relay → client ----

    fun encodeServer(frame: ServerFrame): String = when (frame) {
        is ServerFrame.HostCreated -> JSONObject()
            .put("t", "host.created")
            .put("sessionId", frame.sessionId)
            .put("token", frame.token)
            .put("code", frame.code)
            .put("codeExpiresAt", frame.codeExpiresAt)
            .toString()
        is ServerFrame.HostResumed -> JSONObject()
            .put("t", "host.resumed")
            .put("sessionId", frame.sessionId)
            .put("code", frame.code)
            .put("codeExpiresAt", frame.codeExpiresAt)
            .toString()
        is ServerFrame.CodeUpdated -> JSONObject()
            .put("t", "code.updated")
            .put("code", frame.code)
            .put("codeExpiresAt", frame.codeExpiresAt)
            .toString()
        is ServerFrame.JoinRequest -> JSONObject()
            .put("t", "join.request")
            .put("guestId", frame.guestId)
            .put("name", frame.name)
            .toString()
        is ServerFrame.JoinPending -> JSONObject().put("t", "join.pending").toString()
        is ServerFrame.JoinApproved -> JSONObject()
            .put("t", "join.approved")
            .put("sessionId", frame.sessionId)
            .put("guestId", frame.guestId)
            .put("token", frame.token)
            .put("role", frame.role.wire)
            .toString()
        is ServerFrame.JoinDenied -> JSONObject().put("t", "join.denied").toString()
        is ServerFrame.Kicked -> JSONObject().put("t", "kicked").toString()
        is ServerFrame.SessionEnded -> JSONObject().put("t", "session.ended").toString()
        is ServerFrame.HostOffline -> JSONObject().put("t", "host.offline").toString()
        is ServerFrame.HostOnline -> JSONObject().put("t", "host.online").toString()
        is ServerFrame.GuestOnline -> JSONObject()
            .put("t", "guest.online")
            .put("guestId", frame.guestId)
            .put("name", frame.name)
            .put("role", frame.role.wire)
            .toString()
        is ServerFrame.GuestLeft -> JSONObject()
            .put("t", "guest.left")
            .put("guestId", frame.guestId)
            .toString()
        is ServerFrame.From -> JSONObject()
            .put("t", "from")
            .put("guestId", frame.guestId)
            .put("role", frame.role.wire)
            .put("msg", encodeGuestMsg(frame.msg))
            .toString()
        is ServerFrame.Msg -> JSONObject()
            .put("t", "msg")
            .put("msg", encodeHostMsg(frame.msg))
            .toString()
        is ServerFrame.ErrorFrame -> JSONObject()
            .put("t", "error")
            .put("code", frame.code)
            .toString()
    }

    fun decodeServer(text: String): ServerFrame? {
        val o = try { JSONObject(text) } catch (_: Exception) { return null }
        val role = Role.fromWire(o.optString("role"))
        return when (o.optString("t", "")) {
            "host.created" -> ServerFrame.HostCreated(
                o.optString("sessionId"),
                o.optString("token"),
                o.optString("code"),
                o.optLong("codeExpiresAt"),
            )
            "host.resumed" -> ServerFrame.HostResumed(
                o.optString("sessionId"),
                o.optString("code"),
                o.optLong("codeExpiresAt"),
            )
            "code.updated" -> ServerFrame.CodeUpdated(
                o.optString("code"),
                o.optLong("codeExpiresAt"),
            )
            "join.request" -> ServerFrame.JoinRequest(
                o.optString("guestId"),
                o.optString("name"),
            )
            "join.pending" -> ServerFrame.JoinPending
            "join.approved" -> role?.let {
                ServerFrame.JoinApproved(
                    o.optString("sessionId"),
                    o.optString("guestId"),
                    o.optString("token"),
                    it,
                )
            }
            "join.denied" -> ServerFrame.JoinDenied
            "kicked" -> ServerFrame.Kicked
            "session.ended" -> ServerFrame.SessionEnded
            "host.offline" -> ServerFrame.HostOffline
            "host.online" -> ServerFrame.HostOnline
            "guest.online" -> role?.let {
                ServerFrame.GuestOnline(o.optString("guestId"), o.optString("name"), it)
            }
            "guest.left" -> ServerFrame.GuestLeft(o.optString("guestId"))
            "from" -> {
                val r = role ?: return null
                o.optJSONObject("msg")?.let { decodeGuestMsg(it) }?.let {
                    ServerFrame.From(o.optString("guestId"), r, it)
                }
            }
            "msg" -> o.optJSONObject("msg")?.let { decodeHostMsg(it) }?.let {
                ServerFrame.Msg(it)
            }
            "error" -> ServerFrame.ErrorFrame(o.optString("code"))
            else -> null
        }
    }

    // ---- nested messages ----

    fun encodeGuestMsg(msg: GuestMsg): JSONObject = when (msg) {
        is GuestMsg.OpMsg -> JSONObject()
            .put("t", "op")
            .put("id", msg.id)
            .put("op", encodeOp(msg.op))
        is GuestMsg.Release -> JSONObject()
            .put("t", "release")
            .put("track", msg.track)
            .put("param", msg.param)
        is GuestMsg.Resync -> JSONObject().put("t", "resync")
    }

    fun decodeGuestMsg(o: JSONObject): GuestMsg? = when (o.optString("t")) {
        "op" -> o.optJSONObject("op")?.let { decodeOp(it) }?.let {
            GuestMsg.OpMsg(o.optInt("id"), it)
        }
        "release" -> GuestMsg.Release(o.optString("track"), o.optString("param"))
        "resync" -> GuestMsg.Resync
        else -> null
    }

    fun encodeHostMsg(msg: HostMsg): JSONObject = when (msg) {
        is HostMsg.State -> JSONObject()
            .put("t", "state")
            .put("state", encodeSessionState(msg.state))
        is HostMsg.Meters -> JSONObject()
            .put("t", "meters")
            .put("levels", JSONObject().apply {
                msg.levels.forEach { (id, pair) ->
                    put(id, JSONArray().put(pair.getOrElse(0) { 0f }).put(pair.getOrElse(1) { 0f }))
                }
            })
        is HostMsg.Ack -> JSONObject()
            .put("t", "ack")
            .put("id", msg.id)
            .put("ok", msg.ok)
            .apply { msg.reason?.let { put("reason", it) } }
        is HostMsg.DeltaMsg -> JSONObject()
            .put("t", "delta")
            .put("rev", msg.rev)
            .apply {
                if (msg.delta.tracks.isNotEmpty()) {
                    put("tracks", JSONArray().apply {
                        msg.delta.tracks.forEach { put(encodeTrackDelta(it)) }
                    })
                }
                msg.delta.transport?.let { put("transport", encodeTransport(it)) }
            }
    }

    fun decodeHostMsg(o: JSONObject): HostMsg? = when (o.optString("t")) {
        "state" -> o.optJSONObject("state")?.let { HostMsg.State(decodeSessionState(it)) }
        "meters" -> {
            val levels = mutableMapOf<String, List<Float>>()
            val obj = o.optJSONObject("levels") ?: JSONObject()
            obj.keys().forEach { id ->
                val arr = obj.optJSONArray(id) ?: return@forEach
                levels[id] = listOf(
                    arr.optDouble(0, 0.0).toFloat(),
                    arr.optDouble(1, 0.0).toFloat(),
                )
            }
            HostMsg.Meters(levels)
        }
        "ack" -> HostMsg.Ack(
            o.optInt("id"),
            o.optBoolean("ok"),
            o.optString("reason").takeIf { it.isNotEmpty() },
        )
        "delta" -> HostMsg.DeltaMsg(
            rev = o.optLong("rev"),
            delta = Delta(
                tracks = o.optJSONArray("tracks")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { decodeTrackDelta(it) }
                    }
                } ?: emptyList(),
                transport = o.optJSONObject("transport")?.let { decodeTransport(it) },
            ),
        )
        else -> null
    }

    fun encodeOp(op: Op): JSONObject = when (op) {
        is Op.Param -> JSONObject()
            .put("k", "param")
            .put("track", op.track)
            .put("param", op.param)
            .put("value", op.value.toDouble())
        is Op.Toggle -> JSONObject()
            .put("k", "toggle")
            .put("track", op.track)
            .put("param", op.param)
            .put("value", op.value)
        is Op.Rename -> JSONObject()
            .put("k", "rename")
            .put("track", op.track)
            .put("name", op.name)
        is Op.Tempo -> JSONObject()
            .put("k", "tempo")
            .put("bpm", op.bpm.toDouble())
        is Op.TransportCmd -> JSONObject()
            .put("k", "transport")
            .put("cmd", op.cmd)
            .apply { op.beats?.let { put("beats", it) } }
    }

    fun decodeOp(o: JSONObject): Op? = when (o.optString("k")) {
        "param" -> Op.Param(
            o.optString("track"),
            o.optString("param"),
            o.optDouble("value", 0.0).toFloat(),
        )
        "toggle" -> Op.Toggle(
            o.optString("track"),
            o.optString("param"),
            o.optBoolean("value"),
        )
        "rename" -> Op.Rename(o.optString("track"), o.optString("name"))
        "tempo" -> Op.Tempo(o.optDouble("bpm", 120.0).toFloat())
        "transport" -> Op.TransportCmd(
            o.optString("cmd"),
            if (o.has("beats")) o.optDouble("beats") else null,
        )
        else -> null
    }

    fun encodeSessionState(state: SessionState): JSONObject = JSONObject()
        .put("title", state.title)
        .put("transport", encodeTransport(state.transport))
        .put("tracks", JSONArray().apply { state.tracks.forEach { put(encodeTrack(it)) } })
        .put("rev", state.rev)

    fun decodeSessionState(o: JSONObject): SessionState = SessionState(
        title = o.optString("title"),
        transport = o.optJSONObject("transport")?.let { decodeTransport(it) }
            ?: TransportState(),
        tracks = o.optJSONArray("tracks")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { decodeTrack(it) } }
        } ?: emptyList(),
        rev = o.optLong("rev"),
    )

    private fun encodeTrack(t: TrackState): JSONObject = JSONObject()
        .put("id", t.id)
        .put("name", t.name)
        .put("color", t.color)
        .put("volume", t.volume.toDouble())
        .put("pan", t.pan.toDouble())
        .put("mute", t.mute)
        .put("solo", t.solo)
        .put("armed", t.armed)

    private fun decodeTrack(o: JSONObject): TrackState = TrackState(
        id = o.optString("id"),
        name = o.optString("name"),
        color = o.optString("color"),
        volume = o.optDouble("volume", 1.0).toFloat(),
        pan = o.optDouble("pan", 0.0).toFloat(),
        mute = o.optBoolean("mute"),
        solo = o.optBoolean("solo"),
        armed = o.optBoolean("armed"),
    )

    private fun encodeTrackDelta(d: TrackDelta): JSONObject = JSONObject()
        .put("id", d.id)
        .apply {
            d.name?.let { put("name", it) }
            d.color?.let { put("color", it) }
            d.volume?.let { put("volume", it.toDouble()) }
            d.pan?.let { put("pan", it.toDouble()) }
            d.mute?.let { put("mute", it) }
            d.solo?.let { put("solo", it) }
            d.armed?.let { put("armed", it) }
        }

    private fun decodeTrackDelta(o: JSONObject): TrackDelta = TrackDelta(
        id = o.optString("id"),
        name = o.optString("name").takeIf { o.has("name") },
        color = o.optString("color").takeIf { o.has("color") },
        volume = if (o.has("volume")) o.optDouble("volume", 1.0).toFloat() else null,
        pan = if (o.has("pan")) o.optDouble("pan", 0.0).toFloat() else null,
        mute = if (o.has("mute")) o.optBoolean("mute") else null,
        solo = if (o.has("solo")) o.optBoolean("solo") else null,
        armed = if (o.has("armed")) o.optBoolean("armed") else null,
    )

    private fun encodeTransport(t: TransportState): JSONObject = JSONObject()
        .put("playing", t.playing)
        .put("recording", t.recording)
        .put("beats", t.beats)
        .put("bpm", t.bpm)

    private fun decodeTransport(o: JSONObject): TransportState = TransportState(
        playing = o.optBoolean("playing"),
        recording = o.optBoolean("recording"),
        beats = o.optDouble("beats", 0.0),
        bpm = o.optDouble("bpm", 120.0),
    )
}
