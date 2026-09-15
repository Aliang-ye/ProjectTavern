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
            raw.endsWith("/v1") -> "$raw/chat/completions"
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
        val arr = JSONArray()
        messages.forEach { (role, content) ->
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", p.model)
            .put("stream", true)
            .put("temperature", preset.temperature)
            .put("top_p", preset.topP)
            .put("max_tokens", preset.maxTokens)
            .put("messages", arr)
            .toString()
        val req = Request.Builder()
            .url(openaiUrl(p))
            .addHeader("Authorization", "Bearer ${p.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return readSse(req, onDelta) { json ->
            json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
        }
    }

    private fun streamClaude(
        p: ApiProfile,
        messages: List<Pair<String, String>>,
        preset: Preset,
        onDelta: (String) -> Unit,
    ): String {
        val system = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
        val arr = JSONArray()
        messages.filter { it.first != "system" }.forEach { (role, content) ->
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", p.model)
            .put("stream", true)
            .put("temperature", preset.temperature)
            .put("top_p", preset.topP)
            .put("max_tokens", preset.maxTokens)
            .put("messages", arr)
        if (system.isNotBlank()) body.put("system", system)
        val req = Request.Builder()
            .url(claudeUrl(p))
            .addHeader("x-api-key", p.apiKey)
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
            throw RuntimeException(mapStatus(res.code, err))
        }
        val full = StringBuilder()
        res.body?.source()?.use { src ->
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                val s = line.trim()
                if (!s.startsWith("data:")) continue
                val data = s.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                try {
                    val piece = pick(JSONObject(data))
                    if (piece.isNotEmpty()) {
                        full.append(piece)
                        onDelta(piece)
                    }
                } catch (_: Exception) {
                }
            }
        }
        currentCall = null
        return full.toString()
    }

    private fun mapStatus(code: Int, body: String): String {
        return when (code) {
            401, 403 -> "UNAUTHORIZED: key rejected"
            404 -> "MODEL_NOT_FOUND"
            429 -> "RATE_LIMIT"
            in 500..599 -> "SERVER_ERROR"
            else -> "Request failed ($code) ${body.take(180)}"
        }
    }
}
