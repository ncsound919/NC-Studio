package com.example.audio.model

import java.util.UUID

/**
 * Defines stem category classified for a track.
 */
enum class StemCategory(val displayName: String) {
    UNKNOWN("Unassigned"),
    VOCAL("Vocal"),
    DRUMS("Drums"),
    BASS("Bass"),
    GUITAR("Guitar"),
    KEYS("Keys / Synth"),
    OTHER("Effects / Other")
}

/**
 * Filter type for 4-band parametric EQ.
 */
enum class FilterType {
    LOW_SHELF,
    PEAKING,
    HIGH_SHELF,
    HIGH_PASS,
    LOW_PASS
}

/**
 * Single parametric EQ band configuration.
 */
data class EqBand(
    val type: FilterType = FilterType.PEAKING,
    val frequencyHz: Float = 1000f,
    val gainDb: Float = 0f,
    val qFactor: Float = 1.0f,
    val enabled: Boolean = true
)

/**
 * Compressor circuit model. Each type has its own knee, attack/release
 * behaviour and ratio personality rather than only a reskinned label.
 */
enum class CompressorType(val displayName: String, val character: String) {
    VCA("Studio VCA", "Clean, fast and modern"),
    LA_2A("LA-2A Opto", "Slow, smooth opto leveling"),
    FET_1176("1176 FET", "Fast, aggressive FET punch"),
    TUBE_TECH("Tube-Tech CL 1B", "Silky vari-mu tube glue")
}

/**
 * 4-Band Parametric EQ settings plus dedicated high-pass and low-pass filters.
 * The HPF/LPF run before the four musical bands.
 */
data class EqConfig(
    val enabled: Boolean = true,
    val band1: EqBand = EqBand(FilterType.LOW_SHELF, 100f, 0f, 0.707f),
    val band2: EqBand = EqBand(FilterType.PEAKING, 350f, 0f, 1.2f),
    val band3: EqBand = EqBand(FilterType.PEAKING, 2500f, 0f, 1.2f),
    val band4: EqBand = EqBand(FilterType.HIGH_SHELF, 8500f, 0f, 0.707f),
    val highPass: EqBand = EqBand(FilterType.HIGH_PASS, 30f, 0f, 0.707f, enabled = false),
    val lowPass: EqBand = EqBand(FilterType.LOW_PASS, 18000f, 0f, 0.707f, enabled = false)
)

/**
 * Stereo dynamic compressor configuration.
 */
data class CompressorConfig(
    val enabled: Boolean = false,
    val thresholdDb: Float = -16f,
    val ratio: Float = 3.5f,
    val attackMs: Float = 15f,
    val releaseMs: Float = 120f,
    val kneeWidthDb: Float = 4f,
    val makeupGainDb: Float = 2f,
    val type: CompressorType = CompressorType.VCA
) {
    /**
     * Applies the time constants and knee that belong to a circuit model while
     * keeping the user's threshold and makeup gain.
     */
    fun withType(newType: CompressorType): CompressorConfig = when (newType) {
        CompressorType.VCA -> copy(
            type = newType,
            ratio = ratio.coerceIn(1f, 16f),
            attackMs = 10f,
            releaseMs = 100f,
            kneeWidthDb = 4f
        )
        CompressorType.LA_2A -> copy(
            type = newType,
            ratio = 3f,
            attackMs = 10f,
            releaseMs = 120f,
            kneeWidthDb = 8f
        )
        CompressorType.FET_1176 -> copy(
            type = newType,
            ratio = ratio.coerceAtLeast(4f).coerceAtMost(20f),
            attackMs = 0.5f,
            releaseMs = 80f,
            kneeWidthDb = 0f
        )
        CompressorType.TUBE_TECH -> copy(
            type = newType,
            ratio = 2.5f,
            attackMs = 3f,
            releaseMs = 150f,
            kneeWidthDb = 6f
        )
    }
}

/**
 * Saturation circuit flavour. The drive/warmth/mix controls stay shared, but
 * each model shapes harmonics differently.
 */
