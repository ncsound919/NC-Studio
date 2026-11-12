package com.overlay.audio.plugins

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical exchange format for the studio FX suite.
 *
 * The same document is read and written by NC Studio, NC Phone Beat and
 * NCSoundLab so an insert chain survives the handoff with its exact module
 * order and parameter values. The schema is deliberately dumb: a list of
 * slots, each identified by [PluginKind] and a flat parameter map. Anything a
 * platform cannot render is dropped as a whole slot rather than silently
 * approximated, because a partially applied chain is a different sound.
 *
 * Schema: `nc.studiofx.v1`
 */
object FxChainCodec {

    const val SCHEMA = "nc.studiofx.v1"

    /** Serializes a chain, preserving slot order and explicit values. */
    fun encode(chain: PluginChain, tempoBpm: Float? = null, name: String? = null): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        if (name != null) root.put("name", name)
        if (tempoBpm != null) root.put("tempoBpm", tempoBpm.toDouble())
        val slots = JSONArray()
        for (slot in chain.slots) {
            val node = JSONObject()
            node.put("id", slot.id)
            node.put("kind", slot.kind.name)
            node.put("enabled", slot.enabled)
            val params = JSONObject()
            // Emit every documented parameter so a reader never has to guess a
            // default that might change between app versions.
            for (spec in PluginCatalog.params(slot.kind)) {
                // Clamp on the way out, exactly like the TS encoder, so a slot
                // holding an out-of-range value exports the same document from
                // both engines and encode -> decode -> encode is idempotent.
                params.put(spec.key, spec.clamp(slot.value(spec.key)).toDouble())
            }
            node.put("params", params)
            slots.put(node)
        }
        root.put("chain", slots)
        return root.toString()
    }

    /**
     * Parses a chain document. Returns null when the schema is unknown or the
     * document is not an object; unknown module kinds are dropped (a newer app
     * may know modules this build does not), unknown parameters are ignored.
     */
    fun decode(json: String): PluginChain? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        if (root.optString("schema") != SCHEMA) return null
        val array = root.optJSONArray("chain") ?: return null
        val slots = ArrayList<PluginSlot>(array.length())
        for (i in 0 until array.length()) {
            val node = array.optJSONObject(i) ?: continue
            val kind = PluginKind.entries.firstOrNull { it.name == node.optString("kind") } ?: continue
            val params = LinkedHashMap<String, Float>()
            val raw = node.optJSONObject("params")
            if (raw != null) {
                for (spec in PluginCatalog.params(kind)) {
                    if (!raw.has(spec.key)) continue
                    val value = raw.optDouble(spec.key, spec.default.toDouble()).toFloat()
                    params[spec.key] = spec.clamp(value)
                }
            }
            slots += PluginSlot(
                id = node.optString("id").ifBlank { "slot-$i" },
                kind = kind,
                enabled = node.optBoolean("enabled", true),
                params = params,
            )
        }
        return PluginChain(slots)
    }

    fun tempoOf(json: String): Float? = try {
        val root = JSONObject(json)
        if (root.has("tempoBpm")) root.optDouble("tempoBpm").toFloat() else null
    } catch (_: Exception) {
        null
    }

    /**
     * Machine-readable catalog. NCSoundLab generates its parameter UI and its
     * DSP constants from this document, so a range change lands once in
     * [PluginCatalog] and reaches every app through the committed copy at
     * `shared/audio-fx/catalog.json`.
     */
    fun catalogJson(): String {
        val root = JSONObject()
        root.put("schema", CATALOG_SCHEMA)
        val kinds = JSONArray()
        for (kind in PluginKind.entries) {
            val node = JSONObject()
            node.put("id", kind.name)
            node.put("name", kind.displayName)
            node.put("category", kind.category.name)
            node.put("blurb", kind.blurb)
            val params = JSONArray()
            for (spec in PluginCatalog.params(kind)) {
                val param = JSONObject()
                param.put("key", spec.key)
                param.put("label", spec.label)
                param.put("min", spec.min.toDouble())
                param.put("max", spec.max.toDouble())
                param.put("default", spec.default.toDouble())
                param.put("unit", spec.unit)
                if (spec.choices.isNotEmpty()) {
                    val choices = JSONArray()
                    for ((value, label) in spec.choices) {
                        choices.put(JSONObject().put("value", value.toDouble()).put("label", label))
                    }
                    param.put("choices", choices)
                }
                params.put(param)
            }
            node.put("params", params)
            kinds.put(node)
        }
        root.put("kinds", kinds)
        return root.toString()
    }

    const val CATALOG_SCHEMA = "nc.studio-fx-catalog.v1"
}

/**
 * Offline renderer for the shared suite. Used by NC Phone Beat to print a chain
 * onto a pad or a master/stem render, and by the parity fixtures that keep the
 * TypeScript implementation honest.
 */
object FxOfflineRenderer {

    /**
     * Runs [input] (interleaved, [channels] = 1 or 2) through [chain] and
     * returns a new buffer of the same shape. A zero-length input returns an
     * empty buffer; the chain is built once, so long renders do not allocate
     * per frame.
     */
    fun render(
        input: FloatArray,
        channels: Int,
        sampleRate: Float,
        chain: PluginChain,
        tempoBpm: Float = 120f,
    ): FloatArray {
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        val frames = input.size / channels
        val output = FloatArray(input.size)
        if (frames == 0) return output
        val rack = TrackPluginRack(sampleRate, tempoBpm)
        rack.setChain(chain)
        val scratch = FloatArray(2)
        var i = 0
        while (i < frames) {
            val base = i * channels
            val inL = input[base]
            val inR = if (channels == 2) input[base + 1] else inL
            rack.processInto(inL, inR, scratch)
            if (channels == 2) {
                output[base] = scratch[0]
                output[base + 1] = scratch[1]
            } else {
                output[base] = (scratch[0] + scratch[1]) * 0.5f
            }
            i++
        }
        return output
    }

    /** Convenience for mono material: processes one channel. */
    fun renderMono(
        input: FloatArray,
        sampleRate: Float,
        chain: PluginChain,
        tempoBpm: Float = 120f,
    ): FloatArray = render(input, 1, sampleRate, chain, tempoBpm)
}
