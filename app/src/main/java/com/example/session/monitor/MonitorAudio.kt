package com.example.session.monitor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.example.session.take.PcmCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-bitrate monitor audio frames.
 *
 * The take is the recorder of record and never comes near this path; the
 * monitor stream exists so the engineer can hear the artist while they work.
 * Frames are compact binary messages on the same WebSocket:
 *
 * ```
 * byte 0      kind (1 = monitor/artist mic, 2 = talkback/engineer mic, 3 = config)
 * bytes 1..4  sequence (big endian int)
 * bytes 5..12 capture timestamp in ms (big endian long)
 * rest        codec payload
 * ```
 */
object MonitorFrame {
    const val KIND_MONITOR: Byte = 1
    const val KIND_TALKBACK: Byte = 2
    const val KIND_CONFIG: Byte = 3

    const val HEADER_BYTES = 13
    const val MAX_FRAME_BYTES = 64 * 1024

    fun encode(kind: Byte, seq: Int, timestampMs: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = kind
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(seq)
        ByteBuffer.wrap(out, 5, 8).order(ByteOrder.BIG_ENDIAN).putLong(timestampMs)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size <= HEADER_BYTES) return null
        if (bytes.size > MAX_FRAME_BYTES) return null
        val kind = bytes[0]
        if (kind != KIND_MONITOR && kind != KIND_TALKBACK && kind != KIND_CONFIG) return null
        val seq = ByteBuffer.wrap(bytes, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        val timestampMs = ByteBuffer.wrap(bytes, 5, 8).order(ByteOrder.BIG_ENDIAN).long
        val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        return Frame(kind, seq, timestampMs, payload)
    }

    data class Frame(
        val kind: Byte,
        val seq: Int,
        val timestampMs: Long,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Frame && kind == other.kind && seq == other.seq &&
                timestampMs == other.timestampMs && payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (((kind * 31 + seq) * 31 + timestampMs.toInt()) * 31) + payload.contentHashCode()
    }
}

/**
 * Re-orders and paces incoming monitor frames.
 *
 * Pure logic, no audio: frames are enqueued by sequence number, played in
 * order once the buffer has [targetDepth] frames of cushion, and dropped when
 * they are too late to be useful. Losing a frame here is harmless; the take on
 * disk is unaffected.
 */
class AudioJitterBuffer(
    private val targetDepth: Int = 3,
    private val maxDepth: Int = 30,
    private val lateThresholdFrames: Int = 12,
) {
    private val pending = sortedMapOf<Int, MonitorFrame.Frame>()
    private var started = false
    private var highestSeq = -1

    var droppedLate: Int = 0
        private set
    var droppedOverflow: Int = 0
        private set

    fun push(frame: MonitorFrame.Frame) {
        if (highestSeq >= 0) {
            val gap = frame.seq - highestSeq
            if (gap <= 0 || gap > lateThresholdFrames) {
                droppedLate++
                return
            }
        }
        pending[frame.seq] = frame
        if (pending.size > maxDepth) {
            val oldest = pending.firstKey()
            pending.remove(oldest)
            droppedOverflow++
            started = true
        }
    }

    /** Next playable frame, or null while the cushion is still filling. */
    fun pop(): MonitorFrame.Frame? {
        if (pending.isEmpty()) return null
        val next = pending.firstKey()
        if (highestSeq >= 0 && next != highestSeq + 1) {
            // A permanent gap: jump forward rather than stall forever.
            if (pending.size > targetDepth) {
                droppedLate += pending.size - 1
                val newest = pending.lastKey()
                val frame = pending[newest]
                pending.clear()
                highestSeq = newest
                started = true
                return frame
            }
        }
        if (!started) {
            if (pending.size < targetDepth) return null
            started = true
        }
        val frame = pending.remove(next) ?: return null
        highestSeq = frame.seq
        return frame
    }

    fun reset() {
        pending.clear()
        started = false
        highestSeq = -1
        droppedLate = 0
        droppedOverflow = 0
    }
}

/** Encodes and decodes monitor audio; implemented with Android codecs. */
interface MonitorCodec {
    fun encode(samples: FloatArray, count: Int): ByteArray?
    fun decode(payload: ByteArray): FloatArray?
    fun release()
}