enum class SaturationType(val displayName: String, val character: String) {
    TAPE("Tape", "Soft tape compression and HF smoothing"),
    TUBE("Tube", "Asymmetric second-harmonic warmth"),
    TRANSFORMER("Transformer", "Iron weight and low-mid colour"),
    CONSOLE("Console", "Tight op-amp clip")
}

/**
 * Master limiter configuration.
 */
data class MasterLimiterConfig(
    val enabled: Boolean = true,
    val ceilingDb: Float = -0.3f,
    val thresholdDb: Float = -1.0f,
    val releaseMs: Float = 50f
)

/**
 * Master algorithmic reverb configuration.
 */
data class ReverbConfig(
    val enabled: Boolean = true,
    val roomSize: Float = 0.6f,
    val damping: Float = 0.4f,
    val stereoWidth: Float = 0.8f,
    val returnLevel: Float = 0.7f,
    val preDelayMs: Float = 20.0f,      // 0 to 200 ms pre-delay
    val decayTimeSec: Float = 2.2f,     // 0.5 to 10.0 s RT60 decay
    val lowCutHz: Float = 150.0f,       // Abbey Road low-cut filter (20 to 1000 Hz)
    val highCutHz: Float = 8000.0f      // Damping high-cut filter (1000 to 20000 Hz)
)

/**
 * Fade curve mathematics for smooth crossfades and non-destructive fades.
 */
enum class FadeCurve(val displayName: String) {
    EQUAL_POWER("Equal Power (-3dB)"),
    LINEAR("Linear"),
    EXPONENTIAL("Exponential"),
    S_CURVE("S-Curve")
}

/**
 * Nondestructive Audio Clip referencing audio by frame offsets.
 * Source media is never modified.
 */
data class AudioClip(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourcePath: String,
    val sourceStartFrame: Long = 0L,
    val lengthFrames: Long,
    val timelineStartFrame: Long = 0L,
    val gainLinear: Float = 1.0f,
    val fadeInFrames: Long = 0L,
    val fadeOutFrames: Long = 0L,
    val fadeInCurve: FadeCurve = FadeCurve.EQUAL_POWER,
    val fadeOutCurve: FadeCurve = FadeCurve.EQUAL_POWER,
    val trackIndex: Int
) {
    val gainDb: Float
        get() = if (gainLinear <= 0.0001f) -60f else (20.0 * Math.log10(gainLinear.toDouble())).toFloat()

    fun withGainDb(db: Float): AudioClip =
        copy(gainLinear = if (db <= -60f) 0f else Math.pow(10.0, db / 20.0).toFloat())
}

/**
 * A single automation breakpoint.
 */
data class AutomationPoint(
    val frame: Long,
    val value: Float
)

/**
 * Automation lane for a track parameter.
 */
data class AutomationLane(
    val parameter: String, // "gainDb", "pan", etc.
    val points: List<AutomationPoint> = emptyList(),
    val enabled: Boolean = false
)

/**
 * Tape / Tube Saturation configuration.
 */
data class SaturationConfig(
    val enabled: Boolean = false,
    val driveDb: Float = 0.0f, // 0 to +24 dB
    val warmth: Float = 0.5f,  // Asymmetry & second harmonic warmth
    val mix: Float = 1.0f,     // Dry/Wet blend
    val type: SaturationType = SaturationType.TAPE
) {
    fun withType(newType: SaturationType): SaturationConfig = copy(type = newType)
}

/**
 * Ping-Pong / Stereo Delay with sidechain ducking.
 */
data class DuckingDelayConfig(
    val enabled: Boolean = false,
    val timeMs: Float = 350f,             // Delay time in ms (e.g. 50ms - 1000ms)
    val feedback: Float = 0.35f,          // 0.0 to 0.85
    val mix: Float = 0.25f,               // Wet level
    val duckingEnabled: Boolean = true,   // Ducks echoes while dry audio plays
    val duckingThresholdDb: Float = -24f, // Dry signal threshold to trigger ducking
    val duckingDepthDb: Float = -12f,     // Attenuation applied to delay tail while speaking
    val duckingReleaseMs: Float = 180f    // Bloom return time after phrase stops
)

