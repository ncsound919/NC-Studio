package com.overlay.audio.plugins

import java.util.UUID

/**
 * Studio plugin system.
 *
 * This is the "real studio tool" layer that replaces the phone-app style
 * fixed channel strip. Every track owns an ordered [PluginChain]: an ordered
 * list of insert slots. Order matters - a Neve preamp before an 1176 sounds
 * different from an 1176 before a Neve - and every slot exposes the parameters
 * the corresponding hardware exposes.
 *
 * The module behaviours follow documented hardware specs (see
 * MIX_PLUGIN_RESEARCH.md): 1176 attack 20-800 us / release 50-1100 ms with
 * program-dependent release and an all-buttons-in circuit state, LA-2A ~3:1
 * optical leveling with a two-stage program-dependent release, SSL G bus
 * compressor 2/4/10:1 with a feedback VCA topology and multi-stage Auto
 * release, Pultec EQP-1A boost+atten curves, a fixed-12 kHz Neve 1073 shelf,
 * and tape head bump scaled by transport speed.
 */
enum class PluginCategory(val displayName: String) {
    GAIN("Gain & Utility"),
    FILTER("Filters & EQ"),
    DYNAMICS("Dynamics"),
    COLOR("Saturation & Tape"),
    SPACE("Reverb & Delay"),
    MODULATION("Modulation & Width"),
    LIMITER("Limiting"),
    PHONE("Phone Mic")
}

/**
 * Every available module. [blurb] is the one-line "why you would reach for it"
 * shown in the rack picker, mirroring the reference guides' cheat sheet.
 */
enum class PluginKind(
    val displayName: String,
    val category: PluginCategory,
    val blurb: String
) {
    TRIM("Trim", PluginCategory.GAIN, "Gain staging to the -18 dBFS analog sweet spot"),
    FILTER("HPF / LPF", PluginCategory.FILTER, "Rumble and fizz control before anything else"),
    LADDER_FILTER("Ladder Filter", PluginCategory.FILTER, "Resonant 4-pole lowpass with drive and self-oscillation"),
    NEVE_1073("Neve 1073 Pre/EQ", PluginCategory.FILTER, "Warmth, thickness, musical fixed-frequency EQ"),
    SSL_CHANNEL_EQ("SSL 4000 E Channel EQ", PluginCategory.FILTER, "Punchy, forward mids with variable-Q mid bands"),
    PULTEC_EQP1A("Pultec EQP-1A", PluginCategory.FILTER, "Weight and air, and the low-end boost+cut trick"),
    AIR_SHELF("Air Shelf", PluginCategory.FILTER, "Vocal presence lift at 10-12 kHz"),
    MID_SIDE_EQ("Mid-Side EQ", PluginCategory.MODULATION, "Widen by frequency: cut sides low, lift sides air"),
    FET_1176("1176 FET Compressor", PluginCategory.DYNAMICS, "Fast, aggressive peak control with FET edge"),
    OPTO_LA2A("LA-2A Opto Leveler", PluginCategory.DYNAMICS, "Slow, smooth optical leveling and tube warmth"),
    VCA_BUS_COMP("SSL Bus Compressor", PluginCategory.DYNAMICS, "Mix and drum bus glue with Auto release"),
    VARI_MU_COMP("Vari-Mu Tube Compressor", PluginCategory.DYNAMICS, "Fairchild-style program-dependent tube squash"),
    PARALLEL_COMP("Parallel Compressor", PluginCategory.DYNAMICS, "NY-style crush blended under the dry signal"),
    DE_ESSER("De-Esser", PluginCategory.DYNAMICS, "Split-band sibilance control at 5-8 kHz"),
    SIDECHAIN_DUCK("Sidechain Duck", PluginCategory.DYNAMICS, "Duck bass/808/pads against the kick"),
    SATURATION("Saturation", PluginCategory.COLOR, "Tape, tube, transformer or console harmonic color"),
    TAPE_MACHINE("Tape Machine", PluginCategory.COLOR, "Head bump, record EQ, bias and wow/flutter"),
    PLATE_REVERB("Plate Reverb", PluginCategory.SPACE, "Dense, bright EMT-style plate for vocals and snare"),
    HALL_REVERB("Hall Reverb", PluginCategory.SPACE, "Long, lush Lexicon-style hall behind the vocal"),
    ROOM_REVERB("Room Reverb", PluginCategory.SPACE, "Short drum ambience and glue without wash"),
    TEMPO_DELAY("Tempo Delay", PluginCategory.SPACE, "Filtered, ducked echoes locked to the grid"),
    CHORUS_WIDTH("Chorus / Width", PluginCategory.MODULATION, "Juno-style chorus and Haas widening"),
    TRUE_PEAK_LIMITER("True Peak Limiter", PluginCategory.LIMITER, "Inter-sample-safe ceiling for the mix bus"),
    CHANNEL_LIMITER("Channel Limiter", PluginCategory.LIMITER, "Catch peaks on a single channel"),
    PHONE_NOISE_REDUCTION("Phone Noise Reduction", PluginCategory.PHONE, "Downward-expanding noise gate for phone recordings, with wind and knock control"),
    PHONE_MIC_EMULATION("Phone Mic Emulation", PluginCategory.PHONE, "Transform phone mic to studio mic (EQ, proximity, self-noise, dynamic range)"),
    VOCAL_RIDER("Vocal Rider", PluginCategory.DYNAMICS, "Rides the vocal level into the chain so later dynamics see a consistent performance"),
    RESONANCE_SMOOTHER("Resonance Smoother", PluginCategory.DYNAMICS, "Dynamic narrow notch for harsh formants, without dulling the whole vocal")
}

