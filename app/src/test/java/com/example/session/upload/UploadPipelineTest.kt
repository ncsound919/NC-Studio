package com.example.session.upload

import com.example.session.take.ChunkedTakeWriter
import com.example.session.take.TakeConfig
import com.example.session.take.TakeManifest
import com.example.session.take.TakeStore
import com.example.session.take.TakeUploadState
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class UploadPipelineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: TakeStore
    private lateinit var manifest: TakeManifest

    private fun jsonStatus(state: String, missing: List<Int>): MockResponse {
        val missingJson = missing.joinToString(",")
        return MockResponse()
            .setResponseCode(if (state == "fresh") 201 else 200)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"takeId":"take-1","state":"$state","missingChunks":[$missingJson]}""")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = TakeStore(temp.newFolder("session"))
        val writer = ChunkedTakeWriter(
            store,
            TakeConfig(takeId = "take-1", sessionId = "session-1", trackId = 3),
            chunkTargetBytes = 300,
        )
        assertTrue(writer.begin())
        val frames = 250 // 3 chunks: 100 + 100 + 50
        val samples = FloatArray(frames) { (it % 50) / 50f - 0.5f }
        writer.write(samples, frames)
        manifest = writer.finish()!!
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = UploadApi(
        baseUrl = server.url("/v1").toString(),
        http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
        tokenProvider = { "test-token" },
    )

    @Test
    fun happyPathUploadsMissingChunksAndCompletes() {
        server.enqueue(jsonStatus("registered", listOf(0, 1, 2)))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        server.enqueue(jsonStatus("verified", emptyList()))

        val outcome = UploadEngine(api(), store).upload(manifest)

        assertTrue(outcome is UploadOutcome.Verified)
        assertEquals(5, server.requestCount) // register + 3 chunks + complete
        val first = server.takeRequest()
        assertEquals("Bearer test-token", first.getHeader("Authorization"))
        assertEquals("/v1/takes", first.path)
        for (index in 0..2) {
            val chunkRequest = server.takeRequest()
            assertEquals("/v1/takes/take-1/chunks/$index", chunkRequest.path)
            assertEquals(manifest.chunks[index].sha256, chunkRequest.getHeader("X-Chunk-Sha256"))
            assertEquals(manifest.chunks[index].bytes, chunkRequest.bodySize)
        }
        assertEquals("/v1/takes/take-1/complete", server.takeRequest().path)
        assertEquals(TakeUploadState.VERIFIED, store.readState("take-1").state)
    }

    @Test
    fun resumeOnlySendsChunksTheServerIsMissing() {
        server.enqueue(jsonStatus("uploading", listOf(1)))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonStatus("verified", emptyList()))

        val outcome = UploadEngine(api(), store).upload(manifest)

        assertTrue(outcome is UploadOutcome.Verified)
        server.takeRequest() // register
        assertEquals("/v1/takes/take-1/chunks/1", server.takeRequest().path)
        assertEquals("/v1/takes/take-1/complete", server.takeRequest().path)
    }

    @Test
    fun corruptLocalChunkIsNeverUploaded() {
        val chunk = store.chunkFile("take-1", 0)
        val bytes = chunk.readBytes()
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        chunk.writeBytes(bytes)

        val outcome = UploadEngine(api(), store).upload(manifest)

        assertTrue(outcome is UploadOutcome.Fatal)
        assertEquals("LOCAL_CHUNK_CORRUPT", (outcome as UploadOutcome.Fatal).reason)
        assertEquals(0, server.requestCount)
        assertEquals(TakeUploadState.FAILED, store.readState("take-1").state)
    }

    @Test
    fun unauthorizedIsPermanent() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val outcome = UploadEngine(api(), store).upload(manifest)
        assertEquals("AUTH", (outcome as UploadOutcome.Fatal).reason)
    }

    @Test
    fun manifestConflictIsPermanent() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
        val outcome = UploadEngine(api(), store).upload(manifest)
        assertEquals("MANIFEST_CONFLICT", (outcome as UploadOutcome.Fatal).reason)
    }

    @Test
    fun serverErrorIsRetried() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val outcome = UploadEngine(api(), store).upload(manifest)
        assertTrue(outcome is UploadOutcome.Retry)
    }

    @Test
    fun chunkRejectedTwiceIsPermanent() {
        server.enqueue(jsonStatus("registered", listOf(0)))
        server.enqueue(MockResponse().setResponseCode(422).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(422).setBody("{}"))

        val outcome = UploadEngine(api(), store).upload(manifest)

        assertEquals("HASH_REJECTED", (outcome as UploadOutcome.Fatal).reason)
    }

    @Test
    fun completeWithoutVerificationIsRetried() {
        server.enqueue(jsonStatus("registered", emptyList()))
        server.enqueue(jsonStatus("uploading", emptyList()))

        val outcome = UploadEngine(api(), store).upload(manifest)

        assertTrue(outcome is UploadOutcome.Retry)
        assertEquals("SERVER_NOT_VERIFIED", (outcome as UploadOutcome.Retry).reason)
    }

    @Test
    fun downloaderVerifiesEveryChunkAndTheWholeTake() {
        for (chunk in manifest.chunks) {
            val bytes = store.chunkFile("take-1", chunk.index).readBytes()
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
        }

        // Fresh store to mimic the engineer's phone.
        val engineerStore = TakeStore(temp.newFolder("engineer"))
        val outcome = TakeDownloader(api(), engineerStore).download(manifest)

        assertTrue(outcome is DownloadOutcome.Verified)
        assertEquals(3, (outcome as DownloadOutcome.Verified).chunks)
        assertTrue(engineerStore.verifyAgainstManifest(manifest).ok)
        assertEquals(TakeUploadState.VERIFIED, engineerStore.readState("take-1").state)
    }

    @Test
    fun downloaderRejectsCorruptedServerBytes() {
        val first = manifest.chunks.first()
        val good = store.chunkFile("take-1", first.index).readBytes()
        val tampered = good.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(tampered)))

        val engineerStore = TakeStore(temp.newFolder("engineer"))
        val outcome = TakeDownloader(api(), engineerStore).download(manifest)

        assertEquals("CHUNK_CORRUPT", (outcome as DownloadOutcome.Fatal).reason)
        assertNull(engineerStore.readManifest("take-1"))
    }
}