/**
 * Per-channel brickwall limiter.
 */
data class ChannelLimiterConfig(
    val enabled: Boolean = false,
    val ceilingDb: Float = -0.5f,
    val releaseMs: Float = 30f
)

/**
 * Stereo Chorus / Flanger / Vocal Doubler effect.
 * Creates lush, thick stereo soundscapes and adapts doubled vocal/synth layers.
 */
data class ChorusConfig(
    val enabled: Boolean = false,
    val rateHz: Float = 1.2f,        // Modulation LFO rate 0.1 to 8.0 Hz
    val depthMs: Float = 3.5f,       // Delay modulation depth 0.5 to 12.0 ms
    val feedback: Float = 0.20f,     // Resonance / shimmer feedback 0.0 to 0.7
    val stereoSpread: Float = 0.85f, // Left/Right LFO phase offset for wide stereo
    val mix: Float = 0.40f           // Wet/Dry blend
)

/**
 * USB Audio Interface / DAC hardware information.
 */
data class UsbAudioDeviceInfo(
    val id: Int,
    val productName: String,
    val typeName: String, // "USB Audio Interface", "USB Headset", etc.
    val isInput: Boolean,
    val isOutput: Boolean,
    val sampleRates: List<Int> = listOf(44100, 48000, 96000),
    val channelCounts: List<Int> = listOf(2),
    val isSelected: Boolean = false
)

/**
 * Audio Track in the 16-track DAW session.
 */
data class TrackData(
    val index: Int,
    val name: String,
    val colorHex: Long,
    val gainDb: Float = 0.0f,
    val pan: Float = 0.0f, // -1.0 (Left) to +1.0 (Right), 0.0 = Center
    val isMuted: Boolean = false,
    val isSoloed: Boolean = false,
    val isArmedForRecording: Boolean = false,
    val reverbSendLevel: Float = 0.0f, // 0.0 to 1.0
    val stemCategory: StemCategory = StemCategory.UNKNOWN,
    val eqConfig: EqConfig = EqConfig(),
    val saturationConfig: SaturationConfig = SaturationConfig(),
    val compressorConfig: CompressorConfig = CompressorConfig(),
    val chorusConfig: ChorusConfig = ChorusConfig(),
    val delayConfig: DuckingDelayConfig = DuckingDelayConfig(),
    val limiterConfig: ChannelLimiterConfig = ChannelLimiterConfig(),
    val resonanceSmootherConfig: ResonanceSmootherConfig = ResonanceSmootherConfig(),
    val vocalRiderConfig: VocalRiderConfig = VocalRiderConfig(),
    val autoPitchConfig: AutoPitchConfig = AutoPitchConfig(),
    val noiseReductionConfig: NoiseReductionConfig = NoiseReductionConfig(),
    /**
     * Ordered studio plugin chain. When it holds at least one enabled module it
     * replaces the fixed legacy channel strip above, so templates can build real
     * signal chains (Neve -> 1176 -> LA-2A -> de-ess -> air) instead of flags.
     */
    val pluginChain: List<com.overlay.audio.plugins.PluginSlot> = emptyList(),
    val clips: List<AudioClip> = emptyList(),
    val automationLanes: List<AutomationLane> = emptyList(),
    // Group / bus routing
    val outputBusId: String = "master",
    val busIndex: Int = -1,
    val outputBus: Int = -1,
    // Sidechain routing (index of source track whose signal keys this track's dynamics)
    val sidechainSourceIndex: Int = -1,
    val sidechainSource: Int = -1,
    val sidechainTrackIndex: Int = -1,
    // Aux sends / returns
    val sends: Map<String, Float> = emptyMap(),
    val auxSends: Map<String, Float> = emptyMap(),
    val sendLevels: List<Float> = emptyList(),
    val reverbSend2Level: Float = 0.0f
) {
    val gainLinear: Float
        get() = if (gainDb <= -60f) 0f else Math.pow(10.0, (gainDb / 20.0)).toFloat()
}

/**
 * A mix bus / group / aux send-return channel.
 */
