package com.projecttavern.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Llm {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()
    @Volatile var currentCall: okhttp3.Call? = null

    fun cancel() {
        currentCall?.cancel()
        currentCall = null
    }

    fun stream(
        profile: ApiProfile,
        messages: List<Pair<String, String>>,
        preset: Preset,
        onDelta: (String) -> Unit,
    ): String {
        return if (profile.provider == "claude") streamClaude(profile, messages, preset, onDelta)
        else streamOpenAI(profile, messages, preset, onDelta)
    }

    private fun openaiUrl(p: ApiProfile): String {
        val raw = (p.endpoint.ifBlank { "https://api.openai.com/v1" }).trimEnd('/')
        return when {
            raw.endsWith("/chat/completions") -> raw
            raw.endsWith("/v1") || raw.endsWith("/v2") || raw.endsWith("/v3") || raw.endsWith("/v4") -> "$raw/chat/completions"
            raw.contains("/v1/") || raw.contains("/v2/") || raw.contains("/v3/") || raw.contains("/v4/") -> "$raw/chat/completions"
            else -> "$raw/v1/chat/completions"
        }
    }

    private fun claudeUrl(p: ApiProfile): String {
        val raw = (p.endpoint.ifBlank { "https://api.anthropic.com" }).trimEnd('/')
        return when {
            raw.endsWith("/messages") -> raw
            raw.endsWith("/v1") -> "$raw/messages"
            else -> "$raw/v1/messages"
        }
    }

    private fun streamOpenAI(
        p: ApiProfile,
        messages: List<Pair<String, String>>,
        preset: Preset,
        onDelta: (String) -> Unit,
    ): String {
        val apiKey = p.apiKey.replace("\r", "").replace("\n", "").trim()
        require(apiKey.isNotBlank()) { "OpenAI API key is required" }

        val arr = JSONArray()
        messages.filter { it.second.isNotBlank() }.forEach { (role, content) ->
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        if (arr.length() == 0) return ""
        val effectiveMaxTokens = if (preset.responseBudget > 0) minOf(preset.maxTokens, preset.responseBudget) else preset.maxTokens
        val body = JSONObject()
            .put("model", p.model.trim())
            .put("stream", true)
            .put("temperature", preset.temperature)
            .put("top_p", preset.topP)
            .put("max_tokens", effectiveMaxTokens)
            .put("messages", arr)
            .toString()
        val req = Request.Builder()
            .url(openaiUrl(p))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return readSse(req, onDelta) { json ->
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            delta?.optString("content")?.ifEmpty { null }
                ?: choice?.optString("text")?.ifEmpty { null }
                ?: ""
        }
    }

    private fun streamClaude(
        p: ApiProfile,
        messages: List<Pair<String, String>>,
        preset: Preset,
        onDelta: (String) -> Unit,
    ): String {
        val apiKey = p.apiKey.replace("\r", "").replace("\n", "").trim()
        require(apiKey.isNotBlank()) { "Claude API key is required" }
        val system = messages.filter { it.first == "system" && it.second.isNotBlank() }.joinToString("\n\n") { it.second }
        val nonSystem = messages.filter { it.first != "system" && it.second.isNotBlank() }.toMutableList()
        if (nonSystem.isEmpty()) {
            nonSystem.add("user" to "Hello")
        } else if (nonSystem.first().first != "user") {
            nonSystem.add(0, "user" to "（开启对话）")
        }
        val merged = mutableListOf<Pair<String, String>>()
        for (msg in nonSystem) {
            if (merged.isNotEmpty() && merged.last().first == msg.first) {
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(prev.first to "${prev.second}\n\n${msg.second}")
            } else {
                merged.add(msg)
            }
        }
        val arr = JSONArray()
        merged.forEach { (role, content) ->
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val effectiveMaxTokens = if (preset.responseBudget > 0) minOf(preset.maxTokens, preset.responseBudget) else preset.maxTokens
        val body = JSONObject()
            .put("model", p.model.trim())
            .put("stream", true)
            .put("temperature", preset.temperature)
            .put("top_p", preset.topP)
            .put("max_tokens", effectiveMaxTokens)
            .put("messages", arr)
        if (system.isNotBlank()) body.put("system", system)
        val req = Request.Builder()
            .url(claudeUrl(p))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return readSse(req, onDelta) { json ->
            if (json.optString("type") == "content_block_delta") {
                json.optJSONObject("delta")?.optString("text").orEmpty()
            } else json.optJSONObject("delta")?.optString("text").orEmpty()
        }
    }

    private fun readSse(req: Request, onDelta: (String) -> Unit, pick: (JSONObject) -> String): String {
        val call = client.newCall(req)
        currentCall = call
        val res = call.execute()
        if (!res.isSuccessful) {
            val err = res.body?.string().orEmpty()
            res.close()
            currentCall = null
            throw RuntimeException(mapStatus(res.code, err))
        }

        val full = StringBuilder()
        val responseBody = res.body ?: run {
            res.close()
            currentCall = null
            return ""
        }

        try {
            val source = responseBody.source()
            while (!source.exhausted()) {
                val rawLine = source.readUtf8Line() ?: break
                val s = rawLine.trim()
                if (!s.startsWith("data:")) continue
                val data = s.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                try {
                    val json = JSONObject(data)
                    val errObj = json.optJSONObject("error")
                    if (errObj != null) {
                        val msg = errObj.optString("message").ifBlank { errObj.toString() }
                        throw RuntimeException(msg)
                    }
                    val piece = pick(json)
                    if (piece.isNotEmpty()) {
                        full.append(piece)
                        onDelta(piece)
                    }
                } catch (e: Exception) {
                    if (e is RuntimeException && e.message != null && !e.message!!.startsWith("Malformed model stream")) {
                        throw e
                    }
                    val preview = data.take(180)
                    throw RuntimeException("Malformed model stream payload: $preview", e)
                }
            }
        } finally {
            res.close()
            currentCall = null
        }

        if (full.isEmpty()) {
            throw RuntimeException(if (Store.state.locale == "en") "Model returned no text output" else "模型未返回任何有效文本")
        }
        return full.toString()
    }

    private fun mapStatus(code: Int, body: String): String {
        val parsedMsg = try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")?.ifBlank { null }
                ?: json.optString("message").ifBlank { null }
        } catch (_: Exception) {
            null
        }
        val detail = parsedMsg ?: body.take(180)
        return when (code) {
            401, 403 -> if (detail.isNotBlank()) "UNAUTHORIZED (401/403): $detail" else "UNAUTHORIZED: API key rejected"
            404 -> if (detail.isNotBlank()) "MODEL_NOT_FOUND (404): $detail" else "MODEL_NOT_FOUND (404)"
            429 -> if (detail.isNotBlank()) "RATE_LIMIT (429): $detail" else "RATE_LIMIT: Quota exceeded or speed limit"
            in 500..599 -> if (detail.isNotBlank()) "SERVER_ERROR ($code): $detail" else "SERVER_ERROR ($code)"
            else -> "HTTP $code: $detail"
        }
    }
}
