package com.overlay.audio.library

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val LIB_NON_TEXT = Regex("[^a-z0-9#]+")
private val BPM_PATTERN = Regex("(\\d{2,3})\\s?bpm", RegexOption.IGNORE_CASE)

/**
 * One folder tree every NC music app reads.
 *
 * Android apps point at `Music/NC Sound` through the system folder picker; the
 * web app points at the same tree through the File System Access API. Nothing
 * is copied between apps: each one indexes metadata and reads the file on
 * demand, so a kick recorded in NC Field is already in NC Phone Beat and NC
 * Studio without an import step.
 */
object SoundLibraryLayout {
    const val ROOT = "NC Sound"
    const val INDEX_FILE = "library.index.json"

    /** Canonical subfolders, created on demand by the first app that can write. */
    val folders: List<String> = listOf(
        "Samples",
        "Drums",
        "Loops",
        "Recordings",
        "Renders",
        "Stems",
        "Kits",
        "Presets",
        "Projects",
    )

    fun categoryFor(folder: String?): LibraryCategory = when (folder?.lowercase(Locale.ROOT)) {
        "drums", "drum" -> LibraryCategory.DRUMS
        "loops", "loop" -> LibraryCategory.LOOPS
        "samples", "sample", "one-shots", "oneshots" -> LibraryCategory.ONE_SHOTS
        "recordings", "recording", "takes" -> LibraryCategory.RECORDINGS
        "renders", "render", "mixes" -> LibraryCategory.RENDERS
        "stems" -> LibraryCategory.STEMS
        "presets", "fx" -> LibraryCategory.PRESETS
        "kits" -> LibraryCategory.KITS
        "projects" -> LibraryCategory.PROJECTS
        else -> LibraryCategory.OTHER
    }
}

enum class LibraryCategory(val label: String) {
    DRUMS("Drums"),
    LOOPS("Loops"),
    ONE_SHOTS("Samples"),
    RECORDINGS("Recordings"),
    RENDERS("Renders"),
    STEMS("Stems"),
    PRESETS("Presets"),
    KITS("Kits"),
    PROJECTS("Projects"),
    OTHER("Other"),
}

enum class LibraryKind { AUDIO, PRESET, KIT, PROJECT, OTHER }

/** Metadata for one file. The audio itself is never held in the index. */
data class LibraryEntry(
    val path: String,
    val name: String,
    val ext: String,
    val category: LibraryCategory,
    val kind: LibraryKind,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val frames: Long? = null,
    val bpm: Float? = null,
    val tags: List<String> = emptyList(),
) {
    val durationSec: Float?
        get() = if (sampleRate != null && frames != null && sampleRate > 0) frames.toFloat() / sampleRate else null

    internal val searchText: String =
        (name + " " + path.replace('/', ' ') + " " + category.label + " " + tags.joinToString(" "))
            .lowercase(Locale.ROOT)
            .replace(LIB_NON_TEXT, " ")
            .trim()
}

/**
 * A scan result. [tokenIndex] is built once so queries do not walk the whole
 * library: short libraries still take the same code path, but a 20k-sample
 * folder answers a prefix query from a handful of ranges.
 */
class LibraryIndex(val rootName: String, val entries: List<LibraryEntry>) {

    private val sortedEntries = entries.sortedByDescending { it.modifiedAt }
    private val tokenPairs: Array<Pair<String, Int>> = ArrayList<Pair<String, Int>>(entries.size * 6).also { list ->
        entries.forEachIndexed { id, entry ->
            for (token in entry.searchText.split(' ')) {
                if (token.isNotBlank()) list.add(token to id)
            }
        }
        list.sortWith(compareBy { it.first })
    }.toTypedArray()

    /** Every distinct token, sorted, for a cheap prefix range scan. */
    fun tokens(): List<String> = tokenPairs.map { it.first }.distinct()

    fun entriesIn(category: LibraryCategory): List<LibraryEntry> =
        sortedEntries.filter { it.category == category }