data class BusData(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "Bus",
    val gainDb: Float = 0.0f,
    val pan: Float = 0.0f,
    val isMuted: Boolean = false,
    val isSoloed: Boolean = false,
    val reverbSendLevel: Float = 0.0f,
    val outputBusId: String = "master",
    /** Ordered group processing (drum bus glue, vocal bus, mix bus...). */
    val pluginChain: List<com.overlay.audio.plugins.PluginSlot> = emptyList()
) {
    val gainLinear: Float
        get() = if (gainDb <= -60f) 0f else Math.pow(10.0, (gainDb / 20.0)).toFloat()
}

// Aliases for common naming conventions
typealias AudioBus = BusData
typealias MixBus = BusData
typealias GroupBus = BusData
typealias AuxBus = BusData
typealias SendReturn = BusData
typealias Bus = BusData

/**
 * Audio feature telemetry snapshot per track.
 */
data class TrackFeatures(
    val trackIndex: Int,
    val peakDb: Float = -60f,
    val rmsDb: Float = -60f,
    val integratedLufs: Float = -60f,
    val subEnergy: Float = 0f,     // < 60 Hz
    val lowEnergy: Float = 0f,     // 60 - 250 Hz
    val midEnergy: Float = 0f,     // 250 - 2000 Hz
    val highMidEnergy: Float = 0f, // 2000 - 6000 Hz
    val airEnergy: Float = 0f,     // > 6000 Hz
    val crestFactorDb: Float = 0f, // Peak - RMS
    val spectralCentroidHz: Float = 1000f,
    val classifiedStem: StemCategory = StemCategory.UNKNOWN,
    val neuralResult: com.example.audio.ai.LocalNeuralInferenceResult? = null
)

/**
 * Diagnostic metrics for audio engine health.
 */
data class AudioTelemetry(
    val callbackMeanMs: Float = 0f,
    val callbackP50Ms: Float = 0f,
    val callbackP95Ms: Float = 0f,
    val callbackP99Ms: Float = 0f,
    val bufferUnderruns: Int = 0,
    val cpuLoadPercent: Float = 0f,
    val activeVoices: Int = 0,
    val sampleRate: Int = 48000,
    val bufferSizeFrames: Int = 512,
    val latencyCalibrationMs: Float = 0f
)

/**
 * Suggestion produced by AI Assistant.
 */
data class MixSuggestion(
    val id: String = UUID.randomUUID().toString(),
    val trackIndex: Int,
    val trackName: String,
    val category: String, // "Gain Staging", "Masking EQ", "Dynamic Control", "Mastering"
    val parameter: String,
    val currentValue: String,
    val proposedValue: String,
    val reason: String,
    val confidence: Float, // 0.0 to 1.0
    val targetRevision: Long
)

/**
 * Embedded NC Phone Beat source package (ncbeat.v1) kept with the session so NC Studio
 * can show where the material came from and hand it back to NC Phone Beat later.
 * Optional: sessions written before it existed must still load.
 */
data class BeatSource(
    val schema: String = "ncbeat.v1",
    val appVersion: String = "",
    val patternName: String = "",
    val tempoBpm: Float = 120f,
    val patternJson: String = "",
    val mixerJson: String = "",
    val exportedAt: String = ""
)

/**
 * Complete versioned project session schema.
 */
data class ProjectSession(
    val version: Int = 1,
    val projectName: String = "NC Studio Project",
    val revision: Long = 1L,
    val sampleRate: Int = 48000,
    val tempoBpm: Float = 120f,
    val totalLengthFrames: Long = 48000L * 16L, // 16 seconds default loop
    val masterGainDb: Float = 0.0f,
    val loopEnabled: Boolean = true,
    val loopStartFrame: Long = 0L,
    val loopEndFrame: Long = 48000L * 16L,
    val latencyOffsetMs: Float = 32.5f,
    val masterLimiter: MasterLimiterConfig = MasterLimiterConfig(),
    val masterReverb: ReverbConfig = ReverbConfig(),
    /** Ordered mix bus chain: tape -> EQ -> bus comp -> limiter, in that order. */
    val masterPluginChain: List<com.overlay.audio.plugins.PluginSlot> = emptyList(),
    val tracks: List<TrackData> = emptyList(),
    val buses: List<BusData> = emptyList(),
    val groups: List<BusData> = emptyList(),
    val auxBuses: List<BusData> = emptyList(),
    val sendReturns: List<BusData> = emptyList(),
    val beatSource: BeatSource? = null,
    /**
     * User-supplied A/B reference track (a commercial mix to compare against),
     * with its measured profile. Optional: sessions written before it existed
     * must still load, and it is never bundled with the app.
     */
    val reference: com.example.audio.reference.ReferenceTrackConfig? = null
) {
    val masterGainLinear: Float
        get() = if (masterGainDb <= -60f) 0f else Math.pow(10.0, (masterGainDb / 20.0)).toFloat()
}

