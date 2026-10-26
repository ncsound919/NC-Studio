package com.example.audio.ai

import com.example.audio.model.CompressorType
import com.example.audio.model.EqBand
import com.example.audio.model.FilterType
import com.example.audio.model.MixSuggestion
import com.example.audio.model.SaturationType
import com.example.audio.model.ProjectSession
import com.example.audio.model.StemCategory
import com.example.audio.model.TrackData
import com.example.audio.model.TrackFeatures
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Feature extraction engine analyzing audio samples into spectral bands and dynamics.
 */
object AudioFeatureExtractor {

    fun extractFeatures(trackIndex: Int, samples: FloatArray, sampleRate: Int = 48000): TrackFeatures {
        if (samples.isEmpty()) return TrackFeatures(trackIndex = trackIndex)

        val totalSamples = samples.size
        var peakVal = 0.0f
        var sumSquares = 0.0
        var zeroCrossings = 0

        // Spectral band energy accumulators using simplified multi-rate filtering / FFT bins
        var subEnergyAcc = 0.0
        var lowEnergyAcc = 0.0
        var midEnergyAcc = 0.0
        var highMidEnergyAcc = 0.0
        var airEnergyAcc = 0.0

        var prevSample = 0.0f
        var weightedFreqSum = 0.0
        var totalMagSum = 0.0

        // Subsample for fast real-time analysis (process up to 48000 samples = 1 sec)
        val step = max(1, totalSamples / 48000)
        var count = 0

        var i = 0
        while (i < totalSamples) {
            val s = samples[i]
            val absS = abs(s)
            if (absS > peakVal) peakVal = absS
            sumSquares += (s * s)

            if ((prevSample < 0 && s >= 0) || (prevSample >= 0 && s < 0)) {
                zeroCrossings++
            }

            // Estimate instantaneous frequency difference (derivative)
            val diff = abs(s - prevSample)
            val approxFreq = (diff * sampleRate * 0.5f).coerceIn(20f, 20000f)

            weightedFreqSum += approxFreq * absS
            totalMagSum += absS

            when {
                approxFreq < 60f -> subEnergyAcc += (absS * 1.5)
                approxFreq < 250f -> lowEnergyAcc += absS
                approxFreq < 2000f -> midEnergyAcc += absS
                approxFreq < 6000f -> highMidEnergyAcc += absS
                else -> airEnergyAcc += absS
            }

            prevSample = s
            count++
            i += step
        }

        val rmsVal = if (count > 0) sqrt(sumSquares / count).toFloat() else 0.0f
        val peakDb = if (peakVal > 1e-5f) 20f * log10(peakVal) else -80f
        val rmsDb = if (rmsVal > 1e-5f) 20f * log10(rmsVal) else -80f
        val crestFactorDb = max(0f, peakDb - rmsDb)

        val totalBand = (subEnergyAcc + lowEnergyAcc + midEnergyAcc + highMidEnergyAcc + airEnergyAcc).coerceAtLeast(1e-5)
        val subFrac = (subEnergyAcc / totalBand).toFloat()
        val lowFrac = (lowEnergyAcc / totalBand).toFloat()
        val midFrac = (midEnergyAcc / totalBand).toFloat()
        val highMidFrac = (highMidEnergyAcc / totalBand).toFloat()
        val airFrac = (airEnergyAcc / totalBand).toFloat()

        val centroid = if (totalMagSum > 1e-5) (weightedFreqSum / totalMagSum).toFloat() else 1000f

        val baseFeatures = TrackFeatures(
            trackIndex = trackIndex,
            peakDb = peakDb,
            rmsDb = rmsDb,
            integratedLufs = rmsDb - 0.7f,
            subEnergy = subFrac,
            lowEnergy = lowFrac,
            midEnergy = midFrac,
            highMidEnergy = highMidFrac,
            airEnergy = airFrac,
            crestFactorDb = crestFactorDb,
            spectralCentroidHz = centroid
        )

        // On-Device Local Neural Model Inference
        val neuralPrediction = LocalNeuralAudioModel.predict(baseFeatures)

        val classified = if (neuralPrediction.confidence >= 0.30f) {
            neuralPrediction.topPredictedStem
        } else {
            classifyStem(
                subFrac = subFrac,
                lowFrac = lowFrac,
                midFrac = midFrac,
                highMidFrac = highMidFrac,
                airFrac = airFrac,
                centroidHz = centroid,
                crestFactorDb = crestFactorDb,
                zeroCrossingRate = zeroCrossings.toFloat() / count.coerceAtLeast(1)
            )
        }

        return baseFeatures.copy(
            classifiedStem = classified,
            neuralResult = neuralPrediction
        )
    }