/**
 * AAC-LC at 64 kbps mono: available on every Android version this app supports,
 * cheap to encode, and good enough to judge a take by ear. Opus replaces it
 * when the WebRTC stage lands.
 */
class AacMonitorCodec(
    private val sampleRate: Int = 48_000,
    private val bitRate: Int = 64_000,
) : MonitorCodec {

    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val encodeInfo = MediaCodec.BufferInfo()
    private val decodeInfo = MediaCodec.BufferInfo()
    private val pcm16 = ByteArray(4096)
    private var decoderConfigured = false

    /** Codec-specific config (csd-0) that the decoder needs before payloads. */
    @Volatile
    var csd: ByteArray? = null

    /**
     * Applies a config that arrived after the decoder was already running (an
     * engineer who joined or reconnected mid-session). Resetting is required:
     * a MediaCodec cannot be reconfigured while it is started.
     */
    fun updateDecoderConfig(config: ByteArray) {
        if (csd?.contentEquals(config) == true && decoderConfigured) return
        csd = config
        if (decoderConfigured) {
            runCatching { decoder.stop() }
            runCatching { decoder.reset() }
            decoderConfigured = false
        }
    }

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
    }

    override fun encode(samples: FloatArray, count: Int): ByteArray? {
        val bytes = PcmCodec.writeFloatPcm(samples, count, 16, pcm16)
        val inputIndex = encoder.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return null
        val input = encoder.getInputBuffer(inputIndex) ?: return null
        input.clear()
        input.put(pcm16, 0, bytes)
        encoder.queueInputBuffer(inputIndex, 0, bytes, System.nanoTime() / 1000, 0)

        val outputIndex = encoder.dequeueOutputBuffer(encodeInfo, 10_000)
        if (outputIndex < 0) return null
        val output = encoder.getOutputBuffer(outputIndex) ?: return null
        val payload = ByteArray(encodeInfo.size)
        output.position(encodeInfo.offset)
        output.get(payload)
        val isConfig = encodeInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (isConfig) csd = payload
        encoder.releaseOutputBuffer(outputIndex, false)
        return if (isConfig) null else payload
    }

    override fun decode(payload: ByteArray): FloatArray? {
        val config = csd
        if (!decoderConfigured) {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            config?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            decoder.configure(format, null, null, 0)
            decoder.start()
            decoderConfigured = true
        }
        val inputIndex = decoder.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return null
        decoder.getInputBuffer(inputIndex)?.apply {
            clear()
            put(payload)
        }
        decoder.queueInputBuffer(inputIndex, 0, payload.size, System.nanoTime() / 1000, 0)

        val outputIndex = decoder.dequeueOutputBuffer(decodeInfo, 10_000)
        if (outputIndex < 0) return null
        val output = decoder.getOutputBuffer(outputIndex) ?: return null
        val pcmBytes = ByteArray(decodeInfo.size)
        output.position(decodeInfo.offset)
        output.get(pcmBytes)
        decoder.releaseOutputBuffer(outputIndex, false)
        return PcmCodec.readFloatPcm(pcmBytes, 0, pcmBytes.size / 2, 16)
    }

    override fun release() {
        runCatching {
            encoder.stop()
            encoder.release()
        }
        runCatching {
            if (decoderConfigured) {
                decoder.stop()
                decoder.release()
            }
        }
    }
}

/**
 * Threads monitor audio on and off the wire.
 *
 * Capture blocks arrive on the take writer thread; they are copied into a
 * bounded queue and encoded on a dedicated thread so a slow encoder can only
 * drop monitor frames, never stall the recorder. Received frames are decoded
 * and played through [AudioTrack].
 */
