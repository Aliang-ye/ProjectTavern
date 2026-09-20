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
        .readTimeout(90, TimeUnit.SECONDS)  // 90s 兜底，避免断网时请求永远挂起
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
        streaming: Boolean = Store.state.streaming,
    ): String {
        assertSafeUrl(if (profile.provider == "claude") claudeUrl(profile) else openaiUrl(profile))
        return if (profile.provider == "claude") streamClaude(profile, messages, preset, onDelta, streaming)
        else streamOpenAI(profile, messages, preset, onDelta, streaming)
    }

    fun testConnection(p: ApiProfile): Pair<Boolean, String> {
        val apiKey = p.apiKey.replace("\r", "").replace("\n", "").trim()
        if (apiKey.isBlank()) {
            return false to (if (Store.state.locale == "en") "API Key is empty" else "API Key 为空")
        }
        val startTime = System.currentTimeMillis()
        try {
            assertSafeUrl(if (p.provider == "claude") claudeUrl(p) else openaiUrl(p))
            val req = if (p.provider == "claude") {
                val body = JSONObject()
                    .put("model", p.model.trim().ifBlank { "claude-3-haiku-20240307" })
                    .put("max_tokens", 5)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                    .toString()
                Request.Builder()
                    .url(claudeUrl(p))
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            } else {
                val body = JSONObject()
                    .put("model", p.model.trim().ifBlank { "gpt-3.5-turbo" })
                    .put("max_tokens", 5)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                    .toString()
                Request.Builder()
                    .url(openaiUrl(p))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            }
            val testClient = client.newBuilder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val response = testClient.newCall(req).execute()
            val duration = System.currentTimeMillis() - startTime
            val code = response.code
            val body = response.body?.string().orEmpty()
            response.close()
            if (response.isSuccessful) {
                return true to "✓ ${Store.t("testSuccess")} (${duration}ms)"
            } else {
                val detail = mapStatus(code, body)
                return false to "${Store.t("testFailed")}: $detail"
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            val msg = e.localizedMessage ?: e.message ?: "Network error"
            return false to "${Store.t("testFailed")} (${duration}ms): $msg"
        }
    }

    private fun openaiUrl(p: ApiProfile): String {
        val raw = (p.endpoint.ifBlank { "https://api.openai.com/v1" }).trimEnd('/')
        return when {
            raw.endsWith("/chat/completions") -> raw
            raw.contains("generativelanguage.googleapis.com") -> "$raw/chat/completions"
            raw.endsWith("/v1") || raw.endsWith("/v2") || raw.endsWith("/v3") || raw.endsWith("/v4") -> "$raw/chat/completions"
            raw.contains("/v1/") || raw.contains("/v2/") || raw.contains("/v3/") || raw.contains("/v4/") -> "$raw/chat/completions"
            else -> "$raw/v1/chat/completions"
        }
    }

    private fun assertSafeUrl(url: String) {
        val uri = try { java.net.URI(url) } catch (_: Exception) { return }
        if (uri.scheme != "http") return
        val host = uri.host ?: return
        if (!isPrivateHost(host)) {
            throw RuntimeException(if (Store.state.locale == "en") "HTTP is only allowed for LAN / localhost. Use HTTPS for public APIs." else "公网接口必须使用 HTTPS。明文 HTTP 仅允许局域网 / localhost。")
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('.')
        if (h == "localhost" || h.endsWith(".local")) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt(); val b = parts[1].toInt()
            if (a == 10) return true
            if (a == 127) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
        }
        return false
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
        streaming: Boolean,
    ): String {
        val apiKey = p.apiKey.replace("\r", "").replace("\n", "").trim()
        require(apiKey.isNotBlank()) { "OpenAI API key is required" }

        val arr = JSONArray()
        messages.filter { it.second.isNotBlank() }.forEach { (role, content) ->
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        if (arr.length() == 0) return ""
        val effectiveMaxTokens = if (preset.responseBudget > 0) minOf(preset.maxTokens, preset.responseBudget) else preset.maxTokens
        val url = openaiUrl(p)
        val body = JSONObject()
            .put("model", p.model.trim())
            .put("stream", streaming)
            .put("temperature", preset.temperature)
            .put("top_p", preset.topP)
            .put("max_tokens", effectiveMaxTokens)
            .put("messages", arr)
            .toString()
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return if (streaming) {
            readSse(req, onDelta) { json ->
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                val delta = choice?.optJSONObject("delta")
                delta?.optString("content")?.ifEmpty { null }
                    ?: choice?.optString("text")?.ifEmpty { null }
                    ?: ""
            }
        } else {
            completeOnce(req, onDelta)
        }
    }

    private fun streamClaude(
        p: ApiProfile,
        messages: List<Pair<String, String>>,
        preset: Preset,
        onDelta: (String) -> Unit,
        streaming: Boolean,
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
            .put("stream", streaming)
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
        return if (streaming) {
            readSse(req, onDelta) { json ->
                if (json.optString("type") == "content_block_delta") {
                    json.optJSONObject("delta")?.optString("text").orEmpty()
                } else json.optJSONObject("delta")?.optString("text").orEmpty()
            }
        } else {
            completeOnce(req, onDelta)
        }
    }

    private fun completeOnce(req: Request, onDelta: (String) -> Unit): String {
        val call = client.newCall(req)
        currentCall = call
        val res = call.execute()
        val body = res.body?.string().orEmpty()
        res.close()
        currentCall = null
        if (!res.isSuccessful) throw RuntimeException(mapStatus(res.code, body))
        val json = JSONObject(body)
        val text = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: json.optJSONArray("content")?.optJSONObject(0)?.optString("text")
            ?: json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            ?: ""
        if (text.isBlank()) throw RuntimeException(if (Store.state.locale == "en") "Model returned no text output" else "模型未返回任何有效文本")
        onDelta(text)
        return text
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
            val reasoning = StringBuilder()
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
                    // DeepSeek-R1 等思维链模型：捕获 reasoning_content 作为 fallback
                    val choice0 = json.optJSONArray("choices")?.optJSONObject(0)
                    val reasonPiece = choice0?.optJSONObject("delta")?.optString("reasoning_content").orEmpty()
                    if (reasonPiece.isNotEmpty()) reasoning.append(reasonPiece)
                } catch (e: Exception) {
                    if (e is RuntimeException && e.message != null && !e.message!!.startsWith("Malformed")) throw e
                    // 格式错误的 SSE 行：静默跳过，不中断整个流
                    continue
                }
            }
            // 若正文为空但有思维链内容（DeepSeek-R1），将思维链作为输出
            if (full.isEmpty() && reasoning.isNotEmpty()) {
                val reasonText = reasoning.toString()
                full.append(reasonText)
                onDelta(reasonText)
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