/**
 * One parameter of a module: value range, unit and the choices a hardware
 * selector would offer (for example Neve mid centres or tape speed).
 */
data class PluginParam(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val choices: List<Pair<Float, String>> = emptyList()
) {
    val isChoice: Boolean get() = choices.isNotEmpty()

    fun clamp(value: Float): Float {
        // A NaN survives coerceIn and makes JSONObject.put throw on export, so
        // every non-finite value falls back to the documented default.
        if (!value.isFinite()) return default
        if (isChoice) {
            // Snap to the nearest documented selector position.
            return choices.minByOrNull { kotlin.math.abs(it.first - value) }?.first ?: default
        }
        return value.coerceIn(min, max)
    }

    fun labelFor(value: Float): String {
        if (!isChoice) return formatValue(value)
        return choices.minByOrNull { kotlin.math.abs(it.first - value) }?.second ?: formatValue(value)
    }

    private fun formatValue(value: Float): String {
        val rounded = when {
            kotlin.math.abs(value) >= 100f -> kotlin.math.round(value)
            else -> kotlin.math.round(value * 100f) / 100f
        }
        return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}$unit" else "$rounded$unit"
    }
}

/**
 * A single insert on a track, bus or the mix bus. [params] is keyed by
 * [PluginParam.key]; absent keys fall back to [PluginCatalog.defaultParams].
 */
data class PluginSlot(
    val id: String = UUID.randomUUID().toString(),
    val kind: PluginKind,
    val enabled: Boolean = true,
    val params: Map<String, Float> = emptyMap()
) {
    fun value(key: String): Float =
        params[key] ?: PluginCatalog.param(kind, key)?.default ?: 0f

    fun withParam(key: String, value: Float): PluginSlot {
        val descriptor = PluginCatalog.param(kind, key) ?: return this
        return copy(params = params + (key to descriptor.clamp(value)))
    }

    fun withDefaults(): PluginSlot {
        val defaults = PluginCatalog.defaultParams(kind)
        return copy(params = defaults + params.filterKeys { PluginCatalog.param(kind, it) != null })
    }

    /** Compact preset label, e.g. "1176 FET 4:1 fast". */
    fun shortLabel(): String = PluginCatalog.shortLabel(this)
}

/**
 * An ordered insert chain. Position 0 is the first module the audio passes
 * through, which is what makes chains sound like the reference mixes.
 */
data class PluginChain(val slots: List<PluginSlot> = emptyList()) {

    val isActive: Boolean get() = slots.any { it.enabled }

    fun enabledSlots(): List<PluginSlot> = slots.filter { it.enabled }

    fun add(slot: PluginSlot): PluginChain = copy(slots = slots + slot)

    fun remove(slotId: String): PluginChain = copy(slots = slots.filterNot { it.id == slotId })

    fun move(slotId: String, delta: Int): PluginChain {
        val index = slots.indexOfFirst { it.id == slotId }
        if (index < 0) return this
        val target = (index + delta).coerceIn(0, slots.lastIndex)
        if (target == index) return this
        val mutable = slots.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        return copy(slots = mutable)
    }

