package com.example.session.protocol

import com.example.session.take.TakeManifest

/**
 * Mode 2 control/telemetry protocol.
 *
 * The artist's phone is the authority: the engineer sends commands, the artist
 * accepts or rejects them, and the engineer's UI is driven by events, never by
 * its own guesses. Wire format is the envelope from the design:
 *
 * ```json
 * {"v":1,"sid":"session-id","seq":42,"type":"StartTake","body":{"takeId":"...","atFrame":960000}}
 * ```
 */
const val PROTOCOL_VERSION = 1

/** Session states, in the order the design defines them. */
enum class SessionState {
    LOBBY,
    PREFLIGHT,
    IDLE,
    ARMED,
    COUNT_IN,
    RECORDING,
    FINALIZING,
    UPLOADING,
    VERIFIED,
    ENDED,
}

/** Why the artist refused a command. */
enum class RejectionReason {
    WRONG_STATE,
    NOT_ARMED,
    LOW_STORAGE,
    NOT_AUTHORIZED,
    BAD_VALUE,
    UNSUPPORTED,
}

/** Non-fatal problems the artist surfaces to the engineer. */
enum class WarningCode {
    LOW_STORAGE,
    CLIPPING,
    ROUTE_CHANGED,
    THERMAL,
}

enum class SessionRole(val wire: String) {
    ARTIST("artist"),
    ENGINEER("engineer"),
    ;

    companion object {
        fun fromWire(value: String?): SessionRole? = entries.firstOrNull { it.wire == value }
    }
}

/** Capabilities exchanged in Hello so both sides know what the other can do. */
object Capabilities {
    const val TAKE_CHUNKS = "take-chunks"
    const val MONITOR_STREAM = "monitor-stream"
    const val TALKBACK = "talkback"
    const val CUE_MIX = "cue-mix"
    const val CALIBRATION = "calibration"
    const val UPLOAD_RESUME = "upload-resume"

    val all = listOf(TAKE_CHUNKS, MONITOR_STREAM, TALKBACK, CUE_MIX, CALIBRATION, UPLOAD_RESUME)
}

/** The protocol envelope every message travels in. */
data class Envelope(
    val v: Int,
    val sid: String,
    val seq: Long,
    val type: String,
    /** Raw JSON body, kept as text so unknown messages stay inspectable. */
    val bodyJson: String,
)

/** Base type of every protocol message. */
sealed interface SessionMessage {
    val type: String
}

// ---------------------------------------------------------------- commands

sealed interface Command : SessionMessage

data class Arm(val armed: Boolean) : Command {
    override val type: String get() = "Arm"
}

data class StartTake(val takeId: String, val atFrame: Long) : Command {
    override val type: String get() = "StartTake"
}

data object StopTake : Command {
    override val type: String get() = "StopTake"
}

data class MarkTake(val takeId: String? = null) : Command {
    override val type: String get() = "MarkTake"
}

data class SetCueLevel(val levelDb: Float) : Command {
    override val type: String get() = "SetCueLevel"
}

data class Talkback(val on: Boolean) : Command {
    override val type: String get() = "Talkback"
}

data class Seek(val atFrame: Long) : Command {
    override val type: String get() = "Seek"
}

data class PlayBacking(val atFrame: Long) : Command {
    override val type: String get() = "PlayBacking"
}

data object Pause : Command {
    override val type: String get() = "Pause"
}

// ------------------------------------------------------------------ events

sealed interface Event : SessionMessage

data class StateChanged(val state: String, val revision: Long) : Event {
    override val type: String get() = "StateChanged"
}

data class TakeStarted(val takeId: String, val actualStartFrame: Long) : Event {
    override val type: String get() = "TakeStarted"
}

data class TakeStopped(val takeId: String, val frames: Long, val reason: String) : Event {
    override val type: String get() = "TakeStopped"
}

data class UploadProgress(
    val takeId: String,
    val state: String,
    val chunksDone: Int,
    val chunksTotal: Int,
) : Event {
    override val type: String get() = "UploadProgress"
}

data class Warning(val code: String, val detail: String = "") : Event {
    override val type: String get() = "Warning"
}

/** Full artist state, sent in reply to Hello and after every reconnect. */
data class StateSnapshot(
    val state: String,
    val revision: Long,
    val armed: Boolean,
    val cueLevelDb: Float,
    val activeTakeId: String? = null,
    val monitoring: Boolean = false,
    val storageFreeBytes: Long = -1L,
    val lastTakeId: String? = null,
    val lastTakeFrames: Long = 0L,
    val capabilities: List<String> = Capabilities.all,
    /** So the engineer can show who is on the other end without a second message. */
    val displayName: String? = null,
) : Event {
    override val type: String get() = "StateSnapshot"
}

/** The engineer may download and verify this take. */
data class TakeAvailable(val takeId: String, val manifest: TakeManifest) : Event {
    override val type: String get() = "TakeAvailable"
}

// ------------------------------------------------------------ link traffic

data class Hello(
    val protocolVersion: Int,
    val role: String,
    val capabilities: List<String>,
    val lastStateRevision: Long = -1L,
    val displayName: String? = null,
) : SessionMessage {
    override val type: String get() = "Hello"
}

data class Ping(val sentAtMs: Long) : SessionMessage {
    override val type: String get() = "Ping"
}

data class Pong(val pingSentAtMs: Long, val stateRevision: Long) : SessionMessage {
    override val type: String get() = "Pong"
}

data class Bye(val reason: String) : SessionMessage {
    override val type: String get() = "Bye"
}

data class Ack(
    val ackSeq: Long,
    val accepted: Boolean,
    val reason: String? = null,
    val detail: String? = null,
) : SessionMessage {
    override val type: String get() = "Ack"
}

/** Internal marker for a well-formed envelope with a type this build does not know. */
data class Unknown(val rawType: String) : SessionMessage {
    override val type: String get() = rawType
}
