package com.example.audio.model


/**
 * A reusable mix template: a named starting point with per-track DSP
 * settings + master bus settings, derived from professional mixing practice.
 *
 * Templates are applied by the local model (Qwen3.5-4B etc.) via tool calls,
 * or directly by the user from the template browser.
 */
data class MixTemplate(
    val id: String,
    val name: String,
    val genre: String,
    val description: String,
    val referenceArtist: String = "",
    val masterLufsTarget: Float = -14.0f,
    val masterPeakDb: Float = -1.0f,
    val trackTemplates: Map<String, TrackTemplate> = emptyMap(),
    val masterEq: FourBandEqConfig? = null,
    val masterCompressor: StereoCompressorConfig? = null,
    val masterLimiter: ChannelLimiterConfig? = null
)

data class TrackTemplate(
    val trackName: String,
    val gainDb: Float = 0f,
    val pan: Float = 0f,
    val eq: FourBandEqConfig? = null,
    val compressor: StereoCompressorConfig? = null,
    val saturation: SaturationConfig? = null,
    val reverbSend: Float = 0f,
    val delaySend: Float = 0f
)

data class FourBandEqConfig(
    val lowShelfGainDb: Float = 0f,
    val lowMidGainDb: Float = 0f,
    val highMidGainDb: Float = 0f,
    val highShelfGainDb: Float = 0f,
    val lowShelfFreq: Float = 100f,
    val lowMidFreq: Float = 350f,
    val highMidFreq: Float = 2500f,
    val highShelfFreq: Float = 8000f
)

data class StereoCompressorConfig(
    val thresholdDb: Float = -18f,
    val ratio: Float = 2f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 100f,
    val makeupDb: Float = 0f,
    val kneeDb: Float = 3f
)

/**
 * Starter library of genre templates built from professional mixing practice.
 * Values reflect common starting points used by top engineers (Scheps, Wallace,
 * Pensado, etc.) and are meant to be refined by the local model per-song.
 */
object MixTemplateLibrary {

    val MODERN_POP_VOCAL = MixTemplate(
        id = "modern_pop_vocal",
        name = "Modern Pop Vocal",
        genre = "Pop",
        description = "Bright, present lead vocal with tight compression and subtle saturation. Reference: Ariana Grande, Dua Lipa.",
        referenceArtist = "Dua Lipa",
        masterLufsTarget = -8.0f,
        masterPeakDb = -0.5f,
        trackTemplates = mapOf(
            "Lead Vocal" to TrackTemplate(
                trackName = "Lead Vocal",
                gainDb = 0f,
                eq = FourBandEqConfig(lowShelfGainDb = -2f, lowMidGainDb = -1.5f, highMidGainDb = 2.5f, highShelfGainDb = 3f, highMidFreq = 3000f, highShelfFreq = 10000f),
                compressor = StereoCompressorConfig(thresholdDb = -18f, ratio = 3f, attackMs = 5f, releaseMs = 80f, makeupDb = 2f),
                saturation = SaturationConfig(driveDb = 2f, warmth = 0.3f, mix = 0.2f),
                reverbSend = 0.15f
            ),
            "Bass" to TrackTemplate(
                trackName = "Bass",
                gainDb = -2f,
                eq = FourBandEqConfig(lowShelfGainDb = 3f, lowMidGainDb = -3f, highMidGainDb = 1f, highShelfGainDb = -2f, lowShelfFreq = 60f, lowMidFreq = 250f),
                compressor = StereoCompressorConfig(thresholdDb = -20f, ratio = 4f, attackMs = 10f, releaseMs = 100f, makeupDb = 3f),
                reverbSend = 0f
            ),
            "Drums" to TrackTemplate(
                trackName = "Drums",
                gainDb = 0f,
                eq = FourBandEqConfig(lowShelfGainDb = 2f, lowMidGainDb = -1f, highMidGainDb = 1.5f, highShelfGainDb = 2f),
                compressor = StereoCompressorConfig(thresholdDb = -12f, ratio = 2.5f, attackMs = 3f, releaseMs = 60f, makeupDb = 1.5f),
                reverbSend = 0.05f
            )
        ),
        masterCompressor = StereoCompressorConfig(thresholdDb = -10f, ratio = 2f, attackMs = 30f, releaseMs = 200f, makeupDb = 1f),
        masterLimiter = ChannelLimiterConfig(enabled = true, ceilingDb = -0.5f)
    )

