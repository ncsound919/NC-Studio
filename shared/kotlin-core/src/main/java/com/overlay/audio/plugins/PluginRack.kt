package com.overlay.audio.plugins

/**
 * Builds DSP modules from chain slots. One place to add future modules.
 */
internal object RackModuleFactory {
    fun build(slot: PluginSlot, sampleRate: Float, tempoBpm: Float): RackModule? {
        val module = when (slot.kind) {
            PluginKind.TRIM -> TrimModule(sampleRate)
            PluginKind.FILTER -> FilterModule(sampleRate)
            PluginKind.LADDER_FILTER -> LadderFilterModule(sampleRate)
            PluginKind.NEVE_1073 -> Neve1073Module(sampleRate)
            PluginKind.SSL_CHANNEL_EQ -> SslChannelEqModule(sampleRate)
            PluginKind.PULTEC_EQP1A -> PultecEqp1aModule(sampleRate)
            PluginKind.AIR_SHELF -> AirShelfModule(sampleRate)
            PluginKind.MID_SIDE_EQ -> MidSideEqModule(sampleRate)
            PluginKind.FET_1176 -> Fet1176Module(sampleRate)
            PluginKind.OPTO_LA2A -> OptoLa2aModule(sampleRate)
            PluginKind.VCA_BUS_COMP -> VcaBusCompModule(sampleRate)
            PluginKind.VARI_MU_COMP -> VariMuCompModule(sampleRate)
            PluginKind.PARALLEL_COMP -> ParallelCompModule(sampleRate)
            PluginKind.DE_ESSER -> DeEsserModule(sampleRate)
            PluginKind.SIDECHAIN_DUCK -> SidechainDuckModule(sampleRate)
            PluginKind.SATURATION -> SaturationModule(sampleRate)
            PluginKind.TAPE_MACHINE -> TapeMachineModule(sampleRate)
            PluginKind.PLATE_REVERB -> AlgorithmicReverbModule(sampleRate, PluginKind.PLATE_REVERB)
            PluginKind.HALL_REVERB -> AlgorithmicReverbModule(sampleRate, PluginKind.HALL_REVERB)
            PluginKind.ROOM_REVERB -> AlgorithmicReverbModule(sampleRate, PluginKind.ROOM_REVERB)
            PluginKind.TEMPO_DELAY -> TempoDelayModule(sampleRate)
            PluginKind.CHORUS_WIDTH -> ChorusWidthModule(sampleRate)
            PluginKind.TRUE_PEAK_LIMITER -> TruePeakLimiterModule(sampleRate)
            PluginKind.CHANNEL_LIMITER -> ChannelLimiterModule(sampleRate)
            PluginKind.PHONE_NOISE_REDUCTION -> PhoneNoiseReductionModule(sampleRate)
            PluginKind.PHONE_MIC_EMULATION -> PhoneMicEmulationModule(sampleRate)
            PluginKind.VOCAL_RIDER -> VocalRiderModule(sampleRate)
            PluginKind.RESONANCE_SMOOTHER -> ResonanceSmootherModule(sampleRate)
        }
        module.configure(slot.withDefaults())
        module.setTempoBpm(tempoBpm)
        return module
    }
}

/**
 * An ordered insert chain for one track, bus or the mix bus.
 *
 * The rack rebuilds only when the chain structure changes; moving a parameter
 * re-configures the existing module in place, so filter and compressor state
 * survives and editing never clicks. Order is the order audio flows.
 */
class TrackPluginRack(private val sampleRate: Float, tempoBpm: Float = 120f) {

    private var tempo = tempoBpm
    private var modules: List<RackModule> = emptyList()
    private var moduleIds: List<String> = emptyList()
    private var chain: PluginChain = PluginChain()
    private val scratch = FloatArray(2)

    val isEmpty: Boolean get() = modules.isEmpty()
    val size: Int get() = modules.size
    val slotChain: PluginChain get() = chain

