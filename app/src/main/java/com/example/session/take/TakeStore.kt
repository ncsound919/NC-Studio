package com.example.session.take

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Crash-safe on-disk store for takes.
 *
 * Layout, per take:
 * ```
 * takes/<takeId>/
 *   journal.jsonl        append-only: take_started, chunk, finalized, abandoned
 *   chunk-0000.part      finalized (hashed) chunks, in index order
 *   chunk-0000.part.tmp  chunk still being written
 *   manifest.json        written once, at finalize
 *   upload-state.json    local upload progress (never part of the wire manifest)
 *   recovered.json       present when the take was rebuilt after a crash
 * ```
 *
 * A chunk is only journaled after it is fully written, flushed and renamed, so
 * a crash can never reference a partial chunk. [recoverAll] therefore rebuilds
 * a manifest that is byte-identical to the one a clean finalize would have
 * produced, using only hashes that are re-verified from disk.
 */
class TakeStore(
    val rootDir: File,
    moshi: Moshi? = null,
) {
    private val json = (moshi ?: defaultMoshi()).adapter(JournalLine::class.java)
    private val manifestJson = (moshi ?: defaultMoshi()).adapter(TakeManifest::class.java)
    private val stateJson = (moshi ?: defaultMoshi()).adapter(UploadStateRecord::class.java)

    val takesDir: File get() = File(rootDir, "takes")
    private val logsDir: File get() = File(rootDir, "session-logs")

    /**
     * Every path below is derived from an ID that can arrive over the control
     * channel (`TakeAvailable`), so [takeDir] is the single choke point that
     * keeps a peer from escaping the takes directory with `..` or a path
     * separator. Callers that cannot guarantee a valid ID must check
     * [isValidId] first and fail closed.
     */
    fun takeDir(takeId: String): File {
        require(isValidId(takeId)) { "Invalid take id" }
        return File(takesDir, takeId)
    }

    fun journalFile(takeId: String): File = File(takeDir(takeId), JOURNAL_NAME)
    fun manifestFile(takeId: String): File = File(takeDir(takeId), MANIFEST_NAME)
    fun stateFile(takeId: String): File = File(takeDir(takeId), STATE_NAME)
    fun chunkTmpFile(takeId: String, index: Int): File = File(takeDir(takeId), chunkName(index) + TMP_SUFFIX)
    fun chunkFile(takeId: String, index: Int): File = File(takeDir(takeId), chunkName(index))

    fun chunkName(index: Int): String = "chunk-%04d.part".format(index)

    fun sessionLogFile(sessionId: String): File =
        File(logsDir, "session-${sessionId.replace(SAFE_LOG_CHARS, "_").take(80)}.jsonl")

    fun appendSessionLog(sessionId: String, line: String) {
        val file = sessionLogFile(sessionId)
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    // ---------------------------------------------------------------- takes

    /** Creates the take folder and writes the `take_started` journal line. */
    fun startTake(config: TakeConfig): Boolean {
        val dir = takeDir(config.takeId)
        if (dir.exists() && journalFile(config.takeId).exists()) return false
        if (!dir.mkdirs() && !dir.isDirectory) return false
        appendJournal(
            config.takeId,
            JournalLine(
                type = TYPE_TAKE_STARTED,
                at = System.currentTimeMillis(),
                sessionId = config.sessionId,
                trackId = config.trackId,
                sampleRate = config.sampleRate,
                bitDepth = config.bitDepth,
                channels = config.channels,
                startFrame = config.startFrame,
                calibrationOffsetFrames = config.calibrationOffsetFrames,
            ),
        )
        return true
    }

    /**
     * Promotes a fully written temp chunk to its final name, hashes the stored
     * bytes and journals the result. Returns null if the rename failed.
     */
    fun promoteChunk(takeId: String, index: Int, frames: Long): ChunkInfo? {
        val tmp = chunkTmpFile(takeId, index)
        if (!tmp.exists()) return null
        val target = chunkFile(takeId, index)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) return null
        val sha = hashFile(target)
        appendJournal(
            takeId,
            JournalLine(
                type = TYPE_CHUNK,
                at = System.currentTimeMillis(),
                index = index,
                bytes = target.length(),
                sha256 = sha,
                frames = frames,
            ),
        )
        return ChunkInfo(index = index, bytes = target.length(), sha256 = sha)
    }

    fun appendFinalized(takeId: String, manifest: TakeManifest) {
        appendJournal(
            takeId,
            JournalLine(
                type = TYPE_FINALIZED,
                at = System.currentTimeMillis(),
                totalFrames = manifest.totalFrames,
                takeSha256 = manifest.takeSha256,
            ),
        )
    }

    fun appendAbandoned(takeId: String, reason: String) {
        appendJournal(takeId, JournalLine(type = TYPE_ABANDONED, at = System.currentTimeMillis(), reason = reason))
    }

    fun appendJournal(takeId: String, line: JournalLine) {
        val file = journalFile(takeId)
        file.parentFile?.mkdirs()
        val text = json.toJson(line) + "\n"
        FileOutputStream(file, true).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    fun readJournal(takeId: String): List<JournalLine> {
        val file = journalFile(takeId)
        if (!file.exists()) return emptyList()
        return readJournalFile(file)
    }

    fun writeManifest(manifest: TakeManifest) {
        writeAtomically(manifestFile(manifest.takeId), manifestJson.toJson(manifest))
    }

    fun readManifest(takeId: String): TakeManifest? {
        val file = manifestFile(takeId)
        if (!file.exists()) return null
        return runCatching { manifestJson.fromJson(file.readText()) }.getOrNull()
    }

    fun readState(takeId: String): UploadStateRecord {
        val file = stateFile(takeId)
        if (!file.exists()) return UploadStateRecord(takeId = takeId, state = TakeUploadState.RECORDED)
        return runCatching { stateJson.fromJson(file.readText()) }.getOrNull()
            ?: UploadStateRecord(takeId = takeId, state = TakeUploadState.RECORDED)
    }

    fun writeState(record: UploadStateRecord) {
        val stamped = if (record.updatedAtEpochMs == 0L) record.copy(updatedAtEpochMs = System.currentTimeMillis()) else record
        val takeId = stamped.takeId
        takeDir(takeId).mkdirs()
        writeAtomically(stateFile(takeId), stateJson.toJson(stamped))
    }

    fun listTakeIds(): List<String> {
        val dirs = takesDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.map { it.name }.filter { isValidId(it) }.sorted()
    }

    fun hasRecoveredMarker(takeId: String): Boolean = File(takeDir(takeId), RECOVERED_NAME).exists()

    // ------------------------------------------------------------ recovery

    /**
     * Rebuilds manifests for takes that were interrupted by an app kill or a
     * reboot: a folder with a journal but no manifest.
     *
     * Only chunks that still match their journaled size and hash survive; the
     * first mismatch (and anything after it) is dropped, keeping the chunk
     * index sequence contiguous, and the discarded files are deleted. A folder
     * where nothing survived is marked [TakeUploadState.EMPTY] and left in
     * place — this app never deletes an unverified take on its own.
     */
    fun recoverAll(): List<TakeRecovery> {
        val results = mutableListOf<TakeRecovery>()
        for (takeId in listTakeIds()) {
            val dir = takeDir(takeId)
            if (!dir.isDirectory) continue
            if (manifestFile(takeId).exists()) continue
            val journal = readJournal(takeId)
            if (journal.none { it.type == TYPE_TAKE_STARTED }) continue
            results += recoverTake(takeId, journal)
        }
        return results
    }

    private fun recoverTake(takeId: String, journal: List<JournalLine>): TakeRecovery {
        val started = journal.first { it.type == TYPE_TAKE_STARTED }
        val chunkLines = journal.filter { it.type == TYPE_CHUNK }.sortedBy { it.index ?: -1 }

        val keptChunks = mutableListOf<ChunkInfo>()
        var frames = 0L
        var dropped = 0
        var sawGap = false

        for (line in chunkLines) {
            val index = line.index ?: continue
            val expectedBytes = line.bytes ?: 0L
            val expectedHash = line.sha256 ?: ""
            val file = chunkFile(takeId, index)
            val valid = !sawGap &&
                index == keptChunks.size &&
                file.exists() &&
                file.length() == expectedBytes &&
                hashFile(file) == expectedHash
            if (valid) {
                keptChunks += ChunkInfo(index = index, bytes = expectedBytes, sha256 = expectedHash)
                frames += line.frames ?: 0L
            } else {
                sawGap = true
                dropped++
            }
        }

        // Remove temp files and any chunk files the journal no longer vouches for.
        (takeDir(takeId).listFiles() ?: emptyArray()).forEach { file ->
            val name = file.name
            if (name.endsWith(TMP_SUFFIX)) {
                file.delete()
            } else if (name.startsWith(CHUNK_PREFIX)) {
                val index = name.removePrefix(CHUNK_PREFIX).removeSuffix(".part").toIntOrNull()
                if (index == null || index >= keptChunks.size) file.delete()
            }
        }

        if (keptChunks.isEmpty()) {
            writeAtomically(
                File(takeDir(takeId), RECOVERED_NAME),
                """{"recoveredAt":${System.currentTimeMillis()},"chunksRecovered":0,"chunksDropped":$dropped}""",
            )
            writeState(
                UploadStateRecord(
                    takeId = takeId,
                    state = TakeUploadState.EMPTY,
                    lastError = "Crash recovery found no complete chunks",
                ),
            )
            return TakeRecovery(takeId, manifest = null, chunksRecovered = 0, chunksDropped = dropped, note = "empty")
        }

        val manifest = TakeManifest(
            takeId = takeId,
            sessionId = started.sessionId ?: "",
            trackId = started.trackId ?: -1L,
            sampleRate = started.sampleRate ?: TakeConfig.DEFAULT_SAMPLE_RATE,
            bitDepth = started.bitDepth ?: TakeConfig.DEFAULT_BIT_DEPTH,
            channels = started.channels ?: TakeConfig.DEFAULT_CHANNELS,
            totalFrames = frames,
            startFrame = started.startFrame ?: 0L,
            calibrationOffsetFrames = started.calibrationOffsetFrames ?: 0,
            chunks = keptChunks,
            takeSha256 = hashChunks(takeId, keptChunks),
        )
        writeManifest(manifest)
        appendFinalized(takeId, manifest)
        writeAtomically(
            File(takeDir(takeId), RECOVERED_NAME),
            """{"recoveredAt":${System.currentTimeMillis()},"chunksRecovered":${keptChunks.size},"chunksDropped":$dropped}""",
        )
        writeState(
            UploadStateRecord(
                takeId = takeId,
                state = TakeUploadState.RECORDED,
                chunksDone = 0,
                chunksTotal = keptChunks.size,
                lastError = "Recovered after interruption",
            ),
        )
        return TakeRecovery(takeId, manifest, keptChunks.size, dropped, "recovered")
    }

    // --------------------------------------------------------- verification

    /**
     * Re-checks a take folder against its manifest: size and hash per chunk,
     * then the whole-take hash over the concatenated chunks in index order.
     */
    fun verifyAgainstManifest(manifest: TakeManifest): TakeVerification {
        val problems = mutableListOf<String>()
        for (chunk in manifest.chunks) {
            val file = chunkFile(manifest.takeId, chunk.index)
            if (!file.exists()) {
                problems += "chunk ${chunk.index}: missing"
                continue
            }
            if (file.length() != chunk.bytes) {
                problems += "chunk ${chunk.index}: size ${file.length()} != ${chunk.bytes}"
                continue
            }
            if (hashFile(file) != chunk.sha256) {
                problems += "chunk ${chunk.index}: sha256 mismatch"
            }
        }
        if (problems.isNotEmpty()) {
            return TakeVerification(ok = false, chunkProblems = problems, takeHashMatches = false)
        }
        val computed = hashChunks(manifest.takeId, manifest.chunks)
        val matches = computed == manifest.takeSha256
        return TakeVerification(
            ok = matches,
            takeHashMatches = matches,
            details = if (matches) "verified ${manifest.chunks.size} chunks" else "whole-take hash mismatch",
        )
    }

    // ------------------------------------------------------------- helpers

    /** SHA-256 of the concatenated chunk bytes, in index order. */
    fun hashChunks(takeId: String, chunks: List<ChunkInfo>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        for (chunk in chunks.sortedBy { it.index }) {
            chunkFile(takeId, chunk.index).inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().toHex()
    }

    fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun readJournalFile(file: File): List<JournalLine> {
        val lines = mutableListOf<JournalLine>()
        file.useLines { sequence ->
            sequence.forEach { raw ->
                val text = raw.trim()
                if (text.isEmpty()) return@forEach
                // A crash can leave a partial trailing line; tolerate it.
                runCatching { json.fromJson(text) }.getOrNull()?.let { lines += it }
            }
        }
        return lines
    }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun defaultMoshi(): Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    companion object {
        const val JOURNAL_NAME = "journal.jsonl"
        const val MANIFEST_NAME = "manifest.json"
        const val STATE_NAME = "upload-state.json"
        const val RECOVERED_NAME = "recovered.json"
        const val TMP_SUFFIX = ".tmp"
        const val CHUNK_PREFIX = "chunk-"

        /** Conservative on purpose: IDs are folder names, never paths. */
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        private val SAFE_LOG_CHARS = Regex("[^A-Za-z0-9._-]")

        fun isValidId(id: String): Boolean =
            id.isNotEmpty() &&
                id != "." &&
                id != ".." &&
                !id.contains('/') &&
                !id.contains('\\') &&
                !id.contains("..") &&
                SAFE_ID.matches(id)

        const val TYPE_TAKE_STARTED = "take_started"
        const val TYPE_CHUNK = "chunk"
        const val TYPE_FINALIZED = "finalized"
        const val TYPE_ABANDONED = "abandoned"
    }
}

/** One append-only journal line. Unused fields stay null so lines stay compact. */
data class JournalLine(
    val type: String,
    val at: Long = 0L,
    val index: Int? = null,
    val bytes: Long? = null,
    val sha256: String? = null,
    val frames: Long? = null,
    val totalFrames: Long? = null,
    val takeSha256: String? = null,
    val reason: String? = null,
    // take_started fields
    val sessionId: String? = null,
    val trackId: Long? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val startFrame: Long? = null,
    val calibrationOffsetFrames: Int? = null,
)

/** Summary of one crash-recovery pass over a take folder. */
data class TakeRecovery(
    val takeId: String,
    val manifest: TakeManifest?,
    val chunksRecovered: Int,
    val chunksDropped: Int,
    val note: String,
)

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
