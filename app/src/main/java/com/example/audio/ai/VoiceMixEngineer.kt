package com.example.audio.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.example.audio.model.CompressorType
import com.example.audio.model.AudioSourceFormat
import com.example.audio.model.ProjectSession
import com.example.audio.model.SaturationType
import com.example.audio.model.StemCategory
import com.example.audio.model.TrackData
import com.example.audio.model.TrackFeatures
import java.util.Locale
import kotlin.math.abs

/**
 * UI state for the spoken engineer. Kept in one place so the AI Studio can show
 * exactly what was heard, what the engineer understood, and whether the local
 * model or the built-in planner produced the move.
 */
data class VoiceEngineerState(
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val partialTranscript: String = "",
    val transcript: String = "",
    val lastAction: String = "",
    val source: String = "",
    val error: String? = null
)

/**
 * On-device speech input plus spoken confirmations. Uses the platform
 * SpeechRecognizer (on-device engine when the phone offers one) and TextToSpeech,
 * so a request can be made and confirmed without leaving the DAW.
 */
class VoiceMixEngineer(private val context: Context) : RecognitionListener {

    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onPartialTranscript: ((String) -> Unit)? = null
    var onFinalTranscript: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    fun isListening(): Boolean = listening

    fun startListening() {
        mainHandler.post {
            if (listening) return@post
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                onError?.invoke("Microphone permission is required for voice commands.")
                return@post
            }
            val engine = ensureRecognizer() ?: run {
                onError?.invoke("No speech recognition service is available on this device.")
                return@post
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            }
            listening = true
            onListeningChanged?.invoke(true)
            try {
                engine.startListening(intent)
            } catch (t: Throwable) {
                listening = false
                onListeningChanged?.invoke(false)
                onError?.invoke(t.message ?: "Could not start speech recognition.")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                recognizer?.stopListening()
            } catch (_: Throwable) {
            /* best-effort recognizer/TTS cleanup: the engine may already be gone */ }
        }
    }

    fun cancelListening() {
        mainHandler.post {
            try {
                recognizer?.cancel()
            } catch (_: Throwable) {
            /* best-effort recognizer/TTS cleanup: the engine may already be gone */ }
            if (listening) {
                listening = false
                onListeningChanged?.invoke(false)
            }
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        val created = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (_: Throwable) {
            try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (_: Throwable) {
                null
            }
        }
        return created?.also {
            it.setRecognitionListener(this)
            recognizer = it
        }
    }

    /**
     * Speaks a short confirmation. TTS initialization is lazy and queued so the
     * first command still gets a spoken result.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            ensureTts()
            if (ttsReady) speakNow(text) else pendingSpeech = text
        }
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = try {
            TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault()
                    tts?.setSpeechRate(1.02f)
                    pendingSpeech?.let {
                        pendingSpeech = null
                        speakNow(it)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun speakNow(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nc_studio_engineer")
        } catch (_: Throwable) {
        /* best-effort recognizer/TTS cleanup: the engine may already be gone */ }
    }

    fun shutdown() {
        mainHandler.post {
            try {
                recognizer?.destroy()
            } catch (_: Throwable) {
            /* best-effort recognizer/TTS cleanup: the engine may already be gone */ }
            recognizer = null
            listening = false
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Throwable) {
            /* best-effort recognizer/TTS cleanup: the engine may already be gone */ }
            tts = null
            ttsReady = false
            pendingSpeech = null
        }
    }

    // RecognitionListener

    override fun onReadyForSpeech(params: Bundle?) {
        listening = true
        onListeningChanged?.invoke(true)
    }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        onListeningChanged?.invoke(false)
    }

    override fun onError(error: Int) {
        listening = false
        onListeningChanged?.invoke(false)
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error. Check the microphone."
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer stopped. Try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice commands."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition needs a network connection, or an on-device speech pack, on this phone."
            SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Try again with a shorter request."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Try again in a moment."
            SpeechRecognizer.ERROR_SERVER -> "Speech recognition service error. Try again."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything. Tap the mic and speak."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "This language isn't available to the speech recognizer."
            else -> "Speech recognition error ($error). Try again."
        }
        onError?.invoke(message)
    }

    override fun onResults(results: Bundle?) {
        listening = false
        onListeningChanged?.invoke(false)
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isBlank()) {
            onError?.invoke("I didn't catch that. Try again.")
        } else {
            onFinalTranscript?.invoke(text)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isNotBlank()) onPartialTranscript?.invoke(text)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}

/**
 * Deterministic fallback planner. It keeps voice control working when the
 * local model is offline or returns prose, and its tool-call vocabulary is the
 * same one the model is asked to emit.
 */
object MixEngineerBrain {

    data class Plan(
        val calls: List<ToolCallDispatcher.ToolCall>,
        val summary: String,
        val clarification: String? = null,
        val source: String = "Built-in engineer"
    )