    /** Total reported latency, used to keep the rack honest about delay-comp. */
    val latencySamples: Int get() = modules.sumOf { it.latencySamples }

    fun setTempoBpm(bpm: Float) {
        tempo = bpm
        modules.forEach { it.setTempoBpm(bpm) }
    }

    fun setChain(newChain: PluginChain) {
        val sameShape = modules.size == newChain.slots.size &&
            modules.indices.all { newChain.slots[it].kind == modules[it].kind && newChain.slots[it].id == moduleIds[it] }
        if (sameShape) {
            modules.forEachIndexed { index, module -> module.configure(newChain.slots[index].withDefaults()) }
        } else {
            build(newChain)
        }
        chain = newChain
    }

    fun clear() {
        modules = emptyList()
        moduleIds = emptyList()
        chain = PluginChain()
    }

    private fun build(newChain: PluginChain) {
        val built = ArrayList<RackModule>(newChain.slots.size)
        val ids = ArrayList<String>(newChain.slots.size)
        for (slot in newChain.slots) {
            val module = RackModuleFactory.build(slot, sampleRate, tempo) ?: continue
            built.add(module)
            ids.add(slot.id)
        }
        modules = built
        moduleIds = ids
    }

    /** Runs one stereo frame through the chain in order. */
    fun processInto(inL: Float, inR: Float, out: FloatArray) {
        if (modules.isEmpty()) { out[0] = inL; out[1] = inR; return }
        out[0] = inL
        out[1] = inR
        for (module in modules) {
            module.processSampleInto(out[0], out[1], scratch)
            out[0] = scratch[0]
            out[1] = scratch[1]
        }
    }

    /** External key for sidechain modules in this rack. */
    fun setKey(l: Float, r: Float) {
        for (module in modules) module.setKey(l, r)
    }

    /** True when the rack contains a sidechain module that needs a key feed. */
    val hasSidechain: Boolean get() = modules.any { it.kind == PluginKind.SIDECHAIN_DUCK }

    val gainReductionDb: Float get() = modules.maxOfOrNull { it.gainReductionDb } ?: 0f

    val inputPeak: Float get() = modules.firstOrNull()?.inputPeakLevel ?: 0f
    val outputPeak: Float get() = modules.lastOrNull()?.outputPeakLevel ?: 0f

    fun reset() {
        modules.forEach { it.reset() }
    }

    /** Current gain reduction per slot, in chain order. */
    fun slotGainReductions(): FloatArray = FloatArray(modules.size) { modules[it].gainReductionDb }

    /** Per-slot readout for the rack UI (gain reduction per module). */
    fun readout(): List<PluginSlotReadout> = modules.mapIndexed { index, module ->
        PluginSlotReadout(
            slotId = moduleIds.getOrElse(index) { "" },
            kind = module.kind,
            displayName = chain.slots.getOrNull(index)?.shortLabel() ?: module.kind.displayName,
            enabled = chain.slots.getOrNull(index)?.enabled ?: true,
            gainReductionDb = module.gainReductionDb,
            hasMeter = module.kind in METERED_KINDS
        )
    }

    companion object {
        private val METERED_KINDS = setOf(
            PluginKind.FET_1176, PluginKind.OPTO_LA2A, PluginKind.VCA_BUS_COMP,
            PluginKind.VARI_MU_COMP, PluginKind.PARALLEL_COMP, PluginKind.DE_ESSER,
            PluginKind.SIDECHAIN_DUCK, PluginKind.TRUE_PEAK_LIMITER, PluginKind.CHANNEL_LIMITER,
            PluginKind.PHONE_NOISE_REDUCTION, PluginKind.PHONE_MIC_EMULATION
        )
    }
}

/** UI-facing snapshot of one rack slot. */
data class PluginSlotReadout(
    val slotId: String,
    val kind: PluginKind,
    val displayName: String,
    val enabled: Boolean,
    val gainReductionDb: Float,
    val hasMeter: Boolean
)