    private fun classifyStem(
        subFrac: Float,
        lowFrac: Float,
        midFrac: Float,
        highMidFrac: Float,
        airFrac: Float,
        centroidHz: Float,
        crestFactorDb: Float,
        zeroCrossingRate: Float
    ): StemCategory {
        return when {
            subFrac + lowFrac > 0.55f && centroidHz < 350f -> StemCategory.BASS
            crestFactorDb > 14f && (subFrac > 0.25f || airFrac > 0.35f) -> StemCategory.DRUMS
            centroidHz in 700f..3500f && midFrac > 0.35f && crestFactorDb in 6f..14f -> StemCategory.VOCAL
            midFrac + highMidFrac > 0.5f && centroidHz in 1000f..4500f -> StemCategory.GUITAR
            airFrac > 0.3f && centroidHz > 4000f -> StemCategory.KEYS
            else -> StemCategory.OTHER
        }
    }
}

/**
 * Intelligent mixing rules, masking detector, reference matcher, and suggestion engine.
 */
object MixAdvisor {

    data class MaskingClash(
        val trackAIndex: Int,
        val trackAName: String,
        val trackBIndex: Int,
        val trackBName: String,
        val conflictFrequencyHz: Float,
        val severity: Float, // 0.0 to 1.0
        val suggestedCutDb: Float
    )

    data class ReferenceTarget(
        val name: String,
        val targetLufs: Float,
        val subRatio: Float,
        val midRatio: Float,
        val airRatio: Float,
        val description: String
    )

    val STANDARD_REFERENCES = listOf(
        ReferenceTarget("Modern Pop / Streaming", -14.0f, 0.25f, 0.45f, 0.30f, "Clean balanced streaming master (Spotify / Apple Music)"),
        ReferenceTarget("Punchy Hip-Hop / Trap", -10.0f, 0.40f, 0.35f, 0.25f, "Heavy low-end impact with crisp high-hat transients"),
        ReferenceTarget("Warm Rock / Acoustic", -16.0f, 0.20f, 0.55f, 0.25f, "Natural dynamics with organic midrange punch"),
        ReferenceTarget("EDM Club Banger", -8.5f, 0.38f, 0.32f, 0.30f, "Maximum commercial loudness with sidechain headroom")
    )

    /**
     * Detects frequency masking clashes between tracks (e.g. Vocal vs Guitar, Kick vs Bass).
     */
    fun detectMaskingClashes(
        tracks: List<TrackData>,
        features: List<TrackFeatures>
    ): List<MaskingClash> {
        val clashes = mutableListOf<MaskingClash>()
        for (i in features.indices) {
            for (j in i + 1 until features.size) {
                val fA = features[i]
                val fB = features[j]
                val trkA = tracks.find { it.index == fA.trackIndex } ?: continue
                val trkB = tracks.find { it.index == fB.trackIndex } ?: continue

                // Check Low-end collision (< 150 Hz)
                if (fA.subEnergy > 0.25f && fB.subEnergy > 0.25f) {
                    val severity = (fA.subEnergy * fB.subEnergy * 3.5f).coerceIn(0.2f, 0.95f)
                    clashes.add(
                        MaskingClash(
                            trackAIndex = trkA.index,
                            trackAName = trkA.name,
                            trackBIndex = trkB.index,
                            trackBName = trkB.name,
                            conflictFrequencyHz = 80f,
                            severity = severity,
                            suggestedCutDb = -3.0f
                        )
                    )
                }

                // Check Mid-range vocal masking (1.5 kHz - 3.5 kHz)
                val isVocalPair = trkA.stemCategory == StemCategory.VOCAL || trkB.stemCategory == StemCategory.VOCAL
                if (isVocalPair && fA.midEnergy > 0.3f && fB.midEnergy > 0.3f) {
                    val severity = (fA.midEnergy * fB.midEnergy * 2.8f).coerceIn(0.2f, 0.95f)
                    clashes.add(
                        MaskingClash(
                            trackAIndex = trkA.index,
                            trackAName = trkA.name,
                            trackBIndex = trkB.index,
                            trackBName = trkB.name,
                            conflictFrequencyHz = 2400f,
                            severity = severity,
                            suggestedCutDb = -2.5f
                        )
                    )
                }
            }
        }
        return clashes
    }

