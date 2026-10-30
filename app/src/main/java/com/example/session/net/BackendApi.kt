package com.example.session.net

import com.example.session.upload.HttpStatusException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class CreateSessionRequest(
    val role: String,
    val displayName: String? = null,
)

data class JoinSessionRequest(
    val inviteCode: String,
    val displayName: String? = null,
)

/** Credentials and identity handed out by the backend for one session. */
data class SessionCredentials(
    val sessionId: String,
    val token: String,
    val role: String,
    val inviteCode: String? = null,
    val peerName: String? = null,
)

data class SessionInfo(
    val sessionId: String,
    val inviteCode: String? = null,
    val artistName: String? = null,
    val engineerName: String? = null,
    val createdAtEpochMs: Long = 0L,
)

/** Result of a short upload probe during preflight. */
data class ProbeResult(
    val uploadKbps: Int,
    val bytes: Int,
)

/**
 * Session REST API: the engineer creates a session and shares an expiring
 * invite code; the artist joins with it. Tokens are short-lived and scoped to
 * the session.
 */
class BackendApi(
    private val baseUrl: String,
    private val http: OkHttpClient,
    moshi: Moshi? = null,
) {
    private val json = (moshi ?: defaultMoshi())
    private val credentialsAdapter = json.adapter(SessionCredentials::class.java)
    private val infoAdapter = json.adapter(SessionInfo::class.java)
    private val createAdapter = json.adapter(CreateSessionRequest::class.java)
    private val joinAdapter = json.adapter(JoinSessionRequest::class.java)
    private val jsonType = "application/json".toMediaType()
    private val binType = "application/octet-stream".toMediaType()

    fun createSession(role: String, displayName: String?): SessionCredentials {
        val body = createAdapter.toJson(CreateSessionRequest(role, displayName)).toRequestBody(jsonType)
        val text = exec(Request.Builder().url(path("/sessions")).post(body).build())
        return credentialsAdapter.fromJson(text) ?: throw IOException("empty create response")
    }

    fun joinSession(inviteCode: String, displayName: String?): SessionCredentials {
        val body = joinAdapter.toJson(JoinSessionRequest(inviteCode.trim().uppercase(), displayName))
            .toRequestBody(jsonType)
        val text = exec(Request.Builder().url(path("/sessions/join")).post(body).build())
        return credentialsAdapter.fromJson(text) ?: throw IOException("empty join response")
    }

    fun sessionInfo(sessionId: String, token: String): SessionInfo {
        val text = exec(
            Request.Builder().url(path("/sessions/$sessionId"))
                .header("Authorization", "Bearer $token")
                .get()
                .build(),
        )
        return infoAdapter.fromJson(text) ?: throw IOException("empty session response")
    }

    /** Uploads throwaway bytes to measure usable upload bandwidth. */
    fun probe(bytes: Int = DEFAULT_PROBE_BYTES): ProbeResult {
        val payload = ByteArray(bytes)
        val start = System.nanoTime()
        exec(
            Request.Builder().url(path("/probe")).post(payload.toRequestBody(binType)).build(),
        )
        val elapsedMs = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        val kbps = ((bytes / 1024.0) / (elapsedMs / 1000.0)).toInt()
        return ProbeResult(uploadKbps = kbps, bytes = bytes)
    }

    private fun path(suffix: String): String = baseUrl.trimEnd('/') + suffix

    private fun exec(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpStatusException(response.code, body)
            return body
        }
    }

    private fun defaultMoshi(): Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    companion object {
        const val DEFAULT_PROBE_BYTES = 256 * 1024
    }
}
