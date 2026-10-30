package com.example.session.net

import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Where this device is connected, and with which credentials.
 *
 * Persisted so background work (uploads after an app kill or reboot) can
 * rebuild its client without a running UI, and so a reconnect after process
 * death can find the same session.
 */
data class ConnectionSettings(
    val baseUrl: String,
    val wsUrl: String,
    val sessionId: String,
    val role: String,
    val token: String,
    val inviteCode: String? = null,
    val displayName: String? = null,
    val savedAtEpochMs: Long = 0L,
) {
    val isArtist: Boolean get() = role == ROLE_ARTIST
    val isEngineer: Boolean get() = role == ROLE_ENGINEER

    companion object {
        const val ROLE_ARTIST = "artist"
        const val ROLE_ENGINEER = "engineer"

        /**
         * Builds settings from whatever the user typed into the server field:
         * `192.168.1.20`, `192.168.1.20:8787`, `http://host:8787`, or a full
         * URL with a path. Loopback and LAN HTTP are expected during a shoot.
         */
        fun fromHost(
            hostInput: String,
            sessionId: String,
            role: String,
            token: String,
            inviteCode: String? = null,
            displayName: String? = null,
            allowCleartext: Boolean = BuildConfig.DEBUG,
        ): ConnectionSettings {
            val trimmed = hostInput.trim().trimEnd('/')
            val base = when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> if (allowCleartext) "http://$trimmed" else "https://$trimmed"
            }
            if (base.startsWith("http://") && !allowCleartext) {
                throw IllegalArgumentException(
                    "Remote sessions require https:// in release builds. Use a TLS-terminated server URL."
                )
            }
            val root = base.removeSuffix("/v1")
            return ConnectionSettings(
                baseUrl = "$root/v1",
                wsUrl = root.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + "/ws",
                sessionId = sessionId,
                role = role,
                token = token,
                inviteCode = inviteCode,
                displayName = displayName,
            )
        }
    }
}

/** Atomic JSON file holding the current connection. */
class ConnectionSettingsStore(rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(ConnectionSettings::class.java)

    fun read(): ConnectionSettings? {
        if (!file.exists()) return null
        return runCatching { adapter.fromJson(file.readText()) }.getOrNull()
    }

    fun write(settings: ConnectionSettings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(adapter.toJson(settings.copy(savedAtEpochMs = System.currentTimeMillis())).toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val FILE_NAME = "session-connection.json"
    }
}
