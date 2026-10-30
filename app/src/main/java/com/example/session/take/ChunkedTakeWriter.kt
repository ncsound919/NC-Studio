package com.example.session.take

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Writes one take as fixed-size chunk files plus a manifest.
 *
 * Pure JVM: no Android APIs, so chunking, hashing, journaling and finalize
 * behavior are all unit-testable. Capture lives in [TakeRecorder].
 *
 * Chunk boundaries are frame-aligned: each chunk holds exactly
 * `chunkTargetBytes / bytesPerFrame` whole frames (except the last one), which
 * keeps manifests reproducible and lets the artist resume an interrupted
 * recording without ever emitting a half frame.
 */
class ChunkedTakeWriter(
    private val store: TakeStore,
    private val config: TakeConfig,
    chunkTargetBytes: Int = DEFAULT_CHUNK_BYTES,
    private val onChunkComplete: ((ChunkInfo) -> Unit)? = null,
) {
    private val bytesPerFrame = PcmCodec.bytesPerSample(config.bitDepth) * config.channels
    private val framesPerChunk = (chunkTargetBytes / bytesPerFrame).coerceAtLeast(1)
    private val scratch = ByteArray(PcmCodec.bytesPerSample(config.bitDepth) * ScratchFrames)

    private var out: BufferedOutputStream? = null
    private var chunkIndex = 0
    private var bytesInChunk = 0L
    private var framesInChunk = 0L

    private val chunks = mutableListOf<ChunkInfo>()
    private var finished = false

    var framesWritten: Long = 0L
        private set

    /** Highest absolute sample seen so far, for the clipping warning. */
    var peakLevel: Float = 0f
        private set

    /** Creates the take folder and its journal. Fails if the take already exists. */
    fun begin(): Boolean {
        if (finished) return false
        return store.startTake(config)
    }

    /**
     * Appends [count] interleaved float samples. Returns the number of samples
     * consumed (always a whole number of frames).
     */
    fun write(samples: FloatArray, count: Int): Int {
        // A failed chunk calls abort() from inside this loop; the caller must
        // see a clean stop rather than a new temp chunk being opened after the
        // take was already abandoned.
        if (finished) return 0
        val frames = count / config.channels
        if (frames <= 0) return 0

        var consumedFrames = 0
        while (consumedFrames < frames) {
            val room = (framesPerChunk - framesInChunk).coerceAtLeast(1).toInt()
            val take = minOf(frames - consumedFrames, room)
            val sampleOffset = consumedFrames * config.channels
            val sampleCount = take * config.channels

            var peak = peakLevel
            for (i in 0 until sampleCount) {
                val value = samples[sampleOffset + i]
                val abs = if (value < 0f) -value else value
                if (abs > peak) peak = abs
            }
            peakLevel = peak

            var written = 0
            while (written < sampleCount) {
                val block = minOf(sampleCount - written, ScratchFrames)
                val bytes = PcmCodec.writeFloatPcm(
                    samples = samples,
                    count = block,
                    bitDepth = config.bitDepth,
                    out = scratch,
                    outOffset = 0,
                )
                stream().write(scratch, 0, bytes)
                written += block
            }

            bytesInChunk += sampleCount.toLong() * PcmCodec.bytesPerSample(config.bitDepth)
            framesInChunk += take.toLong()
            framesWritten += take.toLong()
            consumedFrames += take

            if (framesInChunk >= framesPerChunk) {
                completeChunk()
                if (finished) return consumedFrames * config.channels
            }
        }
        return consumedFrames * config.channels
    }

    /**
     * Finalizes the current chunk, hashes the whole take and writes the
     * manifest. Returns null if nothing was captured (no manifest, no upload).
     */
    fun finish(): TakeManifest? {
        if (finished) return null
        finished = true
        if (framesInChunk > 0) completeChunk()
        closeStream()
        if (chunks.isEmpty()) {
            store.appendAbandoned(config.takeId, "no audio captured")
            store.writeState(
                UploadStateRecord(
                    takeId = config.takeId,
                    state = TakeUploadState.EMPTY,
                    lastError = "No audio captured",
                ),
            )
            return null
        }

        val manifest = TakeManifest(
            takeId = config.takeId,
            sessionId = config.sessionId,
            trackId = config.trackId,
            sampleRate = config.sampleRate,
            bitDepth = config.bitDepth,
            channels = config.channels,
            totalFrames = framesWritten,
            startFrame = config.startFrame,
            calibrationOffsetFrames = config.calibrationOffsetFrames,
            chunks = chunks.toList(),
            takeSha256 = store.hashChunks(config.takeId, chunks),
        )
        store.writeManifest(manifest)
        store.appendFinalized(config.takeId, manifest)
        store.writeState(
            UploadStateRecord(
                takeId = config.takeId,
                state = TakeUploadState.RECORDED,
                chunksDone = 0,
                chunksTotal = manifest.chunks.size,
            ),
        )
        return manifest
    }

    /**
     * Stops without finalizing. Already-promoted chunks stay on disk and the
     * journal records the take as abandoned; recovery can still rebuild a
     * manifest from the promoted chunks if the session decides to keep it.
     */
    fun abort(reason: String) {
        if (finished) return
        finished = true
        closeStream()
        File(store.takeDir(config.takeId), store.chunkName(chunkIndex) + TakeStore.TMP_SUFFIX).delete()
        store.appendAbandoned(config.takeId, reason)
        if (chunks.isEmpty()) {
            store.writeState(
                UploadStateRecord(
                    takeId = config.takeId,
                    state = TakeUploadState.EMPTY,
                    lastError = reason,
                ),
            )
        }
    }

    private fun stream(): BufferedOutputStream {
        check(!finished) { "writer already finished" }
        out?.let { return it }
        val tmp = store.chunkTmpFile(config.takeId, chunkIndex)
        tmp.parentFile?.mkdirs()
        tmp.delete()
        return BufferedOutputStream(FileOutputStream(tmp), 64 * 1024).also { out = it }
    }

    private fun completeChunk() {
        closeStream()
        val info = store.promoteChunk(config.takeId, chunkIndex, framesInChunk)
        if (info == null) {
            // The chunk could not be stored. Stop writing and leave what was
            // already promoted on disk: recovery rebuilds a manifest from the
            // good chunks rather than pretending this take is complete.
            abort("chunk $chunkIndex could not be stored")
            return
        }
        chunks += info
        onChunkComplete?.invoke(info)
        chunkIndex++
        bytesInChunk = 0L
        framesInChunk = 0L
    }

    private fun closeStream() {
        val stream = out ?: return
        out = null
        runCatching {
            stream.flush()
            stream.close()
        }
    }

    /** Chunk files that are safe to upload, in index order. */
    fun chunkFiles(): List<File> = chunks.map { store.chunkFile(config.takeId, it.index) }

    companion object {
        /** ~2 MB per chunk: about 14.5 s of 48 kHz / 24-bit mono audio. */
        const val DEFAULT_CHUNK_BYTES = 2 * 1024 * 1024

        private const val ScratchFrames = 2048
    }
}