    fun toggle(slotId: String): PluginChain = copy(
        slots = slots.map { if (it.id == slotId) it.copy(enabled = !it.enabled) else it }
    )

    fun update(slotId: String, transform: (PluginSlot) -> PluginSlot): PluginChain = copy(
        slots = slots.map { if (it.id == slotId) transform(it) else it }
    )

    /**
     * Swaps the module in a slot without moving it. The slot id is preserved so
     * the rack keeps its position and any gain-reduction meter stays attached;
     * parameters that exist on both modules survive the swap (an EQ frequency
     * stays put when moving from an SSL to a Neve channel EQ).
     */
    fun replace(slotId: String, kind: PluginKind): PluginChain = update(slotId) { old ->
        val compatible = old.params
            .filterKeys { PluginCatalog.param(kind, it) != null }
            .toMutableMap()
        // The Neve and SSL channel EQs share a high-pass but spell the key
        // differently. Carry the value across so a swap does not silently reset
        // the low cut the engineer just set.
        fun carry(from: String, to: String) {
            val value = old.params[from] ?: return
            if (to !in compatible && PluginCatalog.param(kind, to) != null) {
                compatible[to] = value
            }
        }
        carry("hpfHz", "hpHz")
        carry("hpHz", "hpfHz")
        carry("outDb", "outputDb")
        carry("outputDb", "outDb")
        old.copy(
            kind = kind,
            params = PluginCatalog.defaultParams(kind) + compatible
        )
    }

    fun withSlots(replacement: List<PluginSlot>): PluginChain = copy(slots = replacement)
}

/**
 * Catalog of module parameters. Values mirror the documented hardware ranges
 * researched in MIX_PLUGIN_RESEARCH.md rather than arbitrary UI ranges.
 */
object PluginCatalog {

