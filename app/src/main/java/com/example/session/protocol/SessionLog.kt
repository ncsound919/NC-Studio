package com.example.session.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Append-only session log, one JSON object per line.
 *
 * Both phones keep one. It is the audit trail for what was said, what was
 * accepted and when state actually changed — useful when an artist and an
 * engineer disagree about what happened during a take.
 */
class SessionLog(
    private val sink: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(LogEntry::class.java)

    @Volatile
    private var enabled = true

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun record(
        direction: Direction,
        type: String,
        seq: Long = -1L,
        state: SessionState? = null,
        note: String = "",
    ) {
        if (!enabled) return
        runCatching {
            sink(
                adapter.toJson(
                    LogEntry(
                        at = clock(),
                        direction = direction.wire,
                        seq = seq,
                        type = type,
                        state = state?.name,
                        note = note,
                    ),
                ),
            )
        }
    }

    fun recordMessage(direction: Direction, message: SessionMessage, seq: Long, state: SessionState? = null) {
        record(direction, message.type, seq = seq, state = state)
    }

    fun recordAck(direction: Direction, ack: Ack, state: SessionState? = null) {
        record(
            direction = direction,
            type = ack.type,
            seq = ack.ackSeq,
            state = state,
            note = if (ack.accepted) "accepted" else "rejected ${ack.reason ?: ""} ${ack.detail ?: ""}".trim(),
        )
    }

    enum class Direction(val wire: String) {
        IN("in"),
        OUT("out"),
        LOCAL("local"),
    }
}

data class LogEntry(
    val at: Long,
    val direction: String,
    val seq: Long,
    val type: String,
    val state: String? = null,
    val note: String = "",
)