    /**
     * All query tokens must match (AND). Results are ranked by where the match
     * landed: an exact name beats a name prefix, which beats a path/tag match.
     */
    fun search(query: String, category: LibraryCategory? = null, limit: Int = 100): List<LibraryEntry> {
        val needle = normalize(query)
        if (needle.isBlank()) {
            return sortedEntries.asSequence()
                .filter { category == null || it.category == category }
                .take(limit)
                .toList()
        }
        val queryTokens = needle.split(' ').filter { it.isNotBlank() }
        var candidates: Set<Int>? = null
        for (token in queryTokens) {
            val hits = prefixIds(token)
            candidates = if (candidates == null) hits else candidates.intersect(hits)
            if (candidates.isEmpty()) return emptyList()
        }
        val ids = candidates ?: return emptyList()
        return ids.asSequence()
            .map { entries[it] }
            .filter { category == null || it.category == category }
            .sortedWith(
                compareByDescending<LibraryEntry> { score(it, needle, queryTokens) }
                    .thenByDescending { it.modifiedAt }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
            .take(limit)
            .toList()
    }

    private fun prefixIds(prefix: String): Set<Int> {
        var lo = 0
        var hi = tokenPairs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tokenPairs[mid].first < prefix) lo = mid + 1 else hi = mid
        }
        val out = HashSet<Int>()
        var i = lo
        while (i < tokenPairs.size && tokenPairs[i].first.startsWith(prefix)) {
            out.add(tokenPairs[i].second)
            i++
        }
        return out
    }

    private fun score(entry: LibraryEntry, needle: String, queryTokens: List<String>): Int {
        val name = entry.name.lowercase(Locale.ROOT)
        var score = 0
        if (name == needle) score += 1000
        if (name.startsWith(needle)) score += 400
        if (name.split(' ').any { it.startsWith(queryTokens.first()) }) score += 150
        if (name.contains(needle)) score += 120
        if (entry.searchText.contains(needle)) score += 60
        for (token in queryTokens) {
            if (name.split(' ').any { it == token }) score += 90
            if (entry.tags.any { it.equals(token, ignoreCase = true) }) score += 80
        }
        if (entry.category != LibraryCategory.OTHER) score += 10
        // Shorter names are usually the exact hit ("Kick 909" over "Kick 909 layered 3").
        score -= (name.length / 40)
        return score
    }

    companion object {
        fun normalize(input: String): String =
            input.lowercase(Locale.ROOT)
                .replace(LIB_NON_TEXT, " ")
                .trim()
    }
}

/** Metadata the platform layer supplies for each file; no audio here. */
data class LibraryFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/** Parsed WAV facts. Returns null for anything that is not a plain PCM WAV. */
data class WavFacts(val sampleRate: Int, val channels: Int, val frames: Long)

object SoundLibrary {

    const val INDEX_SCHEMA = "nc.sound-library.v1"
    private const val MAX_HEADER_BYTES = 4096

    /** Derives the category, kind and filename hints for one file; null = not library material. */
    fun entryFor(
        file: LibraryFile,
        wav: WavFacts? = null,
    ): LibraryEntry? {
        val normalized = file.path.replace('\\', '/')
        val segments = normalized.split('/')
        val folder = segments.dropLast(1).lastOrNull()
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val kind = kindFor(ext, folder)
        if (kind == LibraryKind.OTHER) return null
        val category = if (kind == LibraryKind.AUDIO) SoundLibraryLayout.categoryFor(folder)
        else when (kind) {
            LibraryKind.KIT -> LibraryCategory.KITS
            LibraryKind.PRESET -> LibraryCategory.PRESETS
            LibraryKind.PROJECT -> LibraryCategory.PROJECTS
            else -> LibraryCategory.OTHER
        }
        val stem = file.name.substringBeforeLast('.').ifBlank { file.name }
        return LibraryEntry(
            path = normalized,
            name = stem,
            ext = ext,
            category = category,
            kind = kind,
            sizeBytes = file.sizeBytes,
            modifiedAt = file.modifiedAt,
            sampleRate = wav?.sampleRate,
            channels = wav?.channels,
            frames = wav?.frames,
            bpm = bpmFromName(stem),
            tags = tagsFromName(stem),
        )
    }

    fun build(rootName: String, files: List<LibraryFile>, probe: (LibraryFile) -> WavFacts? = { null }): LibraryIndex {
        val entries = files.mapNotNull { file ->
            val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val folder = file.path.replace('\\', '/').split('/').dropLast(1).lastOrNull()
            entryFor(file, if (ext in AUDIO_EXTS) probe(file) else null)
        }
        return LibraryIndex(rootName, entries)
    }

    /** Classifies by extension, using the containing folder for ambiguous `.json`. */
    fun kindFor(ext: String, folder: String? = null): LibraryKind = when (ext) {
        in AUDIO_EXTS -> LibraryKind.AUDIO
        "ncfx", "fxp" -> LibraryKind.PRESET
        "nckit" -> LibraryKind.KIT
        "ncbeat", "ncstudio", "nsl", "zip" -> LibraryKind.PROJECT
        "json" -> when (folder?.lowercase(Locale.ROOT)) {
            "projects" -> LibraryKind.PROJECT
            "presets" -> LibraryKind.PRESET
            "kits" -> LibraryKind.KIT
            else -> LibraryKind.OTHER
        }
        else -> LibraryKind.OTHER
    }

    val AUDIO_EXTS = setOf("wav", "aif", "aiff", "mp3", "flac", "ogg", "oga", "m4a", "opus", "aac")

