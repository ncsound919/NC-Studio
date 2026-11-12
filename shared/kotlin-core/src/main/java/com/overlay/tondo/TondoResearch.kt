package com.overlay.tondo

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Tondo Research Assist — the shared Android client.
 *
 * Sibling apps call this when their own agent is stuck: a failed task, a
 * contradiction it cannot settle, or a local model that is unavailable. The
 * call is synchronous on purpose; run it from `Dispatchers.IO`.
 *
 * Contract: tondo/design/research-assist.md. The same JSON is parsed by
 * `shared/llm-mesh/clients/ts/tondoResearch.ts`, so a change here must land
 * there too.
 */

data class TondoResearchConfig(
    val baseUrl: String,
    val appId: String,
    val token: String,
    val timeoutMs: Int = 60_000,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && appId.isNotBlank() && token.isNotBlank()
}

data class TondoEvidence(
    val id: String,
    val title: String,
    val text: String,
    val url: String? = null,
)

data class TondoFinding(
    val claim: String,
    val support: String,
    val confidence: Double,
    val kind: String,
)

data class TondoAction(
    val step: String,
    val why: String,
    val risk: String,
)

data class TondoCitation(
    val evidenceId: String,
    val title: String,
    val url: String?,
)

/** One turn supplied to the model. Retrieval stays in the caller's harness. */
data class TondoMessage(
    val role: String,
    val content: String,
)

/**
 * One model turn returned by the gateway. `lane` and `model` are attribution,
 * not a promise: a NIM timeout can be served by the gateway's fallback lane.
 */
data class TondoReasonReply(
    val content: String,
    val lane: String?,
    val model: String?,
    val deep: Boolean,
    val inputTokens: Int,
    val outputTokens: Int,
)

data class TondoResearchResult(
    val requestId: String?,
    val app: String,
    val operation: String,
    val question: String,
    val status: String,
    val summary: String,
    val findings: List<TondoFinding>,
    val actions: List<TondoAction>,
    val unresolved: List<String>,
    val citations: List<TondoCitation>,
    val escalate: Boolean,
    val lane: String?,
    val model: String?,
    val adapter: String?,
    val latencyMs: Long,
    /** Retrieval steps, e.g. `search_sources · 6 sources`. Empty when upstream sent none. */
    val trace: List<String> = emptyList(),
) {
    /**
     * A compact rendering for a status line or console transcript. It never
     * adds a claim the service did not return.
     */
    fun toPlainText(): String {
        val out = StringBuilder()
        out.append("Tondo ").append(status).append(": ").append(summary)
        for (finding in findings) {
            out.append("\n• ").append(finding.claim)
            out.append(" [").append(finding.support)
            out.append(", ").append((finding.confidence * 100).roundToInt()).append("%]")
        }
        for (action in actions) {
            out.append("\n→ ").append(action.step).append(" (").append(action.risk).append(" risk)")
        }
        if (unresolved.isNotEmpty()) {
            out.append("\nStill open: ").append(unresolved.joinToString("; "))
        }
        return out.toString()
    }
}

class TondoResearchException(
    message: String,
    val status: Int = 0,
    val code: String = "tondo_error",
) : Exception(message)

/**
 * `optString` returns the literal text "null" for a JSON null. For nullable
 * provider fields that is indistinguishable from a model actually named
 * "null", so check `isNull` first.
 */
private fun JSONObject.optionalString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

class TondoResearchClient(private val config: TondoResearchConfig) {

    val isConfigured: Boolean get() = config.isConfigured

    fun health(): JSONObject = request("GET", "/health", null)

    /**
     * Ask Tondo for a bounded research assist.
     *
     * @param operation the caller's operation, e.g. "oncology evidence review".
     * @param question the concrete question the caller's agent could not settle.
     * @param symptom what went wrong, e.g. "local model unavailable".
     * @param context small, already-redacted operational context. Never PHI.
     * @param evidence optional quoted evidence; citations must reference these ids.
     * @param dataClass `aggregate`, `internal`, `commercial`, or `phi`. The
     *   deployed gateway rejects `phi` unless it was explicitly enabled.
     */
    fun assist(
        operation: String,
        question: String,
        symptom: String = "",
        context: String = "",
        evidence: List<TondoEvidence> = emptyList(),
        dataClass: String = "internal",
        maxFindings: Int = 4,
    ): TondoResearchResult {
        if (!config.isConfigured) throw TondoResearchException("Tondo research is not configured", 0, "not_configured")
        if (question.isBlank()) throw TondoResearchException("question is required", 0, "question_required")
        if (evidence.size > 8) throw TondoResearchException("at most 8 evidence items", 0, "too_much_evidence")

        val body = JSONObject().apply {
            put("app", config.appId)
            put("operation", operation)
            put("question", question)
            if (symptom.isNotBlank()) put("symptom", symptom)
            if (context.isNotBlank()) put("context", context)
            put("data_class", dataClass)
            put("max_findings", maxFindings.coerceIn(1, 6))
            if (evidence.isNotEmpty()) {
                put("evidence", JSONArray().also { array ->
                    for (item in evidence) {
                        array.put(JSONObject().apply {
                            put("id", item.id)
                            put("title", item.title)
                            put("text", item.text)
                            item.url?.takeIf { it.isNotBlank() }?.let { put("url", it) }
                        })
                    }
                })
            }
        }

        val data = request("POST", "/v1/research/assist", body)
        return parseTondoResearch(data, config.appId)
    }

