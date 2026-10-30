package com.example.audio.history

import com.example.audio.model.BeatSource
import com.example.audio.model.StemCategory
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Thrown when a selected file is not a readable NC Phone Beat package. */
class NcbeatException(message: String) : Exception(message)

/**
 * Reader for the `.ncbeat.v1` interchange package written by NC Phone Beat
 * (canonical spec: NCBEAT_FORMAT.md in the Phone Beat repository).
 *
 * Everything read here is untrusted: schema and app identity are checked, entry
 * names may not escape the archive, and every size is capped before allocation.
 * [importPlan] then maps the package onto NC Studio's native session model —
 * tempo, per-track mix, stem clips and the embedded beat source.
 */
object NcbeatImporter {
    const val SCHEMA = "ncbeat.v1"
    const val APP = "nc-phone-beat"

    private const val MAX_ENTRIES = 64
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_JSON_BYTES = 4 * 1024 * 1024
    private const val MAX_TRACKS = 16

    /** Instrument type → display name and stem category, mirroring NC Phone Beat. */
    private val TYPE_NAMES = listOf("Kit", "Rompler", "Juno", "DX7", "Bass")
    private val TYPE_CATEGORIES = listOf(
        StemCategory.DRUMS, StemCategory.OTHER, StemCategory.KEYS, StemCategory.KEYS, StemCategory.BASS,
    )

    data class Manifest(
        val schema: String,
        val app: String,
        val appVersion: String,
        val exportedAt: String,
        val tempoBpm: Float,
        val patternSchema: String,
        val patternName: String,
        val grid: String,
        val lengthSteps: Int,
        val loopLengthFrames: Long,
    )

    data class MixerTrack(
        val id: Int,
        val type: Int,
        val gain: Float,
        val pan: Float,
        val mute: Boolean,
        val solo: Boolean,
        val params: List<Float>,
    )

    data class Mixer(
        val bpm: Int,
        val master: Float,
        val tracks: List<MixerTrack>,
    )

    data class Package(
        val manifest: Manifest,
        val patternJson: String,
        val mixerJson: String,
        val mixer: Mixer,
        val stems: Map<String, ByteArray>,
        val samples: Map<String, ByteArray>,
    )

    /** One beat track prepared to become a TrackData (+ AudioClip when a stem exists). */
    data class TrackPlan(
        val sessionTrackIndex: Int,
        val name: String,
        val category: StemCategory,
        val gainDb: Float,
        val pan: Float,
        val muted: Boolean,
        val soloed: Boolean,
        val stemEntry: String?,   // null: mixer data only, no audio in the package
        val clipName: String,
    )

    data class ImportPlan(
        val tempoBpm: Float,
        val masterGainDb: Float,
        val tracks: List<TrackPlan>,
        val beatSource: BeatSource,
        /** Set when the package has no per-track stems: play this entry once instead. */
        val masterFallbackEntry: String?,
        val droppedTracks: Int,
    )

    // ---------------------------------------------------------------- reading