    val HIP_HOP_TRAP = MixTemplate(
        id = "hip_hop_trap",
        name = "Hip-Hop / Trap",
        genre = "Hip-Hop",
        description = "Heavy 808 sub, punchy drums, forward vocal, dark atmosphere. Reference: Travis Scott, Metro Boomin.",
        referenceArtist = "Travis Scott",
        masterLufsTarget = -7.0f,
        masterPeakDb = -0.3f,
        trackTemplates = mapOf(
            "808 Sub" to TrackTemplate(
                trackName = "808 Sub",
                gainDb = -3f,
                eq = FourBandEqConfig(lowShelfGainDb = 4f, lowMidGainDb = -4f, highMidGainDb = -2f, highShelfGainDb = -6f, lowShelfFreq = 50f, lowMidFreq = 200f),
                compressor = StereoCompressorConfig(thresholdDb = -14f, ratio = 6f, attackMs = 5f, releaseMs = 120f, makeupDb = 4f),
                reverbSend = 0f
            ),
            "Lead Vocal" to TrackTemplate(
                trackName = "Lead Vocal",
                gainDb = 1f,
                eq = FourBandEqConfig(lowShelfGainDb = -3f, lowMidGainDb = -2f, highMidGainDb = 3f, highShelfGainDb = 2f, highMidFreq = 2500f),
                compressor = StereoCompressorConfig(thresholdDb = -16f, ratio = 4f, attackMs = 3f, releaseMs = 60f, makeupDb = 3f),
                saturation = SaturationConfig(driveDb = 3f, warmth = 0.4f, mix = 0.25f),
                reverbSend = 0.1f
            ),
            "Hi-Hats" to TrackTemplate(
                trackName = "Hi-Hats",
                gainDb = -4f,
                eq = FourBandEqConfig(lowShelfGainDb = -6f, lowMidGainDb = -2f, highMidGainDb = 2f, highShelfGainDb = 4f, highShelfFreq = 12000f),
                compressor = StereoCompressorConfig(thresholdDb = -20f, ratio = 3f, attackMs = 1f, releaseMs = 40f, makeupDb = 2f),
                reverbSend = 0.05f
            )
        ),
        masterCompressor = StereoCompressorConfig(thresholdDb = -8f, ratio = 3f, attackMs = 10f, releaseMs = 150f, makeupDb = 2f),
        masterLimiter = ChannelLimiterConfig(enabled = true, ceilingDb = -0.3f)
    )

    val ACOUSTIC_LIVE = MixTemplate(
        id = "acoustic_live",
        name = "Acoustic / Live",
        genre = "Acoustic",
        description = "Natural, dynamic, minimal processing. Preserve transients and room tone. Reference: John Mayer, Ed Sheeran live.",
        referenceArtist = "John Mayer",
        masterLufsTarget = -12.0f,
        masterPeakDb = -1.5f,
        trackTemplates = mapOf(
            "Acoustic Guitar" to TrackTemplate(
                trackName = "Acoustic Guitar",
                gainDb = 0f,
                eq = FourBandEqConfig(lowShelfGainDb = 1f, lowMidGainDb = -1f, highMidGainDb = 1.5f, highShelfGainDb = 1f, lowShelfFreq = 80f),
                compressor = StereoCompressorConfig(thresholdDb = -22f, ratio = 2f, attackMs = 20f, releaseMs = 200f, makeupDb = 1f),
                reverbSend = 0.2f
            ),
            "Vocal" to TrackTemplate(
                trackName = "Vocal",
                gainDb = 0f,
                eq = FourBandEqConfig(lowShelfGainDb = -1f, lowMidGainDb = -1f, highMidGainDb = 2f, highShelfGainDb = 1.5f),
                compressor = StereoCompressorConfig(thresholdDb = -20f, ratio = 2.5f, attackMs = 10f, releaseMs = 150f, makeupDb = 1.5f),
                reverbSend = 0.25f
            )
        ),
        masterCompressor = StereoCompressorConfig(thresholdDb = -14f, ratio = 1.5f, attackMs = 50f, releaseMs = 300f, makeupDb = 0.5f),
        masterLimiter = ChannelLimiterConfig(enabled = true, ceilingDb = -1.5f)
    )

    val RNB_SMOOTH = MixTemplate(
        id = "rnb_smooth",
        name = "R&B / Smooth",
        genre = "R&B",
        description = "Warm, silky, spacious. Soft saturation, wide stereo, lush reverb. Reference: SZA, Frank Ocean.",
        referenceArtist = "SZA",
        masterLufsTarget = -9.0f,
        masterPeakDb = -0.8f,
        trackTemplates = mapOf(
            "Lead Vocal" to TrackTemplate(
                trackName = "Lead Vocal",
                gainDb = 0f,
                eq = FourBandEqConfig(lowShelfGainDb = 1f, lowMidGainDb = -1f, highMidGainDb = 2f, highShelfGainDb = 2.5f),
                compressor = StereoCompressorConfig(thresholdDb = -16f, ratio = 2.5f, attackMs = 8f, releaseMs = 120f, makeupDb = 2f),
                saturation = SaturationConfig(driveDb = 1.5f, warmth = 0.5f, mix = 0.15f),
                reverbSend = 0.3f
            ),
            "Keys" to TrackTemplate(
                trackName = "Keys",
                gainDb = -2f,
                eq = FourBandEqConfig(lowShelfGainDb = 0f, lowMidGainDb = -1f, highMidGainDb = 1f, highShelfGainDb = 1.5f),
                reverbSend = 0.35f
            ),
            "Bass" to TrackTemplate(
                trackName = "Bass",
                gainDb = -1f,
                eq = FourBandEqConfig(lowShelfGainDb = 2f, lowMidGainDb = -2f, highMidGainDb = 0f, highShelfGainDb = -3f),
                compressor = StereoCompressorConfig(thresholdDb = -18f, ratio = 3.5f, attackMs = 10f, releaseMs = 100f, makeupDb = 2.5f),
                reverbSend = 0f
            )
        ),
        masterCompressor = StereoCompressorConfig(thresholdDb = -12f, ratio = 2f, attackMs = 40f, releaseMs = 250f, makeupDb = 1f),
        masterLimiter = ChannelLimiterConfig(enabled = true, ceilingDb = -0.8f)
    )

    val ALL: List<MixTemplate> = listOf(MODERN_POP_VOCAL, HIP_HOP_TRAP, ACOUSTIC_LIVE, RNB_SMOOTH)

    fun byId(id: String): MixTemplate? = ALL.firstOrNull { it.id == id }
    fun byGenre(genre: String): List<MixTemplate> = ALL.filter { it.genre.equals(genre, ignoreCase = true) }
}