    private val specs: Map<PluginKind, List<PluginParam>> = mapOf(
        PluginKind.TRIM to listOf(
            PluginParam("gainDb", "Gain", -24f, 24f, 0f, " dB"),
            PluginParam("autoStage", "Auto-stage to -18", 0f, 1f, 0f, choices = choices(0f to "Off", 1f to "On"))
        ),
        PluginKind.FILTER to listOf(
            PluginParam("hpHz", "HPF", 0f, 800f, 0f, " Hz"),
            PluginParam("lpHz", "LPF", 0f, 20000f, 0f, " Hz")
        ),
        PluginKind.LADDER_FILTER to listOf(
            PluginParam("cutoffHz", "Cutoff", 20f, 20000f, 1200f, " Hz"),
            PluginParam("resonance", "Resonance", 0f, 20f, 4f),
            PluginParam("drive", "Drive", 0f, 10f, 0f),
            PluginParam("mode", "Family", 0f, 2f, 0f, choices = choices(0f to "Moog", 1f to "Juno", 2f to "Prophet"))
        ),
        PluginKind.NEVE_1073 to listOf(
            PluginParam("driveDb", "Preamp drive", 0f, 24f, 3f, " dB"),
            PluginParam("hpfHz", "HPF", 50f, 300f, 50f, " Hz"),
            PluginParam("lfHz", "LF", 35f, 220f, 60f, " Hz", choices(35f to "35 Hz", 60f to "60 Hz", 110f to "110 Hz", 220f to "220 Hz")),
            PluginParam("lfDb", "LF gain", -16f, 16f, 0f, " dB"),
            PluginParam("midHz", "Mid", 360f, 7200f, 1600f, " Hz", choices(360f to "360 Hz", 700f to "700 Hz", 1600f to "1.6 kHz", 3200f to "3.2 kHz", 4800f to "4.8 kHz", 7200f to "7.2 kHz")),
            PluginParam("midDb", "Mid gain", -18f, 18f, 0f, " dB"),
            PluginParam("hfDb", "HF 12 kHz", -16f, 16f, 0f, " dB"),
            PluginParam("outDb", "Output", -12f, 12f, 0f, " dB")
        ),
        PluginKind.SSL_CHANNEL_EQ to listOf(
            PluginParam("lfHz", "LF", 30f, 450f, 90f, " Hz"),
            PluginParam("lfDb", "LF gain", -18f, 18f, 0f, " dB"),
            PluginParam("lfBell", "LF shape", 0f, 1f, 0f, choices = choices(0f to "Shelf", 1f to "Bell")),
            PluginParam("lmfHz", "LMF", 200f, 2500f, 400f, " Hz"),
            PluginParam("lmfDb", "LMF gain", -18f, 18f, 0f, " dB"),
            PluginParam("lmfQ", "LMF Q", 0.5f, 4f, 1.0f),
            PluginParam("hmfHz", "HMF", 600f, 7000f, 2500f, " Hz"),
            PluginParam("hmfDb", "HMF gain", -18f, 18f, 0f, " dB"),
            PluginParam("hmfQ", "HMF Q", 0.5f, 4f, 1.0f),
            PluginParam("hfHz", "HF", 1500f, 16000f, 10000f, " Hz"),
            PluginParam("hfDb", "HF gain", -18f, 18f, 0f, " dB"),
            PluginParam("hfBell", "HF shape", 0f, 1f, 0f, choices = choices(0f to "Shelf", 1f to "Bell")),
            PluginParam("hpHz", "HPF 18 dB/oct", 0f, 300f, 0f, " Hz"),
            PluginParam("outDb", "Output", -18f, 18f, 0f, " dB")
        ),
        PluginKind.PULTEC_EQP1A to listOf(
            PluginParam("lowBoostHz", "Low boost", 20f, 100f, 60f, " Hz", choices(20f to "20 Hz", 30f to "30 Hz", 60f to "60 Hz", 100f to "100 Hz")),
            PluginParam("lowBoostDb", "Low boost amount", 0f, 13.5f, 0f, " dB"),
            PluginParam("lowAttenHz", "Low atten", 20f, 100f, 60f, " Hz", choices(20f to "20 Hz", 30f to "30 Hz", 60f to "60 Hz", 100f to "100 Hz")),
            PluginParam("lowAttenDb", "Low atten amount", 0f, 17.5f, 0f, " dB"),
            PluginParam("highBoostHz", "High boost", 3000f, 16000f, 10000f, " Hz", choices(3000f to "3 kHz", 4000f to "4 kHz", 5000f to "5 kHz", 8000f to "8 kHz", 10000f to "10 kHz", 12000f to "12 kHz", 16000f to "16 kHz")),
            PluginParam("highBoostDb", "High boost amount", 0f, 18f, 0f, " dB"),
            PluginParam("highAttenHz", "High atten", 5000f, 20000f, 10000f, " Hz", choices(5000f to "5 kHz", 10000f to "10 kHz", 20000f to "20 kHz")),
            PluginParam("highAttenDb", "High atten amount", 0f, 16f, 0f, " dB"),
            PluginParam("outDb", "Tube makeup", -6f, 12f, 0f, " dB")
        ),
        PluginKind.AIR_SHELF to listOf(
            PluginParam("freqHz", "Frequency", 6000f, 16000f, 11000f, " Hz"),
            PluginParam("gainDb", "Air", -6f, 9f, 2f, " dB")
        ),
        PluginKind.MID_SIDE_EQ to listOf(
            PluginParam("sideHpHz", "Side low cut", 0f, 400f, 120f, " Hz"),
            PluginParam("sideAirDb", "Side air", -6f, 6f, 0f, " dB"),
            PluginParam("width", "Width", 0.25f, 2f, 1f)
        ),
        PluginKind.FET_1176 to listOf(
            PluginParam("inputDb", "Input drive", -6f, 24f, 6f, " dB"),
            PluginParam("attackUs", "Attack", 20f, 800f, 200f, " us"),
            PluginParam("releaseMs", "Release", 50f, 1100f, 250f, " ms"),
            PluginParam("ratio", "Ratio", 4f, 20f, 4f, ":1", choices(4f to "4:1", 8f to "8:1", 12f to "12:1", 20f to "20:1")),
            PluginParam("allButtons", "All buttons in", 0f, 1f, 0f, choices = choices(0f to "Off", 1f to "British")),
            PluginParam("outputDb", "Output", -12f, 12f, 0f, " dB"),
            PluginParam("mix", "Mix", 0f, 1f, 1f)
        ),
        PluginKind.OPTO_LA2A to listOf(
            PluginParam("peakReduction", "Peak reduction", 0f, 10f, 4f),
            PluginParam("gainDb", "Gain", 0f, 24f, 6f, " dB"),
            PluginParam("limitMode", "Mode", 0f, 1f, 0f, choices = choices(0f to "Compress", 1f to "Limit")),
            PluginParam("mix", "Mix", 0f, 1f, 1f)
        ),
        PluginKind.VCA_BUS_COMP to listOf(
            PluginParam("thresholdDb", "Threshold", -30f, 0f, -10f, " dB"),
            PluginParam("ratio", "Ratio", 2f, 10f, 2f, ":1", choices(2f to "2:1", 4f to "4:1", 10f to "10:1")),
            PluginParam("attackMs", "Attack", 0.1f, 30f, 10f, " ms", choices(0.1f to "0.1 ms", 0.3f to "0.3 ms", 1f to "1 ms", 3f to "3 ms", 10f to "10 ms", 30f to "30 ms")),
            PluginParam("autoRelease", "Auto release", 0f, 1f, 1f, choices = choices(0f to "Fixed", 1f to "Auto")),
            PluginParam("releaseMs", "Release", 100f, 1200f, 300f, " ms", choices(100f to "100 ms", 300f to "300 ms", 600f to "600 ms", 1200f to "1.2 s")),
            PluginParam("makeupDb", "Makeup", 0f, 12f, 0f, " dB"),
            PluginParam("mix", "Mix", 0f, 1f, 1f)
        ),
        PluginKind.VARI_MU_COMP to listOf(
            PluginParam("driveDb", "Input drive", 0f, 18f, 4f, " dB"),
            PluginParam("thresholdDb", "DC threshold", -30f, 0f, -12f, " dB"),
            PluginParam("ratio", "Ratio", 1.5f, 6f, 2.5f, ":1"),
            PluginParam("timeConstant", "Time constant", 0f, 5f, 2f, choices = choices(0f to "TC1 0.2ms/0.3s", 1f to "TC2 0.2ms/0.8s", 2f to "TC3 0.4ms/2s", 3f to "TC4 0.8ms/5s", 4f to "TC5 prog 2/10s", 5f to "TC6 prog 0.3/25s")),
            PluginParam("makeupDb", "Makeup", -12f, 12f, 0f, " dB"),
            PluginParam("mix", "Mix", 0f, 1f, 1f)
        ),
        PluginKind.PARALLEL_COMP to listOf(
            PluginParam("thresholdDb", "Threshold", -40f, -4f, -22f, " dB"),
            PluginParam("ratio", "Ratio", 4f, 20f, 8f, ":1"),
            PluginParam("attackMs", "Attack", 1f, 30f, 5f, " ms"),
            PluginParam("releaseMs", "Release", 30f, 400f, 120f, " ms"),
            PluginParam("blend", "Blend", 0f, 1f, 0.35f),
            PluginParam("makeupDb", "Makeup", 0f, 18f, 6f, " dB")
        ),
        PluginKind.DE_ESSER to listOf(
            PluginParam("freqHz", "Frequency", 4000f, 12000f, 6000f, " Hz"),
            PluginParam("thresholdDb", "Threshold", -40f, 0f, -22f, " dB"),
            PluginParam("amountDb", "Max reduction", 0f, 12f, 3f, " dB"),
            PluginParam("split", "Mode", 0f, 1f, 1f, choices = choices(0f to "Wideband", 1f to "Split band"))
        ),
        PluginKind.SIDECHAIN_DUCK to listOf(
            PluginParam("thresholdDb", "Threshold", -40f, 0f, -20f, " dB"),
            PluginParam("ratio", "Ratio", 2f, 20f, 6f, ":1"),
            PluginParam("attackMs", "Attack", 1f, 50f, 5f, " ms"),
            PluginParam("releaseMs", "Release", 50f, 600f, 120f, " ms"),
            PluginParam("floorDb", "Duck floor", -24f, 0f, -12f, " dB")
        ),
        PluginKind.SATURATION to listOf(
            PluginParam("type", "Model", 0f, 3f, 0f, choices = choices(0f to "Tape", 1f to "Tube", 2f to "Transformer", 3f to "Console")),
            PluginParam("driveDb", "Drive", 0f, 24f, 6f, " dB"),
            PluginParam("warmth", "Warmth", 0f, 1f, 0.4f),
            PluginParam("mix", "Mix", 0f, 1f, 1f),
            PluginParam("oversample", "Oversample", 1f, 4f, 2f, "x", choices(1f to "Off", 2f to "2x", 4f to "4x"))
        ),
        PluginKind.TAPE_MACHINE to listOf(
            PluginParam("driveDb", "Record level", -6f, 12f, 0f, " dB"),
            PluginParam("speedIps", "Tape speed", 7.5f, 30f, 15f, " ips", choices(7.5f to "7.5 ips", 15f to "15 ips", 30f to "30 ips")),
            PluginParam("bias", "Bias", -1f, 1f, 0f, choices = choices(-1f to "Under", 0f to "Optimized", 1f to "Over")),
            PluginParam("wowFlutter", "Wow / flutter", 0f, 1f, 0.35f),
            PluginParam("noise", "Tape noise", 0f, 1f, 0f, choices = choices(0f to "Off", 1f to "On")),
            PluginParam("outDb", "Output", -6f, 6f, 0f, " dB")
        ),
        PluginKind.PLATE_REVERB to reverbParams(defaultDecay = 1.4f, defaultPredelay = 10f, defaultLp = 9000f),
        PluginKind.HALL_REVERB to reverbParams(defaultDecay = 2.4f, defaultPredelay = 35f, defaultLp = 6500f),
        PluginKind.ROOM_REVERB to reverbParams(defaultDecay = 0.8f, defaultPredelay = 8f, defaultLp = 7500f),
        PluginKind.TEMPO_DELAY to listOf(
            PluginParam("division", "Division", 0f, 6f, 1f, choices = choices(0f to "1/16", 1f to "1/8", 2f to "1/8 dot", 3f to "1/4", 4f to "1/4 dot", 5f to "1/2", 6f to "Slapback")),
            PluginParam("timeMs", "Free time", 40f, 800f, 110f, " ms"),
            PluginParam("feedback", "Feedback", 0f, 0.85f, 0.25f),
            PluginParam("hpHz", "Feedback HPF", 60f, 800f, 200f, " Hz"),
            PluginParam("lpHz", "Feedback LPF", 1500f, 12000f, 4000f, " Hz"),
            PluginParam("ducking", "Ducking", 0f, 1f, 1f, choices = choices(0f to "Off", 1f to "On")),
            PluginParam("pingPong", "Ping-pong", 0f, 1f, 0f, choices = choices(0f to "Stereo", 1f to "Ping-pong")),
            PluginParam("mix", "Mix", 0f, 1f, 0.25f)
        ),
        PluginKind.CHORUS_WIDTH to listOf(
            PluginParam("mode", "Mode", 0f, 2f, 1f, choices = choices(0f to "Chorus I", 1f to "Chorus II", 2f to "Haas / Doubler")),
            PluginParam("rateHz", "Rate", 0.1f, 6f, 0.8f, " Hz"),
            PluginParam("depthMs", "Depth", 0.5f, 12f, 3.5f, " ms"),
            PluginParam("width", "Width", 0f, 1f, 0.6f),
            PluginParam("mix", "Mix", 0f, 1f, 0.35f)
        ),
        PluginKind.TRUE_PEAK_LIMITER to listOf(
            PluginParam("ceilingDb", "Ceiling", -6f, 0f, -1f, " dBTP"),
            PluginParam("releaseMs", "Release", 10f, 1000f, 120f, " ms"),
            PluginParam("oversample", "Oversample", 1f, 4f, 4f, "x", choices(1f to "Off", 2f to "2x", 4f to "4x"))
        ),
        PluginKind.CHANNEL_LIMITER to listOf(
            PluginParam("ceilingDb", "Ceiling", -12f, 0f, -0.5f, " dB"),
            PluginParam("releaseMs", "Release", 5f, 300f, 30f, " ms")
        ),
        PluginKind.PHONE_NOISE_REDUCTION to listOf(
            PluginParam("thresholdDb", "Threshold", -70f, -10f, -35f, " dB"),
            PluginParam("reductionDb", "Reduction", 0f, 24f, 12f, " dB"),
            PluginParam("smoothingMs", "Smoothing", 10f, 500f, 100f, " ms"),
            PluginParam("windDetection", "Wind detect", 0f, 1f, 1f, choices = choices(0f to "Off", 1f to "On")),
            PluginParam("handlingDetection", "Handling detect", 0f, 1f, 1f, choices = choices(0f to "Off", 1f to "On"))
        ),
        PluginKind.PHONE_MIC_EMULATION to listOf(
            PluginParam("targetMic", "Target mic", 0f, 2f, 0f, choices = choices(0f to "U87", 1f to "SM7B", 2f to "C414")),
            PluginParam("distanceMeters", "Distance", 0.1f, 2f, 0.3f, " m"),
            PluginParam("proximityAmount", "Proximity", 0f, 2f, 1f),
            PluginParam("selfNoiseAmount", "Self-noise", 0f, 1f, 0.5f),
            PluginParam("dynamicRangeExpansion", "Dyn range", 0f, 1f, 0.5f)
        ),
        PluginKind.VOCAL_RIDER to listOf(
            PluginParam("targetLufs", "Target level", -30f, -8f, -18f, " LUFS"),
            PluginParam("maxGainDb", "Max boost", 0f, 12f, 5f, " dB"),
            PluginParam("minGainDb", "Max cut", -12f, 0f, -5f, " dB"),
            PluginParam("sensitivity", "Sensitivity", 0f, 1f, 0.55f)
        ),
        PluginKind.RESONANCE_SMOOTHER to listOf(
            PluginParam("frequencyHz", "Frequency", 1000f, 8000f, 3200f, " Hz"),
            PluginParam("thresholdDb", "Threshold", -40f, 0f, -20f, " dB"),
            PluginParam("depthDb", "Max depth", 0f, 18f, 6f, " dB"),
            PluginParam("qFactor", "Q", 1f, 12f, 4f)
        )
    )

