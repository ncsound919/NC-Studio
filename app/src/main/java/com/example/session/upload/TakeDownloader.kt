package com.example.session.upload

import com.example.session.take.TakeManifest
import com.example.session.take.TakeStore
import com.example.session.take.TakeUploadState
import com.example.session.take.UploadStateRecord
import java.io.IOException

/** Result of downloading a take to the engineer's phone. */
sealed interface DownloadOutcome {
    /** Every chunk hash and the whole-take hash matched the manifest. */
    data class Verified(val chunks: Int) : DownloadOutcome

    /** Transient failure; safe to retry later (already-downloaded chunks are kept). */
    data class Retry(val reason: String) : DownloadOutcome

    /** Permanent failure, e.g. the server served bytes that do not match. */
    data class Fatal(val reason: String) : DownloadOutcome
}

/**
 * Engineer-side download and verification.
 *
 * The manifest arrives over the control channel (`TakeAvailable`), so this
 * class never trusts the server's word for correctness: after downloading it
 * re-hashes every chunk and then the whole take, and only then does the take
 * show as verified and become editable.
 */
class TakeDownloader(
    private val api: UploadApi,
    private val store: TakeStore,
) {

    fun download(
        manifest: TakeManifest,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): DownloadOutcome {
        // The manifest is peer-supplied. Validate every ID/index before the
        // first path is built so a hostile TakeAvailable cannot write outside
        // the takes directory or make us allocate an unbounded chunk list.
        val indexes = manifest.chunks.map { it.index }
        if (!TakeStore.isValidId(manifest.takeId) ||
            manifest.chunks.size > MAX_CHUNKS ||
            indexes.any { it < 0 } ||
            indexes.toSet().size != indexes.size
        ) {
            return DownloadOutcome.Fatal("INVALID_MANIFEST")
        }

        val total = manifest.chunks.size
        var done = 0
        try {
            for (chunk in manifest.chunks.sortedBy { it.index }) {
                val target = store.chunkFile(manifest.takeId, chunk.index)
                val alreadyGood = target.exists() &&
                    target.length() == chunk.bytes &&
                    store.hashFile(target) == chunk.sha256
                if (!alreadyGood) {
                    var attempt = 0
                    while (true) {
                        try {
                            api.downloadChunk(manifest.takeId, chunk.index, target)
                            break
                        } catch (e: HttpStatusException) {
                            if (e.code == 404 && attempt < 1) {
                                attempt++
                                continue
                            }
                            throw e
                        }
                    }
                    val good = target.exists() &&
                        target.length() == chunk.bytes &&
                        store.hashFile(target) == chunk.sha256
                    if (!good) {
                        store.writeState(
                            UploadStateRecord(
                                takeId = manifest.takeId,
                                state = TakeUploadState.FAILED,
                                chunksDone = done,
                                chunksTotal = total,
                                lastError = "CHUNK_CORRUPT",
                            ),
                        )
                        return DownloadOutcome.Fatal("CHUNK_CORRUPT")
                    }
                }
                done++
                onProgress?.invoke(done, total)
            }

            val verification = store.verifyAgainstManifest(manifest)
            return if (verification.ok) {
                // Persist the manifest with the verified bytes. Without it the
                // engineer's copy is unrecoverable after a process restart and
                // cannot be imported onto a DAW track later.
                store.writeManifest(manifest)
                store.writeState(
                    UploadStateRecord(
                        takeId = manifest.takeId,
                        state = TakeUploadState.VERIFIED,
                        chunksDone = total,
                        chunksTotal = total,
                    ),
                )
                DownloadOutcome.Verified(total)
            } else {
                store.writeState(
                    UploadStateRecord(
                        takeId = manifest.takeId,
                        state = TakeUploadState.FAILED,
                        chunksDone = done,
                        chunksTotal = total,
                        lastError = "TAKE_HASH_MISMATCH",
                    ),
                )
                DownloadOutcome.Fatal("TAKE_HASH_MISMATCH")
            }
        } catch (e: HttpStatusException) {
            return when {
                e.code == 401 || e.code == 403 -> DownloadOutcome.Fatal("AUTH")
                e.code == 429 || e.code >= 500 -> DownloadOutcome.Retry("HTTP_${e.code}")
                else -> DownloadOutcome.Fatal("HTTP_${e.code}")
            }
        } catch (e: IOException) {
            return DownloadOutcome.Retry(e.message ?: "NETWORK")
        } catch (e: IllegalArgumentException) {
            // Belt and braces: a path builder rejected the peer's ID.
            return DownloadOutcome.Fatal("INVALID_MANIFEST")
        }
    }

    private companion object {
        /** A four-minute take at 512 KiB chunks is well under this. */
        const val MAX_CHUNKS = 10_000
    }
}
