package com.example.session.protocol

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okio.Buffer

sealed interface DecodeResult {
    data class Ok(val envelope: Envelope, val message: SessionMessage) : DecodeResult
    data class Malformed(val reason: String) : DecodeResult
}

/**
 * Encodes and decodes protocol envelopes.
 *
 * - unknown types decode to [Unknown] so the receiver can explicitly reject
 *   them instead of silently dropping the message;
 * - malformed input returns [DecodeResult.Malformed], never an exception;
 * - unknown fields are tolerated for forward compatibility.
 */
class MessageCodec(moshi: Moshi? = null) {

    private val moshi = moshi ?: Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val stringAdapter = this.moshi.adapter(String::class.java)

    /**
     * Messages with no fields. Moshi cannot build a reflective adapter for a
     * Kotlin `data object`, so they are handled directly and their body is `{}`.
     */
    private val parameterless: Map<String, SessionMessage> = mapOf(
        "StopTake" to StopTake,
        "Pause" to Pause,
    )

    private val adapters: Map<String, JsonAdapter<Any>> = buildMap {
        fun <T : SessionMessage> add(type: String, adapter: JsonAdapter<T>) {
            @Suppress("UNCHECKED_CAST")
            put(type, adapter as JsonAdapter<Any>)
        }
        add("Arm", this@MessageCodec.moshi.adapter(Arm::class.java))
        add("StartTake", this@MessageCodec.moshi.adapter(StartTake::class.java))
        add("MarkTake", this@MessageCodec.moshi.adapter(MarkTake::class.java))
        add("SetCueLevel", this@MessageCodec.moshi.adapter(SetCueLevel::class.java))
        add("Talkback", this@MessageCodec.moshi.adapter(Talkback::class.java))
        add("Seek", this@MessageCodec.moshi.adapter(Seek::class.java))
        add("PlayBacking", this@MessageCodec.moshi.adapter(PlayBacking::class.java))
        add("StateChanged", this@MessageCodec.moshi.adapter(StateChanged::class.java))
        add("TakeStarted", this@MessageCodec.moshi.adapter(TakeStarted::class.java))
        add("TakeStopped", this@MessageCodec.moshi.adapter(TakeStopped::class.java))
        add("UploadProgress", this@MessageCodec.moshi.adapter(UploadProgress::class.java))
        add("Warning", this@MessageCodec.moshi.adapter(Warning::class.java))
        add("StateSnapshot", this@MessageCodec.moshi.adapter(StateSnapshot::class.java))
        add("TakeAvailable", this@MessageCodec.moshi.adapter(TakeAvailable::class.java))
        add("Hello", this@MessageCodec.moshi.adapter(Hello::class.java))
        add("Ping", this@MessageCodec.moshi.adapter(Ping::class.java))
        add("Pong", this@MessageCodec.moshi.adapter(Pong::class.java))
        add("Bye", this@MessageCodec.moshi.adapter(Bye::class.java))
        add("Ack", this@MessageCodec.moshi.adapter(Ack::class.java))
    }

    /** Full envelope JSON for [message]. */
    fun encode(message: SessionMessage, sid: String, seq: Long): String {
        val body = bodyValue(message)
        return buildString {
            append("{\"v\":").append(PROTOCOL_VERSION)
            append(",\"sid\":").append(stringAdapter.toJson(sid))
            append(",\"seq\":").append(seq)
            append(",\"type\":").append(stringAdapter.toJson(message.type))
            append(",\"body\":")
            writeJsonValue(this, body)
            append('}')
        }
    }

    /** Body-only JSON, used by the session log and tests. */
    fun encodeBody(message: SessionMessage): String {
        return buildString { writeJsonValue(this, bodyValue(message)) }
    }

    private fun bodyValue(message: SessionMessage): Any {
        if (parameterless.containsKey(message.type)) return emptyMap<String, Any?>()
        val adapter = adapters[message.type]
            ?: throw IllegalArgumentException("Unknown message type ${message.type}")
        return adapter.toJsonValue(message) ?: emptyMap<String, Any?>()
    }

    fun decode(text: String): DecodeResult {
        return try {
            decodeOrThrow(text)
        } catch (e: Exception) {
            DecodeResult.Malformed(e.message ?: "invalid message")
        }
    }

    private fun decodeOrThrow(text: String): DecodeResult {
        if (text.isBlank()) return DecodeResult.Malformed("empty message")
        var v = -1
        var sid: String? = null
        var seq = -1L
        var type: String? = null
        var body: Any? = null
        var sawBody = false

        JsonReader.of(Buffer().writeUtf8(text)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "v" -> v = reader.nextInt()
                    "sid" -> sid = reader.nextString()
                    "seq" -> seq = reader.nextLong()
                    "type" -> type = reader.nextString()
                    "body" -> {
                        body = reader.readJsonValue()
                        sawBody = true
                    }

                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        if (v != PROTOCOL_VERSION) return DecodeResult.Malformed("unsupported protocol version $v")
        if (sid.isNullOrBlank()) return DecodeResult.Malformed("missing session id")
        if (seq < 0) return DecodeResult.Malformed("invalid sequence")
        val messageType = type ?: return DecodeResult.Malformed("missing type")
        val envelope = Envelope(
            v = v,
            sid = sid,
            seq = seq,
            type = messageType,
            bodyJson = body?.let { encodeValueToString(it) } ?: "",
        )
        if (!sawBody) return DecodeResult.Malformed("missing body")

        parameterless[messageType]?.let { return DecodeResult.Ok(envelope, it) }

        val adapter = adapters[messageType]
            ?: return DecodeResult.Ok(envelope, Unknown(messageType))
        val message = try {
            adapter.fromJsonValue(body)
        } catch (e: Exception) {
            return DecodeResult.Malformed("bad body for $messageType: ${e.message}")
        } ?: return DecodeResult.Malformed("empty body for $messageType")
        val typed = message as? SessionMessage
            ?: return DecodeResult.Malformed("body for $messageType did not decode")
        return DecodeResult.Ok(envelope, typed)
    }

    /** Serializes a decoded JSON value (Map/List/scalars) back to JSON text. */
    fun encodeValueToString(value: Any?): String = buildString { writeJsonValue(this, value) }

    private fun writeJsonValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> out.append(stringAdapter.toJson(value))
            is Boolean -> out.append(value.toString())
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Float -> writeNumber(out, value.toDouble())
            is Double -> writeNumber(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    out.append(stringAdapter.toJson(key.toString())).append(':')
                    writeJsonValue(out, item)
                }
                out.append('}')
            }

            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    writeJsonValue(out, item)
                }
                out.append(']')
            }

            else -> out.append(stringAdapter.toJson(value.toString()))
        }
    }

    private fun writeNumber(out: StringBuilder, value: Double) {
        if (!value.isFinite()) {
            out.append("null")
            return
        }
        if (value == value.toLong().toDouble()) out.append(value.toLong()) else out.append(value)
    }
}