    private fun choices(vararg entries: Pair<Float, String>): List<Pair<Float, String>> = entries.toList()

    private fun reverbParams(defaultDecay: Float, defaultPredelay: Float, defaultLp: Float): List<PluginParam> = listOf(
        PluginParam("decaySec", "Decay", 0.3f, 6f, defaultDecay, " s"),
        PluginParam("preDelayMs", "Pre-delay", 0f, 120f, defaultPredelay, " ms"),
        PluginParam("hpHz", "Low cut", 40f, 600f, 200f, " Hz"),
        PluginParam("lpHz", "High cut", 2000f, 16000f, defaultLp, " Hz"),
        PluginParam("width", "Width", 0f, 1f, 0.9f),
        PluginParam("mix", "Mix", 0f, 1f, 0.25f)
    )

    fun params(kind: PluginKind): List<PluginParam> = specs[kind].orEmpty()

    fun param(kind: PluginKind, key: String): PluginParam? = specs[kind]?.firstOrNull { it.key == key }

    fun defaultParams(kind: PluginKind): Map<String, Float> =
        specs[kind].orEmpty().associate { it.key to it.default }

    fun defaultSlot(kind: PluginKind, enabled: Boolean = true): PluginSlot =
        PluginSlot(kind = kind, enabled = enabled, params = defaultParams(kind))