    /**
     * Generates a comprehensive set of verified, bounded mix suggestions.
     */
    fun generateSuggestions(
        session: ProjectSession,
        trackFeatures: List<TrackFeatures>
    ): List<MixSuggestion> {
        val suggestions = mutableListOf<MixSuggestion>()
        val rev = session.revision

        for (feat in trackFeatures) {
            val track = session.tracks.find { it.index == feat.trackIndex } ?: continue
            // Do not suggest fader moves or compression for empty tracks.
            if (feat.rmsDb <= -60f && feat.peakDb <= -60f) continue

            // 1. Gain Staging Rule: Target nominal -18 dBFS RMS / -6 dBFS Peak per track
            if (feat.peakDb > -2.0f) {
                val proposedGain = (track.gainDb - (feat.peakDb - (-6.0f))).coerceIn(-24.0f, 6.0f)
                suggestions.add(
                    MixSuggestion(
                        trackIndex = track.index,
                        trackName = track.name,
                        category = "Gain Staging",
                        parameter = "Fader Gain",
                        currentValue = "${String.format("%.1f", track.gainDb)} dB",
                        proposedValue = "${String.format("%.1f", proposedGain)} dB",
                        reason = "Channel peak is ${String.format("%.1f", feat.peakDb)} dBFS. Lowering fader to preserve -6 dB headroom avoids mix bus saturation.",
                        confidence = 0.92f,
                        targetRevision = rev
                    )
                )
            } else if (feat.rmsDb < -35.0f && feat.peakDb < -20.0f) {
                val proposedGain = (track.gainDb + 6.0f).coerceIn(-24.0f, 6.0f)
                suggestions.add(
                    MixSuggestion(
                        trackIndex = track.index,
                        trackName = track.name,
                        category = "Gain Staging",
                        parameter = "Fader Gain",
                        currentValue = "${String.format("%.1f", track.gainDb)} dB",
                        proposedValue = "${String.format("%.1f", proposedGain)} dB",
                        reason = "Channel is unusually quiet (${String.format("%.1f", feat.rmsDb)} dBFS RMS). Boost fader to sit in the mix pocket.",
                        confidence = 0.85f,
                        targetRevision = rev
                    )
                )
            }

            // 2. High-Pass Mud Rule: Non-bass tracks should high-pass sub-80Hz
            if (feat.classifiedStem != StemCategory.BASS && feat.classifiedStem != StemCategory.DRUMS) {
                if (feat.subEnergy > 0.08f && !track.eqConfig.highPass.enabled) {
                    suggestions.add(
                        MixSuggestion(
                            trackIndex = track.index,
                            trackName = track.name,
                            category = "Clean Mud",
                            parameter = "High-Pass Filter 90 Hz",
                            currentValue = "Off",
                            proposedValue = "HPF @ 90Hz Enabled",
                            reason = "Detected low-frequency rumble (${String.format("%.1f", feat.subEnergy * 100)}% sub energy) on ${feat.classifiedStem.displayName}. HPF tightens the bass pocket.",
                            confidence = 0.94f,
                            targetRevision = rev
                        )
                    )
                }
            }

            // 3. Dynamic Control: Heavy crest factor on drums or vocals
            if (feat.crestFactorDb > 16.0f && !track.compressorConfig.enabled) {
                suggestions.add(
                    MixSuggestion(
                        trackIndex = track.index,
                        trackName = track.name,
                        category = "Dynamics Control",
                        parameter = "Compressor 4:1",
                        currentValue = "Bypassed",
                        proposedValue = "Enabled (Thresh -18dB, 4:1, 15ms)",
                        reason = "High dynamic spikes (${String.format("%.1f", feat.crestFactorDb)} dB crest factor). Gentle compression glues transients.",
                        confidence = 0.88f,
                        targetRevision = rev
                    )
                )
            }
        }

        // 4. Masking Clashes suggestions
        val clashes = detectMaskingClashes(session.tracks, trackFeatures)
        for (clash in clashes) {
            suggestions.add(
                MixSuggestion(
                    trackIndex = clash.trackBIndex,
                    trackName = clash.trackBName,
                    category = "Spectral Masking",
                    parameter = "EQ Dip @ ${clash.conflictFrequencyHz.toInt()} Hz",
                    currentValue = "0.0 dB",
                    proposedValue = "${clash.suggestedCutDb} dB",
                    reason = "Frequency clash with ${clash.trackAName} at ${clash.conflictFrequencyHz.toInt()} Hz. Dipping ${clash.suggestedCutDb} dB carves clarity.",
                    confidence = 0.89f,
                    targetRevision = rev
                )
            )
        }

        return suggestions
    }

