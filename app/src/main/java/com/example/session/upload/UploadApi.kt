package com.example.session.upload

import com.example.session.take.ChunkInfo
import com.example.session.take.TakeManifest
import com.example.session.take.TakeStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Non-2xx response from the session backend. */
class HttpStatusException(
    val code: Int,
    val bodyText: String,
) : IOException("HTTP $code: ${bodyText.take(300)}")

/**
 * Take upload API, matching `upload-api.yaml`.
 *
 * Blocking by design; callers run it on [kotlinx.coroutines.Dispatchers.IO].
 * The server is the source of truth for which chunks it already holds, so a
 * resumed upload only re-sends what is missing.
 */
class UploadApi(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val tokenProvider: () -> String,
    moshi: Moshi? = null,
) {
    private val json = (moshi ?: defaultMoshi()).adapter(TakeManifest::class.java)
    private val statusJson = (moshi ?: defaultMoshi()).adapter(TakeStatus::class.java)
    private val jsonType = "application/json".toMediaType()
    private val binType = "application/octet-stream".toMediaType()

    private fun request(path: String): Request.Builder =
        Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${tokenProvider()}")

    private fun exec(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpStatusException(response.code, body)
            return body
        }
    }

    /**
     * Registers the take. Idempotent on takeId plus identical manifest; the
     * reply says which chunks the server still needs.
     */
    fun register(manifest: TakeManifest): TakeStatus {
        val body = json.toJson(manifest).toRequestBody(jsonType)
        val response = exec(request("/takes").post(body).build())
        return statusJson.fromJson(response) ?: throw IOException("Empty register response")
    }

    fun status(takeId: String): TakeStatus {
        val response = exec(request("/takes/$takeId").get().build())
        return statusJson.fromJson(response) ?: throw IOException("Empty status response")
    }

    fun putChunk(takeId: String, chunk: ChunkInfo, file: File) {
        val request = request("/takes/$takeId/chunks/${chunk.index}")
            .header("X-Chunk-Sha256", chunk.sha256)
            .put(file.asRequestBody(binType))
            .build()
        exec(request)
    }

    fun complete(takeId: String): TakeStatus {
        val response = exec(
            request("/takes/$takeId/complete").post("".toRequestBody(jsonType)).build(),
        )
        return statusJson.fromJson(response) ?: throw IOException("Empty complete response")
    }

    /** Downloads one chunk to [target], replacing any existing file. */
    fun downloadChunk(takeId: String, index: Int, target: File) {
        val request = request("/takes/$takeId/chunks/$index").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, response.body?.string().orEmpty())
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".download")
            response.body?.byteStream()?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Empty chunk body")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not store chunk $index")
        }
    }

    private fun defaultMoshi(): Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