    /**
     * Run one retrieval tool on the gateway. Both the gateway's own model loop
     * and a phone-local model use this endpoint, so the corpus access path is
     * identical whichever model is reasoning.
     *
     * @param tool `search_sources` or `fetch_source`.
     * @param arguments tool arguments: query, limit and freshness for search;
     *   url and maxChars for fetch.
     */
    fun runTool(tool: String, arguments: JSONObject = JSONObject()): JSONObject {
        if (!config.isConfigured) throw TondoResearchException("Tondo research is not configured", 0, "not_configured")
        val body = JSONObject(arguments.toString()).put("tool", tool)
        return request("POST", "/v1/research/tools", body)
    }

    /**
     * One model turn for a caller that owns its own research loop.
     *
     * This is how the phone's harness uses the gateway's NVIDIA NIM lane for
     * query planning and synthesis without handing the loop to the gateway. No
     * tools are sent: the app has already chosen what evidence to read.
     *
     * @param deep route to the larger NIM research deployment when available.
     */
    fun reason(
        messages: List<TondoMessage>,
        maxTokens: Int = 700,
        deep: Boolean = false,
        temperature: Double = 0.2,
    ): TondoReasonReply {
        if (!config.isConfigured) throw TondoResearchException("Tondo research is not configured", 0, "not_configured")
        if (messages.isEmpty()) throw TondoResearchException("messages are required", 0, "messages_required")
        if (messages.size > 12) throw TondoResearchException("at most 12 messages", 0, "too_many_messages")
        val body = JSONObject().apply {
            put("messages", JSONArray().also { array ->
                messages.forEach { message ->
                    array.put(JSONObject().put("role", message.role).put("content", message.content))
                }
            })
            put("deep", deep)
            put("maxTokens", maxTokens.coerceIn(128, 1_600))
            put("temperature", temperature.coerceIn(0.0, 1.0))
        }
        val data = request("POST", "/v1/research/reason", body)
        val content = data.optionalString("content")
            ?: throw TondoResearchException("Tondo returned no model content", 502, "bad_upstream")
        val usage = data.optJSONObject("usage")
        return TondoReasonReply(
            content = content,
            lane = data.optionalString("lane"),
            model = data.optionalString("model"),
            deep = data.optBoolean("deep", deep),
            inputTokens = usage?.optInt("input", 0) ?: 0,
            outputTokens = usage?.optInt("output", 0) ?: 0,
        )
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val connection = (URL(config.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = minOf(config.timeoutMs, 10_000)
            readTimeout = config.timeoutMs
            doInput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-tondo-app", config.appId)
            setRequestProperty("Authorization", "Bearer ${config.token}")
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input)).use { reader -> reader.readText() }
            } ?: ""
            val parsed = if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse {
                throw TondoResearchException("Tondo returned non-JSON", status, "invalid_json")
            }
            if (status !in 200..299) {
                val code = parsed.optString("error", "tondo_error")
                val detail = parsed.optString("detail")
                throw TondoResearchException(
                    if (detail.isNotBlank()) "$code: $detail" else code,
                    status,
                    code,
                )
            }
            return parsed
        } finally {
            connection.disconnect()
        }
    }
}

/** Parse the research JSON shared by the gateway, the phone-local lane and the TS client. */
fun parseTondoResearch(data: JSONObject, appId: String = "tondo"): TondoResearchResult {
    val findings = mutableListOf<TondoFinding>()
    data.optJSONArray("findings")?.let { array ->
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            findings.add(
                TondoFinding(
                    claim = item.optString("claim"),
                    support = item.optString("support", "model_prior"),
                    confidence = item.optDouble("confidence", 0.0),
                    kind = item.optString("kind", "fact"),
                ),
            )
        }
    }

    val actions = mutableListOf<TondoAction>()
    data.optJSONArray("actions")?.let { array ->
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            actions.add(
                TondoAction(
                    step = item.optString("step"),
                    why = item.optString("why"),
                    risk = item.optString("risk", "medium"),
                ),
            )
        }
    }

    val unresolved = mutableListOf<String>()
    data.optJSONArray("unresolved")?.let { array ->
        for (i in 0 until array.length()) unresolved.add(array.optString(i))
    }

    val citations = mutableListOf<TondoCitation>()
    data.optJSONArray("citations")?.let { array ->
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            citations.add(
                TondoCitation(
                    evidenceId = item.optString("evidenceId"),
                    title = item.optString("title"),
                    url = item.optionalString("url"),
                ),
            )
        }
    }

    val trace = mutableListOf<String>()
    data.optJSONArray("trace")?.let { array ->
        for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(trace::add)
    }

    val overlay = data.optJSONObject("overlay")
    return TondoResearchResult(
        requestId = data.optionalString("requestId"),
        app = data.optionalString("app") ?: appId,
        operation = data.optString("operation"),
        question = data.optString("question"),
        status = data.optionalString("status") ?: "insufficient",
        summary = data.optionalString("summary").orEmpty(),
        findings = findings,
        actions = actions,
        unresolved = unresolved,
        citations = citations,
        escalate = data.optBoolean("escalate", false),
        lane = data.optionalString("lane"),
        model = data.optionalString("model"),
        adapter = data.optionalString("adapter"),
        latencyMs = overlay?.optLong("latencyMs", 0L) ?: 0L,
        trace = trace,
    )
}
