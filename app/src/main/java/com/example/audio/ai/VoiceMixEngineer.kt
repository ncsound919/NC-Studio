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
import com.example.audio.model.ProjectSession
import com.example.audio.model.SaturationType
import com.example.audio.model.StemCategory
import com.example.audio.model.TrackData
import java.util.Locale

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
            }
        }
    }

    fun cancelListening() {
        mainHandler.post {
            try {
                recognizer?.cancel()
            } catch (_: Throwable) {
            }
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
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pulse_engineer")
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        mainHandler.post {
            try {
                recognizer?.destroy()
            } catch (_: Throwable) {
            }
            recognizer = null
            listening = false
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Throwable) {
            }
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
            "\\b(rough mix|mix (it|this|the session|the song)|do a mix|take it away|make it sound (good|professional|finished))\\b"
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
            return Plan(calls, summarize(calls, session), source = "Built-in engineer")
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

        // Saturation flavour.
        val saturationType = ToolCallDispatcher.saturationTypeFromText(text)
        val wantsSaturation = saturationType != null && !text.contains("tube tech") && !text.contains("tube-tech") ||
            Regex("\\b(saturat|tape warm|tape drive|tube warm|drive it|harmonic|grit|analog warmth)\\b").containsMatchIn(text)
        val saturationOff = Regex("\\b(saturation off|bypass the saturation|remove the saturation|clean it up)\\b").containsMatchIn(text)
        if (wantsSaturation || saturationOff) {
            val drive = Regex("\\bdrive(?: to| at)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:db)?\\b").find(text)
                ?.groupValues?.get(1)?.toFloatOrNull()
            targets.ifEmpty { listOfNotNull(selected) }.forEach { track ->
                add(ToolCallDispatcher.ToolCall("setSaturation", buildMap {
                    put("trackIndex", track.index.toString())
                    put("enabled", if (saturationOff) "false" else "true")
                    put("type", (saturationType ?: SaturationType.TUBE).name)
                    put("driveDb", (drive ?: 3.5f).toString())
                }))
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
                add(ToolCallDispatcher.ToolCall("setEqFilter", mapOf(
                    "trackIndex" to track.index.toString(),
                    "filter" to "highPass",
                    "frequencyHz" to (parsedFreq ?: 90f).toString(),
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
            if (wantsBright) {
                add(ToolCallDispatcher.ToolCall("setEqBand", mapOf(
                    "trackIndex" to track.index.toString(),
                    "band" to "4",
                    "frequencyHz" to "10000",
                    "gainDb" to "2.5",
                    "enabled" to "true"
                )))
            }
            if (wantsMudCut) {
                add(ToolCallDispatcher.ToolCall("setEqBand", mapOf(
                    "trackIndex" to track.index.toString(),
                    "band" to "2",
                    "frequencyHz" to "350",
                    "gainDb" to "-2.5",
                    "qFactor" to "1.2",
                    "enabled" to "true"
                )))
            }
            if (wantsPresence) {
                add(ToolCallDispatcher.ToolCall("setEqBand", mapOf(
                    "trackIndex" to track.index.toString(),
                    "band" to "3",
                    "frequencyHz" to "3000",
                    "gainDb" to "2.0",
                    "enabled" to "true"
                )))
            }
            if (wantsWarmth) {
                add(ToolCallDispatcher.ToolCall("setSaturation", mapOf(
                    "trackIndex" to track.index.toString(),
                    "enabled" to "true",
                    "type" to SaturationType.TUBE.name,
                    "driveDb" to "2.5",
                    "warmth" to "0.6",
                    "mix" to "0.35"
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

    private fun resolveTargets(
        text: String,
        session: ProjectSession,
        selectedTrackIndex: Int
    ): List<TrackData> {
        val explicitTrack = Regex("\\b(?:track|channel|ch)\\s*(\\d{1,2})\\b").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (explicitTrack != null && explicitTrack in 1..session.tracks.size) {
            return listOfNotNull(session.tracks.firstOrNull { it.index == explicitTrack - 1 })
        }
        if (Regex("\\ball (tracks|channels|vocals|drums|guitars|bass)\\b|\\beverything\\b|\\bthe whole mix\\b")
                .containsMatchIn(text)
        ) {
            return when {
                text.contains("vocal") -> session.tracks.filter { it.stemCategory == StemCategory.VOCAL }
                text.contains("drum") -> session.tracks.filter { it.stemCategory == StemCategory.DRUMS }
                text.contains("guitar") -> session.tracks.filter { it.stemCategory == StemCategory.GUITAR }
                text.contains("bass") -> session.tracks.filter { it.stemCategory == StemCategory.BASS }
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
            Regex("\\bkick\\b").containsMatchIn(text) -> byName("kick")
            Regex("\\bsnare|clap\\b").containsMatchIn(text) -> byName("snare")
            Regex("\\bhi.?hat|hats\\b").containsMatchIn(text) -> byName("hat")
            Regex("\\btoms?\\b|perc").containsMatchIn(text) -> byName("tom")
            Regex("\\b808|sub bass\\b").containsMatchIn(text) -> byName("808") + byName("sub bass")
            Regex("\\bsynth bass\\b").containsMatchIn(text) -> byName("synth bass")
            Regex("\\bdrums?\\b|drum bus|the kit\\b").containsMatchIn(text) ->
                session.tracks.filter { it.stemCategory == StemCategory.DRUMS }
            Regex("\\bbass\\b").containsMatchIn(text) ->
                session.tracks.filter { it.stemCategory == StemCategory.BASS }.take(1)
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
        return "Done. $head$tail."
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
    fun systemPromptFor(session: ProjectSession): String {
        val tracks = session.tracks.joinToString("\n") { track ->
            "${track.index}. ${track.name} [${track.stemCategory.displayName}] gain=${track.gainDb}dB pan=${track.pan}"
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
            Never invent a track index. Use the exact track index from the session list. If the request is unclear, return no objects.
            Session tracks:
            $tracks
        """.trimIndent()
    }
}