/**
 * Audio hardware buffer size preset with associated hardware latency.
 */
enum class BufferSizePreset(val frames: Int, val label: String, val latencyMs: Float) {
    ULTRA_LOW(128, "128 frames (2.7 ms)", 2.67f),
    LOW(256, "256 frames (5.3 ms)", 5.33f),
    BALANCED(512, "512 frames (10.7 ms)", 10.67f),
    RELAXED(1024, "1024 frames (21.3 ms)", 21.33f)
}

/**
 * Metering ballistics standards.
 */
enum class MeterBallistics(val displayName: String, val description: String) {
    PEAK_HOLD("True Peak", "Instantaneous sample peak with hold indicator"),
    RMS("RMS Average", "Root Mean Square averaged over 300 ms"),
    LUFS("EBU R128 / LUFS", "Perceptual loudness matching broadcast standards")
}

/**
 * USB Audio Interface Routing Strategy.
 */
enum class UsbRoutingMode(val displayName: String, val description: String) {
    AUTO_DETECT("Auto-Detect & Switch", "Automatically route playback to Type-C DAC when plugged in"),
    ALWAYS_USB("Prefer USB Interface", "Always target external soundcard when available"),
    BUILT_IN_ONLY("Force Built-in Audio", "Prevent USB hardware routing and use phone speaker/DAC")
}

/**
 * Pro Tools style timeline edit mode.
 */
enum class EditMode(val displayName: String, val description: String) {
    SLIP("Slip", "Clips land exactly where you drag them, sample-accurate"),
    GRID("Grid", "Clips snap to the bar, beat or 1/16 grid"),
    SHUFFLE("Shuffle", "Clips snap to their neighbours and later clips close the gap"),
    SPOT("Spot", "Clips only land where you drop them, then report the time")
}

/**
 * Timeline tool, mirroring a Pro Tools toolbar: the tool decides what a drag on
 * a clip means before the gesture starts.
 */
enum class TimelineTool(val displayName: String, val description: String) {
    SELECTOR("Selector", "Tap to select a clip; drag to make a time selection"),
    GRABBER("Grabber", "Drag clips straight away, with snapping"),
    TRIM("Trim", "Drag near a clip edge to trim start or end"),
    SMART("Smart", "Edge zones trim, the body drags, taps select")
}

/**
 * How a Strip Silence pass rewrites the selected clips. Both modes use the
 * shared `nc.sample-edit.v1` silence detector; only the clip layout differs.
 */
enum class StripSilenceMode(val displayName: String, val description: String) {
    TRIM_EDGES("Trim edges", "Trim only the silent head and tail; keep one clip"),
    SPLIT_REGIONS("Split out silence", "Keep one clip per loud region and leave gaps between them")
}

/**
 * A Pro Tools style edit (time) selection: the range operations act on.
 */
data class EditSelection(
    val trackIndex: Int,
    val startFrame: Long,
    val endFrame: Long
) {
    val lengthFrames: Long
        get() = (endFrame - startFrame).coerceAtLeast(0L)

    fun containsFrame(frame: Long): Boolean = frame in startFrame until endFrame
}

/**
 * Vocal Resonance Smoother (Dynamic Formant Tamer & De-Esser) configuration.
 */