    /** Human summary used in chain lists, e.g. "1176 4:1 8 dB in". */
    fun shortLabel(slot: PluginSlot): String = when (slot.kind) {
        PluginKind.FET_1176 -> buildString {
            append("1176 ")
            append(params(slot.kind).first { it.key == "ratio" }.labelFor(slot.value("ratio")))
            if (slot.value("allButtons") >= 0.5f) append(" all-buttons")
        }
        PluginKind.OPTO_LA2A -> if (slot.value("limitMode") >= 0.5f) "LA-2A limit" else "LA-2A compress"
        PluginKind.VCA_BUS_COMP -> buildString {
            append("Bus ")
            append(params(slot.kind).first { it.key == "ratio" }.labelFor(slot.value("ratio")))
            append(if (slot.value("autoRelease") >= 0.5f) " auto" else " fixed")
        }
        PluginKind.NEVE_1073 -> "1073 drive ${slot.value("driveDb").toInt()} dB"
        PluginKind.PULTEC_EQP1A -> "EQP-1A"
        PluginKind.SATURATION -> "Sat " + params(slot.kind).first { it.key == "type" }.labelFor(slot.value("type"))
        PluginKind.TAPE_MACHINE -> "Tape " + params(slot.kind).first { it.key == "speedIps" }.labelFor(slot.value("speedIps"))
        PluginKind.DE_ESSER -> "De-ess ${slot.value("freqHz").toInt() / 1000}k"
        PluginKind.TEMPO_DELAY -> "Delay " + params(slot.kind).first { it.key == "division" }.labelFor(slot.value("division"))
        PluginKind.VOCAL_RIDER -> "Ride to ${slot.value("targetLufs").toInt()} LUFS"
        PluginKind.RESONANCE_SMOOTHER -> "Resonance ${slot.value("frequencyHz").toInt() / 1000}k"
        else -> slot.kind.displayName
    }
}
