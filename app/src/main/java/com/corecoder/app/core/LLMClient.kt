package com.corecoder.app.core

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Represents a tool call returned by the LLM.
 * Corresponds to Python's ToolCall dataclass.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

/**
 * LLM response containing text content, tool calls, and token usage.
 * Corresponds to Python's LLMResponse dataclass.
 */
data class LLMResponse(
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
) {
    /** Convert to OpenAI message format for appending to history. */
    fun toMessage(): JsonObject {
        val msg = JsonObject()
        msg.addProperty("role", "assistant")
        msg.addProperty("content", content.ifEmpty { null })

        if (toolCalls.isNotEmpty()) {
            val tcArray = JsonArray()
            for (tc in toolCalls) {
                val tcObj = JsonObject()
                tcObj.addProperty("id", tc.id)
                tcObj.addProperty("type", "function")
                val fn = JsonObject()
                fn.addProperty("name", tc.name)
                fn.addProperty("arguments", Gson().toJson(tc.arguments))
                tcObj.add("function", fn)
                tcArray.add(tcObj)
            }
            msg.add("tool_calls", tcArray)
        }
        return msg
    }
}

/**
 * Pricing per million tokens: (input, output).
 * Sources: openai.com/api/pricing, api-docs.deepseek.com, platform.claude.com,
 *          platform.moonshot.ai, alibabacloud.com/help/en/model-studio
 */
private val PRICING = mapOf(
    // OpenAI current flagships
    "gpt-5.4" to Pair(2.5, 15.0),
    "gpt-5.4-mini" to Pair(0.75, 4.5),
    "gpt-5.4-nano" to Pair(0.2, 1.25),
    "o4-mini" to Pair(1.1, 4.4),
    // OpenAI previous gen
    "gpt-4.1" to Pair(2.0, 8.0),
    "gpt-4.1-mini" to Pair(0.4, 1.6),
    "gpt-4.1-nano" to Pair(0.1, 0.4),
    "gpt-4o" to Pair(2.5, 10.0),
    "gpt-4o-mini" to Pair(0.15, 0.6),
    // DeepSeek
    "deepseek-chat" to Pair(0.27, 1.10),
    "deepseek-reasoner" to Pair(0.55, 2.19),
    // Anthropic Claude
    "claude-opus-4-6" to Pair(5.0, 25.0),
    "claude-sonnet-4-6" to Pair(3.0, 15.0),
    "claude-haiku-4-5" to Pair(1.0, 5.0),
    // Alibaba Qwen
    "qwen3-max" to Pair(0.78, 3.9),
    "qwen3-plus" to Pair(0.26, 0.78),
    "qwen-max" to Pair(0.78, 3.9),
    // Moonshot Kimi
    "kimi-k2.5" to Pair(0.6, 3.0),
)

/**
 * LLM client using OkHttp SSE streaming for OpenAI-compatible APIs.
 * Corresponds to Python's LLM class (llm.py, 327 lines).
 *
 * Uses raw OkHttp + SSE parsing instead of OpenAI SDK to avoid
 * JVM-only SDK dependency on Android.
 */