data class ResonanceSmootherConfig(
    val enabled: Boolean = false,
    val frequencyHz: Float = 3200f, // 2 kHz to 8 kHz resonance / harshness zone
    val thresholdDb: Float = -18f,
    val depthDb: Float = -6.0f,     // Dynamic attenuation when resonance peaks
    val qFactor: Float = 4.0f       // Narrow notch Q
)

/**
 * Vocal Rider (Automatic Volume/Gain Leveler) configuration.
 */
data class VocalRiderConfig(
    val enabled: Boolean = false,
    val targetLufs: Float = -18.0f, // Target vocal level
    val maxGainDb: Float = +6.0f,   // Maximum upward riding boost
    val minGainDb: Float = -6.0f,   // Maximum downward riding cut
    val sensitivity: Float = 0.5f   // Riding response speed
)

/**
 * AutoPitch / monophonic pitch correction configuration.
 */
data class AutoPitchConfig(
    val enabled: Boolean = false,
    val correctionStrength: Float = 1.0f
)

/**
 * Noise reduction configuration.
 */
data class NoiseReductionConfig(
    val enabled: Boolean = false,
    val thresholdDb: Float = -40f,
    val reductionDb: Float = -12f
)

/**
 * Magnetic grid snapping resolution.
 */
enum class GridResolution(val displayName: String, val frames: Long) {
    BAR("1 Bar", 19200L),
    BEAT("1 Beat (1/4)", 4800L),
    SIXTEENTH("1/16 Note", 1200L),
    CLIP_BOUNDARIES("Clip Boundaries", 0L)
}

/**
 * AI Mixing Studio sonic target profile.
 */
enum class AiMixProfile(val displayName: String, val description: String) {
    BALANCED("Balanced Commercial", "Modern streaming master (-14 LUFS, clean stereo field)"),
    WARM_VINTAGE("Warm Vintage Analog", "Gentle tube saturation, tape compression & high roll-off"),
    PUNCHY_MODERN("Punchy Modern Club", "Aggressive transient snap, forward vocals, heavy sub bass"),
    TRANSPARENT("Transparent Acoustic", "Subtle level balancing with non-destructive corrective EQ")
}

/**
 * Global DAW configuration state.
 */
data class DawSettings(
    val bufferSize: BufferSizePreset = BufferSizePreset.BALANCED,
    val sampleRateHz: Int = 48000,
    val latencyCompensationMs: Float = 32.5f,
    val usbRoutingMode: UsbRoutingMode = UsbRoutingMode.AUTO_DETECT,
    val meterBallistics: MeterBallistics = MeterBallistics.PEAK_HOLD,
    val peakHoldDecayFast: Boolean = false,
    val enableInputMonitoring: Boolean = false,
    val countInBars: Int = 1,
    val metronomeEnabled: Boolean = false,
    val metronomeVolumeDb: Float = -6.0f,
    val autoSaveMinutes: Int = 2,
    val enableCrashRecovery: Boolean = true,
    val enableHapticFeedback: Boolean = true,
    val aiMixProfile: AiMixProfile = AiMixProfile.BALANCED,
    val aiStemAutoClassification: Boolean = true,
    val enableLocalNeuralModel: Boolean = true,
    val localNeuralModelMode: String = "ON_DEVICE_QUANTIZED", // "ON_DEVICE_QUANTIZED", "SPECTRAL_RULES", "HYBRID_ENSEMBLE"
    val customOllamaUrl: String = "http://10.0.2.2:11434",
    val autoDetectLocalModelsOnStartup: Boolean = true,
    val activeLocalModelName: String = "llama3.2:1b",
    val activeLocalModelProvider: String = "Ollama REST",
    val aiMaxEqBoostDb: Float = 6.0f,
    val highResolutionWaveforms: Boolean = true,
    val editMode: EditMode = EditMode.GRID,
    val isMagneticSnapEnabled: Boolean = true,
    val gridResolution: GridResolution = GridResolution.BEAT,
    val timelineTool: TimelineTool = TimelineTool.SMART,
    /** Fade length applied when adjacent clips are joined by a crossfade. */
    val crossfadeMs: Float = 10f
)
