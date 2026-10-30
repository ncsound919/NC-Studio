package com.example.session.take

/**
 * Core take models for Mode 2 remote sessions.
 *
 * The manifest layout follows `upload-api.yaml`: the artist's phone is the
 * recorder of record and publishes exactly these fields, nothing more, so a
 * strict server can validate it without app-specific extensions. Anything the
 * app needs beyond the wire contract (recovery notes, upload state, ...) is
 * persisted alongside instead of inside the manifest.
 */

/** One fixed-size piece of a finished take, addressed by index and hash. */
data class ChunkInfo(
    val index: Int,
    val bytes: Long,
    val sha256: String,
)

/**
 * Immutable description of a finalized take. Written once, at finalize time.
 *
 * [calibrationOffsetFrames] is the loopback offset measured for the capture
 * route that was active while recording, so the engineer can line the take up
 * against the backing track sample-exactly.
 */
data class TakeManifest(
    val takeId: String,
    val sessionId: String,
    val trackId: Long,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val totalFrames: Long,
    val startFrame: Long,
    val calibrationOffsetFrames: Int,
    val chunks: List<ChunkInfo>,
    val takeSha256: String,
)

/** Server-side upload status for a take. */
data class TakeStatus(
    val takeId: String,
    val state: String,
    val missingChunks: List<Int> = emptyList(),
) {
    companion object {
        const val STATE_REGISTERED = "registered"
        const val STATE_UPLOADING = "uploading"
        const val STATE_VERIFIED = "verified"
        const val STATE_FAILED = "failed"
    }
}

/** Everything needed to start recording one take. */
data class TakeConfig(
    val takeId: String,
    val sessionId: String,
    val trackId: Long,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val bitDepth: Int = DEFAULT_BIT_DEPTH,
    val channels: Int = DEFAULT_CHANNELS,
    val startFrame: Long = 0L,
    val calibrationOffsetFrames: Int = 0,
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_BIT_DEPTH = 24
        const val DEFAULT_CHANNELS = 1
    }
}

/**
 * Local lifecycle of a take. The wire [TakeStatus] states are a subset; the
 * extra local states cover the window before a take is registered and the
 * crash-recovery cases.
 */
enum class TakeUploadState {
    /** Finished on this device, not yet registered with the server. */
    RECORDED,

    /** Server knows the manifest; chunks still missing. */
    REGISTERED,

    /** Chunks are in flight. */
    UPLOADING,

    /** All chunks stored; waiting for the server's whole-take verification. */
    COMPLETE,

    /** Server verified the whole take. Safe to delete locally. */
    VERIFIED,

    /** Permanent failure: auth, manifest conflict, or local corruption. */
    FAILED,

    /** A crash left the take folder without a manifest and no usable chunks. */
    EMPTY,
    ;

    val isFinished: Boolean
        get() = this == RECORDED || this == COMPLETE || this == VERIFIED || this == FAILED
}

/** Result of validating a take folder against its manifest. */
data class TakeVerification(
    val ok: Boolean,
    val chunkProblems: List<String> = emptyList(),
    val takeHashMatches: Boolean = false,
    val details: String = "",
)

/** Outcome of updating a take's upload state in the local store. */
data class UploadStateRecord(
    val takeId: String,
    val state: TakeUploadState,
    val chunksDone: Int = 0,
    val chunksTotal: Int = 0,
    val lastError: String? = null,
    val updatedAtEpochMs: Long = 0L,
)
