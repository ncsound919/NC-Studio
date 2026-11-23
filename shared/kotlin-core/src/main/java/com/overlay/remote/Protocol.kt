package com.overlay.remote

/**
 * NCSound Remote protocol — a faithful Kotlin port of
 * `ncsound-daw/collab/src/protocol.ts`.
 *
 * The wire format is JSON text frames with a discriminator `t` (envelope),
 * nested `msg` / `op` discriminators, and an authoritative host. Keeping this
 * byte-compatible means a desktop DAW host/guest and the phone apps can share
 * one session, whether the relay runs as the bundled Node service or as the
 * embedded Kotlin relay in this module.
 */

/** Roles a host can grant a guest seat. */
enum class Role(val wire: String) {
    ENGINEER("engineer"),
    MIXER("mixer"),
    VIEWER("viewer");

    companion object {
        fun fromWire(value: String?): Role? = entries.firstOrNull { it.wire == value }
    }
}

/** Authoritative per-track mixer state (volumes are linear 0..1). */
data class TrackState(
    val id: String,
    val name: String,
    val color: String,
    val volume: Float,
    val pan: Float,
    val mute: Boolean,
    val solo: Boolean,
    val armed: Boolean,
)

data class TransportState(
    val playing: Boolean = false,
    val recording: Boolean = false,
    val beats: Double = 0.0,
    val bpm: Double = 120.0,
)

data class SessionState(
    val title: String,
    val transport: TransportState,
    val tracks: List<TrackState>,
    val rev: Long,
)

/** Partial track update; null fields mean "unchanged". */
data class TrackDelta(
    val id: String,
    val name: String? = null,
    val color: String? = null,
    val volume: Float? = null,
    val pan: Float? = null,
    val mute: Boolean? = null,
    val solo: Boolean? = null,
    val armed: Boolean? = null,
)

data class Delta(
    val tracks: List<TrackDelta> = emptyList(),
    val transport: TransportState? = null,
)

/** A guest's only mutating payload. */
sealed class Op {
    data class Param(val track: String, val param: String, val value: Float) : Op()
    data class Toggle(val track: String, val param: String, val value: Boolean) : Op()
    data class Rename(val track: String, val name: String) : Op()
    data class Tempo(val bpm: Float) : Op()
    data class TransportCmd(val cmd: String, val beats: Double? = null) : Op()
}

/** Guest → host messages. */
sealed class GuestMsg {
    data class OpMsg(val id: Int, val op: Op) : GuestMsg()
    data class Release(val track: String, val param: String) : GuestMsg()
    data object Resync : GuestMsg()
}

/** Host → guest messages. `meters` is unversioned and droppable. */
sealed class HostMsg {
    data class State(val state: SessionState) : HostMsg()
    data class Meters(val levels: Map<String, List<Float>>) : HostMsg()
    data class Ack(val id: Int, val ok: Boolean, val reason: String? = null) : HostMsg()
    data class DeltaMsg(val rev: Long, val delta: Delta) : HostMsg()
}

object RemoteLimits {
    const val MAX_TRACKS = 64
    const val MAX_PENDING = 16
    const val LOCK_LEASE_MS = 1_200L
    const val GUEST_SEAT_GRACE_MS = 120_000L
    const val CODE_TTL_MS = 15 * 60_000L
    const val GUEST_OPS_PER_SECOND = 200
    const val MAX_FRAME_BYTES = 128 * 1024
}

/**
 * Session codes: 32-character alphabet without I, L, O, U (they read as
 * digits on a phone keypad), 8 characters. Normalization accepts sloppy input
 * (`abcd-efgh`, lowercase, O for zero) exactly like the web client.
 */
object RemoteCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 8

    fun generate(random: java.util.Random = java.util.Random()): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun normalize(input: String): String = buildString {
        for (ch in input.uppercase()) {
            when (ch) {
                'O' -> append('0')
                'I', 'L' -> append('1')
                in '0'..'9', in 'A'..'Z' -> if (ch in ALPHABET) append(ch)
            }
        }
    }

    fun isCode(input: String): Boolean =
        input.length == LENGTH && input.all { it in ALPHABET }

    fun display(code: String): String =
        if (code.length == LENGTH) "${code.substring(0, 4)}-${code.substring(4)}" else code
}

/** Validation + authorization rules, ported from `validateOp` / `allowed`. */
object RemoteRules {
    private val TOGGLE_PARAMS = setOf("mute", "solo", "armed")
    private val TRANSPORT_CMDS = setOf("play", "stop", "record", "locate")

    /** Returns a failure reason, or null when the op is well-formed. */
    fun validate(op: Op, trackExists: (String) -> Boolean): String? = when (op) {
        is Op.Param -> when {
            op.track.isBlank() || op.track.length > 64 -> "invalid"
            op.param != "volume" && op.param != "pan" -> "invalid"
            op.param == "volume" && (op.value < 0f || op.value > 1f) -> "invalid"
            op.param == "pan" && (op.value < -1f || op.value > 1f) -> "invalid"
            !trackExists(op.track) -> "unknown_track"
            else -> null
        }
        is Op.Toggle -> when {
            op.param !in TOGGLE_PARAMS -> "invalid"
            !trackExists(op.track) -> "unknown_track"
            else -> null
        }
        is Op.Rename -> when {
            op.track.isBlank() || op.track.length > 64 -> "invalid"
            op.name.trim().isEmpty() || op.name.trim().length > 64 -> "invalid"
            !trackExists(op.track) -> "unknown_track"
            else -> null
        }
        is Op.Tempo -> if (op.bpm < 20f || op.bpm > 300f) "invalid" else null
        is Op.TransportCmd -> when {
            op.cmd !in TRANSPORT_CMDS -> "invalid"
            op.cmd == "locate" && ((op.beats ?: -1.0) < 0.0 || (op.beats ?: -1.0) > 1_000_000.0) -> "invalid"
            else -> null
        }
    }

    /** Role gate, ported from `allowed()` in protocol.ts. */
    fun allowed(role: Role, op: Op): Boolean = when (role) {
        Role.ENGINEER -> true
        Role.MIXER -> when (op) {
            is Op.Param -> true
            is Op.Toggle -> op.param == "mute" || op.param == "solo"
            is Op.Rename, is Op.Tempo -> false
            is Op.TransportCmd -> op.cmd != "record"
        }
        Role.VIEWER -> false
    }

    /** Lock key for conflict prevention: only params and toggles take locks. */
    fun lockKey(op: Op): String? = when (op) {
        is Op.Param -> "${op.track}:${op.param}"
        is Op.Toggle -> "${op.track}:${op.param}"
        else -> null
    }
}
