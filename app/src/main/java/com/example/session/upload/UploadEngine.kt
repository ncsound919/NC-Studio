package com.example.session.upload

import com.example.session.take.TakeManifest
import com.example.session.take.TakeStatus
import com.example.session.take.TakeStore
import com.example.session.take.TakeUploadState
import com.example.session.take.UploadStateRecord
import java.io.IOException

/** Result of one attempt to push a take to the server. */
sealed interface UploadOutcome {
    /** The server verified every chunk and the whole-take hash. */
    data class Verified(val chunks: Int) : UploadOutcome

    /** Transient failure: keep the take and try again later. */
    data class Retry(val reason: String) : UploadOutcome

    /** Permanent failure: retrying unchanged input cannot succeed. */
    data class Fatal(val reason: String) : UploadOutcome
}

/**
 * Pushes one finished take, resuming from whatever the server already holds.
 *
 * Rules that matter:
 * - local bytes are re-verified before they leave the phone, so corrupt audio
 *   is never uploaded;
 * - the server's `missingChunks` is authoritative, never a local guess;
 * - a resumed upload re-registers (idempotent) and only sends missing chunks;
 * - nothing here is allowed to touch the audio engine; it reads finished files.
 */
class UploadEngine(
    private val api: UploadApi,
    private val store: TakeStore,
    private val stateWriter: (UploadStateRecord) -> Unit = { store.writeState(it) },
) {

    fun upload(
        manifest: TakeManifest,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): UploadOutcome {
        val total = manifest.chunks.size

        // Never upload bad data.
        val local = store.verifyAgainstManifest(manifest)
        if (!local.ok) {
            markFailed(manifest, "LOCAL_CHUNK_CORRUPT", total)
            return UploadOutcome.Fatal("LOCAL_CHUNK_CORRUPT")
        }

        return try {
            var status = api.register(manifest)
            if (status.state == TakeStatus.STATE_VERIFIED) {
                markVerified(manifest, total)
                return UploadOutcome.Verified(total)
            }

            val missing = status.missingChunks.filter { index ->
                manifest.chunks.any { it.index == index }
            }.toSet()
            var done = total - missing.size
            stateWriter(
                UploadStateRecord(
                    takeId = manifest.takeId,
                    state = TakeUploadState.UPLOADING,
                    chunksDone = done,
                    chunksTotal = total,
                ),
            )
            onProgress?.invoke(done, total)

            for (chunk in manifest.chunks.sortedBy { it.index }) {
                if (chunk.index !in missing) continue
                val file = store.chunkFile(manifest.takeId, chunk.index)
                if (!file.exists() || file.length() != chunk.bytes || store.hashFile(file) != chunk.sha256) {
                    markFailed(manifest, "LOCAL_CHUNK_CORRUPT", total)
                    return UploadOutcome.Fatal("LOCAL_CHUNK_CORRUPT")
                }

                var attempt = 0
                while (true) {
                    try {
                        api.putChunk(manifest.takeId, chunk, file)
                        break
                    } catch (e: HttpStatusException) {
                        // 422 means the server rejected these bytes. Retrying the
                        // same file once covers a truncated in-flight body; beyond
                        // that the local copy and the manifest disagree.
                        if (e.code == 422 && attempt < 1) {
                            attempt++
                            continue
                        }
                        throw e
                    }
                }

                done++
                onProgress?.invoke(done, total)
                stateWriter(
                    UploadStateRecord(
                        takeId = manifest.takeId,
                        state = TakeUploadState.UPLOADING,
                        chunksDone = done,
                        chunksTotal = total,
                    ),
                )
            }

            // All chunks are in; the server now checks sizes, per-chunk hashes
            // and the whole-take hash before it says verified.
            val completed = api.complete(manifest.takeId)
            if (completed.state == TakeStatus.STATE_VERIFIED) {
                markVerified(manifest, total)
                UploadOutcome.Verified(total)
            } else {
                stateWriter(
                    UploadStateRecord(
                        takeId = manifest.takeId,
                        state = TakeUploadState.COMPLETE,
                        chunksDone = total,
                        chunksTotal = total,
                        lastError = "Server has not verified the take yet",
                    ),
                )
                UploadOutcome.Retry("SERVER_NOT_VERIFIED")
            }
        } catch (e: HttpStatusException) {
            when {
                e.code == 401 || e.code == 403 -> {
                    markFailed(manifest, "AUTH", total)
                    UploadOutcome.Fatal("AUTH")
                }

                e.code == 409 -> {
                    markFailed(manifest, "MANIFEST_CONFLICT", total)
                    UploadOutcome.Fatal("MANIFEST_CONFLICT")
                }

                e.code == 429 || e.code >= 500 -> UploadOutcome.Retry("HTTP_${e.code}")

                e.code == 422 -> {
                    markFailed(manifest, "HASH_REJECTED", total)
                    UploadOutcome.Fatal("HASH_REJECTED")
                }

                else -> {
                    markFailed(manifest, "HTTP_${e.code}", total)
                    UploadOutcome.Fatal("HTTP_${e.code}")
                }
            }
        } catch (e: IOException) {
            // Network drop, timeout, connection reset: keep everything and resume.
            stateWriter(
                UploadStateRecord(
                    takeId = manifest.takeId,
                    state = TakeUploadState.UPLOADING,
                    chunksDone = 0,
                    chunksTotal = total,
                    lastError = e.message ?: "network",
                ),
            )
            UploadOutcome.Retry("NETWORK")
        }
    }

    private fun markVerified(manifest: TakeManifest, total: Int) {
        stateWriter(
            UploadStateRecord(
                takeId = manifest.takeId,
                state = TakeUploadState.VERIFIED,
                chunksDone = total,
                chunksTotal = total,
                lastError = null,
            ),
        )
    }

    private fun markFailed(manifest: TakeManifest, reason: String, total: Int) {
        stateWriter(
            UploadStateRecord(
                takeId = manifest.takeId,
                state = TakeUploadState.FAILED,
                chunksDone = 0,
                chunksTotal = total,
                lastError = reason,
            ),
        )
    }
}
