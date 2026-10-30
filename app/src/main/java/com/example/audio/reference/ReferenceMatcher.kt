package com.example.audio.reference

import com.example.audio.model.ProjectSession
import com.overlay.audio.plugins.PluginCatalog
import com.overlay.audio.plugins.PluginKind
import com.overlay.audio.plugins.PluginSlot
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns "my mix versus this reference" into a short list of moves a mixer would
 * actually make, and can apply them to the session.
 *
 * The rules are deliberately conservative and bounded, in the spirit of the
 * agent mixing guide in this repo (AGENT_MIXING_GUIDE.md §6: one decision per
 * problem, bounded by the tool's real range). A reference is a target, not a
 * copy: if the low end differs by 8 dB the answer is a modest shelf plus a
 * conversation about arrangement, not an 8 dB shelf.
 *
 * Bounds:
 *  - level moves are LU-measured and clamped to ±12 dB, and the report always
 *    states the true peak the move would land on;
 *  - mix-bus shelves are clamped to ±6 dB (beyond that the balance problem is
 *    upstream, in the tracks);
 *  - width correction is a ratio in [0.5, 1.75] and is skipped below 1.5 dB.
 */

enum class ReferenceMoveKind { LEVEL, LOW_SHELF, HIGH_SHELF, WIDTH }

/**
 * One concrete correction. [amountDb] is dB for the shelves and the level move,
 * and a multiplier for [ReferenceMoveKind.WIDTH].
 */
data class ReferenceMove(
    val kind: ReferenceMoveKind,
    val title: String,
    val detail: String,
    val amountDb: Float,
    val bandLabel: String = ""
)

/** Everything the comparison produced, ready to render in the UI. */
data class ReferenceMatchReport(
    val mix: ReferenceProfile,
    val reference: ReferenceProfile,
    /** `reference - mix`, in LU: how far the mix is from the reference's loudness. */
    val levelDeltaLu: Float,
    /** `mix - reference` per octave band, dB. Positive means the mix is hotter. */
    val bandDeltasDb: List<Float>,
    /** `mix - reference` mid/side width, dB. Negative means the mix is narrower. */
    val widthDeltaDb: Float,
    /** `mix - reference` peak-to-loudness, LU. Negative means the mix is denser. */
    val crestDeltaLu: Float,
    /** `mix - reference` true peak, dB. */
    val truePeakDeltaDb: Float,
    val moves: List<ReferenceMove>
) {
    val hasReference: Boolean get() = !reference.isSilent
}

object ReferenceMatcher {

    /** True-peak ceiling a mix should keep; also the default of the limiter slot. */
    const val TARGET_CEILING_DBTP = -1.0f

    private const val LEVEL_DEADBAND_LU = 0.3f
    private const val TILT_DEADBAND_DB = 1.0f
    private const val MAX_SHELF_DB = 6.0f

    /** A level match is a mastering decision, not a remix: half a dozen dB, at most. */
    const val MAX_LEVEL_MOVE_DB = 12.0f
    private const val WIDTH_DEADBAND_DB = 1.5f

    /** Slot ids the tone match owns, so repeated matches update instead of stacking. */
    const val TONE_SLOT_ID = "ref-tone-match"
    const val WIDTH_SLOT_ID = "ref-width-match"

    fun compare(mix: ReferenceProfile, reference: ReferenceProfile): ReferenceMatchReport {
        val bandDeltas = if (mix.bandBalanceDb.size == reference.bandBalanceDb.size) {
            mix.bandBalanceDb.indices.map { mix.bandBalanceDb[it] - reference.bandBalanceDb[it] }
        } else {
            emptyList()
        }
        val levelDelta = reference.integratedLufs - mix.integratedLufs
        val widthDelta = mix.widthDb - reference.widthDb
        val crestDelta = mix.crestFactorLu - reference.crestFactorLu
        val truePeakDelta = mix.truePeakDb - reference.truePeakDb

        val moves = mutableListOf<ReferenceMove>()

        // A silent mix has no balance to correct: whatever the bands read on an
        // empty session would be an artefact, and a level move into silence is
        // meaningless. Report the numbers, suggest nothing.
        if (mix.isSilent) {
            return ReferenceMatchReport(
                mix = mix,
                reference = reference,
                levelDeltaLu = levelDelta,
                bandDeltasDb = bandDeltas,
                widthDeltaDb = widthDelta,
                crestDeltaLu = crestDelta,
                truePeakDeltaDb = truePeakDelta,
                moves = emptyList()
            )
        }

        if (abs(levelDelta) >= LEVEL_DEADBAND_LU) {
            val amount = levelDelta.coerceIn(-MAX_LEVEL_MOVE_DB, MAX_LEVEL_MOVE_DB)
            val projectedPeak = mix.truePeakDb + amount
            val ceilingNote = if (projectedPeak > TARGET_CEILING_DBTP) {
                " True peak would land at ${fmt(projectedPeak)} dBTP, so the mix-bus limiter takes the rest."
            } else {
                " True peak lands at ${fmt(projectedPeak)} dBTP."
            }
            moves += ReferenceMove(
                kind = ReferenceMoveKind.LEVEL,
                title = "Match reference loudness",
                detail = "Your mix measures ${fmt(mix.integratedLufs)} LUFS, " +
                    "the reference ${fmt(reference.integratedLufs)} LUFS " +
                    "(${signed(levelDelta)} LU).$ceilingNote",
                amountDb = amount
            )
        }

        if (bandDeltas.isNotEmpty()) {
            val lowTilt = tilt(bandDeltas, LOW_TILT_BANDS)
            if (abs(lowTilt) >= TILT_DEADBAND_DB) {
                val amount = (-lowTilt).coerceIn(-MAX_SHELF_DB, MAX_SHELF_DB)
                moves += ReferenceMove(
                    kind = ReferenceMoveKind.LOW_SHELF,
                    title = if (amount < 0) "Trim the low end" else "Add low-end weight",
                    detail = "63-250 Hz sits ${signed(lowTilt)} dB against the reference: " +
                        "a ${signed(amount)} dB shelf at 100 Hz on the mix bus.",
                    amountDb = amount,
                    bandLabel = "63-250 Hz"
                )
            }
            val highTilt = tilt(bandDeltas, HIGH_TILT_BANDS)
            if (abs(highTilt) >= TILT_DEADBAND_DB) {
                val amount = (-highTilt).coerceIn(-MAX_SHELF_DB, MAX_SHELF_DB)
                moves += ReferenceMove(
                    kind = ReferenceMoveKind.HIGH_SHELF,
                    title = if (amount < 0) "Soften the top" else "Open up the top",
                    detail = "4-8 kHz sits ${signed(highTilt)} dB against the reference: " +
                        "a ${signed(amount)} dB shelf at 10 kHz on the mix bus.",
                    amountDb = amount,
                    bandLabel = "4-8 kHz"
                )
            }
        }

        if (abs(widthDelta) >= WIDTH_DEADBAND_DB) {
            val ratio = 10.0.pow((-widthDelta) / 20.0).toFloat().coerceIn(0.5f, 1.75f)
            val targetWidth = (mix.widthDb - widthDelta)
            moves += ReferenceMove(
                kind = ReferenceMoveKind.WIDTH,
                title = if (widthDelta < 0) "Widen the image" else "Tighten the image",
                detail = "Your mix is ${signed(widthDelta)} dB " +
                    (if (widthDelta < 0) "narrower" else "wider") +
                    " than the reference (${fmt(targetWidth)} dB sides-to-mid): " +
                    "width x${String.format(java.util.Locale.US, "%.2f", ratio)}.",
                amountDb = ratio,
                bandLabel = "Stereo"
            )
        }

        return ReferenceMatchReport(
            mix = mix,
            reference = reference,
            levelDeltaLu = levelDelta,
            bandDeltasDb = bandDeltas,
            widthDeltaDb = widthDelta,
            crestDeltaLu = crestDelta,
            truePeakDeltaDb = truePeakDelta,
            moves = moves
        )
    }

    /** Mean of the band deltas at [indices]. */
    private fun tilt(deltas: List<Float>, indices: List<Int>): Float {
        val values = indices.mapNotNull { deltas.getOrNull(it) }
        if (values.isEmpty()) return 0f
        return values.sum() / values.size
    }

    private val LOW_TILT_BANDS = listOf(1, 2, 3)      // 63, 125, 250 Hz
    private val HIGH_TILT_BANDS = listOf(7, 8)        // 4 k, 8 k

    /**
     * Applies [moves] to [session] and returns the new session.
     *
     * Level moves go to the master gain. Tone moves go to one dedicated
     * SSL-strip slot on the mix bus that repeated matches update in place
     * (measure again after applying: the next report is computed against the
     * result, never compounded blindly). Because an active mix-bus chain takes
     * over bus processing, a true-peak limiter is added if the chain has none.
     */
    fun applyToSession(session: ProjectSession, moves: List<ReferenceMove>): ProjectSession {
        if (moves.isEmpty()) return session

        var masterGainDb = session.masterGainDb
        var lowShelf: Float? = null
        var highShelf: Float? = null
        var widthRatio: Float? = null

        for (move in moves) {
            when (move.kind) {
                ReferenceMoveKind.LEVEL ->
                    masterGainDb = (masterGainDb + move.amountDb).coerceIn(-24f, 24f)
                ReferenceMoveKind.LOW_SHELF -> lowShelf = move.amountDb
                ReferenceMoveKind.HIGH_SHELF -> highShelf = move.amountDb
                ReferenceMoveKind.WIDTH -> widthRatio = move.amountDb
            }
        }

        var chain = session.masterPluginChain.toMutableList()

        if (lowShelf != null || highShelf != null) {
            val existing = chain.indexOfFirst { it.id == TONE_SLOT_ID }
            val base = if (existing >= 0) chain[existing] else PluginCatalog.defaultSlot(PluginKind.SSL_CHANNEL_EQ)
                .copy(id = TONE_SLOT_ID)
            var slot = base
                .withParam("lfHz", 100f)
                .withParam("hfHz", 10000f)
                .withParam("lfBell", 0f)
                .withParam("hfBell", 0f)
            lowShelf?.let { slot = slot.withParam("lfDb", it) }
            highShelf?.let { slot = slot.withParam("hfDb", it) }

            if (existing >= 0) chain[existing] = slot else chain.add(insertBeforeLimiter(chain), slot)
        }

        if (widthRatio != null) {
            val existing = chain.indexOfFirst { it.id == WIDTH_SLOT_ID }
            val base = if (existing >= 0) chain[existing] else PluginCatalog.defaultSlot(PluginKind.MID_SIDE_EQ)
                .copy(id = WIDTH_SLOT_ID)
            val slot = base.withParam("width", widthRatio)
            if (existing >= 0) chain[existing] = slot else chain.add(insertBeforeLimiter(chain), slot)
        }

        if (chain.isNotEmpty() && chain.none { it.kind == PluginKind.TRUE_PEAK_LIMITER }) {
            chain.add(PluginCatalog.defaultSlot(PluginKind.TRUE_PEAK_LIMITER))
        }

        return session.copy(
            masterGainDb = masterGainDb,
            masterPluginChain = chain,
            revision = session.revision + 1
        )
    }

    /** Tone shaping belongs before the limiter, never after it. */
    private fun insertBeforeLimiter(chain: List<PluginSlot>): Int {
        val limiterIndex = chain.indexOfFirst { it.kind == PluginKind.TRUE_PEAK_LIMITER }
        return if (limiterIndex >= 0) limiterIndex else chain.size
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    private fun signed(v: Float): String {
        val rounded = (v * 10f).roundToInt() / 10f
        return if (rounded > 0f) "+$rounded" else "$rounded"
    }
}