class LLMClient(
    var model: String,
    val apiKey: String,
    val baseUrl: String? = null,
    val temperature: Float = 0f,
    val maxTokens: Int = 4096
) {
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    var totalPromptTokens = 0
        private set
    var totalCompletionTokens = 0
        private set

    private val effectiveBaseUrl: String
        get() = baseUrl?.trimEnd('/') ?: "https://api.openai.com/v1"

    /** Rough cost estimate in USD. Returns null if model not in pricing table. */
    val estimatedCost: Double?
        get() {
            val (inputRate, outputRate) = PRICING[model] ?: return null
            return totalPromptTokens * inputRate / 1_000_000 +
                    totalCompletionTokens * outputRate / 1_000_000
        }

    /**
     * Send messages, stream back response, handle tool calls.
     * Corresponds to Python's LLM.chat() method.
     */
    suspend fun chat(
        messages: List<JsonObject>,
        tools: List<JsonObject>? = null,
        onToken: (String) -> Unit = {}
    ): LLMResponse = withContext(Dispatchers.IO) {
        callWithRetry(messages, tools, onToken, maxRetries = 3)
    }

    private fun callWithRetry(
        messages: List<JsonObject>,
        tools: List<JsonObject>?,
        onToken: (String) -> Unit,
        maxRetries: Int
    ): LLMResponse {
        var lastException: Exception? = null

        for (attempt in 0 until maxRetries) {
            try {
                return executeRequest(messages, tools, onToken)
            } catch (e: RateLimitException) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val waitMs = (1L shl attempt) * 1000
                    Thread.sleep(waitMs)
                }
            } catch (e: ServerErrorException) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val waitMs = (1L shl attempt) * 1000
                    Thread.sleep(waitMs)
                }
            } catch (e: TimeoutException) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val waitMs = (1L shl attempt) * 1000
                    Thread.sleep(waitMs)
                }
            } catch (e: Exception) {
                // Non-retryable error
                throw e
            }
        }
        throw lastException ?: RuntimeException("Unknown error")
    }

    private fun executeRequest(
        messages: List<JsonObject>,
        tools: List<JsonObject>?,
        onToken: (String) -> Unit
    ): LLMResponse {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("stream", true)
        body.addProperty("temperature", temperature)
        body.addProperty("max_tokens", maxTokens)

        val messagesArray = JsonArray()
        messages.forEach { messagesArray.add(it) }
        body.add("messages", messagesArray)

        if (tools != null && tools.isNotEmpty()) {
            val toolsArray = JsonArray()
            tools.forEach { toolsArray.add(it) }
            body.add("tools", toolsArray)
        }

        // stream_options for usage info (not all providers support it)
        val streamOptions = JsonObject()
        streamOptions.addProperty("include_usage", true)
        body.add("stream_options", streamOptions)

        val jsonBody = gson.toJson(body)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val url = "$effectiveBaseUrl/chat/completions"
        Log.d("LLMClient", "Request URL: $url")
        Log.d("LLMClient", "Model: $model, Messages: ${messages.size}, Tools: ${tools?.size ?: 0}")
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response: Response = httpClient.newCall(request).execute()
        Log.d("LLMClient", "Response code: ${response.code}, headers: ${response.headers}")

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error body"
            Log.e("LLMClient", "API error ${response.code}: $errorBody")
            when (response.code) {
                429 -> throw RateLimitException("Rate limited: $errorBody")
                in 500..599 -> throw ServerErrorException("Server error ${response.code}: $errorBody")
                408, 504 -> throw TimeoutException("Timeout: $errorBody")
                else -> throw RuntimeException("API error ${response.code}: $errorBody")
            }
        }

        return parseSseStream(response, onToken)
    }

    private fun parseSseStream(response: Response, onToken: (String) -> Unit): LLMResponse {
        val contentParts = mutableListOf<String>()
        val tcMap = mutableMapOf<Int, ToolCallAccumulator>() // index -> accumulator
        var promptTok = 0
        var completionTok = 0

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

        reader.useLines { lines ->
            for (line in lines) {
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val chunk = gson.fromJson(data, JsonObject::class.java)

                        // Extract usage info (comes in final chunk)
                        val usageElem = chunk.get("usage")
                        if (usageElem != null && !usageElem.isJsonNull && usageElem.isJsonObject) {
                            val usage = usageElem.asJsonObject
                            promptTok = usage.get("prompt_tokens")?.asInt ?: 0
                            completionTok = usage.get("completion_tokens")?.asInt ?: 0
                        }

                        val choices = chunk.getAsJsonArray("choices") ?: continue
                        if (choices.size() == 0) continue

                        val choiceObj = choices[0].asJsonObject
                        val deltaElem = choiceObj.get("delta")
                        if (deltaElem == null || deltaElem.isJsonNull || !deltaElem.isJsonObject) continue
                        val delta = deltaElem.asJsonObject

                        // Accumulate text content
                        val contentElem = delta.get("content")
                        if (contentElem != null && !contentElem.isJsonNull) {
                            val text = contentElem.asString
                            if (text.isNotEmpty()) {
                                contentParts.add(text)
                                onToken(text)
                            }
                        }

                        // Accumulate tool calls across chunks
                        val toolCallsElem = delta.get("tool_calls")
                        if (toolCallsElem != null && !toolCallsElem.isJsonNull && toolCallsElem.isJsonArray) {
                            for (tcDelta in toolCallsElem.asJsonArray) {
                                val tcObj = tcDelta.asJsonObject
                                val idx = tcObj.get("index")?.asInt ?: 0

                                val acc = tcMap.getOrPut(idx) { ToolCallAccumulator() }

                                tcObj.get("id")?.let { id ->
                                    if (!id.isJsonNull) acc.id = id.asString
                                }

                                val fnElem = tcObj.get("function")
                                if (fnElem != null && !fnElem.isJsonNull && fnElem.isJsonObject) {
                                    val fn = fnElem.asJsonObject
                                    fn.get("name")?.let { name ->
                                        if (!name.isJsonNull) acc.name = name.asString
                                    }
                                    fn.get("arguments")?.let { args ->
                                        if (!args.isJsonNull) acc.argsBuilder.append(args.asString)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("LLMClient", "Failed to parse SSE chunk: ${e.message}")
                    }
                }
            }
        }

        // Parse accumulated tool calls
        val parsedToolCalls = tcMap.entries
            .sortedBy { it.key }
            .map { (_, acc) ->
                val args = try {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(acc.argsBuilder.toString(), Map::class.java) as? Map<String, Any?>
                        ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap<String, Any?>()
                }
                ToolCall(id = acc.id, name = acc.name, arguments = args)
            }

        totalPromptTokens += promptTok
        totalCompletionTokens += completionTok

        Log.d("LLMClient", "SSE done. Content length=${contentParts.joinToString("").length}, ToolCalls=${parsedToolCalls.size}, Prompt=$promptTok, Completion=$completionTok")
        return LLMResponse(
            content = contentParts.joinToString(""),
            toolCalls = parsedToolCalls,
            promptTokens = promptTok,
            completionTokens = completionTok
        )
    }

    private data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val argsBuilder: StringBuilder = StringBuilder()
    )

    // Custom exception types for retry logic
    class RateLimitException(message: String) : Exception(message)
    class ServerErrorException(message: String) : Exception(message)
    class TimeoutException(message: String) : Exception(message)
}
