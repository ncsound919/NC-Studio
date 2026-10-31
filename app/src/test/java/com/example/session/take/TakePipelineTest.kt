package com.example.session.take

import com.example.session.upload.DownloadOutcome
import com.example.session.upload.TakeDownloader
import com.example.session.upload.UploadApi
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.sin

class TakePipelineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun samples(count: Int): FloatArray = FloatArray(count) { i ->
        (sin(2.0 * PI * 440.0 * i / 48_000.0) * 0.5).toFloat()
    }

    private fun writeTake(
        store: TakeStore,
        takeId: String = "take-1",
        frames: Int = 350,
        chunkTargetBytes: Int = 300,
    ): TakeManifest? {
        val writer = ChunkedTakeWriter(
            store = store,
            config = TakeConfig(takeId = takeId, sessionId = "session-1", trackId = 7L),
            chunkTargetBytes = chunkTargetBytes,
        )
        assertTrue(writer.begin())
        val input = samples(frames)
        var written = 0
        while (written < frames) {
            val block = minOf(37, frames - written)
            written += writer.write(input.copyOfRange(written, written + block), block)
        }
        return writer.finish()
    }

    @Test
    fun pcm24RoundTripsWithinQuantizationError() {
        val input = floatArrayOf(0f, 0.5f, -0.5f, 0.999f, -0.999f, 0.1234f)
        val bytes = ByteArray(input.size * 3)
        PcmCodec.writeFloatPcm(input, input.size, 24, bytes)
        val output = PcmCodec.readFloatPcm(bytes, 0, input.size, 24)
        for (i in input.indices) {
            assertEquals(input[i].toDouble(), output[i].toDouble(), 2.0 / 8_388_608.0)
        }
    }

    @Test
    fun writerProducesFrameAlignedChunksAndManifest() {
        val store = TakeStore(temp.newFolder("session"))
        val manifest = writeTake(store, frames = 350, chunkTargetBytes = 300)!!

        assertEquals(350L, manifest.totalFrames)
        assertEquals(4, manifest.chunks.size) // 100 + 100 + 100 + 50 frames
        assertEquals(listOf(300L, 300L, 300L, 150L), manifest.chunks.map { it.bytes })
        assertEquals(manifest.chunks.map { it.index }, manifest.chunks.indices.toList())
        assertEquals(24, manifest.bitDepth)
        assertEquals(1, manifest.channels)
        assertTrue(store.manifestFile("take-1").exists())
        assertTrue(store.readState("take-1").state == TakeUploadState.RECORDED)

        val verification = store.verifyAgainstManifest(manifest)
        assertTrue(verification.details, verification.ok)
    }

    @Test
    fun wholeTakeHashIsStableAcrossRuns() {
        val first = writeTake(TakeStore(temp.newFolder("a")))
        val second = writeTake(TakeStore(temp.newFolder("b")))
        assertEquals(first!!.takeSha256, second!!.takeSha256)
    }

    @Test
    fun tamperedChunkFailsVerification() {
        val store = TakeStore(temp.newFolder("session"))
        val manifest = writeTake(store)!!
        val chunk = store.chunkFile(manifest.takeId, 1)
        val bytes = chunk.readBytes()
        bytes[10] = (bytes[10].toInt() xor 0x01).toByte()
        chunk.writeBytes(bytes)

        val verification = store.verifyAgainstManifest(manifest)
        assertFalse(verification.ok)
        assertTrue(verification.chunkProblems.any { it.contains("chunk 1") })
    }

    @Test
    fun crashRecoveryRebuildsManifestFromCompletedChunksOnly() {
        val store = TakeStore(temp.newFolder("session"))
        // 350 frames with 100-frame chunks: three chunks complete, 50 frames remain
        // in a temp file when the process dies.
        val clean = writeTake(store, takeId = "crashed", frames = 350, chunkTargetBytes = 300)
        assertEquals(4, clean!!.chunks.size)

        // Simulate the crash: remove the manifest as if finalize never ran, and
        // leave a half-written chunk tmp file behind.
        store.manifestFile("crashed").delete()
        store.chunkFile("crashed", 3).delete()
        store.chunkTmpFile("crashed", 3).writeBytes(ByteArray(42))
        val expectedHash = store.hashChunks("crashed", clean.chunks.take(3))

        val recovered = store.recoverAll().single()
        assertEquals("crashed", recovered.takeId)
        assertEquals(3, recovered.chunksRecovered)
        assertNotNull(recovered.manifest)
        assertEquals(300L, recovered.manifest!!.totalFrames)
        assertEquals(3, recovered.manifest!!.chunks.size)
        assertEquals(expectedHash, recovered.manifest!!.takeSha256)
        assertFalse(store.chunkTmpFile("crashed", 3).exists())
        assertTrue(store.verifyAgainstManifest(recovered.manifest!!).ok)
        assertTrue(store.readState("crashed").state == TakeUploadState.RECORDED)
    }

    @Test
    fun abortedTakeIsNotResurrectedAsRecordedByRecovery() {
        val store = TakeStore(temp.newFolder("session"))
        val writer = ChunkedTakeWriter(
            store = store,
            config = TakeConfig(takeId = "aborted", sessionId = "session-1", trackId = 7L),
            chunkTargetBytes = 300,
        )
        assertTrue(writer.begin())
        val input = samples(350)
        var written = 0
        while (written < 350) {
            val block = minOf(37, 350 - written)
            written += writer.write(input.copyOfRange(written, written + block), block)
        }
        // At least one chunk is already promoted to disk when the artist aborts.
        writer.abort("artist pressed stop")
        assertNull(store.manifestFile("aborted").takeIf { it.exists() })

        val recovered = store.recoverAll()
        assertTrue("aborted take must not be recovered", recovered.isEmpty())
        assertNull(store.readManifest("aborted"))
        assertEquals(TakeUploadState.EMPTY, store.readState("aborted").state)
    }

    @Test
    fun recoveryDropsChunksWhoseBytesNoLongerMatch() {
        val store = TakeStore(temp.newFolder("session"))
        writeTake(store, takeId = "crashed", frames = 350)
        store.manifestFile("crashed").delete()

        val chunkTwo = store.chunkFile("crashed", 2)
        chunkTwo.writeBytes(ByteArray(300)) // all zeros: hash mismatch

        val recovered = store.recoverAll().single()
        assertEquals(2, recovered.chunksRecovered)
        assertEquals(2, recovered.chunksDropped)
        assertEquals(2, recovered.manifest!!.chunks.size)
        assertFalse("chunks after the first gap must be removed", chunkTwo.exists())
        assertTrue(store.verifyAgainstManifest(recovered.manifest!!).ok)
    }

    @Test
    fun emptyCrashLeftoverIsMarkedEmptyAndNeverDeleted() {
        val store = TakeStore(temp.newFolder("session"))
        store.startTake(TakeConfig(takeId = "empty", sessionId = "s", trackId = 1L))
        val recovered = store.recoverAll().single()
        assertNull(recovered.manifest)
        assertEquals(0, recovered.chunksRecovered)
        assertEquals(TakeUploadState.EMPTY, store.readState("empty").state)
        assertTrue(store.takeDir("empty").exists())
    }

    @Test
    fun malformedTrailingJournalLineDoesNotBreakRecovery() {
        val store = TakeStore(temp.newFolder("session"))
        writeTake(store, takeId = "crashed", frames = 120)
        store.manifestFile("crashed").delete()
        store.journalFile("crashed").appendText("{\"type\":\"chunk\",\"index\":9")

        val recovered = store.recoverAll().single()
        assertEquals(2, recovered.chunksRecovered)
        assertNotNull(recovered.manifest)
    }

    @Test
    fun abortingAnEmptyTakeWritesNoManifest() {
        val store = TakeStore(temp.newFolder("session"))
        val writer = ChunkedTakeWriter(
            store,
            TakeConfig(takeId = "take-empty", sessionId = "s", trackId = 1),
            chunkTargetBytes = 300,
        )
        writer.begin()
        writer.abort("engineer cancelled")
        assertNull(store.readManifest("take-empty"))
        assertEquals(TakeUploadState.EMPTY, store.readState("take-empty").state)
    }

    @Test
    fun takeStoreRejectsIdsThatCouldEscapeTheTakesDirectory() {
        val store = TakeStore(temp.newFolder("session"))
        assertFalse(TakeStore.isValidId("../escape"))
        assertFalse(TakeStore.isValidId("take/../escape"))
        assertFalse(TakeStore.isValidId(".."))
        assertTrue(TakeStore.isValidId("take-1"))
        assertThrows(IllegalArgumentException::class.java) { store.takeDir("../escape") }
        assertThrows(IllegalArgumentException::class.java) { store.chunkFile("../../escape", 0) }
    }

    @Test
    fun downloaderRejectsAHostileManifestBeforeItTouchesDiskOrNetwork() {
        val store = TakeStore(temp.newFolder("session"))
        val manifest = TakeManifest(
            takeId = "../escape",
            sessionId = "s",
            trackId = 1L,
            sampleRate = 48_000,
            bitDepth = 24,
            channels = 1,
            totalFrames = 10L,
            startFrame = 0L,
            calibrationOffsetFrames = 0,
            chunks = listOf(ChunkInfo(index = 0, bytes = 10L, sha256 = "00")),
            takeSha256 = "00",
        )
        val downloader = TakeDownloader(
            api = UploadApi("http://127.0.0.1:1", OkHttpClient(), { "" }),
            store = store,
        )

        val outcome = downloader.download(manifest)
        assertTrue(outcome is DownloadOutcome.Fatal)
        assertEquals("INVALID_MANIFEST", (outcome as DownloadOutcome.Fatal).reason)
        assertFalse(java.io.File(temp.root, "escape").exists())
        assertFalse(store.takesDir.exists())
    }
}
