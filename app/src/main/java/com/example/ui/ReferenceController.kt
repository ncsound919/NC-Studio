package com.example.ui

import android.content.Context
import android.net.Uri
import com.example.audio.engine.AudioEngineCommand
import com.example.audio.engine.AudioFileImporter
import com.example.audio.engine.AudioResampler
import com.example.audio.engine.AudioTrackPlaybackEngine
import com.example.audio.engine.OfflineRenderer
import com.example.audio.engine.WavAudioIO
import com.example.audio.history.SessionCommand
import com.example.audio.history.UndoManager
import com.example.audio.model.ProjectSession
import com.example.audio.reference.ReferenceMatcher
import com.example.audio.reference.ReferenceMonitor
import com.example.audio.reference.ReferenceProfile
import com.example.audio.reference.ReferenceProfiler
import com.example.audio.reference.ReferenceTrackConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Reference track A/B: a user-supplied commercial mix, measured with BS.1770
 * loudness and auditioned against this session's mix, level matched and click
 * free. No commercial audio ships with the app; the user imports their own
 * reference (see REFERENCE_AB.md).
 *
 * The controller owns the decoded audition samples and all match state. The
 * ViewModel keeps thin delegating methods so existing call sites and tests do
 * not need to know which object holds the feature.
 */
class ReferenceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sampleRate: Int,
    private val uiState: MutableStateFlow<EngineUiState>,
    private val session: MutableStateFlow<ProjectSession>,
    private val undoManager: UndoManager,
    private val playbackEngine: AudioTrackPlaybackEngine,
    private val trackAudioBuffers: Array<FloatArray>,
    private val requestJournalWrite: (ProjectSession) -> Unit,
    private val onStatus: (String, StatusKind, StatusAction?) -> Unit,
) {

    private var referenceTrackSamples: FloatArray? = null

    fun loadReferenceTrack(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            uiState.update { it.copy(isReferenceWorking = true) }
            onStatus("Importing reference track...", StatusKind.INFO, null)
            val result = AudioFileImporter.importAudioUri(
                context,
                uri,
                targetTrackIndex = -1,
                targetTimelineStartFrame = 0L,
            )
            if (result == null) {
                uiState.update { it.copy(isReferenceWorking = false) }
                onStatus("Could not decode reference track.", StatusKind.ERROR, null)
                return@launch
            }
            // Imported audio is canonical 48 kHz stereo; the engine may run at a
            // different rate and a reference must never play pitch shifted.
            val engineRate = if (result.sampleRate == sampleRate) result.samples
            else AudioResampler.normalizeToSessionStereo(
                samples = result.samples,
                srcChannels = 2,
                srcRate = result.sampleRate,
                dstRate = sampleRate,
            )
            installReferenceNow(engineRate, result.originalFileName, result.clip.sourcePath, offsetFrames = 0L)
        }
    }

    /**
     * Testable core of [loadReferenceTrack]: engine-rate interleaved stereo in,
     * analysed and auditionable reference out. Decoding stays with the caller so
     * this path needs no MediaCodec and no device.
     */
    suspend fun installReference(
        samples: FloatArray,
        name: String,
        filePath: String,
        offsetFrames: Long = 0L,
    ) {
        installReferenceNow(samples, name, filePath, offsetFrames)
    }

    suspend fun installReferenceNow(
        samples: FloatArray,
        name: String,
        filePath: String,
        offsetFrames: Long = 0L,
    ) {
        val frames = (samples.size / 2).toLong()
        val maxFrames = ReferenceMonitor.MAX_AUDITION_SECONDS.toLong() * sampleRate
        val auditionSamples = if (frames > maxFrames) samples.copyOf((maxFrames * 2).toInt()) else samples

        val profile = withContext(Dispatchers.Default) {
            ReferenceProfiler.analyze(samples, sampleRate, label = name)
        }

        referenceTrackSamples = auditionSamples
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReferenceTrack(auditionSamples, offsetFrames))
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReferenceAudition(false, 0f))

        val before = session.value
        val after = before.copy(
            reference = ReferenceTrackConfig(
                name = name,
                filePath = filePath,
                offsetFrames = offsetFrames,
                auditionEnabled = false,
                matchGainDb = 0f,
                profile = profile,
            ),
            revision = before.revision + 1,
        )
        session.value = after
        requestJournalWrite(after)

        uiState.update {
            it.copy(
                hasReferenceTrack = true,
                isReferenceAuditioning = false,
                referenceName = name,
                referenceProfile = profile,
                isReferenceWorking = true,
            )
        }

        val capNote = if (frames > maxFrames) {
            " A/B auditions the first ${ReferenceMonitor.MAX_AUDITION_SECONDS / 60} minutes; the whole file was measured."
        } else ""
        onStatus(
            "Reference \"$name\": ${fmtDb(profile.integratedLufs)} LUFS, ${fmtDb(profile.truePeakDb)} dBTP." +
                " Measuring your mix for a level-matched A/B...$capNote",
            StatusKind.INFO,
            null,
        )
        measureAndMatchReference()
    }

    /** Forgets the reference and hands the transport back to the mix alone. */
    fun clearReference() {
        val before = session.value
        val after = before.copy(reference = null, revision = before.revision + 1)
        if (before.reference != null) {
            undoManager.pushAction(SessionCommand.ReplaceSession("Clear reference track", before, after))
        }
        session.value = after
        requestJournalWrite(after)
        referenceTrackSamples = null
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReferenceTrack(null))
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReferenceAudition(false, 0f))
        uiState.update {
            it.copy(
                hasReferenceTrack = false,
                isReferenceAuditioning = false,
                referenceName = "",
                referenceProfile = null,
                mixProfile = null,
                referenceMatch = null,
            )
        }
        onStatus("Reference cleared.", StatusKind.INFO, null)
    }

    /** Moves the alignment point of the reference without re-importing it. */
    fun setReferenceOffset(offsetFrames: Long) {
        val cfg = session.value.reference ?: return
        val updated = session.value.copy(
            reference = cfg.copy(offsetFrames = offsetFrames.coerceAtLeast(0L)),
            revision = session.value.revision + 1,
        )
        session.value = updated
        requestJournalWrite(updated)
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReferenceOffset(offsetFrames.coerceAtLeast(0L)))
        onStatus(
            "Reference alignment: ${"%.2f".format(Locale.US, offsetFrames / sampleRate.toFloat())} s.",
            StatusKind.INFO,
            null,
        )
    }

    /**
     * Which side of the A/B you hear. On: the reference, level matched, playing
     * the same position (and looping with the transport). Off: your mix.
     */
    fun toggleAbComparison() {
        if (!uiState.value.hasReferenceTrack) {
            onStatus("Load a reference track first (Ref).", StatusKind.ERROR, null)
            return
        }
        val enable = !uiState.value.isReferenceAuditioning
        setReferenceAudition(enable)
        if (enable && uiState.value.mixProfile == null) {
            // No measurement yet, so match the levels before the first switch:
            // a louder side always sounds better and would decide the A/B.
            scope.launch { measureAndMatchReference() }
        }
    }

    fun defaultMatchGain(): Float = currentReferenceMatchGain()

    fun setReferenceAudition(enabled: Boolean, matchGainDb: Float = currentReferenceMatchGain()) {
        val gated = enabled && uiState.value.hasReferenceTrack
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReferenceAudition(gated, matchGainDb))
        val cfg = session.value.reference
        if (cfg != null && (cfg.auditionEnabled != gated || cfg.matchGainDb != matchGainDb)) {
            val updated = session.value.copy(
                reference = cfg.copy(auditionEnabled = gated, matchGainDb = matchGainDb),
                revision = session.value.revision + 1,
            )
            session.value = updated
            requestJournalWrite(updated)
        }
        uiState.update { it.copy(isReferenceAuditioning = gated) }
        onStatus(
            if (gated) "A/B: the reference, level matched (${signedDb(matchGainDb)} dB against your mix loudness)."
            else "A/B: your mix.",
            StatusKind.INFO,
            null,
        )
    }

    /**
     * Moves the mix to the reference's loudness - LUFS, not RMS. Whatever the
     * move cannot cover (a clamped move) is left to the reference's own audition
     * gain, so the A/B stays level matched whichever side you hear.
     */
    fun matchToReference() {
        if (currentReferenceProfile() == null) {
            onStatus("Load a reference track first (Ref).", StatusKind.ERROR, null)
            return
        }
        scope.launch { matchToReferenceNow() }
    }

    /** The suspend core of [matchToReference], testable without a looper. */
    suspend fun matchToReferenceNow() {
        val reference = currentReferenceProfile() ?: return
        uiState.update { it.copy(isReferenceWorking = true) }
        onStatus("Matching to reference loudness (LUFS)...", StatusKind.INFO, null)
        val mix = measureMixProfile()
        if (mix == null) {
            uiState.update { it.copy(isReferenceWorking = false) }
            onStatus("Could not measure the mix.", StatusKind.ERROR, null)
            return
        }
        val requested = reference.integratedLufs - mix.integratedLufs
        val before = session.value
        val movedDb = requested.coerceIn(-ReferenceMatcher.MAX_LEVEL_MOVE_DB, ReferenceMatcher.MAX_LEVEL_MOVE_DB)
        val newGain = (before.masterGainDb + movedDb).coerceIn(-24f, 24f)
        val appliedDb = newGain - before.masterGainDb
        val auditionGain = appliedDb - requested

        val after = before.copy(
            masterGainDb = newGain,
            reference = before.reference?.copy(matchGainDb = auditionGain),
            revision = before.revision + 1,
        )
        undoManager.pushAction(SessionCommand.ReplaceSession("Match mix to reference loudness", before, after))
        session.value = after
        playbackEngine.syncFromProject(after)
        requestJournalWrite(after)

        val report = ReferenceMatcher.compare(mix, reference)
        uiState.update {
            it.copy(isReferenceWorking = false, mixProfile = mix, referenceMatch = report)
        }
        onStatus(
            "Matched: mix moved ${signedDb(appliedDb)} dB to " +
                "${fmtDb(mix.integratedLufs + appliedDb)} LUFS (reference ${fmtDb(reference.integratedLufs)} LUFS)." +
                " A/B is level matched.",
            StatusKind.SUCCESS,
            StatusAction("Undo"),
        )
    }

    /**
     * Measures the mix against the reference and builds the move list: level,
     * low-end shelf, top-end shelf and width - each bounded, each with the
     * numbers behind it. Nothing changes until [applyReferenceMoves].
     */
    fun refreshReferenceMatch() {
        if (currentReferenceProfile() == null) {
            onStatus("Load a reference track first (Ref).", StatusKind.ERROR, null)
            return
        }
        scope.launch { refreshReferenceMatchNow() }
    }

    /** The suspend core of [refreshReferenceMatch], testable without a looper. */
    suspend fun refreshReferenceMatchNow() {
        val reference = currentReferenceProfile() ?: return
        uiState.update { it.copy(isReferenceWorking = true) }
        onStatus("Measuring your mix against the reference...", StatusKind.INFO, null)
        val mix = measureMixProfile()
        if (mix == null) {
            uiState.update { it.copy(isReferenceWorking = false) }
            onStatus("Could not measure the mix.", StatusKind.ERROR, null)
            return
        }
        val report = ReferenceMatcher.compare(mix, reference)
        uiState.update { it.copy(isReferenceWorking = false, mixProfile = mix, referenceMatch = report) }
        onStatus(
            if (report.moves.isEmpty()) {
                "Reference check: the mix is already within the match thresholds on level, balance and width."
            } else {
                "Reference check: ${report.moves.size} suggested " +
                    (if (report.moves.size == 1) "move" else "moves") + " - review and apply."
            },
            StatusKind.INFO,
            null,
        )
    }

    /** Applies the reference moves (master gain, mix-bus tone and width) as one undo step. */
    fun applyReferenceMoves() {
        val report = uiState.value.referenceMatch ?: run {
            refreshReferenceMatch()
            return
        }
        if (report.moves.isEmpty()) {
            onStatus("Nothing to apply: the mix is already within the match thresholds.", StatusKind.INFO, null)
            return
        }
        val before = session.value
        val applied = ReferenceMatcher.applyToSession(before, report.moves)
        if (applied == before) return
        val after = applied.copy(reference = applied.reference?.copy(toneMatchApplied = true))
        undoManager.pushAction(
            SessionCommand.ReplaceSession("Apply reference match (${report.moves.size})", before, after),
        )
        session.value = after
        playbackEngine.syncFromProject(after)
        requestJournalWrite(after)
        onStatus(
            "Applied reference match: " + report.moves.joinToString(", ") { it.title.lowercase(Locale.US) } + ".",
            StatusKind.SUCCESS,
            StatusAction("Undo"),
        )
        // The mix just moved: re-measure so the A/B and the next report are
        // computed against what you can actually hear.
        scope.launch { measureAndMatchReference() }
    }

    private fun currentReferenceProfile(): ReferenceProfile? =
        uiState.value.referenceProfile ?: session.value.reference?.profile

    private fun currentReferenceMatchGain(): Float = session.value.reference?.matchGainDb ?: 0f

    /**
     * Renders the session exactly as the engine plays it (racks, buses, master
     * chain) and measures it with the same analyser used on the reference, so
     * both sides of the comparison are in the same units.
     */
    suspend fun measureMixProfile(): ReferenceProfile? = withContext(Dispatchers.Default) {
        try {
            val current = session.value
            val pcm = OfflineRenderer.renderSessionToPcmBuffer(current, trackAudioBuffers)
            if (pcm.isEmpty()) null else ReferenceProfiler.analyze(pcm, current.sampleRate, label = "Your mix")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        }
    }

    /**
     * Measures the mix and sets the reference's audition gain so both are
     * equally loud, then builds the move list. Suspending on purpose: loading a
     * reference calls it inline, and tests drive it without a looper.
     */
    suspend fun measureAndMatchReference() {
        val reference = currentReferenceProfile() ?: return
        val mix = measureMixProfile()
        if (mix == null) {
            uiState.update { it.copy(isReferenceWorking = false) }
            return
        }
        val auditionGain = (mix.integratedLufs - reference.integratedLufs)
            .coerceIn(-48f, 48f)
        applyAuditionGain(auditionGain)
        val report = ReferenceMatcher.compare(mix, reference)
        uiState.update { it.copy(isReferenceWorking = false, mixProfile = mix, referenceMatch = report) }
        onStatus(
            "Reference ready: your mix ${fmtDb(mix.integratedLufs)} LUFS vs reference " +
                "${fmtDb(reference.integratedLufs)} LUFS. A/B is level matched" +
                (if (report.moves.isEmpty()) "." else "; ${report.moves.size} suggested move(s) in the studio panel."),
            StatusKind.SUCCESS,
            null,
        )
    }

    private fun applyAuditionGain(gainDb: Float) {
        val cfg = session.value.reference ?: return
        if (cfg.matchGainDb != gainDb) {
            val updated = session.value.copy(
                reference = cfg.copy(matchGainDb = gainDb),
                revision = session.value.revision + 1,
            )
            session.value = updated
            requestJournalWrite(updated)
        }
        playbackEngine.commandQueue.enqueue(
            AudioEngineCommand.SetReferenceAudition(uiState.value.isReferenceAuditioning, gainDb),
        )
    }

    /**
     * Re-hydrates the reference of a restored project: the imported copy lives in
     * app storage, so the audio is reloaded and handed to the engine, and the
     * stored profile is shown immediately. Audition always comes back off - the
     * transport is stopped, so there is nothing to A/B until you press play.
     */
    suspend fun restoreReference(project: ProjectSession) {
        val cfg = project.reference
        if (cfg == null || cfg.filePath.isBlank()) {
            referenceTrackSamples = null
            playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReferenceTrack(null))
            uiState.update {
                it.copy(
                    hasReferenceTrack = false,
                    isReferenceAuditioning = false,
                    referenceName = "",
                    referenceProfile = null,
                    referenceMatch = null,
                )
            }
            return
        }
        val samples = withContext(Dispatchers.IO) {
            val file = File(cfg.filePath)
            if (!file.exists()) null else WavAudioIO.readWavFile(file)?.samples
        }
        val maxFrames = ReferenceMonitor.MAX_AUDITION_SECONDS.toLong() * sampleRate
        val engineSamples = when {
            samples == null -> null
            samples.size / 2 > maxFrames -> samples.copyOf((maxFrames * 2).toInt())
            else -> samples
        }
        referenceTrackSamples = engineSamples
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReferenceTrack(engineSamples, cfg.offsetFrames))
        playbackEngine.commandQueue.enqueue(AudioEngineCommand.SetReferenceAudition(false, cfg.matchGainDb))
        uiState.update {
            it.copy(
                hasReferenceTrack = engineSamples != null,
                isReferenceAuditioning = false,
                referenceName = cfg.name,
                referenceProfile = cfg.profile,
                referenceMatch = null,
            )
        }
        if (engineSamples == null) {
            onStatus("Reference \"${cfg.name}\" lost its audio file; import it again.", StatusKind.ERROR, null)
        }
    }

    private fun fmtDb(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun signedDb(value: Float): String {
        val rounded = kotlin.math.round(value * 10f) / 10f
        return if (rounded > 0f) "+$rounded" else "$rounded"
    }
}