    /**
     * Applies a list of suggestions as one single atomic, undoable transaction.
     */
    fun applySuggestionsToSession(
        session: ProjectSession,
        suggestions: List<MixSuggestion>
    ): ProjectSession {
        var updatedTracks = session.tracks.toMutableList()

        for (sug in suggestions) {
            val idx = updatedTracks.indexOfFirst { it.index == sug.trackIndex }
            if (idx == -1) continue
            val trk = updatedTracks[idx]

            when {
                sug.parameter.contains("Fader Gain") -> {
                    val gainVal = sug.proposedValue.replace("dB", "").trim().toFloatOrNull()
                    if (gainVal != null) {
                        updatedTracks[idx] = trk.copy(gainDb = gainVal.coerceIn(-60f, 6f))
                    }
                }
                sug.parameter.contains("High-Pass") -> {
                    val updatedEq = trk.eqConfig.copy(
                        highPass = EqBand(FilterType.HIGH_PASS, 90f, 0f, 0.707f, enabled = true)
                    )
                    updatedTracks[idx] = trk.copy(eqConfig = updatedEq)
                }
                sug.parameter.contains("Compressor") -> {
                    val updatedComp = trk.compressorConfig.copy(
                        enabled = true,
                        thresholdDb = -18f,
                        ratio = 3.5f,
                        attackMs = 15f,
                        releaseMs = 120f,
                        makeupGainDb = 2.0f
                    )
                    updatedTracks[idx] = trk.copy(compressorConfig = updatedComp)
                }
                sug.parameter.contains("EQ Dip") -> {
                    val updatedEq = trk.eqConfig.copy(
                        band2 = EqBand(FilterType.PEAKING, 2400f, -2.5f, 2.0f, enabled = true)
                    )
                    updatedTracks[idx] = trk.copy(eqConfig = updatedEq)
                }
            }
        }

        return session.copy(tracks = updatedTracks, revision = session.revision + 1)
    }

    /**
     * Evaluates natural language mix requests into safe, bounded suggestions.
     */
    fun parseNaturalLanguageCommand(
        prompt: String,
        session: ProjectSession
    ): List<MixSuggestion> {
        val p = prompt.lowercase()
        val suggestions = mutableListOf<MixSuggestion>()
        val rev = session.revision

        when {
            p.contains("bright") || p.contains("air") || p.contains("sparkle") -> {
                for (trk in session.tracks) {
                    if (trk.stemCategory == StemCategory.VOCAL || trk.stemCategory == StemCategory.KEYS) {
                        suggestions.add(
                            MixSuggestion(
                                trackIndex = trk.index,
                                trackName = trk.name,
                                category = "Tone Shaping",
                                parameter = "High Shelf +2.5 dB @ 10 kHz",
                                currentValue = "${trk.eqConfig.band4.gainDb} dB",
                                proposedValue = "+2.5 dB",
                                reason = "User requested brighter tone. Gentle high-shelf lift adds vocal presence and sheen.",
                                confidence = 0.95f,
                                targetRevision = rev
                            )
                        )
                    }
                }
            }
            p.contains("glue") || p.contains("punch") || p.contains("drum") -> {
                for (trk in session.tracks) {
                    if (trk.stemCategory == StemCategory.DRUMS) {
                        suggestions.add(
                            MixSuggestion(
                                trackIndex = trk.index,
                                trackName = trk.name,
                                category = "Dynamic Glue",
                                parameter = "Compressor 4:1",
                                currentValue = if (trk.compressorConfig.enabled) "Active" else "Bypassed",
                                proposedValue = "Enabled (Thresh -16dB, 4:1)",
                                reason = "User requested drum glue. Snappy attack with medium release bonds kicks and snare.",
                                confidence = 0.94f,
                                targetRevision = rev
                            )
                        )
                    }
                }
            }
            p.contains("warm") || p.contains("analog") || p.contains("bass") -> {
                for (trk in session.tracks) {
                    if (trk.stemCategory == StemCategory.BASS) {
                        suggestions.add(
                            MixSuggestion(
                                trackIndex = trk.index,
                                trackName = trk.name,
                                category = "Warmth",
                                parameter = "Low Shelf +2.0 dB @ 100 Hz",
                                currentValue = "${trk.eqConfig.band1.gainDb} dB",
                                proposedValue = "+2.0 dB",
                                reason = "User requested warmth. Boosting 100 Hz adds full-bodied harmonic weight to the bass.",
                                confidence = 0.92f,
                                targetRevision = rev
                            )
                        )
                    }
                }
            }
            p.contains("clean") || p.contains("mud") || p.contains("rumble") -> {
                for (trk in session.tracks) {
                    if (trk.stemCategory != StemCategory.BASS && trk.stemCategory != StemCategory.DRUMS) {
                        suggestions.add(
                            MixSuggestion(
                                trackIndex = trk.index,
                                trackName = trk.name,
                                category = "Clean Mud",
                                parameter = "High-Pass Filter 90 Hz",
                                currentValue = "Bypassed",
                                proposedValue = "Enabled @ 90 Hz",
                                reason = "User requested mud cleanup. Cutting sub frequencies leaves clean room for 808s and kick.",
                                confidence = 0.95f,
                                targetRevision = rev
                            )
                        )
                    }
                }
            }
            else -> {
                // Default intelligent mix balance
                return generateSuggestions(session, session.tracks.map { trk ->
                    TrackFeatures(trk.index, classifiedStem = trk.stemCategory, peakDb = -3.5f, rmsDb = -18.0f)
                })
            }
        }
        return suggestions
    }
}

