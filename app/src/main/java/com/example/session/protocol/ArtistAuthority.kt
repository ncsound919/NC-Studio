package com.example.session.protocol

import com.example.session.take.TakeManifest

/** What the authority decided about one command. */
data class AuthorityResult(
    val ack: Ack,
    val events: List<SessionMessage> = emptyList(),
) {
    val accepted: Boolean get() = ack.accepted
}

/** Reply to a Hello. [epoch] is the link generation the commands must carry. */
data class HelloResult(
    val epoch: Long,
    val accepted: Boolean,
    val events: List<SessionMessage>,
)

/**
 * The artist's phone is the timing and safety authority.
 *
 * It owns the session state, decides which commands are legal in that state,
 * schedules takes ahead of the playhead, guarantees a duplicated `StartTake`
 * only ever creates one take, and never replays a command from a previous
 * connection (each transport generation needs a fresh `Hello`).
 */
class ArtistAuthority(
    private val sid: String,
    private val currentFrame: () -> Long = { 0L },
    private val startSafetyFrames: Long = DEFAULT_START_SAFETY_FRAMES,
    private val displayName: () -> String? = { null },
) {
    var state: SessionState = SessionState.LOBBY
        private set
    var revision: Long = 0L
        private set
    var armed: Boolean = false
        private set
    var cueLevelDb: Float = -6f
        private set
    var activeTakeId: String? = null
        private set
    var monitoring: Boolean = false
        private set
    var storageLow: Boolean = false
        private set
    var storageFreeBytes: Long = -1L
        private set

    private var transportGeneration = 0L
    private var helloedGeneration = -1L
    private var lastTakeId: String? = null
    private var lastTakeFrames: Long = 0L
    private val completedTakeIds = mutableSetOf<String>()

    /** Called when a transport connects; commands must carry this generation. */
    fun newTransportGeneration(): Long {
        helloedGeneration = -1L
        return ++transportGeneration
    }

    /** Marks the link lost; pending commands from the old generation are dead. */
    fun onTransportLost() {
        helloedGeneration = -1L
    }

    fun onHello(hello: Hello, generation: Long): HelloResult {
        if (generation != transportGeneration) {
            return HelloResult(generation, accepted = false, events = emptyList())
        }
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            return HelloResult(
                generation,
                accepted = false,
                events = listOf(Bye("UNSUPPORTED_PROTOCOL_VERSION")),
            )
        }
        helloedGeneration = generation
        revision++
        return HelloResult(generation, accepted = true, events = listOf(snapshot()))
    }

    fun preflightPassed(freeBytes: Long) {
        storageFreeBytes = freeBytes
        storageLow = freeBytes in 0..MIN_FREE_BYTES
        if (state == SessionState.LOBBY || state == SessionState.PREFLIGHT) {
            transition(SessionState.IDLE)
        }
    }

    fun beginPreflight() {
        if (state == SessionState.LOBBY) transition(SessionState.PREFLIGHT)
    }

    fun endSession() {
        transition(SessionState.ENDED)
        helloedGeneration = -1L
    }

    fun setMonitoring(active: Boolean) {
        monitoring = active
    }

    fun updateStorage(freeBytes: Long) {
        storageFreeBytes = freeBytes
        storageLow = freeBytes in 0..MIN_FREE_BYTES
    }

    /**
     * Validates and applies one decoded engineer command. Returns the ack plus
     * any events the artist emits as a result. Never throws on bad input.
     */
    fun handleCommand(
        envelope: Envelope,
        message: SessionMessage?,
        generation: Long,
    ): AuthorityResult {
        if (generation != helloedGeneration) {
            return reject(envelope, RejectionReason.NOT_AUTHORIZED, "no live Hello for this link")
        }
        if (envelope.sid != sid) {
            return reject(envelope, RejectionReason.NOT_AUTHORIZED, "wrong session")
        }
        if (message == null) {
            return reject(envelope, RejectionReason.UNSUPPORTED, "malformed body")
        }
        if (message is Unknown) {
            return reject(envelope, RejectionReason.UNSUPPORTED, "unknown type ${message.rawType}")
        }
        if (message !is Command) {
            return reject(envelope, RejectionReason.UNSUPPORTED, "not a command")
        }
        // A retry of a StartTake for the take already running is a no-op that
        // must still be acknowledged: the engineer may simply not have seen our
        // StateChanged event yet.
        if (message is StartTake && activeTakeId == message.takeId) {
            return accept(envelope)
        }
        // A finished take id is spent: replaying its StartTake cannot start a
        // second take, and saying so plainly is more useful than WRONG_STATE.
        if (message is StartTake && message.takeId in completedTakeIds) {
            return reject(envelope, RejectionReason.BAD_VALUE, "take already finished")
        }
        if (!isAllowed(message, state)) {
            val reason = if (message is StartTake && !armed) RejectionReason.NOT_ARMED else RejectionReason.WRONG_STATE
            return reject(envelope, reason, "not allowed in $state")
        }

        return when (message) {
            is Arm -> {
                armed = message.armed
                val target = if (armed) SessionState.ARMED else SessionState.IDLE
                transition(target)
                accept(envelope, listOf(StateChanged(target.name, revision)))
            }

            is StartTake -> startTake(envelope, message)

            is StopTake -> {
                if (activeTakeId == null) {
                    reject(envelope, RejectionReason.BAD_VALUE, "no active take")
                } else {
                    transition(SessionState.FINALIZING)
                    accept(envelope, listOf(StateChanged(SessionState.FINALIZING.name, revision)))
                }
            }

            is MarkTake -> accept(envelope)

            is SetCueLevel -> {
                if (message.levelDb < MIN_CUE_DB || message.levelDb > MAX_CUE_DB) {
                    reject(envelope, RejectionReason.BAD_VALUE, "cue level out of range")
                } else {
                    cueLevelDb = message.levelDb
                    accept(envelope)
                }
            }

            is Talkback -> {
                monitoring = message.on
                accept(envelope)
            }

            is Seek, is PlayBacking, is Pause -> accept(envelope)
        }
    }

    /** The artist can always stop the take; this wins over any engineer command. */
    fun artistStop(reason: String = "artist"): List<SessionMessage> {
        if (state != SessionState.RECORDING && state != SessionState.COUNT_IN) {
            return emptyList()
        }
        transition(SessionState.FINALIZING)
        return listOf(StateChanged(SessionState.FINALIZING.name, revision))
    }

    /** The recorder actually started at [actualStartFrame]. */
    fun engineTakeStarted(takeId: String, actualStartFrame: Long): List<SessionMessage> {
        if (activeTakeId != takeId) return emptyList()
        if (state != SessionState.COUNT_IN && state != SessionState.ARMED) return emptyList()
        transition(SessionState.RECORDING)
        return listOf(
            TakeStarted(takeId, actualStartFrame),
            StateChanged(SessionState.RECORDING.name, revision),
        )
    }

    /** The take is closed and its manifest written. */
    fun engineTakeFinalized(frames: Long, reason: String): List<SessionMessage> {
        val takeId = activeTakeId ?: return emptyList()
        activeTakeId = null
        lastTakeId = takeId
        lastTakeFrames = frames
        completedTakeIds += takeId
        transition(SessionState.UPLOADING)
        return listOf(
            TakeStopped(takeId, frames, reason),
            StateChanged(SessionState.UPLOADING.name, revision),
        )
    }

    fun engineTakeVerified(takeId: String): List<SessionMessage> {
        if (takeId == lastTakeId) transition(SessionState.VERIFIED)
        return listOf(StateChanged(state.name, revision))
    }

    fun uploadProgress(takeId: String, state: String, done: Int, total: Int): List<SessionMessage> =
        listOf(UploadProgress(takeId, state, done, total))

    fun warning(code: WarningCode, detail: String = ""): List<SessionMessage> =
        listOf(Warning(code.name, detail))

    /** A take is complete and downloadable; the manifest goes with the event. */
    fun takeAvailable(manifest: TakeManifest): List<SessionMessage> =
        listOf(TakeAvailable(manifest.takeId, manifest))

    fun snapshot(): StateSnapshot = StateSnapshot(
        state = state.name,
        revision = revision,
        armed = armed,
        cueLevelDb = cueLevelDb,
        activeTakeId = activeTakeId,
        monitoring = monitoring,
        storageFreeBytes = storageFreeBytes,
        lastTakeId = lastTakeId,
        lastTakeFrames = lastTakeFrames,
        capabilities = Capabilities.all,
        displayName = displayName(),
    )

    private fun startTake(envelope: Envelope, command: StartTake): AuthorityResult {
        if (storageLow) {
            return reject(envelope, RejectionReason.LOW_STORAGE, "free storage below safety floor")
        }
        if (command.takeId.isBlank()) {
            return reject(envelope, RejectionReason.BAD_VALUE, "blank take id")
        }
        // A retry of the same StartTake must be a no-op, never a second take.
        if (activeTakeId == command.takeId) return accept(envelope)
        if (command.atFrame < currentFrame() + startSafetyFrames) {
            return reject(envelope, RejectionReason.BAD_VALUE, "start frame inside safety margin")
        }

        activeTakeId = command.takeId
        armed = false
        transition(SessionState.COUNT_IN)
        return accept(envelope, listOf(StateChanged(SessionState.COUNT_IN.name, revision)))
    }

    private fun accept(envelope: Envelope, events: List<SessionMessage> = emptyList()): AuthorityResult =
        AuthorityResult(
            ack = Ack(ackSeq = envelope.seq, accepted = true),
            events = events,
        )

    private fun reject(envelope: Envelope, reason: RejectionReason, detail: String): AuthorityResult =
        AuthorityResult(
            ack = Ack(ackSeq = envelope.seq, accepted = false, reason = reason.name, detail = detail),
            events = emptyList(),
        )

    private fun transition(target: SessionState) {
        if (state == target) return
        state = target
        revision++
    }

    private fun isAllowed(command: Command, state: SessionState): Boolean {
        val commonIdle = command is Arm || command is Seek || command is PlayBacking ||
            command is SetCueLevel || command is Talkback || command is Pause
        return when (state) {
            SessionState.LOBBY, SessionState.ENDED -> false
            SessionState.PREFLIGHT -> command is Talkback || command is SetCueLevel
            SessionState.IDLE, SessionState.VERIFIED -> commonIdle
            SessionState.ARMED -> commonIdle || command is StartTake
            SessionState.COUNT_IN -> command is StopTake || command is SetCueLevel || command is Talkback
            SessionState.RECORDING -> command is StopTake || command is MarkTake ||
                command is SetCueLevel || command is Talkback
            SessionState.FINALIZING, SessionState.UPLOADING -> command is MarkTake ||
                command is SetCueLevel || command is Talkback
        }
    }

    companion object {
        /** Two seconds of lead time for a scheduled start. */
        const val DEFAULT_START_SAFETY_FRAMES = 96_000L

        /** Floor below which the artist refuses new takes (~5 minutes of 24-bit mono). */
        const val MIN_FREE_BYTES = 45L * 1024 * 1024

        const val MIN_CUE_DB = -60f
        const val MAX_CUE_DB = 6f
    }
}
