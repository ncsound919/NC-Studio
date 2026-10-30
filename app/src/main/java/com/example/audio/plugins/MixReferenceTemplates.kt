package com.example.audio.plugins

import com.example.audio.model.BusData
import com.overlay.audio.plugins.PluginCatalog
import com.overlay.audio.plugins.PluginKind
import com.overlay.audio.plugins.PluginSlot
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData

/**
 * Mix templates built from the reference material in this repo
 * (mixing_settings.json, AGENT_MIXING_GUIDE.md, MIXING_REFERENCE_BIBLE.md) and
 * the engineer research in MIX_PLUGIN_RESEARCH.md.
 *
 * A template is not a preset dump: it is an ordered signal chain per source
 * role, a group bus routing, and a mix bus chain - exactly the structure the
 * reference guides describe. Order is the whole point, so every list below
 * reads like the hardware it mirrors.
 */
enum class ChainRole(val displayName: String, val aliases: List<String>, val busId: String) {
    HARMONY("Harmonies / Choir", listOf("harmon", "backing", "choir", "bg", "adlib", "double"), "bus-vocal"),
    LEAD_VOCAL("Lead Vocal", listOf("lead vocal", "vocal", "vox", "lead"), "bus-vocal"),
    KICK("Kick", listOf("kick", "bd "), "bus-drums"),
    SNARE("Snare / Clap", listOf("snare", "clap", "rim"), "bus-drums"),
    HATS("Hi-Hats", listOf("hat", "shaker", "tamb"), "bus-drums"),
    PERC("Percussion / Toms", listOf("perc", "tom"), "bus-drums"),
    DRUMS("Drum Bus", listOf("drum"), "bus-drums"),
    SUB_808("808 / Sub", listOf("808", "sub"), "bus-bass"),
    BASS("Bass", listOf("bass"), "bus-bass"),
    GUITARS("Guitars", listOf("guitar", "gtr"), "bus-music"),
    KEYS("Keys / Pads / Synths", listOf("piano", "rhodes", "pad", "synth", "organ", "key", "string"), "bus-music"),
    FX("FX / Risers", listOf("fx", "riser", "drop", "sweep"), "bus-fx");

    companion object {
        /** First role whose alias appears in the track name; harmony wins over "vocal". */
        fun forTrackName(name: String): ChainRole? {
            val lower = name.lowercase()
            for (role in values()) {
                if (role.aliases.any { lower.contains(it) }) return role
            }
            return null
        }
    }
}

/** Bus identity used by templates; created on demand when a template is applied. */
object MixBuses {
    const val DRUMS = "bus-drums"
    const val BASS = "bus-bass"
    const val MUSIC = "bus-music"
    const val VOCAL = "bus-vocal"
    const val FX = "bus-fx"

    val ALL = listOf(
        BusData(id = DRUMS, name = "Drums Bus"),
        BusData(id = BASS, name = "Bass Bus"),
        BusData(id = MUSIC, name = "Music Bus"),
        BusData(id = VOCAL, name = "Vocal Bus"),
        BusData(id = FX, name = "FX Bus")
    )

    fun nameFor(id: String): String = ALL.firstOrNull { it.id == id }?.name ?: id
}

data class MixReferenceTemplate(
    val id: String,
    val name: String,
    val genre: String,
    val referenceTrack: String,
    val referenceCredit: String,
    val tempoRange: IntRange,
    val lufsTarget: Pair<Float, Float>,
    val description: String,
    val roleChains: Map<ChainRole, List<PluginSlot>>,
    val drumBusChain: List<PluginSlot> = emptyList(),
    val mixBusChain: List<PluginSlot> = emptyList()
) {
    val hasBusChains: Boolean get() = drumBusChain.isNotEmpty()
}

object MixReferenceTemplateLibrary {

    private fun slot(kind: PluginKind, vararg params: Pair<String, Float>): PluginSlot {
        var s = PluginCatalog.defaultSlot(kind)
        for ((k, v) in params) s = s.withParam(k, v)
        return s
    }

    // ---------------------------------------------------------------
    // Genre templates, straight from mixing_settings.json
    // ---------------------------------------------------------------

