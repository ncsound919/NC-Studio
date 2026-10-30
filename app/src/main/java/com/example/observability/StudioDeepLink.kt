package com.example.observability

import java.net.URI

/**
 * The small deep-link surface NC Studio exposes to sibling apps:
 *
 *   ncstudio://open            bring the DAW to the foreground
 *   ncstudio://open/arrangement   same, with the intended surface recorded
 *
 * Only the scheme and host are trusted. The path is informational and never
 * becomes a file path or a project id, so a hostile link cannot ask the app to
 * open arbitrary storage.
 */
object StudioDeepLink {

    const val SCHEME = "ncstudio"
    const val HOST = "open"

    fun isOpenLink(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals(SCHEME, ignoreCase = true) &&
            uri.host.equals(HOST, ignoreCase = true)
    }

    /** Optional surface name, for analytics only. */
    fun surface(raw: String?): String? {
        if (!isOpenLink(raw)) return null
        val uri = runCatching { URI(raw!!.trim()) }.getOrNull() ?: return null
        return uri.path?.trim('/')?.substringBefore('/')?.takeIf { it.isNotBlank() }
    }
}