/**
 * Tool-call parsing + dispatch for local LLM integration.
 * Parses simple JSON-ish tool calls of the form {"tool": "name", "args": {...}}
 * from an LLM response and applies them to a session.
 */
object ToolCallDispatcher {
    data class ToolCall(val name: String, val args: Map<String, String> = emptyMap())

    private val KNOWN_TOOLS = setOf(
        "applyTemplate", "setTrackGain", "setTrackPan", "toggleMute", "setTrackMute",
        "setTrackSolo", "armTrack", "setCompressor", "setSaturation", "setEqBand",
        "setEqFilter", "setVocalRider", "setResonanceSmoother", "applyVocalChain",
        "setReverbSend", "setMasterGain", "transport",
        "setChorus", "setDelay", "setLimiter", "setAutoPitch", "setNoiseReduction",
        "setMasterLimiter", "setMasterReverb", "setSidechain", "setTrackSend"
    )

    fun isKnownTool(name: String): Boolean = name in KNOWN_TOOLS

    fun parseToolCalls(llmOutput: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        for (jsonObject in extractJsonObjects(llmOutput)) {
            val nameMatch = Regex("\"tool\"\\s*:\\s*\"([^\"]+)\"").find(jsonObject) ?: continue
            val name = nameMatch.groupValues[1]
            val args = mutableMapOf<String, String>()
            // Accept flat {"tool":..., "trackIndex":...} and nested args objects.
            val argRegex = Regex(
                "\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?)|(true|false))"
            )
            for (am in argRegex.findAll(jsonObject)) {
                val key = am.groupValues[1]
                if (key == "tool") continue
                val value = am.groupValues[2].ifBlank {
                    am.groupValues[3].ifBlank { am.groupValues[4] }
                }
                if (value.isNotBlank()) args[key] = value
            }
            if (name.isNotBlank()) calls.add(ToolCall(name, args))
        }
        return calls
    }