    fun plan(
        transcript: String,
        session: ProjectSession,
        selectedTrackIndex: Int = 0
    ): Plan {
        val text = transcript.lowercase(Locale.US)
        val calls = mutableListOf<ToolCallDispatcher.ToolCall>()
        val targets = resolveTargets(text, session, selectedTrackIndex)
        val twoTrackTracks = session.tracks.filter { it.isTwoTrackBeat }
        val selected = session.tracks.firstOrNull { it.index == selectedTrackIndex }
            ?: session.tracks.firstOrNull()

        fun callKey(call: ToolCallDispatcher.ToolCall): String =
            call.name + "|" + (call.args["trackIndex"] ?: "") + "|" +
                (call.args["filter"] ?: "") + "|" +
                (if (call.name == "transport") call.args["action"] ?: "" else "")

        fun add(call: ToolCallDispatcher.ToolCall) {
            val key = callKey(call)
            if (calls.none { callKey(it) == key }) calls.add(call)
        }

        // Transport and record-room commands.
        val wantsStopRecording = Regex("\\b(stop recording|stop the take|end the take)\\b").containsMatchIn(text)
        val wantsStartRecording = Regex("\\b(start recording|start the take|hit record|record now|roll the tape|record)\\b").containsMatchIn(text)
        when {
            wantsStopRecording ->
                add(ToolCallDispatcher.ToolCall("transport", mapOf("action" to "stopRecording")))
            wantsStartRecording ->
                add(ToolCallDispatcher.ToolCall("transport", mapOf("action" to "record")))
            Regex("\\b(stop|halt)\\b").containsMatchIn(text) && !text.contains("stop record") ->
                add(ToolCallDispatcher.ToolCall("transport", mapOf("action" to "stop")))
            Regex("\\b(play|start playback|let it roll)\\b").containsMatchIn(text) &&
                !text.contains("playlist") ->
                add(ToolCallDispatcher.ToolCall("transport", mapOf("action" to "play")))
        }
        if (wantsStartRecording && session.tracks.none { it.isArmedForRecording }) {
            (targets.firstOrNull() ?: selected)?.let { track ->
                add(ToolCallDispatcher.ToolCall("armTrack", mapOf("trackIndex" to track.index.toString())))
            }
        }
        if (Regex("\\bloop (on|the loop on|it)\\b|\\bturn on the loop\\b|\\blooping on\\b").containsMatchIn(text)) {
            add(ToolCallDispatcher.ToolCall("transport", mapOf("action" to "loopOn")))
        }
        if (Regex("\\bloop off\\b|\\bturn off the loop\\b|\\blooping off\\b").containsMatchIn(text)) {
            add(ToolCallDispatcher.ToolCall("transport", mapOf("action" to "loopOff")))
        }

        // Rough-mix shortcut: one request, a complete starting point.
        val roughMix = Regex(
            "\\b(rough mix|mix (it|this|the session|the song|my song|this song|the track|this track|my track|the beat|this beat)|" +
                "do a mix|give me a mix|just mix|mix it down|" +
                "make it sound (good|professional|finished|better)|balance (it|this|the mix|the session))\\b"
        ).containsMatchIn(text)
        if (roughMix) {
            session.tracks.filter { it.stemCategory == StemCategory.VOCAL }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("applyVocalChain", mapOf("trackIndex" to track.index.toString())))
                add(ToolCallDispatcher.ToolCall("setResonanceSmoother", mapOf(
                    "trackIndex" to track.index.toString(),
                    "enabled" to "true",
                    "frequencyHz" to "3200",
                    "depthDb" to "-4"
                )))
            }
            session.tracks.filter { it.stemCategory == StemCategory.DRUMS }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setCompressor", mapOf(
                    "trackIndex" to track.index.toString(),
                    "enabled" to "true",
                    "type" to CompressorType.FET_1176.name,
                    "thresholdDb" to "-18",
                    "ratio" to "4"
                )))
            }
            session.tracks.filter { it.stemCategory == StemCategory.BASS }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setSaturation", mapOf(
                    "trackIndex" to track.index.toString(),
                    "enabled" to "true",
                    "type" to SaturationType.TRANSFORMER.name,
                    "driveDb" to "4"
                )))
            }
            if (calls.isEmpty() && twoTrackTracks.isNotEmpty()) {
                val premaster = session.sourceFormat == AudioSourceFormat.TWO_TRACK_PREMASTER ||
                    twoTrackTracks.any { it.sourceFormat == AudioSourceFormat.TWO_TRACK_PREMASTER }
                if (premaster) {
                    return Plan(
                        emptyList(),
                        "",
                        "This is a 2-track premaster, so I will not add another limiter or stem move. " +
                            "Bounce the unmastered mix if you want me to master it."
                    )
                }
                // A finished stereo beat has no isolated stems, but a gentle
                // master move is still honest and audible.
                add(ToolCallDispatcher.ToolCall("setMasterLimiter", mapOf(
                    "enabled" to "true",
                    "ceilingDb" to "-1.0",
                    "thresholdDb" to "-8.0",
                    "releaseMs" to "120"
                )))
                add(ToolCallDispatcher.ToolCall("setMasterGain", mapOf("gainDb" to "0.0")))
            }
            val roughMixSummary = summarize(calls, session) + when {
                twoTrackTracks.isNotEmpty() && calls.any { it.name == "setMasterLimiter" } ->
                    " The stereo 2-track beat got a gentle master limiter."
                twoTrackTracks.isNotEmpty() ->
                    " The stereo 2-track beat is left intact; I mixed the vocals against it."
                else -> ""
            }
            return Plan(calls, roughMixSummary, source = "Built-in engineer")
        }

        // A stereo 2-track beat has no isolated vocal. Applying a vocal chain to
        // the whole beat would wreck it, so explain the limitation instead.
        // A recording request is different: the artist is about to add the vocal.
        val mentionsVocal = Regex("\\b(vocals?|vox|lead vocal|rap|verse|hook)\\b").containsMatchIn(text)
        val recordingIntent = Regex("\\b(record|recording|take|punch)\\b").containsMatchIn(text)
        val hasVocalStem = session.tracks.any { it.stemCategory == StemCategory.VOCAL }
        if (mentionsVocal && !recordingIntent && !hasVocalStem && twoTrackTracks.isNotEmpty()) {
            return Plan(
                emptyList(),
                "",
                "This beat is a 2-track stereo mix, so its vocal is baked in. Record a vocal take or import the vocal stem, then I can mix it against the beat."
            )
        }

        // Vocal chain shortcut.
        val wantsVocalChain = Regex(
            "\\b(vocal chain|set ?up (the |my )?(lead |main )?vocals?|mix (the |my )?(lead |main )?vocals?|vocal setup|vocal preset)\\b"
        ).containsMatchIn(text)
        if (wantsVocalChain) {
            val vocalTargets = targets.ifEmpty {
                session.tracks.filter { it.stemCategory == StemCategory.VOCAL }.ifEmpty { listOfNotNull(selected) }
            }
            vocalTargets.forEach { track ->
                add(ToolCallDispatcher.ToolCall("applyVocalChain", mapOf("trackIndex" to track.index.toString())))
            }
        }

        // Compressor model, ratio, threshold and bypass.
        val compressorType = ToolCallDispatcher.compressorTypeFromText(text)
        val compressorOff = Regex("\\b(compressor off|bypass the compressor|no compression|remove the compression)\\b").containsMatchIn(text)
        val wantsCompression = compressorType != null || Regex(
            "\\b(compress|compression|compressor|control the dynamics|glue|smash|punch|snap|tighten the dynamics)\\b"
        ).containsMatchIn(text)
        if (wantsCompression || compressorOff) {
            val ratio = Regex("(\\d+(?:\\.\\d+)?)\\s*:\\s*1").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("\\bratio(?: of| to| at)?\\s*(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
            val threshold = Regex("\\bthreshold(?: to| at)?\\s*(-?\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                if (track.isTwoTrackBeat) {
                    // Bus glue, not stem compression: a slow 2:1 with the sub and
                    // transient attacks left alone. A premaster already has its
                    // limiting, so never add a second limiter here.
                    add(ToolCallDispatcher.ToolCall("setCompressor", buildMap {
                        put("trackIndex", track.index.toString())
                        put("enabled", if (compressorOff) "false" else "true")
                        put("type", CompressorType.VCA.name)
                        put("ratio", (ratio ?: 2f).coerceAtMost(2f).toString())
                        put("thresholdDb", (threshold ?: -14f).coerceIn(-24f, -8f).toString())
                        put("attackMs", "30")
                        put("releaseMs", "250")
                        put("makeupGainDb", "0.5")
                    }))
                } else {
                    val type = compressorType ?: if (track.stemCategory == StemCategory.VOCAL) CompressorType.LA_2A else CompressorType.VCA
                    add(ToolCallDispatcher.ToolCall("setCompressor", buildMap {
                        put("trackIndex", track.index.toString())
                        put("enabled", if (compressorOff) "false" else "true")
                        put("type", type.name)
                        ratio?.let { put("ratio", it.toString()) }
                        threshold?.let { put("thresholdDb", it.toString()) }
                    }))
                }
            }
        }

        // Saturation flavour.
        val saturationType = ToolCallDispatcher.saturationTypeFromText(text)
        val wantsSaturation = saturationType != null && !text.contains("tube tech") && !text.contains("tube-tech") ||
            Regex("\\b(saturat|tape warm|tape drive|tube warm|drive it|harmonic|grit|analog warmth)\\b").containsMatchIn(text)
        val saturationOff = Regex("\\b(saturation off|bypass the saturation|remove the saturation|clean it up)\\b").containsMatchIn(text)
        if (wantsSaturation || saturationOff) {
            val drive = Regex("\\bdrive(?: to| at)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:db)?\\b").find(text)
                ?.groupValues?.get(1)?.toFloatOrNull()
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                if (track.isTwoTrackBeat) {
                    // Saturation on a finished stereo beat is a glue decision:
                    // keep it barely there so the premaster keeps its headroom.
                    add(ToolCallDispatcher.ToolCall("setSaturation", mapOf(
                        "trackIndex" to track.index.toString(),
                        "enabled" to if (saturationOff) "false" else "true",
                        "type" to SaturationType.TAPE.name,
                        "driveDb" to "1.5",
                        "mix" to "0.2"
                    )))
                } else {
                    add(ToolCallDispatcher.ToolCall("setSaturation", buildMap {
                        put("trackIndex", track.index.toString())
                        put("enabled", if (saturationOff) "false" else "true")
                        put("type", (saturationType ?: SaturationType.TUBE).name)
                        put("driveDb", (drive ?: 3.5f).toString())
                    }))
                }
            }
        }

        // High-pass / low-pass.
        val wantsHighPass = Regex(
            "\\b(high[- ]?pass|hpf|low[- ]?cut|cut the lows?|remove the (rumble|mud)|clean the low end)\\b"
        ).containsMatchIn(text)
        val wantsLowPass = Regex(
            "\\b(low[- ]?pass|lpf|high[- ]?cut|cut the highs?|remove the (hiss|fizz)|soften the top end)\\b"
        ).containsMatchIn(text)
        val parsedFreq = parseFrequency(text)
        if (wantsHighPass) {
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                // On a 2-track beat only a rumble filter is safe; 90 Hz would
                // remove the kick and 808 the beat was mixed around.
                val frequency = if (track.isTwoTrackBeat) {
                    (parsedFreq ?: 30f).coerceIn(20f, 40f)
                } else {
                    parsedFreq ?: 90f
                }
                add(ToolCallDispatcher.ToolCall("setEqFilter", mapOf(
                    "trackIndex" to track.index.toString(),
                    "filter" to "highPass",
                    "frequencyHz" to frequency.toString(),
                    "enabled" to "true"
                )))
            }
        }
        if (wantsLowPass) {
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setEqFilter", mapOf(
                    "trackIndex" to track.index.toString(),
                    "filter" to "lowPass",
                    "frequencyHz" to (parsedFreq ?: 16000f).toString(),
                    "enabled" to "true"
                )))
            }
        }

        // Vocal rider.
        if (Regex("\\b(vocal rider|ride the vocal|level the vocal|keep the vocal (even|consistent)|vocal leveler)\\b")
                .containsMatchIn(text)
        ) {
            targets.ifEmpty { session.tracks.filter { it.stemCategory == StemCategory.VOCAL }.ifEmpty { listOfNotNull(selected) } }
                .forEach { track ->
                    add(ToolCallDispatcher.ToolCall("setVocalRider", mapOf(
                        "trackIndex" to track.index.toString(),
                        "enabled" to "true",
                        "targetLufs" to "-18"
                    )))
                }
        }

        // Resonance suppression / de-essing.
        val wantsResonance = Regex("\\b(resonance|resonant|harsh|harshness|nasal|de[- ]?ess|sibilance)\\b")
            .containsMatchIn(text)
        if (wantsResonance) {
            val isDeEss = text.contains("de-ess") || text.contains("deess") || text.contains("sibilance")
            val freq = parsedFreq ?: if (isDeEss) 6500f else 3200f
            targets.ifEmpty { session.tracks.filter { it.stemCategory == StemCategory.VOCAL }.ifEmpty { listOfNotNull(selected) } }
                .forEach { track ->
                    add(ToolCallDispatcher.ToolCall("setResonanceSmoother", mapOf(
                        "trackIndex" to track.index.toString(),
                        "enabled" to "true",
                        "frequencyHz" to freq.toString(),
                        "depthDb" to if (isDeEss) "-5" else "-6"
                    )))
                }
        }

        // Tone requests map to the same bounded EQ moves the advisor proposes.
        val wantsBright = Regex("\\b(bright|brighter|air|airy|sparkle|crisp|sheen|open up the top)\\b").containsMatchIn(text)
        val wantsWarmth = Regex("\\b(warm|warmer|fuller|body|analog warmth)\\b").containsMatchIn(text)
        val wantsMudCut = Regex("\\b(mud|muddy|boxy|cloudy|clean up the mids)\\b").containsMatchIn(text)
        val wantsPresence = Regex("\\b(presence|forward|upfront|in your face|cut through)\\b").containsMatchIn(text)
        targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
            // Broad strokes only on a finished stereo mix: narrow boosts turn
            // into stem surgery the engineer cannot undo inside the beat.
            val twoTrack = track.isTwoTrackBeat
            val bandQ = if (twoTrack) "0.7" else "1.2"
            if (wantsBright) {
                add(ToolCallDispatcher.ToolCall("setEqBand", mapOf(
                    "trackIndex" to track.index.toString(),
                    "band" to "4",
                    "frequencyHz" to "10000",
                    "gainDb" to if (twoTrack) "1.5" else "2.5",
                    "enabled" to "true"
                )))
            }
            if (wantsMudCut) {
                add(ToolCallDispatcher.ToolCall("setEqBand", mapOf(
                    "trackIndex" to track.index.toString(),
                    "band" to "2",
                    "frequencyHz" to "350",
                    "gainDb" to if (twoTrack) "-1.5" else "-2.5",
                    "qFactor" to bandQ,
                    "enabled" to "true"
                )))
            }
            if (wantsPresence) {
                add(ToolCallDispatcher.ToolCall("setEqBand", mapOf(
                    "trackIndex" to track.index.toString(),
                    "band" to "3",
                    "frequencyHz" to "3000",
                    "gainDb" to if (twoTrack) "1.0" else "2.0",
                    "qFactor" to bandQ,
                    "enabled" to "true"
                )))
            }
            if (wantsWarmth) {
                add(ToolCallDispatcher.ToolCall("setSaturation", mapOf(
                    "trackIndex" to track.index.toString(),
                    "enabled" to "true",
                    "type" to (if (twoTrack) SaturationType.TAPE else SaturationType.TUBE).name,
                    "driveDb" to if (twoTrack) "1.5" else "2.5",
                    "warmth" to if (twoTrack) "0.35" else "0.6",
                    "mix" to if (twoTrack) "0.2" else "0.35"
                )))
            }
        }

        // Fader rides.
        val explicitGain = Regex("\\b(?:gain|fader|level)\\s*(?:to|at)?\\s*(-?\\d+(?:\\.\\d+)?)\\s*(?:db|decibels?)\\b")
            .find(text)?.groupValues?.get(1)?.toFloatOrNull()
        val gainDelta = Regex("\\b(?:turn|bring|push|take)\\s+(?:it\\s+|the\\s+\\w+\\s+)?(up|down)\\s*(?:by\\s*)?(\\d+(?:\\.\\d+)?)?\\s*(?:db|decibels?)?")
            .find(text)?.let { m ->
                val magnitude = m.groupValues[2].toFloatOrNull() ?: 2f
                if (m.groupValues[1] == "up") magnitude else -magnitude
            }
            ?: when {
                Regex("\\b(louder|boost it|more level)\\b").containsMatchIn(text) -> 2f
                Regex("\\b(quieter|softer|less level|turn it down)\\b").containsMatchIn(text) -> -2f
                Regex("\\b(?:boost|cut)\\s+(-?\\d+(?:\\.\\d+)?)\\s*(?:db|decibels?)\\b")
                    .containsMatchIn(text) -> Regex("\\b(?:boost|cut)\\s+(-?\\d+(?:\\.\\d+)?)\\s*(?:db|decibels?)\\b")
                    .find(text)?.let {
                        val amount = it.groupValues[1].toFloatOrNull() ?: 0f
                        if (text.substring(it.range.first).startsWith("cut")) -kotlin.math.abs(amount) else kotlin.math.abs(amount)
                    }
                else -> null
            }
        if (explicitGain != null || gainDelta != null) {
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                val newGain = (explicitGain ?: (track.gainDb + (gainDelta ?: 0f)))
                    .coerceIn(-60f, 12f)
                add(ToolCallDispatcher.ToolCall("setTrackGain", mapOf(
                    "trackIndex" to track.index.toString(),
                    "gainDb" to newGain.toString()
                )))
            }
        }

        // Pan.
        when {
            Regex("\\b(pan|send|move)\\b.*\\bleft\\b|\\bhard left\\b").containsMatchIn(text) ->
                targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                    add(ToolCallDispatcher.ToolCall("setTrackPan", mapOf(
                        "trackIndex" to track.index.toString(),
                        "pan" to "-0.7"
                    )))
                }
            Regex("\\b(pan|send|move)\\b.*\\bright\\b|\\bhard right\\b").containsMatchIn(text) ->
                targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                    add(ToolCallDispatcher.ToolCall("setTrackPan", mapOf(
                        "trackIndex" to track.index.toString(),
                        "pan" to "0.7"
                    )))
                }
            Regex("\\b(center|centre|middle)\\b").containsMatchIn(text) ->
                targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                    add(ToolCallDispatcher.ToolCall("setTrackPan", mapOf(
                        "trackIndex" to track.index.toString(),
                        "pan" to "0.0"
                    )))
                }
        }

        // Reverb send.
        if (Regex("\\b(more reverb|add reverb|wetter)\\b").containsMatchIn(text)) {
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setReverbSend", mapOf(
                    "trackIndex" to track.index.toString(),
                    "sendLevel" to (track.reverbSendLevel + 0.15f).coerceIn(0f, 1f).toString()
                )))
            }
        } else if (Regex("\\b(less reverb|drier|dry it up)\\b").containsMatchIn(text)) {
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setReverbSend", mapOf(
                    "trackIndex" to track.index.toString(),
                    "sendLevel" to (track.reverbSendLevel - 0.15f).coerceIn(0f, 1f).toString()
                )))
            }
        }

        // Chorus / doubler.
        if (Regex("\\b(chorus|doubler|thicken|widen|lush)\\b").containsMatchIn(text)) {
            val off = Regex("\\b(chorus off|bypass the chorus|no chorus)\\b").containsMatchIn(text)
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setChorus", mapOf(
                    "trackIndex" to track.index.toString(),
                    "enabled" to (!off).toString(),
                    "rateHz" to "1.2",
                    "depthMs" to "3.5",
                    "mix" to "0.4"
                )))
            }
        }

        // Delay / echo.
        if (Regex("\\b(delay|echo|slapback|repeats)\\b").containsMatchIn(text)) {
            val off = Regex("\\b(delay off|no delay|dry)\\b").containsMatchIn(text)
            val time = parseFrequency(text)?.let { if (it < 1000f) it else null } ?: 350f
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setDelay", mapOf(
                    "trackIndex" to track.index.toString(),
                    "enabled" to (!off).toString(),
                    "timeMs" to time.toString(),
                    "feedback" to "0.35",
                    "mix" to "0.25"
                )))
            }
        }

        // Channel limiter.
        if (Regex("\\b(limiter|brickwall|peak (protect|protection)|ceiling)\\b").containsMatchIn(text)) {
            val off = Regex("\\b(limiter off|no limiter|bypass the limiter)\\b").containsMatchIn(text)
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setLimiter", mapOf(
                    "trackIndex" to track.index.toString(),
                    "enabled" to (!off).toString(),
                    "ceilingDb" to "-0.5"
                )))
            }
        }

        // Auto-pitch correction.
        if (Regex("\\b(auto[- ]?pitch|pitch correct|tune the (vocal|voice)|autotune)\\b").containsMatchIn(text)) {
            val off = Regex("\\b(auto[- ]?pitch off|no pitch correction)\\b").containsMatchIn(text)
            targets.ifEmpty { session.tracks.filter { it.stemCategory == StemCategory.VOCAL }.ifEmpty { listOfNotNull(selected) } }
                .forEach { track ->
                    add(ToolCallDispatcher.ToolCall("setAutoPitch", mapOf(
                        "trackIndex" to track.index.toString(),
                        "enabled" to (!off).toString(),
                        "correctionStrength" to "1.0"
                    )))
                }
        }

        // Noise reduction.
        if (Regex("\\b(noise (reduction|gate|floor)|denoise|clean (up )?the noise)\\b").containsMatchIn(text)) {
            val off = Regex("\\b(noise reduction off|no noise reduction)\\b").containsMatchIn(text)
            targets.ifEmpty { session.tracks.filter { it.stemCategory == StemCategory.VOCAL }.ifEmpty { listOfNotNull(selected) } }
                .forEach { track ->
                    add(ToolCallDispatcher.ToolCall("setNoiseReduction", mapOf(
                        "trackIndex" to track.index.toString(),
                        "enabled" to (!off).toString(),
                        "thresholdDb" to "-40",
                        "reductionDb" to "-12"
                    )))
                }
        }

        // Sidechain routing.
        val sidechainMatch = Regex("\\bsidechain (?:to|from|source)\\s+(?:track\\s*)?(\\d{1,2})\\b").find(text)
        if (sidechainMatch != null) {
            val source = sidechainMatch.groupValues[1].toIntOrNull()
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                source?.let {
                    add(ToolCallDispatcher.ToolCall("setSidechain", mapOf(
                        "trackIndex" to track.index.toString(),
                        "sourceTrackIndex" to (it - 1).toString()
                    )))
                }
            }
        }

        // Aux send level.
        val sendMatch = Regex("\\bsend\\s+(?:to\\s+)?(reverb|delay|aux\\s*\\d*)\\s*(?:at|to)?\\s*(\\d+(?:\\.\\d+)?)").find(text)
        if (sendMatch != null) {
            val bus = sendMatch.groupValues[1].trim()
            val level = (sendMatch.groupValues[2].toFloatOrNull() ?: 50f) / 100f
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setTrackSend", mapOf(
                    "trackIndex" to track.index.toString(),
                    "bus" to bus,
                    "level" to level.toString()
                )))
            }
        }

        // Master bus processing.
        if (Regex("\\b(master limiter|master ceiling|loudness ceiling)\\b").containsMatchIn(text)) {
            val off = Regex("\\b(master limiter off)\\b").containsMatchIn(text)
            add(ToolCallDispatcher.ToolCall("setMasterLimiter", mapOf(
                "enabled" to (!off).toString(),
                "ceilingDb" to "-0.3"
            )))
        }
        if (Regex("\\b(master reverb|room (size|tone)|decay time)\\b").containsMatchIn(text)) {
            val decay = Regex("\\bdecay\\s*(?:to|at)?\\s*(\\d+(?:\\.\\d+)?)\\s*s").find(text)?.groupValues?.get(1)?.toFloatOrNull()
            add(ToolCallDispatcher.ToolCall("setMasterReverb", buildMap {
                put("enabled", "true")
                decay?.let { put("decayTimeSec", it.toString()) }
            }))
        }

        // Mute / solo / arm.
        val wantsUnmute = Regex("\\bunmute\\b").containsMatchIn(text)
        val wantsMute = !wantsUnmute && Regex("\\bmute\\b").containsMatchIn(text)
        val wantsUnsolo = Regex("\\bunsolo\\b").containsMatchIn(text)
        val wantsSolo = !wantsUnsolo && Regex("\\bsolo\\b").containsMatchIn(text)
        if (wantsMute || wantsUnmute) {
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setTrackMute", mapOf(
                    "trackIndex" to track.index.toString(),
                    "muted" to (!wantsUnmute).toString()
                )))
            }
        }
        if (wantsSolo || wantsUnsolo) {
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setTrackSolo", mapOf(
                    "trackIndex" to track.index.toString(),
                    "soloed" to (!wantsUnsolo).toString()
                )))
            }
        }
        if (Regex("\\barm\\b").containsMatchIn(text)) {
            targets.firstOrNull()?.let { track ->
                add(ToolCallDispatcher.ToolCall("armTrack", mapOf("trackIndex" to track.index.toString())))
            }
        }

        if (calls.isEmpty()) {
            val clarification =
                "I didn't catch a mix move. Try \"set up the lead vocal\", \"compress the drums with an 1176\", or \"high-pass the guitars at 100 hertz\"."
            return Plan(emptyList(), "", clarification)
        }
        return Plan(calls, summarize(calls, session))
    }

    /**
     * Content-aware fallback when the model returns no usable tool calls and the
     * session already has measured features. This is the "learn" part of the
     * smart-engineer idea: level-match by measured loudness, apply role offsets,
     * and spread the wide sources. It guarantees the engineer never silently
     * does nothing, and it is still bounded to the app's own tools.
     */
    fun genericMix(session: ProjectSession, features: List<TrackFeatures>): Plan {
        val measured = session.tracks.mapNotNull { track ->
            val f = features.firstOrNull { it.trackIndex == track.index }
            if (f == null || f.rmsDb <= -50f || f.peakDb <= -50f) null else track to f
        }
        if (measured.size < 2) {
            return Plan(emptyList(), "", "I need at least two tracks with audio to balance a mix.")
        }
        val levels = measured.associate { (track, f) ->
            val loudness = if (f.integratedLufs > -50f) f.integratedLufs else f.rmsDb
            // A finished stereo beat is the record itself, not a stem bus, so it
            // gets no role offset: stem-grade offsets would push the premaster
            // several dB below its own measured level.
            val offset = if (track.isTwoTrackBeat) 0f else roleOffset(track.stemCategory)
            track.index to (-loudness + offset)
        }
        val anchorIndex = measured.firstOrNull { it.first.stemCategory == StemCategory.DRUMS }?.first?.index
            ?: measured.firstOrNull { it.first.isTwoTrackBeat }?.first?.index
        val anchor = anchorIndex?.let { levels[it] } ?: (levels.values.minOrNull() ?: 0f)
        val pans = panSpread(measured)
        val calls = mutableListOf<ToolCallDispatcher.ToolCall>()
        for ((track, _) in measured) {
            val relativeGain = (levels[track.index] ?: anchor) - anchor
            // Broad bus moves only on a finished mix: a -24 dB "stem" cut would
            // gut the whole record.
            val gainDb = if (track.isTwoTrackBeat) relativeGain.coerceIn(-6f, 6f)
            else relativeGain.coerceIn(-24f, 6f)
            calls += ToolCallDispatcher.ToolCall(
                "setTrackGain",
                mapOf(
                    "trackIndex" to track.index.toString(),
                    "gainDb" to String.format(Locale.US, "%.1f", gainDb)
                )
            )
            pans[track.index]?.let { pan ->
                if (abs(pan) > 0.01f) {
                    calls += ToolCallDispatcher.ToolCall(
                        "setTrackPan",
                        mapOf(
                            "trackIndex" to track.index.toString(),
                            "pan" to String.format(Locale.US, "%.2f", pan)
                        )
                    )
                }
            }
        }
        return Plan(calls, "Balanced ${measured.size} tracks from measured loudness.", source = "Built-in analyser")
    }

    private fun roleOffset(category: StemCategory): Float = when (category) {
        StemCategory.DRUMS -> 0f
        StemCategory.BASS -> -1.5f
        StemCategory.VOCAL -> 0.5f
        StemCategory.GUITAR -> -2.5f
        StemCategory.KEYS -> -3f
        else -> -4f
    }

    private fun panSpread(measured: List<Pair<TrackData, TrackFeatures>>): Map<Int, Float> {
        val pans = mutableMapOf<Int, Float>()
        val wide = measured.filter { (track, _) ->
            // A 2-track beat is a mastered stereo image; spreading it would
            // desynchronise the record from itself. Only isolated stems pan.
            !track.isTwoTrackBeat && (
                track.stemCategory == StemCategory.KEYS ||
                    track.stemCategory == StemCategory.GUITAR ||
                    track.stemCategory == StemCategory.OTHER
                )
        }
        wide.forEachIndexed { i, (track, _) ->
            if (abs(track.pan) > 0.05f) return@forEachIndexed
            val sign = if (i % 2 == 0) -1f else 1f
            pans[track.index] = sign * (0.12f + 0.04f * (i / 2)).coerceAtMost(0.3f)
        }
        return pans
    }

    private fun resolveTargets(
        text: String,
        session: ProjectSession,
        selectedTrackIndex: Int
    ): List<TrackData> {
        val twoTrackTracks = session.tracks.filter { it.isTwoTrackBeat }
        val explicitTrack = Regex("\\b(?:track|channel|ch)\\s*(\\d{1,2})\\b").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (explicitTrack != null && explicitTrack in 1..session.tracks.size) {
            return listOfNotNull(session.tracks.firstOrNull { it.index == explicitTrack - 1 })
        }
        // The beat itself wins over a stem name: "compress the drums on the
        // 2-track" means the stereo beat, not a drum stem that does not exist.
        if (Regex("\\b(beat|instrumental|backing track|2[- ]?track|two[- ]?track|stereo mix)\\b")
                .containsMatchIn(text)
        ) {
            return twoTrackTracks
        }
        if (Regex("\\ball (tracks|channels|vocals|drums|guitars|bass)\\b|\\beverything\\b|\\bthe whole mix\\b")
                .containsMatchIn(text)
        ) {
            return when {
                text.contains("vocal") -> session.tracks.filter { it.stemCategory == StemCategory.VOCAL }
                text.contains("drum") -> session.tracks.filter { it.stemCategory == StemCategory.DRUMS }.ifEmpty { twoTrackTracks }
                text.contains("guitar") -> session.tracks.filter { it.stemCategory == StemCategory.GUITAR }
                text.contains("bass") -> session.tracks.filter { it.stemCategory == StemCategory.BASS }.ifEmpty { twoTrackTracks }
                else -> session.tracks
            }
        }

        fun byName(fragment: String): List<TrackData> =
            session.tracks.filter { it.name.contains(fragment, ignoreCase = true) }

        return when {
            Regex("\\bharmon(y|ies)|backing vocal").containsMatchIn(text) -> byName("harmon")
            Regex("\\blead vocal|main vocal|the vocal|vocals?\\b").containsMatchIn(text) ->
                session.tracks.filter { it.stemCategory == StemCategory.VOCAL }
                    .sortedBy { if (it.name.contains("lead", true)) 0 else 1 }
                    .take(1)
            Regex("\\bkick\\b").containsMatchIn(text) -> byName("kick").ifEmpty { twoTrackTracks }
            Regex("\\bsnare|clap\\b").containsMatchIn(text) -> byName("snare").ifEmpty { twoTrackTracks }
            Regex("\\bhi.?hat|hats\\b").containsMatchIn(text) -> byName("hat")
            Regex("\\btoms?\\b|perc").containsMatchIn(text) -> byName("tom")
            Regex("\\b808|sub bass\\b").containsMatchIn(text) -> (byName("808") + byName("sub bass")).ifEmpty { twoTrackTracks }
            Regex("\\bsynth bass\\b").containsMatchIn(text) -> byName("synth bass")
            Regex("\\bdrums?\\b|drum bus|the kit\\b").containsMatchIn(text) ->
                session.tracks.filter { it.stemCategory == StemCategory.DRUMS }.ifEmpty { twoTrackTracks }
            Regex("\\bbass\\b").containsMatchIn(text) ->
                session.tracks.filter { it.stemCategory == StemCategory.BASS }.take(1).ifEmpty { twoTrackTracks }
            Regex("\\bguitars?\\b").containsMatchIn(text) ->
                session.tracks.filter { it.stemCategory == StemCategory.GUITAR }
            Regex("\\bpiano\\b").containsMatchIn(text) -> byName("piano")
            Regex("\\brhodes|e.?piano\\b").containsMatchIn(text) -> byName("rhodes")
            Regex("\\bpad\\b").containsMatchIn(text) -> byName("pad")
            Regex("\\bsynth lead|lead synth\\b").containsMatchIn(text) -> byName("synth lead")
            Regex("\\bsynth|keys\\b").containsMatchIn(text) ->
                session.tracks.filter { it.stemCategory == StemCategory.KEYS }
            Regex("\\bfx|riser|drops?\\b").containsMatchIn(text) -> byName("fx")
            else -> emptyList()
        }
    }

    private fun parseFrequency(text: String): Float? {
        val khz = Regex("(\\d+(?:\\.\\d+)?)\\s*k(?:ilo)?h(?:z|ertz)").find(text)
        if (khz != null) return (khz.groupValues[1].toFloatOrNull() ?: 0f) * 1000f
        val hz = Regex("(\\d{2,5}(?:\\.\\d+)?)\\s*h(?:z|ertz)").find(text)
        return hz?.groupValues?.get(1)?.toFloatOrNull()
    }

    /**
     * Last line of defence for a stereo beat: a local model can ignore the
     * prompt and emit stem moves anyway. Filter those out and clamp broad bus
     * parameters so a 2-track is never carved up like separated stems.
     */
    private val TWO_TRACK_UNSAFE_TOOLS = setOf(
        "applyVocalChain", "setVocalRider", "setResonanceSmoother",
        "setSidechain", "setAutoPitch", "setNoiseReduction"
    )

    fun enforceSourceFormat(
        calls: List<ToolCallDispatcher.ToolCall>,
        session: ProjectSession
    ): List<ToolCallDispatcher.ToolCall> = calls.mapNotNull { call ->
        val idx = call.args["trackIndex"]?.toIntOrNull() ?: return@mapNotNull call
        val track = session.tracks.firstOrNull { it.index == idx } ?: return@mapNotNull call
        if (!track.isTwoTrackBeat) return@mapNotNull call

        when (call.name) {
            in TWO_TRACK_UNSAFE_TOOLS -> null
            "setEqBand" -> {
                val gain = call.args["gainDb"]?.toFloatOrNull()?.coerceIn(-1.5f, 1.5f)
                    ?: return@mapNotNull call
                val q = call.args["qFactor"]?.toFloatOrNull()?.coerceIn(0.5f, 1.2f)
                val clamped = call.args + ("gainDb" to gain.toString()) +
                    if (q != null) mapOf("qFactor" to q.toString()) else emptyMap()
                call.copy(args = clamped)
            }
            "setEqFilter" -> {
                val raw = call.args["filter"]?.lowercase().orEmpty()
                when {
                    raw.contains("low") -> {
                        val freq = call.args["frequencyHz"]?.toFloatOrNull() ?: 12000f
                        call.copy(args = call.args + ("frequencyHz" to freq.coerceAtLeast(12000f).toString()))
                    }
                    // Missing filter defaults to high-pass in the dispatcher.
                    else -> call.copy(args = call.args + ("frequencyHz" to "30.0"))
                }
            }
            "setCompressor" -> {
                val threshold = call.args["thresholdDb"]?.toFloatOrNull()?.coerceIn(-24f, -8f) ?: -14f
                val attack = call.args["attackMs"]?.toFloatOrNull()?.coerceAtLeast(30f) ?: 30f
                val release = call.args["releaseMs"]?.toFloatOrNull()?.coerceAtLeast(200f) ?: 250f
                val makeup = call.args["makeupGainDb"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
                call.copy(args = call.args + mapOf(
                    "type" to CompressorType.VCA.name,
                    "ratio" to "2.0",
                    "thresholdDb" to threshold.toString(),
                    "attackMs" to attack.toString(),
                    "releaseMs" to release.toString(),
                    "makeupGainDb" to makeup.toString()
                ))
            }
            "setSaturation" -> call.copy(args = call.args + mapOf(
                "type" to SaturationType.TAPE.name,
                "driveDb" to "1.5",
                "mix" to "0.2"
            ))
            else -> call
        }
    }

    fun summarize(calls: List<ToolCallDispatcher.ToolCall>, session: ProjectSession): String {
        fun name(args: Map<String, String>): String {
            args["trackName"]?.let { return it }
            val idx = args["trackIndex"]?.toIntOrNull() ?: return "the selected track"
            return session.tracks.firstOrNull { it.index == idx }?.name ?: "track ${idx + 1}"
        }
        val parts = calls.map { call ->
            when (call.name) {
                "transport" -> "transport ${call.args["action"] ?: "command"}"
                "setTrackGain" -> "${name(call.args)} at ${call.args["gainDb"]} dB"
                "setTrackPan" -> "panned ${name(call.args)}"
                "setTrackMute" -> "${if (call.args["muted"] == "true") "muted" else "unmuted"} ${name(call.args)}"
                "setTrackSolo" -> "${if (call.args["soloed"] == "true") "soloed" else "unsoloed"} ${name(call.args)}"
                "armTrack" -> "armed ${name(call.args)}"
                "setCompressor" -> "${compressorLabel(call.args["type"])} on ${name(call.args)}"
                "setSaturation" -> "${saturationLabel(call.args["type"])} on ${name(call.args)}"
                "setEqBand" -> "EQ band ${call.args["band"]} on ${name(call.args)}"
                "setEqFilter" -> if (call.args["filter"] == "lowPass") "low-pass on ${name(call.args)}" else "high-pass on ${name(call.args)}"
                "setVocalRider" -> "vocal rider on ${name(call.args)}"
                "setResonanceSmoother" -> "resonance control on ${name(call.args)}"
                "applyVocalChain" -> "vocal chain on ${name(call.args)}"
                "setReverbSend" -> "reverb send on ${name(call.args)}"
                "setTrackSend" -> "${call.args["bus"]} send on ${name(call.args)}"
                "setSidechain" -> "sidechain on ${name(call.args)}"
                "setChorus" -> "chorus on ${name(call.args)}"
                "setDelay" -> "delay on ${name(call.args)}"
                "setLimiter" -> "limiter on ${name(call.args)}"
                "setAutoPitch" -> "auto-pitch on ${name(call.args)}"
                "setNoiseReduction" -> "noise reduction on ${name(call.args)}"
                "setMasterLimiter" -> "master limiter ${if (call.args["enabled"] == "false") "off" else "on"}"
                "setMasterReverb" -> "master reverb updated"
                "setMasterGain" -> "master at ${call.args["gainDb"]} dB"
                else -> call.name
            }
        }.distinct()
        val head = parts.take(4).joinToString(", ")
        val tail = if (parts.size > 4) " and ${parts.size - 4} more move${if (parts.size - 4 == 1) "" else "s"}" else ""
        val twoTrackTouched = calls.any { call ->
            val idx = call.args["trackIndex"]?.toIntOrNull() ?: return@any false
            session.tracks.firstOrNull { it.index == idx }?.isTwoTrackBeat == true
        }
        val note = if (twoTrackTouched) " The 2-track beat stays one stereo mix, so these are broad bus moves." else ""
        return "Done. $head$tail.$note"
    }

    private fun compressorLabel(raw: String?): String = when (ToolCallDispatcher.compressorTypeFromText(raw)) {
        CompressorType.LA_2A -> "LA-2A compression"
        CompressorType.FET_1176 -> "1176 FET compression"
        CompressorType.TUBE_TECH -> "Tube-Tech compression"
        CompressorType.VCA -> "VCA compression"
        null -> "compression"
    }

    private fun saturationLabel(raw: String?): String = when (ToolCallDispatcher.saturationTypeFromText(raw)) {
        SaturationType.TAPE -> "tape saturation"
        SaturationType.TUBE -> "tube saturation"
        SaturationType.TRANSFORMER -> "transformer saturation"
        SaturationType.CONSOLE -> "console saturation"
        null -> "saturation"
    }

    /**
     * System prompt for the local model. It lists the exact tool vocabulary and
     * the session tracks; the response is parsed into the same ToolCalls as the
     * deterministic planner.
     */
    fun systemPromptFor(session: ProjectSession, features: List<TrackFeatures> = emptyList()): String {
        val twoTrack = session.isTwoTrackBeat
        val tracks = session.tracks.joinToString("\n") { track ->
            val kind = if (track.isTwoTrackBeat) {
                "[${track.sourceFormat.displayName.uppercase()} - one stereo file, NOT separated stems]"
            } else {
                "[${track.stemCategory.displayName}]"
            }
            val measured = features.firstOrNull { it.trackIndex == track.index }?.let { f ->
                val loudness = if (f.integratedLufs > -50f) f.integratedLufs else f.rmsDb
                " measuredLUFS=${String.format(Locale.US, "%.1f", loudness)}" +
                    " rms=${String.format(Locale.US, "%.1f", f.rmsDb)}" +
                    " centroid=${f.spectralCentroidHz.toInt()}Hz"
            }.orEmpty()
            "${track.index}. ${track.name} $kind gain=${track.gainDb}dB pan=${track.pan}$measured"
        }
        val sourceRules = if (twoTrack) {
            """
            SOURCE FORMAT: 2-TRACK STEREO BEAT. The beat is a finished stereo mix.
            2-track rules:
            - Never treat a 2-track track as isolated drums, bass, keys or vocals.
            - Do not emit applyVocalChain, setVocalRider, setResonanceSmoother or setSidechain on a 2-track track.
            - For "drums" or "bass" requests on a 2-track, use broad bus moves: EQ 1.5 dB or less, gentle 2:1 compression with a 30 ms attack.
            - For a premaster, do not stack another limiter or heavy glue.
            - If the user asks to process a vocal inside a 2-track beat, return no objects and explain that the vocal is baked into the stereo file.
            """.trimIndent()
        } else {
            "SOURCE FORMAT: Separate stems. Each track can be processed as its own instrument."
        }
        return """
            You are NC Studio's recording and mixing engineer. Convert the user's spoken request into JSON tool calls only. No prose, no markdown.
            Emit one flat JSON object per line, for example:
            {"tool":"setCompressor","trackIndex":"13","enabled":"true","type":"LA_2A","thresholdDb":"-20","ratio":"3"}
            Available tools:
            transport (action: play|stop|record|stopRecording|loopOn|loopOff)
            setTrackGain (trackIndex, gainDb)
            setTrackPan (trackIndex, pan)
            setTrackMute (trackIndex, muted)
            setTrackSolo (trackIndex, soloed)
            armTrack (trackIndex)
            setCompressor (trackIndex, enabled, type: VCA|LA_2A|FET_1176|TUBE_TECH, thresholdDb, ratio, attackMs, releaseMs, makeupGainDb)
            setSaturation (trackIndex, enabled, type: TAPE|TUBE|TRANSFORMER|CONSOLE, driveDb, warmth, mix)
            setEqBand (trackIndex, band: 1|2|3|4, frequencyHz, gainDb, qFactor, enabled)
            setEqFilter (trackIndex, filter: highPass|lowPass, frequencyHz, enabled)
            setVocalRider (trackIndex, enabled, targetLufs, maxGainDb, minGainDb, sensitivity)
            setResonanceSmoother (trackIndex, enabled, frequencyHz, thresholdDb, depthDb, qFactor)
            applyVocalChain (trackIndex)
            setReverbSend (trackIndex, sendLevel)
            setTrackSend (trackIndex, bus, level)
            setSidechain (trackIndex, sourceTrackIndex)
            setChorus (trackIndex, enabled, rateHz, depthMs, feedback, stereoSpread, mix)
            setDelay (trackIndex, enabled, timeMs, feedback, mix, duckingEnabled, duckingThresholdDb, duckingDepthDb, duckingReleaseMs)
            setLimiter (trackIndex, enabled, ceilingDb, releaseMs)
            setAutoPitch (trackIndex, enabled, correctionStrength)
            setNoiseReduction (trackIndex, enabled, thresholdDb, reductionDb)
            setMasterLimiter (enabled, ceilingDb, thresholdDb, releaseMs)
            setMasterReverb (enabled, roomSize, damping, stereoWidth, returnLevel, preDelayMs, decayTimeSec, lowCutHz, highCutHz)
            setMasterGain (gainDb)
            $sourceRules
            Never invent a track index. Use the exact track index from the session list. If the request is unclear, return no objects.
            Use measuredLUFS/rms when present: do not boost a track that is already the loudest, and prefer a cut or a pan move when two tracks overlap.
            Session tracks:
            $tracks
        """.trimIndent()
    }
}