    val POP = MixReferenceTemplate(
        id = "pop",
        name = "Modern Pop",
        genre = "Pop",
        referenceTrack = "Flowers - Miley Cyrus",
        referenceCredit = "Record of the Year 2024 (mix: Spike Stent)",
        tempoRange = 100..130,
        lufsTarget = -8f to -6f,
        description = "Forward vocal over a tight, controlled low end. Carve 1-3 kHz on guitars so the vocal cuts without level, sub-group the drums, glue the mix bus.",
        roleChains = mapOf(
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.TRIM, "gainDb" to 0f),
                slot(PluginKind.NEVE_1073, "driveDb" to 3f, "hpfHz" to 80f, "midDb" to 1f, "hfDb" to 1f),
                slot(PluginKind.FET_1176, "inputDb" to 4f, "ratio" to 4f, "attackUs" to 200f, "releaseMs" to 100f, "outputDb" to 1f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 3.5f, "gainDb" to 5f),
                slot(PluginKind.DE_ESSER, "freqHz" to 6000f, "thresholdDb" to -22f, "amountDb" to 3f),
                slot(PluginKind.AIR_SHELF, "freqHz" to 11000f, "gainDb" to 2f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.2f, "preDelayMs" to 30f, "hpHz" to 200f, "lpHz" to 9000f, "mix" to 0.12f),
                slot(PluginKind.TEMPO_DELAY, "division" to 1f, "feedback" to 0.25f, "hpHz" to 200f, "lpHz" to 4000f, "mix" to 0.12f)
            ),
            ChainRole.HARMONY to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 100f, "hfDb" to 1f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 2.5f, "gainDb" to 3f),
                slot(PluginKind.MID_SIDE_EQ, "sideHpHz" to 150f, "width" to 1.3f)
            ),
            ChainRole.KICK to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 30f, "lfHz" to 70f, "lfDb" to 3f, "hmfHz" to 4000f, "hmfDb" to 2f),
                slot(PluginKind.PULTEC_EQP1A, "lowBoostHz" to 60f, "lowBoostDb" to 3f),
                slot(PluginKind.PARALLEL_COMP, "thresholdDb" to -24f, "ratio" to 6f, "attackMs" to 20f, "releaseMs" to 100f, "blend" to 0.35f)
            ),
            ChainRole.SNARE to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 120f, "lmfHz" to 200f, "lmfDb" to 2f, "hmfHz" to 5000f, "hmfDb" to 2f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.1f, "preDelayMs" to 20f, "hpHz" to 200f, "lpHz" to 8000f, "mix" to 0.15f)
            ),
            ChainRole.HATS to listOf(
                slot(PluginKind.FILTER, "hpHz" to 250f),
                slot(PluginKind.AIR_SHELF, "freqHz" to 10000f, "gainDb" to 2f)
            ),
            ChainRole.SUB_808 to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 25f, "lfHz" to 60f, "lfDb" to 2f, "midHz" to 700f, "midDb" to -2f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -22f, "ratio" to 5f, "floorDb" to -10f)
            ),
            ChainRole.BASS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 30f, "lfHz" to 80f, "lfDb" to 2f, "midHz" to 700f, "midDb" to -2f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -22f, "ratio" to 5f, "floorDb" to -10f),
                slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -16f, "ratio" to 2f, "attackMs" to 3f, "autoRelease" to 1f, "makeupDb" to 3f)
            ),
            ChainRole.GUITARS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 80f, "midHz" to 1600f, "midDb" to -2f),
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 80f, "hmfHz" to 2500f, "hmfDb" to -3f, "hfHz" to 10000f, "hfDb" to 1f),
                slot(PluginKind.PARALLEL_COMP, "thresholdDb" to -28f, "ratio" to 8f, "blend" to 0.25f, "makeupDb" to 6f)
            ),
            ChainRole.KEYS to listOf(
                slot(PluginKind.FILTER, "hpHz" to 120f),
                slot(PluginKind.CHORUS_WIDTH, "mode" to 1f, "mix" to 0.3f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -26f, "ratio" to 4f, "floorDb" to -8f)
            )
        ),
        drumBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -14f, "ratio" to 4f, "attackMs" to 30f, "releaseMs" to 100f, "makeupDb" to 3f),
            slot(PluginKind.PARALLEL_COMP, "thresholdDb" to -30f, "ratio" to 10f, "blend" to 0.3f, "makeupDb" to 8f)
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -12f, "ratio" to 2f, "attackMs" to 30f, "autoRelease" to 1f, "makeupDb" to 3f),
            slot(PluginKind.TAPE_MACHINE, "driveDb" to 1f, "speedIps" to 15f, "wowFlutter" to 0.2f)
        )
    )

    val HIP_HOP_CLASSIC = MixReferenceTemplate(
        id = "hip_hop_classic",
        name = "Classic Hip-Hop / G-Funk",
        genre = "Hip-Hop",
        referenceTrack = "Nuthin' but a 'G' Thang - Dr. Dre ft. Snoop Dogg",
        referenceCredit = "Death Row era; Dre mixes on an SSL with a fixed monitoring rig",
        tempoRange = 85..100,
        lufsTarget = -9f to -7f,
        description = "One dominant sample plus live overdubs, dead-simple low end, snare reverb filling the space the kick and bass leave open. Warm Neve weight, mono sub.",
        roleChains = mapOf(
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.NEVE_1073, "driveDb" to 4f, "hpfHz" to 100f, "hfDb" to 1f),
                slot(PluginKind.FET_1176, "inputDb" to 5f, "ratio" to 4f, "attackUs" to 200f, "releaseMs" to 80f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 3f, "gainDb" to 5f),
                slot(PluginKind.DE_ESSER, "freqHz" to 6000f, "amountDb" to 2f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.0f, "preDelayMs" to 20f, "hpHz" to 300f, "lpHz" to 8000f, "mix" to 0.1f)
            ),
            ChainRole.HARMONY to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 120f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 2f, "gainDb" to 3f),
                slot(PluginKind.MID_SIDE_EQ, "width" to 1.35f)
            ),
            ChainRole.KICK to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 30f, "lfHz" to 60f, "lfDb" to 3f, "lmfHz" to 300f, "lmfDb" to -2f),
                slot(PluginKind.PULTEC_EQP1A, "lowBoostHz" to 60f, "lowBoostDb" to 4f, "lowAttenHz" to 60f, "lowAttenDb" to 2f)
            ),
            ChainRole.SNARE to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 150f, "lmfHz" to 250f, "lmfDb" to 2f, "hmfHz" to 5000f, "hmfDb" to 2f),
                slot(PluginKind.ROOM_REVERB, "decaySec" to 0.9f, "preDelayMs" to 12f, "hpHz" to 250f, "lpHz" to 7000f, "mix" to 0.2f)
            ),
            ChainRole.HATS to listOf(
                slot(PluginKind.FILTER, "hpHz" to 300f),
                slot(PluginKind.AIR_SHELF, "freqHz" to 10000f, "gainDb" to 2f)
            ),
            ChainRole.SUB_808 to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 25f, "lfHz" to 60f, "lfDb" to 3f, "midHz" to 700f, "midDb" to -2f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -20f, "ratio" to 6f, "floorDb" to -11f)
            ),
            ChainRole.BASS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 30f, "lfHz" to 60f, "lfDb" to 3f, "midHz" to 700f, "midDb" to -2f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -20f, "ratio" to 6f, "floorDb" to -11f)
            ),
            ChainRole.KEYS to listOf(
                slot(PluginKind.CHORUS_WIDTH, "mode" to 1f, "rateHz" to 0.6f, "mix" to 0.35f),
                slot(PluginKind.TEMPO_DELAY, "division" to 2f, "feedback" to 0.22f, "mix" to 0.15f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.4f, "preDelayMs" to 20f, "mix" to 0.14f)
            ),
            ChainRole.GUITARS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 100f, "hfDb" to 1f),
                slot(PluginKind.CHORUS_WIDTH, "mode" to 0f, "mix" to 0.25f)
            )
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -12f, "ratio" to 2f, "attackMs" to 30f, "autoRelease" to 1f, "makeupDb" to 2f),
            slot(PluginKind.TAPE_MACHINE, "driveDb" to 2f, "speedIps" to 15f, "wowFlutter" to 0.25f)
        )
    )

    val TRAP_DRILL = MixReferenceTemplate(
        id = "trap_drill",
        name = "Trap / Drill",
        genre = "Trap",
        referenceTrack = "Sicko Mode - Travis Scott ft. Drake",
        referenceCredit = "Modern sub-heavy production; 808 tuned to key and sidechained",
        tempoRange = 130..160,
        lufsTarget = -7f to -5f,
        description = "Heavy tuned 808 with its transient separated, rolling hats with pitch variation, layered snare, drenched vocal. Duck the 808 against the kick so both low-end parts survive.",
        roleChains = mapOf(
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.NEVE_1073, "driveDb" to 4f, "hpfHz" to 110f, "hfDb" to 1f),
                slot(PluginKind.FET_1176, "inputDb" to 6f, "ratio" to 4f, "attackUs" to 100f, "releaseMs" to 50f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 3f, "gainDb" to 4f),
                slot(PluginKind.DE_ESSER, "freqHz" to 7000f, "amountDb" to 4f),
                slot(PluginKind.HALL_REVERB, "decaySec" to 1.8f, "preDelayMs" to 10f, "hpHz" to 200f, "lpHz" to 7000f, "mix" to 0.2f),
                slot(PluginKind.TEMPO_DELAY, "division" to 3f, "feedback" to 0.3f, "hpHz" to 200f, "lpHz" to 4000f, "mix" to 0.14f)
            ),
            ChainRole.HARMONY to listOf(
                slot(PluginKind.FET_1176, "inputDb" to 6f, "ratio" to 4f, "releaseMs" to 80f),
                slot(PluginKind.MID_SIDE_EQ, "width" to 1.5f),
                slot(PluginKind.HALL_REVERB, "decaySec" to 2.0f, "mix" to 0.22f)
            ),
            ChainRole.SUB_808 to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 25f, "lfHz" to 60f, "lfDb" to 4f, "midHz" to 700f, "midDb" to -3f),
                slot(PluginKind.SATURATION, "type" to 1f, "driveDb" to 8f, "warmth" to 0.6f, "mix" to 0.35f, "oversample" to 4f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -18f, "ratio" to 8f, "attackMs" to 2f, "releaseMs" to 90f, "floorDb" to -9f)
            ),
            ChainRole.KICK to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 30f, "lfHz" to 50f, "lfDb" to 3f, "hmfHz" to 4000f, "hmfDb" to 3f),
                slot(PluginKind.SATURATION, "type" to 3f, "driveDb" to 6f, "mix" to 0.3f, "oversample" to 2f)
            ),
            ChainRole.SNARE to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 120f, "lmfHz" to 200f, "lmfDb" to 2f, "hmfHz" to 5000f, "hmfDb" to 2f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 0.8f, "preDelayMs" to 10f, "lpHz" to 8000f, "mix" to 0.16f)
            ),
            ChainRole.HATS to listOf(
                slot(PluginKind.FILTER, "hpHz" to 250f),
                slot(PluginKind.AIR_SHELF, "freqHz" to 11000f, "gainDb" to 3f)
            ),
            ChainRole.KEYS to listOf(
                slot(PluginKind.FILTER, "hpHz" to 150f),
                slot(PluginKind.CHORUS_WIDTH, "mode" to 1f, "mix" to 0.4f),
                slot(PluginKind.TEMPO_DELAY, "division" to 2f, "feedback" to 0.3f, "mix" to 0.16f),
                slot(PluginKind.HALL_REVERB, "decaySec" to 2.2f, "mix" to 0.18f)
            )
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -10f, "ratio" to 4f, "attackMs" to 10f, "autoRelease" to 1f, "makeupDb" to 4f),
            slot(PluginKind.TAPE_MACHINE, "driveDb" to 3f, "speedIps" to 15f, "wowFlutter" to 0.2f),
            slot(PluginKind.TRUE_PEAK_LIMITER, "ceilingDb" to -0.8f, "oversample" to 4f)
        )
    )

    val HOUSE = MixReferenceTemplate(
        id = "house",
        name = "Classic House",
        genre = "House",
        referenceTrack = "Show Me Love - Robin S. (StoneBridge Mix)",
        referenceCredit = "StoneBridge / P. Dennis Mitchell / Junior Vasquez",
        tempoRange = 120..130,
        lufsTarget = -8f to -6f,
        description = "Four-on-the-floor with the kick as the heartbeat: everything else is ducked against it. Off-beat hats, mono sub, chopped vocal on top, filtered delay throws.",
        roleChains = mapOf(
            ChainRole.KICK to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 30f, "lfHz" to 60f, "lfDb" to 4f, "hmfHz" to 4000f, "hmfDb" to 3f),
                slot(PluginKind.PULTEC_EQP1A, "lowBoostHz" to 60f, "lowBoostDb" to 3f)
            ),
            ChainRole.BASS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 30f, "lfHz" to 80f, "lfDb" to 3f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -20f, "ratio" to 4f, "attackMs" to 1f, "releaseMs" to 60f, "floorDb" to -8f),
                slot(PluginKind.SATURATION, "type" to 1f, "driveDb" to 4f, "mix" to 0.3f, "oversample" to 2f)
            ),
            ChainRole.HATS to listOf(
                slot(PluginKind.FILTER, "hpHz" to 300f),
                slot(PluginKind.AIR_SHELF, "freqHz" to 9000f, "gainDb" to 3f)
            ),
            ChainRole.SNARE to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 200f, "hmfHz" to 5000f, "hmfDb" to 2f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.0f, "preDelayMs" to 10f, "hpHz" to 250f, "lpHz" to 8000f, "mix" to 0.15f)
            ),
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.NEVE_1073, "driveDb" to 3f, "hpfHz" to 100f, "hfDb" to 1f),
                slot(PluginKind.FET_1176, "inputDb" to 4f, "ratio" to 4f, "releaseMs" to 100f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 3f, "gainDb" to 4f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.2f, "preDelayMs" to 20f, "mix" to 0.14f),
                slot(PluginKind.TEMPO_DELAY, "division" to 2f, "feedback" to 0.2f, "lpHz" to 3500f, "mix" to 0.16f)
            ),
            ChainRole.KEYS to listOf(
                slot(PluginKind.CHORUS_WIDTH, "mode" to 1f, "mix" to 0.4f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -24f, "ratio" to 4f, "floorDb" to -7f)
            )
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -12f, "ratio" to 2f, "attackMs" to 10f, "autoRelease" to 1f, "makeupDb" to 3f),
            slot(PluginKind.TAPE_MACHINE, "driveDb" to 2f, "speedIps" to 15f, "wowFlutter" to 0.2f)
        )
    )

    val RNB = MixReferenceTemplate(
        id = "rnb",
        name = "R&B / Smooth",
        genre = "R&B",
        referenceTrack = "Leave the Door Open - Silk Sonic",
        referenceCredit = "Silk Sonic; Tony Maserati-style focus on the vocal",
        tempoRange = 60..110,
        lufsTarget = -10f to -7f,
        description = "Warm and round low end, velvet top, stacked harmonies spread across the stereo image, and a vocal that everything else supports.",
        roleChains = mapOf(
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.NEVE_1073, "driveDb" to 3f, "hpfHz" to 80f, "lfHz" to 110f, "lfDb" to 1f, "hfDb" to 1f),
                slot(PluginKind.FET_1176, "inputDb" to 4f, "ratio" to 4f, "attackUs" to 300f, "releaseMs" to 120f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 3f, "gainDb" to 4f),
                slot(PluginKind.DE_ESSER, "freqHz" to 6000f, "amountDb" to 2f),
                slot(PluginKind.AIR_SHELF, "freqHz" to 11000f, "gainDb" to 2f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.5f, "preDelayMs" to 35f, "hpHz" to 200f, "lpHz" to 8000f, "mix" to 0.16f),
                slot(PluginKind.TEMPO_DELAY, "division" to 1f, "feedback" to 0.2f, "lpHz" to 3500f, "mix" to 0.12f)
            ),
            ChainRole.HARMONY to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 100f, "hfDb" to 1f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 2.5f, "gainDb" to 3f),
                slot(PluginKind.MID_SIDE_EQ, "sideHpHz" to 150f, "sideAirDb" to 1f, "width" to 1.4f)
            ),
            ChainRole.SNARE to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 150f, "lmfHz" to 250f, "lmfDb" to 1.5f),
                slot(PluginKind.ROOM_REVERB, "decaySec" to 1.1f, "preDelayMs" to 15f, "mix" to 0.18f)
            ),
            ChainRole.KICK to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 30f, "lfHz" to 60f, "lfDb" to 2f, "lmfHz" to 300f, "lmfDb" to -2f)
            ),
            ChainRole.BASS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 30f, "lfHz" to 80f, "lfDb" to 3f, "midHz" to 700f, "midDb" to -2f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -22f, "ratio" to 4f, "floorDb" to -9f),
                slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -16f, "ratio" to 2f, "autoRelease" to 1f, "makeupDb" to 2f)
            ),
            ChainRole.KEYS to listOf(
                slot(PluginKind.FILTER, "hpHz" to 120f),
                slot(PluginKind.CHORUS_WIDTH, "mode" to 1f, "mix" to 0.3f),
                slot(PluginKind.TEMPO_DELAY, "division" to 1f, "feedback" to 0.2f, "mix" to 0.1f)
            )
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -14f, "ratio" to 2f, "attackMs" to 30f, "autoRelease" to 1f, "makeupDb" to 2f),
            slot(PluginKind.TAPE_MACHINE, "driveDb" to 1f, "speedIps" to 30f, "wowFlutter" to 0.15f)
        )
    )

    val GOSPEL = MixReferenceTemplate(
        id = "gospel",
        name = "Gospel",
        genre = "Gospel",
        referenceTrack = "Never Would Have Made It - Marvin Sapp",
        referenceCredit = "Live gospel; voices first, dynamics preserved",
        tempoRange = 60..120,
        lufsTarget = -12f to -9f,
        description = "Lead forward and dry, choir wide and deep; depth - not level - separates them. Minimal compression so the performance dynamics survive.",
        roleChains = mapOf(
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.NEVE_1073, "driveDb" to 2f, "hpfHz" to 80f, "hfDb" to 1f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 2.5f, "gainDb" to 4f),
                slot(PluginKind.DE_ESSER, "freqHz" to 5000f, "amountDb" to 2f),
                slot(PluginKind.HALL_REVERB, "decaySec" to 2.5f, "preDelayMs" to 45f, "hpHz" to 200f, "lpHz" to 6500f, "mix" to 0.14f)
            ),
            ChainRole.HARMONY to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 100f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 2f, "gainDb" to 3f),
                slot(PluginKind.MID_SIDE_EQ, "sideHpHz" to 180f, "sideAirDb" to 1.5f, "width" to 1.45f),
                slot(PluginKind.HALL_REVERB, "decaySec" to 3.0f, "preDelayMs" to 50f, "hpHz" to 200f, "lpHz" to 6000f, "mix" to 0.12f)
            ),
            ChainRole.SNARE to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 150f, "lmfHz" to 250f, "lmfDb" to 1.5f),
                slot(PluginKind.ROOM_REVERB, "decaySec" to 1.2f, "mix" to 0.15f)
            ),
            ChainRole.KICK to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 30f, "lfHz" to 60f, "lfDb" to 2f)
            ),
            ChainRole.BASS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 30f, "lfHz" to 80f, "lfDb" to 2f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -24f, "ratio" to 4f, "floorDb" to -8f)
            ),
            ChainRole.KEYS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 100f, "hfDb" to 1f),
                slot(PluginKind.HALL_REVERB, "decaySec" to 2.2f, "mix" to 0.15f)
            )
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -16f, "ratio" to 2f, "attackMs" to 30f, "autoRelease" to 1f, "makeupDb" to 2f),
            slot(PluginKind.TAPE_MACHINE, "driveDb" to 1f, "speedIps" to 15f, "wowFlutter" to 0.15f)
        )
    )

    val ROCK = MixReferenceTemplate(
        id = "rock",
        name = "Rock",
        genre = "Rock",
        referenceTrack = "Smells Like Teen Spirit - Nirvana",
        referenceCredit = "Andy Wallace school: one EQ/comp per source, snare transient is king",
        tempoRange = 100..160,
        lufsTarget = -9f to -7f,
        description = "Wall-of-sound guitars carved at 1-3 kHz so the vocal cuts, punchy drums with parallel compression, slapback on the vocal, dynamics between verse and chorus preserved.",
        roleChains = mapOf(
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.NEVE_1073, "driveDb" to 4f, "hpfHz" to 100f, "midDb" to 1f, "hfDb" to 1f),
                slot(PluginKind.FET_1176, "inputDb" to 6f, "ratio" to 4f, "attackUs" to 200f, "releaseMs" to 80f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 3f, "gainDb" to 4f),
                slot(PluginKind.DE_ESSER, "freqHz" to 6000f, "amountDb" to 3f),
                slot(PluginKind.TEMPO_DELAY, "division" to 6f, "timeMs" to 100f, "feedback" to 0f, "lpHz" to 4000f, "mix" to 0.12f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.2f, "preDelayMs" to 20f, "mix" to 0.16f)
            ),
            ChainRole.GUITARS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 80f, "midHz" to 700f, "midDb" to 1f),
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 80f, "lmfHz" to 300f, "lmfDb" to -3f, "hmfHz" to 3000f, "hmfDb" to 2f),
                slot(PluginKind.SATURATION, "type" to 2f, "driveDb" to 6f, "mix" to 0.35f, "oversample" to 2f)
            ),
            ChainRole.KICK to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 30f, "lfHz" to 60f, "lfDb" to 3f, "hmfHz" to 4000f, "hmfDb" to 3f),
                slot(PluginKind.PARALLEL_COMP, "thresholdDb" to -26f, "ratio" to 8f, "blend" to 0.35f, "makeupDb" to 6f)
            ),
            ChainRole.SNARE to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 120f, "lmfHz" to 200f, "lmfDb" to 2f, "hmfHz" to 5000f, "hmfDb" to 3f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.1f, "preDelayMs" to 15f, "mix" to 0.18f)
            ),
            ChainRole.HATS to listOf(
                slot(PluginKind.FILTER, "hpHz" to 300f),
                slot(PluginKind.AIR_SHELF, "freqHz" to 10000f, "gainDb" to 2f)
            ),
            ChainRole.BASS to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 30f, "lfHz" to 80f, "lfDb" to 2f, "midHz" to 700f, "midDb" to -2f),
                slot(PluginKind.SATURATION, "type" to 2f, "driveDb" to 5f, "mix" to 0.3f, "oversample" to 2f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -22f, "ratio" to 4f, "floorDb" to -9f)
            ),
            ChainRole.KEYS to listOf(
                slot(PluginKind.FILTER, "hpHz" to 120f),
                slot(PluginKind.CHORUS_WIDTH, "mode" to 0f, "mix" to 0.25f)
            )
        ),
        drumBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -14f, "ratio" to 4f, "attackMs" to 30f, "releaseMs" to 100f, "makeupDb" to 3f),
            slot(PluginKind.PARALLEL_COMP, "thresholdDb" to -30f, "ratio" to 12f, "blend" to 0.35f, "makeupDb" to 8f)
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -12f, "ratio" to 2f, "attackMs" to 30f, "autoRelease" to 1f, "makeupDb" to 3f),
            slot(PluginKind.TAPE_MACHINE, "driveDb" to 2f, "speedIps" to 15f, "wowFlutter" to 0.25f)
        )
    )

    // ---------------------------------------------------------------
    // Engineer-derived templates (see MIX_PLUGIN_RESEARCH.md)
    // ---------------------------------------------------------------

    val ENGINEER_STAGED_VOCAL = MixReferenceTemplate(
        id = "engineer_staged_vocal",
        name = "Staged Vocal Compression",
        genre = "Vocal",
        referenceTrack = "Amy Winehouse - Rehab (mix: Tom Elmhirst)",
        referenceCredit = "Pultec -> 1176 -> Fairchild, then surgical notches and a light de-ess",
        tempoRange = 60..180,
        lufsTarget = -14f to -8f,
        description = "Separate peak catching from smoothing: the 1176 takes the transients, the vari-mu Fairchild smooths the result. Use narrow cuts only where you actually hear a problem.",
        roleChains = mapOf(
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.TRIM, "autoStage" to 1f),
                slot(PluginKind.PULTEC_EQP1A, "lowBoostHz" to 100f, "lowBoostDb" to 2f, "lowAttenHz" to 60f, "lowAttenDb" to 2f, "highBoostHz" to 12000f, "highBoostDb" to 2f),
                slot(PluginKind.FET_1176, "inputDb" to 8f, "ratio" to 8f, "attackUs" to 100f, "releaseMs" to 150f, "outputDb" to -2f),
                slot(PluginKind.VARI_MU_COMP, "driveDb" to 4f, "thresholdDb" to -14f, "ratio" to 2.5f, "timeConstant" to 4f, "makeupDb" to 3f, "mix" to 0.75f),
                slot(PluginKind.DE_ESSER, "freqHz" to 7000f, "amountDb" to 3f),
                slot(PluginKind.SSL_CHANNEL_EQ, "lmfHz" to 400f, "lmfDb" to -2f, "lmfQ" to 2.5f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.3f, "preDelayMs" to 30f, "mix" to 0.14f)
            )
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -14f, "ratio" to 2f, "attackMs" to 30f, "autoRelease" to 1f, "makeupDb" to 2f)
        )
    )

    val ENGINEER_PARALLEL_LOW_END = MixReferenceTemplate(
        id = "engineer_parallel_low_end",
        name = "Parallel Low End + Vocal Strip",
        genre = "Hip-Hop",
        referenceTrack = "Jay-Z - 99 Problems / Kendrick Lamar (mix: Jaycen Joshua, Derek Ali)",
        referenceCredit = "Parallel kick/snare returns against the dry source; SSL E channel for rap vocals",
        tempoRange = 70..160,
        lufsTarget = -10f to -6f,
        description = "Keep the producer's dry kick and snare as the anchor and blend a crushed parallel copy underneath for weight. The vocal gets the SSL channel strip and an air shelf so it cuts without level.",
        roleChains = mapOf(
            ChainRole.KICK to listOf(
                slot(PluginKind.PARALLEL_COMP, "thresholdDb" to -30f, "ratio" to 12f, "attackMs" to 10f, "releaseMs" to 80f, "blend" to 0.4f, "makeupDb" to 8f),
                slot(PluginKind.PULTEC_EQP1A, "lowBoostHz" to 60f, "lowBoostDb" to 3f, "lowAttenHz" to 60f, "lowAttenDb" to 2f)
            ),
            ChainRole.SNARE to listOf(
                slot(PluginKind.PARALLEL_COMP, "thresholdDb" to -28f, "ratio" to 10f, "blend" to 0.35f, "makeupDb" to 8f),
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 150f, "hmfHz" to 5000f, "hmfDb" to 2f)
            ),
            ChainRole.SUB_808 to listOf(
                slot(PluginKind.NEVE_1073, "hpfHz" to 25f, "lfHz" to 60f, "lfDb" to 4f, "midHz" to 700f, "midDb" to -3f),
                slot(PluginKind.SATURATION, "type" to 1f, "driveDb" to 7f, "warmth" to 0.55f, "mix" to 0.3f, "oversample" to 4f),
                slot(PluginKind.FILTER, "lpHz" to 12000f),
                slot(PluginKind.SIDECHAIN_DUCK, "thresholdDb" to -20f, "ratio" to 8f, "floorDb" to -10f)
            ),
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.SSL_CHANNEL_EQ, "hpHz" to 100f, "lmfHz" to 400f, "lmfDb" to -2f, "hmfHz" to 3000f, "hmfDb" to 2f, "hfHz" to 12000f, "hfDb" to 1.5f),
                slot(PluginKind.FET_1176, "inputDb" to 6f, "ratio" to 4f, "attackUs" to 150f, "releaseMs" to 90f),
                slot(PluginKind.DE_ESSER, "freqHz" to 6500f, "amountDb" to 3f),
                slot(PluginKind.CHORUS_WIDTH, "mode" to 2f, "depthMs" to 2.5f, "mix" to 0.18f),
                slot(PluginKind.TEMPO_DELAY, "division" to 1f, "feedback" to 0.25f, "lpHz" to 4000f, "mix" to 0.12f)
            )
        ),
        drumBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -12f, "ratio" to 4f, "attackMs" to 10f, "autoRelease" to 1f, "makeupDb" to 3f)
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -10f, "ratio" to 4f, "attackMs" to 10f, "autoRelease" to 1f, "makeupDb" to 3f),
            slot(PluginKind.TRUE_PEAK_LIMITER, "ceilingDb" to -0.8f, "oversample" to 4f)
        )
    )

    val ENGINEER_DELAY_WIDTH = MixReferenceTemplate(
        id = "engineer_delay_width",
        name = "Delay Width Instead of Reverb",
        genre = "Pop",
        referenceTrack = "Dave Pensado / Manny Marroquin vocal approach",
        referenceCredit = "Short modulated delays and a tiny plate instead of a big reverb",
        tempoRange = 70..150,
        lufsTarget = -14f to -8f,
        description = "Widen with filtered short delays and a doubler so the vocal can sit lower in the mix, then add only a whisper of plate so it still feels like one space.",
        roleChains = mapOf(
            ChainRole.LEAD_VOCAL to listOf(
                slot(PluginKind.TRIM, "autoStage" to 1f),
                slot(PluginKind.NEVE_1073, "hpfHz" to 100f, "hfDb" to 1f),
                slot(PluginKind.OPTO_LA2A, "peakReduction" to 3f, "gainDb" to 4f),
                slot(PluginKind.DE_ESSER, "freqHz" to 6000f, "amountDb" to 2.5f),
                slot(PluginKind.CHORUS_WIDTH, "mode" to 2f, "depthMs" to 2.5f, "rateHz" to 0.4f, "width" to 0.7f, "mix" to 0.2f),
                slot(PluginKind.TEMPO_DELAY, "division" to 1f, "feedback" to 0.18f, "hpHz" to 300f, "lpHz" to 4000f, "mix" to 0.15f),
                slot(PluginKind.PLATE_REVERB, "decaySec" to 1.2f, "preDelayMs" to 40f, "mix" to 0.1f)
            ),
            ChainRole.HARMONY to listOf(
                slot(PluginKind.MID_SIDE_EQ, "sideHpHz" to 150f, "width" to 1.4f),
                slot(PluginKind.CHORUS_WIDTH, "mode" to 1f, "mix" to 0.25f)
            )
        ),
        mixBusChain = listOf(
            slot(PluginKind.VCA_BUS_COMP, "thresholdDb" to -14f, "ratio" to 2f, "attackMs" to 30f, "autoRelease" to 1f, "makeupDb" to 2f),
            slot(PluginKind.TAPE_MACHINE, "driveDb" to 1f, "speedIps" to 15f, "wowFlutter" to 0.15f)
        )
    )

    val ALL = listOf(
        POP, HIP_HOP_CLASSIC, TRAP_DRILL, HOUSE, RNB, GOSPEL, ROCK,
        ENGINEER_STAGED_VOCAL, ENGINEER_PARALLEL_LOW_END, ENGINEER_DELAY_WIDTH
    )

    fun byId(id: String): MixReferenceTemplate? = ALL.firstOrNull { it.id == id }
}