    /** Extracts balanced brace-delimited objects so nested args are not truncated. */
    private fun extractJsonObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) {
                        objects.add(text.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    fun compressorTypeFromText(value: String?): CompressorType? {
        val v = value?.lowercase() ?: return null
        return when {
            v.contains("opto") || (v.contains("la") && v.contains("2a")) -> CompressorType.LA_2A
            v.contains("1176") || v.contains("fet") -> CompressorType.FET_1176
            v.contains("tube tech") || v.contains("tube-tech") || v.contains("vari") || v.contains("cl 1b") -> CompressorType.TUBE_TECH
            v.contains("vca") || v.contains("clean") || v.contains("bus") -> CompressorType.VCA
            else -> null
        }
    }

    fun saturationTypeFromText(value: String?): SaturationType? {
        val v = value?.lowercase() ?: return null
        return when {
            v.contains("tape") -> SaturationType.TAPE
            v.contains("tube") || v.contains("valve") -> SaturationType.TUBE
            v.contains("transformer") || v.contains("iron") || v.contains("neve") -> SaturationType.TRANSFORMER
            v.contains("console") || v.contains("op-amp") || v.contains("opamp") -> SaturationType.CONSOLE
            else -> null
        }
    }

    private fun TrackCallArgs.track(session: ProjectSession): TrackData? {
        val byIndex = session.tracks.firstOrNull { it.index == trackIndex }
        if (byIndex != null) return byIndex
        val name = trackName ?: return null
        return session.tracks.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private data class TrackCallArgs(
        val trackIndex: Int? = null,
        val trackName: String? = null
    ) {
        constructor(args: Map<String, String>) : this(
            trackIndex = args["trackIndex"]?.toIntOrNull(),
            trackName = args["trackName"]
        )
    }

    private fun Map<String, String>.floatArg(key: String): Float? = this[key]?.toFloatOrNull()

    private fun Map<String, String>.boolArg(key: String, default: Boolean = true): Boolean =
        when (this[key]?.lowercase()) {
            "true", "on", "yes", "1" -> true
            "false", "off", "no", "0" -> false
            else -> default
        }

    fun executeToolCall(session: ProjectSession, call: ToolCall): ProjectSession {
        return when (call.name) {
            "applyTemplate" -> {
                val templateId = call.args["templateId"] ?: return session
                val template = com.example.audio.model.MixTemplateLibrary.byId(templateId) ?: return session
                var tracks = session.tracks.toMutableList()
                for ((name, tt) in template.trackTemplates) {
                    val idx = tracks.indexOfFirst { it.name.contains(name, ignoreCase = true) || name.contains(it.name, ignoreCase = true) }
                    if (idx >= 0) {
                        tracks[idx] = tracks[idx].copy(gainDb = tt.gainDb, pan = tt.pan, reverbSendLevel = tt.reverbSend)
                    }
                }
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setTrackGain" -> {
                val idx = call.args["trackIndex"]?.toIntOrNull() ?: return session
                val gain = call.args["gainDb"]?.toFloatOrNull() ?: return session
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == idx }
                if (t >= 0) tracks[t] = tracks[t].copy(gainDb = gain.coerceIn(-60f, 12f))
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setTrackPan" -> {
                val idx = call.args["trackIndex"]?.toIntOrNull() ?: return session
                val pan = call.args["pan"]?.toFloatOrNull() ?: return session
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == idx }
                if (t >= 0) tracks[t] = tracks[t].copy(pan = pan.coerceIn(-1f, 1f))
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "toggleMute" -> {
                val idx = call.args["trackIndex"]?.toIntOrNull() ?: return session
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == idx }
                if (t >= 0) tracks[t] = tracks[t].copy(isMuted = !tracks[t].isMuted)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setTrackMute" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                if (t >= 0) tracks[t] = tracks[t].copy(isMuted = call.args.boolArg("muted", true))
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setTrackSolo" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                if (t >= 0) tracks[t] = tracks[t].copy(isSoloed = call.args.boolArg("soloed", true))
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "armTrack" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val tracks = session.tracks.map {
                    it.copy(isArmedForRecording = it.index == target.index)
                }
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setCompressor" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                var cfg = target.compressorConfig
                compressorTypeFromText(call.args["type"])?.let { cfg = cfg.withType(it) }
                cfg = cfg.copy(
                    enabled = call.args.boolArg("enabled", true),
                    thresholdDb = call.args.floatArg("thresholdDb") ?: cfg.thresholdDb,
                    ratio = call.args.floatArg("ratio") ?: cfg.ratio,
                    attackMs = call.args.floatArg("attackMs") ?: cfg.attackMs,
                    releaseMs = call.args.floatArg("releaseMs") ?: cfg.releaseMs,
                    makeupGainDb = call.args.floatArg("makeupGainDb") ?: cfg.makeupGainDb
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(compressorConfig = cfg)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setSaturation" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                var cfg = target.saturationConfig
                saturationTypeFromText(call.args["type"])?.let { cfg = cfg.withType(it) }
                cfg = cfg.copy(
                    enabled = call.args.boolArg("enabled", true),
                    driveDb = call.args.floatArg("driveDb") ?: cfg.driveDb,
                    warmth = call.args.floatArg("warmth") ?: cfg.warmth,
                    mix = call.args.floatArg("mix") ?: cfg.mix
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(saturationConfig = cfg)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setEqBand" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val bandNumber = call.args["band"]?.toIntOrNull()?.coerceIn(1, 4) ?: 4
                val eq = target.eqConfig
                val existing = when (bandNumber) {
                    1 -> eq.band1
                    2 -> eq.band2
                    3 -> eq.band3
                    else -> eq.band4
                }
                val updatedBand = existing.copy(
                    frequencyHz = call.args.floatArg("frequencyHz") ?: existing.frequencyHz,
                    gainDb = call.args.floatArg("gainDb") ?: existing.gainDb,
                    qFactor = call.args.floatArg("qFactor") ?: existing.qFactor,
                    enabled = call.args.boolArg("enabled", true)
                )
                val updatedEq = when (bandNumber) {
                    1 -> eq.copy(band1 = updatedBand)
                    2 -> eq.copy(band2 = updatedBand)
                    3 -> eq.copy(band3 = updatedBand)
                    else -> eq.copy(band4 = updatedBand)
                }
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(eqConfig = updatedEq)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setEqFilter" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val raw = call.args["filter"]?.lowercase() ?: "highpass"
                val isLowPass = raw.contains("low")
                val eq = target.eqConfig
                val existing = if (isLowPass) eq.lowPass else eq.highPass
                val frequency = call.args.floatArg("frequencyHz") ?: existing.frequencyHz
                val q = call.args.floatArg("qFactor") ?: existing.qFactor
                val enabled = call.args.boolArg("enabled", true)
                val updatedBand = EqBand(
                    type = if (isLowPass) FilterType.LOW_PASS else FilterType.HIGH_PASS,
                    frequencyHz = frequency,
                    gainDb = 0f,
                    qFactor = q,
                    enabled = enabled
                )
                val updatedEq = if (isLowPass) eq.copy(lowPass = updatedBand) else eq.copy(highPass = updatedBand)
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(eqConfig = updatedEq)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setVocalRider" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val cfg = target.vocalRiderConfig.copy(
                    enabled = call.args.boolArg("enabled", true),
                    targetLufs = call.args.floatArg("targetLufs") ?: target.vocalRiderConfig.targetLufs,
                    maxGainDb = call.args.floatArg("maxGainDb") ?: target.vocalRiderConfig.maxGainDb,
                    minGainDb = call.args.floatArg("minGainDb") ?: target.vocalRiderConfig.minGainDb,
                    sensitivity = call.args.floatArg("sensitivity") ?: target.vocalRiderConfig.sensitivity
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(vocalRiderConfig = cfg)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setResonanceSmoother" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val cfg = target.resonanceSmootherConfig.copy(
                    enabled = call.args.boolArg("enabled", true),
                    frequencyHz = call.args.floatArg("frequencyHz") ?: target.resonanceSmootherConfig.frequencyHz,
                    thresholdDb = call.args.floatArg("thresholdDb") ?: target.resonanceSmootherConfig.thresholdDb,
                    depthDb = call.args.floatArg("depthDb") ?: target.resonanceSmootherConfig.depthDb,
                    qFactor = call.args.floatArg("qFactor") ?: target.resonanceSmootherConfig.qFactor
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(resonanceSmootherConfig = cfg)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "applyVocalChain" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val eq = target.eqConfig.copy(
                    enabled = true,
                    highPass = EqBand(FilterType.HIGH_PASS, 90f, 0f, 0.707f, enabled = true)
                )
                val updated = target.copy(
                    eqConfig = eq,
                    compressorConfig = target.compressorConfig.copy(
                        enabled = true,
                        thresholdDb = -20f,
                        makeupGainDb = 2f
                    ).withType(CompressorType.LA_2A),
                    saturationConfig = target.saturationConfig.copy(
                        enabled = true,
                        driveDb = 3.5f,
                        warmth = 0.45f,
                        mix = 0.35f
                    ).withType(SaturationType.TUBE),
                    vocalRiderConfig = target.vocalRiderConfig.copy(
                        enabled = true,
                        targetLufs = -18f,
                        maxGainDb = 5f,
                        minGainDb = -5f,
                        sensitivity = 0.55f
                    ),
                    resonanceSmootherConfig = target.resonanceSmootherConfig.copy(
                        enabled = true,
                        frequencyHz = 3200f,
                        thresholdDb = -20f,
                        depthDb = -5f,
                        qFactor = 5f
                    )
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = updated
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setReverbSend" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val send = call.args.floatArg("sendLevel") ?: return session
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(reverbSendLevel = send.coerceIn(0f, 1f))
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setChorus" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                var cfg = target.chorusConfig
                cfg = cfg.copy(
                    enabled = call.args.boolArg("enabled", true),
                    rateHz = call.args.floatArg("rateHz") ?: cfg.rateHz,
                    depthMs = call.args.floatArg("depthMs") ?: cfg.depthMs,
                    feedback = call.args.floatArg("feedback") ?: cfg.feedback,
                    stereoSpread = call.args.floatArg("stereoSpread") ?: cfg.stereoSpread,
                    mix = call.args.floatArg("mix") ?: cfg.mix
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(chorusConfig = cfg)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setDelay" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                var cfg = target.delayConfig
                cfg = cfg.copy(
                    enabled = call.args.boolArg("enabled", true),
                    timeMs = call.args.floatArg("timeMs") ?: cfg.timeMs,
                    feedback = call.args.floatArg("feedback") ?: cfg.feedback,
                    mix = call.args.floatArg("mix") ?: cfg.mix,
                    duckingEnabled = call.args.boolArg("duckingEnabled", cfg.duckingEnabled),
                    duckingThresholdDb = call.args.floatArg("duckingThresholdDb") ?: cfg.duckingThresholdDb,
                    duckingDepthDb = call.args.floatArg("duckingDepthDb") ?: cfg.duckingDepthDb,
                    duckingReleaseMs = call.args.floatArg("duckingReleaseMs") ?: cfg.duckingReleaseMs
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(delayConfig = cfg)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setLimiter" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                var cfg = target.limiterConfig
                cfg = cfg.copy(
                    enabled = call.args.boolArg("enabled", true),
                    ceilingDb = call.args.floatArg("ceilingDb") ?: cfg.ceilingDb,
                    releaseMs = call.args.floatArg("releaseMs") ?: cfg.releaseMs
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(limiterConfig = cfg)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setAutoPitch" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                var cfg = target.autoPitchConfig
                cfg = cfg.copy(
                    enabled = call.args.boolArg("enabled", true),
                    correctionStrength = call.args.floatArg("correctionStrength") ?: cfg.correctionStrength
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(autoPitchConfig = cfg)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setNoiseReduction" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                var cfg = target.noiseReductionConfig
                cfg = cfg.copy(
                    enabled = call.args.boolArg("enabled", true),
                    thresholdDb = call.args.floatArg("thresholdDb") ?: cfg.thresholdDb,
                    reductionDb = call.args.floatArg("reductionDb") ?: cfg.reductionDb
                )
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(noiseReductionConfig = cfg)
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setMasterLimiter" -> {
                val cfg = session.masterLimiter.copy(
                    enabled = call.args.boolArg("enabled", session.masterLimiter.enabled),
                    ceilingDb = call.args.floatArg("ceilingDb") ?: session.masterLimiter.ceilingDb,
                    thresholdDb = call.args.floatArg("thresholdDb") ?: session.masterLimiter.thresholdDb,
                    releaseMs = call.args.floatArg("releaseMs") ?: session.masterLimiter.releaseMs
                )
                session.copy(masterLimiter = cfg, revision = session.revision + 1)
            }
            "setMasterReverb" -> {
                var cfg = session.masterReverb
                cfg = cfg.copy(
                    enabled = call.args.boolArg("enabled", cfg.enabled),
                    roomSize = call.args.floatArg("roomSize") ?: cfg.roomSize,
                    damping = call.args.floatArg("damping") ?: cfg.damping,
                    stereoWidth = call.args.floatArg("stereoWidth") ?: cfg.stereoWidth,
                    returnLevel = call.args.floatArg("returnLevel") ?: cfg.returnLevel,
                    preDelayMs = call.args.floatArg("preDelayMs") ?: cfg.preDelayMs,
                    decayTimeSec = call.args.floatArg("decayTimeSec") ?: cfg.decayTimeSec,
                    lowCutHz = call.args.floatArg("lowCutHz") ?: cfg.lowCutHz,
                    highCutHz = call.args.floatArg("highCutHz") ?: cfg.highCutHz
                )
                session.copy(masterReverb = cfg, revision = session.revision + 1)
            }
            "setSidechain" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val sourceIndex = call.args["sourceTrackIndex"]?.toIntOrNull() ?: call.args["sourceTrack"]?.toIntOrNull()
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                tracks[t] = target.copy(
                    sidechainSourceIndex = sourceIndex ?: target.sidechainSourceIndex,
                    sidechainSource = sourceIndex ?: target.sidechainSource,
                    sidechainTrackIndex = sourceIndex ?: target.sidechainTrackIndex
                )
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setTrackSend" -> {
                val target = TrackCallArgs(call.args).track(session) ?: return session
                val bus = call.args["bus"] ?: "reverb"
                val level = call.args.floatArg("level") ?: call.args.floatArg("sendLevel") ?: return session
                val tracks = session.tracks.toMutableList()
                val t = tracks.indexOfFirst { it.index == target.index }
                val updatedSends = target.sends.toMutableMap().apply { put(bus, level.coerceIn(0f, 1f)) }
                val updatedAux = target.auxSends.toMutableMap().apply { put(bus, level.coerceIn(0f, 1f)) }
                tracks[t] = target.copy(
                    sends = updatedSends,
                    auxSends = updatedAux,
                    reverbSendLevel = if (bus.equals("reverb", true)) level.coerceIn(0f, 1f) else target.reverbSendLevel,
                    reverbSend2Level = if (bus.equals("reverb2", true)) level.coerceIn(0f, 1f) else target.reverbSend2Level
                )
                session.copy(tracks = tracks, revision = session.revision + 1)
            }
            "setMasterGain" -> {
                val gain = call.args.floatArg("gainDb") ?: return session
                session.copy(masterGainDb = gain.coerceIn(-60f, 12f), revision = session.revision + 1)
            }
            else -> session
        }
    }

    fun executeAll(session: ProjectSession, calls: List<ToolCall>): ProjectSession {
        var s = session
        for (c in calls) s = executeToolCall(s, c)
        return s
    }
}
