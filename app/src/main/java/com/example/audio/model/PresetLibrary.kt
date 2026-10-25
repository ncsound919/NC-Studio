package com.example.audio.model

/**
 * Professional channel strip and master bus preset definitions based on
 * industry-standard audio engineering techniques across Hip-Hop, R&B, Pop, and Rock.
 */
data class TrackPreset(
    val name: String,
    val category: String,
    val description: String,
    val eqConfig: EqConfig,
    val saturationConfig: SaturationConfig,
    val compressorConfig: CompressorConfig,
    val chorusConfig: ChorusConfig,
    val delayConfig: DuckingDelayConfig,
    val limiterConfig: ChannelLimiterConfig,
    val reverbSendLevel: Float
)

data class MasterPreset(
    val name: String,
    val description: String,
    val masterGainDb: Float,
    val masterReverb: ReverbConfig,
    val masterLimiter: MasterLimiterConfig
)

object PresetLibrary {

    val TRACK_PRESETS: List<TrackPreset> = listOf(
        TrackPreset(
            name = "Modern Pop Vocal Chain",
            category = "Vocals",
            description = "High-pass air lift with tight 4:1 VCA compression & 350ms ducking delay",
            eqConfig = EqConfig(
                enabled = true,
                band1 = EqBand(FilterType.HIGH_PASS, 110f, 0f, 0.707f, enabled = true),
                band2 = EqBand(FilterType.PEAKING, 350f, -2.0f, 1.2f, enabled = true),
                band3 = EqBand(FilterType.PEAKING, 3200f, 2.5f, 1.4f, enabled = true),
                band4 = EqBand(FilterType.HIGH_SHELF, 12000f, 3.5f, 0.707f, enabled = true)
            ),
            saturationConfig = SaturationConfig(enabled = true, driveDb = 3.5f, warmth = 0.45f, mix = 0.85f),
            compressorConfig = CompressorConfig(enabled = true, thresholdDb = -18f, ratio = 4.0f, attackMs = 12f, releaseMs = 90f, makeupGainDb = 3.0f),
            chorusConfig = ChorusConfig(enabled = false),
            delayConfig = DuckingDelayConfig(enabled = true, timeMs = 350f, feedback = 0.30f, mix = 0.20f, duckingEnabled = true, duckingThresholdDb = -20f, duckingDepthDb = -12f),
            limiterConfig = ChannelLimiterConfig(enabled = true, ceilingDb = -0.5f, releaseMs = 30f),
            reverbSendLevel = 0.30f
        ),
        TrackPreset(
            name = "Hip-Hop Forward Vocal",
            category = "Vocals",
            description = "Aggressive 5:1 compression with harmonic tube drive for in-your-face rap vocals",
            eqConfig = EqConfig(
                enabled = true,
                band1 = EqBand(FilterType.HIGH_PASS, 95f, 0f, 0.707f, enabled = true),
                band2 = EqBand(FilterType.PEAKING, 250f, -3.0f, 1.5f, enabled = true),
                band3 = EqBand(FilterType.PEAKING, 2800f, 3.8f, 1.6f, enabled = true),
                band4 = EqBand(FilterType.HIGH_SHELF, 10000f, 2.0f, 0.707f, enabled = true)
            ),
            saturationConfig = SaturationConfig(enabled = true, driveDb = 6.0f, warmth = 0.60f, mix = 0.90f),
            compressorConfig = CompressorConfig(enabled = true, thresholdDb = -20f, ratio = 5.5f, attackMs = 8f, releaseMs = 110f, makeupGainDb = 4.5f),
            chorusConfig = ChorusConfig(enabled = false),
            delayConfig = DuckingDelayConfig(enabled = true, timeMs = 280f, feedback = 0.25f, mix = 0.15f, duckingEnabled = true, duckingThresholdDb = -18f, duckingDepthDb = -14f),
            limiterConfig = ChannelLimiterConfig(enabled = true, ceilingDb = -0.3f, releaseMs = 25f),
            reverbSendLevel = 0.18f
        ),
        TrackPreset(
            name = "R&B Silk Harmonies",
            category = "Vocals",
            description = "Stereo chorus double layer with 450ms ducked delay & lush hall send",
            eqConfig = EqConfig(
                enabled = true,
                band1 = EqBand(FilterType.HIGH_PASS, 140f, 0f, 0.707f, enabled = true),
                band2 = EqBand(FilterType.PEAKING, 400f, -1.5f, 1.0f, enabled = true),
                band3 = EqBand(FilterType.PEAKING, 4500f, 2.0f, 1.2f, enabled = true),
                band4 = EqBand(FilterType.HIGH_SHELF, 11000f, 4.0f, 0.707f, enabled = true)
            ),
            saturationConfig = SaturationConfig(enabled = true, driveDb = 2.0f, warmth = 0.70f, mix = 0.60f),
            compressorConfig = CompressorConfig(enabled = true, thresholdDb = -15f, ratio = 2.8f, attackMs = 25f, releaseMs = 180f, makeupGainDb = 2.0f),
            chorusConfig = ChorusConfig(enabled = true, rateHz = 1.4f, depthMs = 4.5f, feedback = 0.25f, stereoSpread = 0.95f, mix = 0.50f),
            delayConfig = DuckingDelayConfig(enabled = true, timeMs = 450f, feedback = 0.40f, mix = 0.30f, duckingEnabled = true, duckingThresholdDb = -24f, duckingDepthDb = -16f),
            limiterConfig = ChannelLimiterConfig(enabled = false),
            reverbSendLevel = 0.50f
        ),
        TrackPreset(
            name = "Trap 808 & Sub Bass",
            category = "Bass",
            description = "55Hz sub boost with heavy 2nd-harmonic tube saturation & punchy 6:1 comp",
            eqConfig = EqConfig(
                enabled = true,
                band1 = EqBand(FilterType.HIGH_PASS, 28f, 0f, 0.707f, enabled = true),
                band2 = EqBand(FilterType.PEAKING, 55f, 4.5f, 1.8f, enabled = true),
                band3 = EqBand(FilterType.PEAKING, 450f, -4.0f, 2.0f, enabled = true),
                band4 = EqBand(FilterType.HIGH_SHELF, 6000f, -6.0f, 0.707f, enabled = true)
            ),
            saturationConfig = SaturationConfig(enabled = true, driveDb = 8.5f, warmth = 0.75f, mix = 1.0f),
            compressorConfig = CompressorConfig(enabled = true, thresholdDb = -14f, ratio = 6.0f, attackMs = 20f, releaseMs = 160f, makeupGainDb = 3.5f),
            chorusConfig = ChorusConfig(enabled = false),
            delayConfig = DuckingDelayConfig(enabled = false),
            limiterConfig = ChannelLimiterConfig(enabled = true, ceilingDb = -0.3f, releaseMs = 40f),
            reverbSendLevel = 0.0f
        ),
        TrackPreset(
            name = "Drum Bus Parallel Smash",
            category = "Drums",
            description = "Fast attack VCA drum compressor with tape distortion for maximum punch",
            eqConfig = EqConfig(
                enabled = true,
                band1 = EqBand(FilterType.HIGH_PASS, 35f, 0f, 0.707f, enabled = true),
                band2 = EqBand(FilterType.PEAKING, 120f, 2.0f, 1.2f, enabled = true),
                band3 = EqBand(FilterType.PEAKING, 2500f, 1.8f, 1.4f, enabled = true),
                band4 = EqBand(FilterType.HIGH_SHELF, 8500f, 2.5f, 0.707f, enabled = true)
            ),
            saturationConfig = SaturationConfig(enabled = true, driveDb = 6.0f, warmth = 0.50f, mix = 0.80f),
            compressorConfig = CompressorConfig(enabled = true, thresholdDb = -22f, ratio = 8.0f, attackMs = 5f, releaseMs = 70f, makeupGainDb = 5.0f),
            chorusConfig = ChorusConfig(enabled = false),
            delayConfig = DuckingDelayConfig(enabled = false),
            limiterConfig = ChannelLimiterConfig(enabled = true, ceilingDb = -0.3f, releaseMs = 20f),
            reverbSendLevel = 0.10f
        ),
        TrackPreset(
            name = "Acoustic Piano & Guitar Space",
            category = "Instruments",
            description = "Clean high-shelf air, subtle chorus width & smooth room send",
            eqConfig = EqConfig(
                enabled = true,
                band1 = EqBand(FilterType.HIGH_PASS, 80f, 0f, 0.707f, enabled = true),
                band2 = EqBand(FilterType.PEAKING, 500f, -1.5f, 1.0f, enabled = true),
                band3 = EqBand(FilterType.PEAKING, 3000f, 1.5f, 1.2f, enabled = true),
                band4 = EqBand(FilterType.HIGH_SHELF, 9000f, 3.0f, 0.707f, enabled = true)
            ),
            saturationConfig = SaturationConfig(enabled = true, driveDb = 1.5f, warmth = 0.30f, mix = 0.50f),
            compressorConfig = CompressorConfig(enabled = true, thresholdDb = -12f, ratio = 2.5f, attackMs = 30f, releaseMs = 200f, makeupGainDb = 1.5f),
            chorusConfig = ChorusConfig(enabled = true, rateHz = 0.8f, depthMs = 2.5f, feedback = 0.15f, stereoSpread = 0.80f, mix = 0.25f),
            delayConfig = DuckingDelayConfig(enabled = true, timeMs = 320f, feedback = 0.20f, mix = 0.15f),
            limiterConfig = ChannelLimiterConfig(enabled = false),
            reverbSendLevel = 0.35f
        )
    )

