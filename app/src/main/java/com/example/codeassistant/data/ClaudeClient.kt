package com.example.codeassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class ApiMessage(val role: String, val content: String)

@Serializable
private data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val stream: Boolean,
    val messages: List<ApiMessage>,
)

/**
 * Minimal streaming client for the Anthropic Messages API.
 * Emits text chunks as they arrive (Server-Sent Events).
 */
class ClaudeClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // streaming: no read timeout
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun stream(apiKey: String, model: String, history: List<ApiMessage>): Flow<String> = flow {
        val payload = json.encodeToString(
            MessagesRequest(
                model = model,
                maxTokens = 4096,
                system = SYSTEM_PROMPT,
                stream = true,
                messages = history,
            )
        )
        val request = Request.Builder()
            .url(API_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        // Cancel the network call when the collecting coroutine is cancelled.
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw IOException("Ошибка API ${response.code}: ${extractError(body)}")
                }
                val source = response.body?.source() ?: throw IOException("Пустой ответ сервера")
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    if (event["type"]?.jsonPrimitive?.content != "content_block_delta") continue
                    val delta = event["delta"]?.jsonObject ?: continue
                    if (delta["type"]?.jsonPrimitive?.content == "text_delta") {
                        delta["text"]?.jsonPrimitive?.content?.let { emit(it) }
                    }
                }
            }
        } catch (e: IOException) {
            // A cancelled call surfaces as IOException; propagate real cancellation instead.
            currentCoroutineContext().ensureActive()
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun extractError(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]
            ?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(300)

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        const val DEFAULT_MODEL = "claude-sonnet-5-5"

        private const val SYSTEM_PROMPT =
            "Ты — помощник программиста в мобильном приложении. Отвечай кратко и по делу, " +
                "на языке пользователя. Код всегда оформляй в блоки Markdown с указанием языка " +
                "(```kotlin ... ```). Если просят исправить код — сначала назови причину ошибки, " +
                "затем покажи исправленный фрагмент, а не весь файл. Экран маленький: без лишней воды."
    }
}