    /** Reads and validates a package from [input]. Throws [NcbeatException] when invalid. */
    fun read(input: InputStream): Package {
        val json = HashMap<String, ByteArray>()
        val stems = LinkedHashMap<String, ByteArray>()
        val samples = LinkedHashMap<String, ByteArray>()
        var entryCount = 0
        var totalBytes = 0L

        ZipInputStream(BufferedInputStream(input)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                entryCount++
                if (entryCount > MAX_ENTRIES) {
                    throw NcbeatException("Package has more than $MAX_ENTRIES entries")
                }
                val name = entry.name
                if (name.startsWith("/") || name.contains("..") || name.contains('\\')) {
                    throw NcbeatException("Unsafe entry name in package: $name")
                }
                val bytes = readEntry(zin, name)
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw NcbeatException("Package exceeds the ${MAX_TOTAL_BYTES / (1024 * 1024)} MB limit")
                }
                when {
                    name == "manifest.json" || name == "pattern.json" || name == "mixer.json" -> {
                        if (bytes.size > MAX_JSON_BYTES) {
                            throw NcbeatException("$name is larger than $MAX_JSON_BYTES bytes")
                        }
                        json[name] = bytes
                    }
                    name.startsWith("stems/") -> stems[name] = bytes
                    name.startsWith("samples/") -> samples[name] = bytes
                    // Unknown entries are ignored for forward compatibility.
                }
            }
        }

        val manifestBytes = json["manifest.json"] ?: throw NcbeatException("manifest.json is missing")
        val patternBytes = json["pattern.json"] ?: throw NcbeatException("pattern.json is missing")
        val mixerBytes = json["mixer.json"] ?: throw NcbeatException("mixer.json is missing")

        val manifest = parseManifest(String(manifestBytes, Charsets.UTF_8))
        if (manifest.schema != SCHEMA) {
            throw NcbeatException("Unsupported package schema '${manifest.schema}' (expected $SCHEMA)")
        }
        if (manifest.app != APP) {
            throw NcbeatException("Package was written by '${manifest.app}', expected $APP")
        }

        val mixerJson = String(mixerBytes, Charsets.UTF_8)
        return Package(
            manifest = manifest,
            patternJson = String(patternBytes, Charsets.UTF_8),
            mixerJson = mixerJson,
            mixer = parseMixer(mixerJson),
            stems = stems,
            samples = samples,
        )
    }

    private fun readEntry(zin: java.util.zip.ZipInputStream, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var size = 0L
        while (true) {
            val n = zin.read(buf)
            if (n < 0) break
            size += n
            if (size > MAX_ENTRY_BYTES) {
                throw NcbeatException("Entry $name exceeds the entry size limit")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun parseManifest(text: String): Manifest {
        val o = try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw NcbeatException("manifest.json is not valid JSON")
        }
        val bpm = o.optDouble("tempoBpm", 120.0).toFloat().coerceIn(40f, 300f)
        val lengthSteps = o.optInt("lengthSteps", 16).coerceIn(1, 64)
        return Manifest(
            schema = o.optString("schema", ""),
            app = o.optString("app", ""),
            appVersion = o.optString("appVersion", ""),
            exportedAt = o.optString("exportedAt", ""),
            tempoBpm = bpm,
            patternSchema = o.optString("patternSchema", ""),
            patternName = o.optString("patternName", "beat").ifBlank { "beat" }.take(40),
            grid = o.optString("grid", "1/16"),
            lengthSteps = lengthSteps,
            loopLengthFrames = o.optLong("loopLengthFrames", 0L).coerceAtLeast(0L),
        )
    }

    private fun parseMixer(text: String): Mixer {
        val o = try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw NcbeatException("mixer.json is not valid JSON")
        }
        val tracks = ArrayList<MixerTrack>()
        o.optJSONArray("tracks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val to = arr.optJSONObject(i) ?: continue
                val params = ArrayList<Float>(24)
                val parr = to.optJSONArray("params")
                for (p in 0 until 24) params.add((parr?.optDouble(p, 0.0) ?: 0.0).toFloat())
                tracks.add(
                    MixerTrack(
                        id = to.optInt("id", i),
                        type = to.optInt("type", 0).coerceIn(0, 4),
                        gain = to.optDouble("gain", 1.0).toFloat(),
                        pan = to.optDouble("pan", 0.0).toFloat().coerceIn(-1f, 1f),
                        mute = to.optBoolean("mute", false),
                        solo = to.optBoolean("solo", false),
                        params = params,
                    ),
                )
            }
        }
        return Mixer(
            bpm = o.optInt("bpm", 120),
            master = o.optDouble("master", 1.0).toFloat().coerceIn(0f, 2f),
            tracks = tracks,
        )
    }

    // ---------------------------------------------------------------- planning

    /** Maps a validated package onto NC Studio's session model. Pure: no I/O. */
    fun importPlan(pkg: Package): ImportPlan {
        val tempo = pkg.manifest.tempoBpm.coerceIn(40f, 300f)
        val masterDb = linearToDb(pkg.mixer.master)

        val usedNames = HashSet<String>()
        val configured = pkg.mixer.tracks.take(MAX_TRACKS)
        val hasTrackStems = configured.any { pkg.stems.containsKey("stems/track_${it.id}.wav") }

        val plans = configured.mapIndexed { index, track ->
            var name = TYPE_NAMES.getOrElse(track.type) { "Track ${track.id}" }
            if (!usedNames.add(name)) name = "$name ${track.id}"
            val stemEntry = "stems/track_${track.id}.wav"
                .takeIf { pkg.stems.containsKey(it) }
            TrackPlan(
                sessionTrackIndex = index,
                name = name,
                category = TYPE_CATEGORIES.getOrElse(track.type) { StemCategory.OTHER },
                gainDb = linearToDb(track.gain),
                pan = track.pan.coerceIn(-1f, 1f),
                muted = track.mute,
                soloed = track.solo,
                stemEntry = stemEntry,
                clipName = pkg.manifest.patternName,
            )
        }

        return ImportPlan(
            tempoBpm = tempo,
            masterGainDb = masterDb,
            tracks = plans,
            beatSource = BeatSource(
                schema = pkg.manifest.schema,
                appVersion = pkg.manifest.appVersion,
                patternName = pkg.manifest.patternName,
                tempoBpm = tempo,
                patternJson = pkg.patternJson,
                mixerJson = pkg.mixerJson,
                exportedAt = pkg.manifest.exportedAt,
            ),
            masterFallbackEntry = if (hasTrackStems) null else "stems/master.wav".takeIf { pkg.stems.containsKey(it) },
            droppedTracks = (pkg.mixer.tracks.size - configured.size).coerceAtLeast(0),
        )
    }

    private fun linearToDb(linear: Float): Float =
        if (linear <= 0.0001f) -60f
        else (20.0 * kotlin.math.log10(linear.toDouble())).toFloat().coerceAtLeast(-60f)
}