    val MASTER_PRESETS: List<MasterPreset> = listOf(
        MasterPreset(
            name = "Commercial Hip-Hop Master",
            description = "Tight -0.3 dB brickwall ceiling with 1.8s chamber reverb & 15ms pre-delay",
            masterGainDb = 1.5f,
            masterReverb = ReverbConfig(
                enabled = true,
                roomSize = 0.55f,
                damping = 0.35f,
                stereoWidth = 0.85f,
                returnLevel = 0.65f,
                preDelayMs = 15.0f,
                decayTimeSec = 1.8f,
                lowCutHz = 160.0f,
                highCutHz = 7500.0f
            ),
            masterLimiter = MasterLimiterConfig(enabled = true, ceilingDb = -0.3f, thresholdDb = -1.5f, releaseMs = 40f)
        ),
        MasterPreset(
            name = "Silky R&B Master",
            description = "Wide stereo hall with 2.8s decay, Abbey Road 180Hz low-cut & soft limiting",
            masterGainDb = 1.0f,
            masterReverb = ReverbConfig(
                enabled = true,
                roomSize = 0.75f,
                damping = 0.25f,
                stereoWidth = 0.95f,
                returnLevel = 0.75f,
                preDelayMs = 35.0f,
                decayTimeSec = 2.8f,
                lowCutHz = 180.0f,
                highCutHz = 8500.0f
            ),
            masterLimiter = MasterLimiterConfig(enabled = true, ceilingDb = -0.3f, thresholdDb = -1.0f, releaseMs = 60f)
        ),
        MasterPreset(
            name = "Modern Pop Streaming Master",
            description = "Transparent loudness matching (-14 LUFS target) with 2.0s plate reverb & 25ms pre-delay",
            masterGainDb = 0.5f,
            masterReverb = ReverbConfig(
                enabled = true,
                roomSize = 0.60f,
                damping = 0.30f,
                stereoWidth = 0.80f,
                returnLevel = 0.70f,
                preDelayMs = 25.0f,
                decayTimeSec = 2.0f,
                lowCutHz = 150.0f,
                highCutHz = 9000.0f
            ),
            masterLimiter = MasterLimiterConfig(enabled = true, ceilingDb = -0.2f, thresholdDb = -0.8f, releaseMs = 50f)
        )
    )
}