/**
 * Applies templates to a session: role chains onto the matching tracks, group
 * bus routing, bus chains and the mix bus chain. Nothing is destructive - only
 * tracks whose role the template covers are touched.
 */
object TemplateApplier {

    fun matchRole(track: TrackData): ChainRole? = ChainRole.forTrackName(track.name)

    /**
     * Applies one role. A template always owns the routing for the roles it
     * recognises - a source it does not chain still belongs on its group bus so
     * the bus and mix-bus processing sees it - and only overwrites a chain when
     * the template actually has one for that role.
     */
    fun applyRoleToTrack(track: TrackData, role: ChainRole, template: MixReferenceTemplate): TrackData {
        val chain = template.roleChains[role]
        return if (chain == null) {
            track.copy(outputBusId = role.busId)
        } else {
            track.copy(
                pluginChain = chain,
                outputBusId = role.busId,
                // Chain modules own the tone now; the legacy send would double it.
                reverbSendLevel = 0f
            )
        }
    }

    fun applyToSession(session: ProjectSession, template: MixReferenceTemplate): ProjectSession {
        val touchesBus = template.roleChains.keys.any { it.busId != "master" } || template.drumBusChain.isNotEmpty()
        val tracks = session.tracks.map { track ->
            val role = matchRole(track) ?: return@map track
            applyRoleToTrack(track, role, template)
        }
        if (!touchesBus && template.mixBusChain.isEmpty()) {
            return session.copy(tracks = tracks)
        }
        // Rebuild the five group buses with the template's chains, keeping the
        // rest of the session's buses untouched.
        val templateBuses = MixBuses.ALL.map { bus ->
            bus.copy(pluginChain = if (bus.id == MixBuses.DRUMS) template.drumBusChain else emptyList())
        }
        val otherBuses = session.buses.filterNot { bus -> MixBuses.ALL.any { it.id == bus.id } }
        return session.copy(
            tracks = tracks,
            buses = templateBuses + otherBuses,
            masterPluginChain = template.mixBusChain
        )
    }

    /**
     * How many tracks in the session this template will actually chain (roles
     * the template covers). Routing can touch more tracks than this when the
     * session has sources the template recognises but does not process.
     */
    fun matchedTrackCount(session: ProjectSession, template: MixReferenceTemplate): Int =
        session.tracks.count { track ->
            val role = matchRole(track) ?: return@count false
            template.roleChains.containsKey(role)
        }
}