class MonitorAudioEngine(
    private val codecFactory: () -> MonitorCodec = { AacMonitorCodec() },
    private val playbackVolume: Float = 1f,
    private val encodeKind: Byte = MonitorFrame.KIND_MONITOR,
) {
    @Volatile
    var onEncodedFrame: ((ByteArray) -> Unit)? = null

    /**
     * The artist drops incoming talkback until the engineer actually holds the
     * talkback button, so a stray frame can never leak into the artist's ears
     * (or, worse, into the take) when they are not expecting it.
     */
    @Volatile
    var acceptTalkback: Boolean = true

    private val running = AtomicBoolean(false)
    private val captureQueue = ArrayBlockingQueue<FloatArray>(64)
    private var encodeThread: Thread? = null
    private var decodeThread: Thread? = null
    private var codec: MonitorCodec? = null
    private val pendingConfig = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    private val jitter = AudioJitterBuffer()
    private var track: AudioTrack? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        codec = codecFactory()
        encodeThread = Thread({ encodeLoop() }, "Session-MonitorEncode").apply { start() }
        decodeThread = Thread({ decodeLoop() }, "Session-MonitorPlayback").apply { start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching {
            encodeThread?.join(500)
            decodeThread?.join(500)
        }
        encodeThread = null
        decodeThread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { codec?.release() }
        codec = null
        captureQueue.clear()
        pendingConfig.set(null)
        jitter.reset()
    }

    /** Called from the take writer thread with a private copy of captured audio. */
    fun submitCapture(samples: FloatArray, count: Int) {
        if (!running.get()) return
        val copy = samples.copyOf(count)
        if (!captureQueue.offer(copy)) {
            // Monitor is best-effort; dropping here never touches the take.
            captureQueue.poll()
            captureQueue.offer(copy)
        }
    }

    /** Called from the transport thread for every incoming binary frame. */
    fun onRemotePayload(payload: ByteArray) {
        if (!running.get()) return
        if (payload.size >= MonitorFrame.HEADER_BYTES) {
            val frame = MonitorFrame.decode(payload) ?: return
            when (frame.kind) {
                MonitorFrame.KIND_MONITOR -> jitter.push(frame)
                MonitorFrame.KIND_TALKBACK -> if (acceptTalkback) jitter.push(frame)
                MonitorFrame.KIND_CONFIG -> pendingConfig.set(frame.payload)
            }
        }
    }

    /** Codec config (csd) the remote decoder needs; sent once per connection. */
    fun configPayload(): ByteArray? = (codec as? AacMonitorCodec)?.csd

    /**
     * Ask the encode loop to re-send the codec config before the next audio
     * frame. Called on every new transport connection so a peer that joined
     * after the stream started can decode it.
     */
    @Volatile
    private var configResendRequested = false

    fun requestConfigResend() {
        configResendRequested = true
    }

    private fun encodeLoop() {
        val encoder = codec ?: return
        var seq = 0
        var configSent = false
        while (running.get()) {
            val block = captureQueue.poll()
            if (block == null) {
                Thread.sleep(5)
                continue
            }
            val payload = runCatching { encoder.encode(block, block.size) }.getOrNull() ?: continue
            // The remote decoder cannot decode without the codec config, so it
            // travels ahead of the first audio frame — and again on every new
            // connection (a peer may have joined after the stream started).
            if (!configSent || configResendRequested) {
                val config = (encoder as? AacMonitorCodec)?.csd
                if (config != null) {
                    configSent = true
                    configResendRequested = false
                    onEncodedFrame?.invoke(
                        MonitorFrame.encode(
                            kind = MonitorFrame.KIND_CONFIG,
                            seq = seq++,
                            timestampMs = System.currentTimeMillis(),
                            payload = config,
                        ),
                    )
                }
            }
            onEncodedFrame?.invoke(
                MonitorFrame.encode(
                    kind = encodeKind,
                    seq = seq++,
                    timestampMs = System.currentTimeMillis(),
                    payload = payload,
                ),
            )
        }
    }

    private fun decodeLoop() {
        val decoder = codec ?: return
        val sampleRate = 48_000
        pendingConfig.get()?.let { config ->
            (decoder as? AacMonitorCodec)?.csd = config
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)
        var localTrack: AudioTrack? = null
        while (running.get()) {
            pendingConfig.get()?.let { config ->
                (decoder as? AacMonitorCodec)?.updateDecoderConfig(config)
            }
            val frame = jitter.pop()
            if (frame == null) {
                Thread.sleep(3)
                continue
            }
            val floats = runCatching { decoder.decode(frame.payload) }.getOrNull() ?: continue
            if (localTrack == null) {
                localTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                localTrack.play()
                track = localTrack
            }
            localTrack.setVolume(playbackVolume.coerceIn(0f, 1f))
            localTrack.write(floats, 0, floats.size, AudioTrack.WRITE_BLOCKING)
        }
        runCatching { localTrack?.stop() }
        runCatching { localTrack?.release() }
        if (track === localTrack) track = null
    }
}
