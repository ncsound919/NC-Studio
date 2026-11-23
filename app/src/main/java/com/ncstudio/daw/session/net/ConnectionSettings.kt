package com.ncstudio.daw.session.net

import com.ncstudio.daw.BuildConfig
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

        /**
         * Shown whenever a plain-HTTP session target cannot work in this build.
         *
         * TLS is the only dependable answer here: `.local` resolution needs
         * both mDNS support in the phone's resolver and the laptop advertising
         * itself, and a DHCP address cannot be listed in the network security
         * config at all.
         */
        const val CLEARTEXT_SESSION_ADVICE =
            "This build needs HTTPS for the session server. Put TLS in front of it " +
                "(for example https://studio.example.com:8787), or use a loopback address."

        /**
         * Why plain HTTP to [hostInput] will be refused, or null when it can
         * connect. The debug network security config can only name hosts -
         * loopback, the emulator host and `.local` names - because the format
         * cannot list a DHCP address, so the connection screen says it in
         * advance instead of letting the platform throw.
         */
        fun cleartextWarning(hostInput: String): String? {
            if (!BuildConfig.DEBUG) return null
            val lower = hostInput.trim().lowercase()
            if (lower.isEmpty() || lower.startsWith("https://")) return null
            val host = lower.removePrefix("http://").substringBefore('/').substringBefore(':')
            if (host in CLEARTEXT_HOSTS || host.endsWith(".local")) return null
            return CLEARTEXT_SESSION_ADVICE
        }

        private val CLEARTEXT_HOSTS = setOf("", "localhost", "127.0.0.1", "10.0.2.2")
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