    /**
     * Reads only the first [MAX_HEADER_BYTES] of the file. This is what keeps a
     * cold scan of a thousand samples in the tens of milliseconds instead of
     * decoding gigabytes of audio.
     */
    fun readWavFacts(header: ByteArray): WavFacts? {
        if (header.size < 12) return null
        if (!asciiIs(header, 0, "RIFF") || !asciiIs(header, 8, "WAVE")) return null
        var offset = 12
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataBytes = -1L
        while (offset + 8 <= header.size) {
            val id = String(header, offset, 4, Charsets.US_ASCII)
            val size = leInt(header, offset + 4)
            if (size < 0) return null
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > header.size) return null
                    val format = leShort(header, body)
                    // 1 = PCM, 0xFFFE = extensible (accept when the subformat is PCM).
                    if (format != 1 && format != 0xFFFE) return null
                    channels = leShort(header, body + 2)
                    sampleRate = leInt(header, body + 4)
                    bits = leShort(header, body + 14)
                }
                "data" -> dataBytes = size.toLong()
            }
            if (dataBytes >= 0 && sampleRate > 0) break
            offset = body + size + (size and 1)
        }
        if (channels <= 0 || sampleRate <= 0 || bits <= 0 || dataBytes < 0) return null
        val bytesPerFrame = channels * (bits / 8)
        if (bytesPerFrame <= 0) return null
        return WavFacts(sampleRate, channels, dataBytes / bytesPerFrame)
    }

    fun encode(index: LibraryIndex): String {
        val root = JSONObject()
        root.put("schema", INDEX_SCHEMA)
        root.put("root", index.rootName)
        root.put("generatedAt", System.currentTimeMillis())
        val array = JSONArray()
        for (e in index.entries) {
            val node = JSONObject()
            node.put("path", e.path)
            node.put("name", e.name)
            node.put("ext", e.ext)
            node.put("category", e.category.name)
            node.put("kind", e.kind.name)
            node.put("size", e.sizeBytes)
            node.put("mtime", e.modifiedAt)
            e.sampleRate?.let { node.put("sampleRate", it) }
            e.channels?.let { node.put("channels", it) }
            e.frames?.let { node.put("frames", it) }
            e.bpm?.let { node.put("bpm", it.toDouble()) }
            if (e.tags.isNotEmpty()) node.put("tags", JSONArray(e.tags))
            array.put(node)
        }
        root.put("entries", array)
        return root.toString()
    }

    fun decode(json: String): LibraryIndex? {
        return try {
            val root = JSONObject(json)
            if (root.optString("schema") != INDEX_SCHEMA) null else {
                val array = root.optJSONArray("entries") ?: JSONArray()
                val entries = ArrayList<LibraryEntry>(array.length())
                for (i in 0 until array.length()) {
                    val node = array.optJSONObject(i) ?: continue
                    val tagsArray = node.optJSONArray("tags")
                    val tags = if (tagsArray == null) emptyList()
                    else (0 until tagsArray.length()).map { tagsArray.optString(it) }
                    entries += LibraryEntry(
                        path = node.optString("path"),
                        name = node.optString("name"),
                        ext = node.optString("ext"),
                        category = runCatching { LibraryCategory.valueOf(node.optString("category")) }
                            .getOrDefault(LibraryCategory.OTHER),
                        kind = runCatching { LibraryKind.valueOf(node.optString("kind")) }
                            .getOrDefault(LibraryKind.OTHER),
                        sizeBytes = node.optLong("size"),
                        modifiedAt = node.optLong("mtime"),
                        sampleRate = if (node.has("sampleRate")) node.optInt("sampleRate") else null,
                        channels = if (node.has("channels")) node.optInt("channels") else null,
                        frames = if (node.has("frames")) node.optLong("frames") else null,
                        bpm = if (node.has("bpm")) node.optDouble("bpm").toFloat() else null,
                        tags = tags,
                    )
                }
                LibraryIndex(root.optString("root", SoundLibraryLayout.ROOT), entries)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bpmFromName(name: String): Float? {
        val match = BPM_PATTERN.find(name) ?: return null
        return match.groupValues[1].toFloatOrNull()?.takeIf { it in 20f..400f }
    }

    private fun tagsFromName(name: String): List<String> {
        val lower = name.lowercase(Locale.ROOT)
        return TAG_WORDS.filter { lower.contains(it) }.take(6)
    }

    private val TAG_WORDS = listOf(
        "kick", "snare", "hat", "clap", "rim", "tom", "perc", "808", "bass", "sub",
        "lead", "pad", "pluck", "key", "vox", "vocal", "fx", "riser", "impact",
        "loop", "break", "fill", "top", "drum", "dark", "bright", "warm", "lofi",
    )

    private fun asciiIs(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset + text.length > bytes.size) return false
        for (i in text.indices) if (bytes[offset + i].toInt() != text[i].code) return false
        return true
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
