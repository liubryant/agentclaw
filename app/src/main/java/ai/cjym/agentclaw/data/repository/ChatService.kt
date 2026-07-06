package ai.cjym.agentclaw.data.repository

import ai.cjym.agentclaw.data.local.prefs.PreferencesManager
import ai.cjym.agentclaw.data.remote.api.NetworkModule
import ai.cjym.agentclaw.domain.model.ChatMessage
import ai.cjym.agentclaw.domain.model.ChatRole
import ai.cjym.agentclaw.safety.ContentSafetyGuard
import ai.inmo.core_common.utils.Logger
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class ChatService(private val context: Context) {
    private companion object {
        private const val TAG = "ChatService"
        private const val IMAGE_MODE_MARKER = "[[OPENCLAW_IMAGE_MODE]]"
        private const val VIDEO_MODE_MARKER = "[[OPENCLAW_VIDEO_MODE]]"
        private const val CHAT_READ_TIMEOUT_SECONDS = 300L
        private const val MEDIA_READ_TIMEOUT_MINUTES = 30L
        private const val VIDEO_REQUEST_TIMEOUT_SECONDS = 30L
        private const val VIDEO_POLL_INTERVAL_MS = 5_000L
        private const val VIDEO_POLL_TIMEOUT_MS = 150_000L  // 2.5 minutes
    }

    private val chatHttpClient by lazy {
        NetworkModule.okHttpClient.newBuilder()
            .readTimeout(CHAT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CHAT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val mediaHttpClient by lazy {
        NetworkModule.okHttpClient.newBuilder()
            .readTimeout(MEDIA_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(MEDIA_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    private val videoHttpClient by lazy {
        NetworkModule.okHttpClient.newBuilder()
            .callTimeout(VIDEO_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(VIDEO_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(VIDEO_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var activeCall: Call? = null

    fun sendMessageStream(
        messages: List<ChatMessage>,
        model: String = "glm-4.7"
    ): Flow<String> = callbackFlow {
        val lastUserText = messages.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        val textSafety = ContentSafetyGuard.evaluate(lastUserText, ContentSafetyGuard.Surface.CHAT)
        if (!textSafety.allowed) {
            close(IOException(textSafety.userMessage))
            return@callbackFlow
        }
        val hasImageModeMarker = messages.any { it.content.contains(IMAGE_MODE_MARKER) }
        val hasVideoModeMarker = messages.any { it.content.contains(VIDEO_MODE_MARKER) }
        val requestModel = if (hasImageModeMarker) {
            "glm-image"
        } else if (hasVideoModeMarker) {
            "cogvideox-3"
        } else if ("glm-image".equals(model, ignoreCase = true)) {
            "openclaw:main"
        } else {
            model
        }
        val payload = JSONObject().apply {
            put("model", requestModel)
            put("stream", false)
            if (hasVideoModeMarker) {
                put("responseMode", "video_only")
            }
            put(
                "messages",
                JSONArray().apply {
                    messages.forEach { message ->
                        put(JSONObject().apply {
                            put("role", message.role.apiName)
                            val cleanText = stripModeMarkers(message.content)
                            val imgBase64 = message.imageBase64
                            if (!imgBase64.isNullOrBlank()) {
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:image/jpeg;base64,$imgBase64")
                                        })
                                    })
                                    if (cleanText.isNotBlank()) {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put("text", cleanText)
                                        })
                                    }
                                })
                            } else {
                                put("content", cleanText)
                            }
                        })
                    }
                }
            )
        }

        val token = PreferencesManager.resolveGatewayToken(context)
        val baseUrl = PreferencesManager(context).agentclawBaseUrl.trimEnd('/')
        val url = "$baseUrl/chat/completions"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        Logger.d(
            TAG,
            "agentclaw REQ_CHAT_COMPLETIONS url=$url, model=$requestModel, stream=false, imageRoute=${requestModel == "glm-image"}, messageCount=${messages.size}, tokenPresent=${!token.isNullOrBlank()}, payload=${payload.toString().take(500)}"
        )

        val httpClient = when {
            hasImageModeMarker || hasVideoModeMarker -> mediaHttpClient
            else -> chatHttpClient
        }
        val call = httpClient.newCall(request)
        activeCall = call

        launch(Dispatchers.IO) {
            try {
                val response = call.execute()
                response.use {
                    if (!it.isSuccessful) {
                        val errBody = runCatching { it.body?.string() }.getOrNull().orEmpty()
                        Logger.e(
                            TAG,
                            "agentclaw RESP_CHAT_COMPLETIONS_FAIL code=${it.code}, message=${it.message}, url=$url, body=${errBody.take(2000)}"
                        )
                        throw IOException(resolveGatewayErrorMessage(errBody, it.code))
                    }
                    Logger.d(TAG, "agentclaw RESP_CHAT_COMPLETIONS_OK code=${it.code}, url=$url")
                    val bodyText = it.body?.string() ?: throw IOException("Empty response body")
                    Logger.d(
                        TAG,
                        "agentclaw RESP_CHAT_COMPLETIONS_BODY url=$url, len=${bodyText.length}, body=${bodyText.take(2000)}"
                    )
                    parseNonStreamJson(bodyText) { chunk -> trySend(chunk).isSuccess }
                }
                close()
            } catch (t: Throwable) {
                Logger.e(TAG, "agentclaw REQ_CHAT_COMPLETIONS_ERROR url=$url, err=${t.message}")
                if (call.isCanceled()) close() else close(t)
            } finally {
                if (activeCall === call) activeCall = null
            }
        }

        awaitClose {
            if (activeCall === call) {
                call.cancel()
                activeCall = null
            }
        }
    }

    fun cancelGeneration() {
        activeCall?.cancel()
        activeCall = null
    }

    /**
     * Gateway errors may contain another JSON response encoded in error.upstream.
     * Prefer that upstream business message, while keeping malformed responses safe.
     */
    private fun resolveGatewayErrorMessage(body: String, httpCode: Int): String {
        val parsedMessage = runCatching {
            extractErrorMessage(JSONObject(body))
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        return parsedMessage?.take(1_000) ?: "Gateway returned HTTP $httpCode"
    }

    private fun extractErrorMessage(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                // The upstream field is often a JSON string and contains the useful provider message.
                extractErrorMessage(value.opt("upstream"))
                    ?: extractErrorMessage(value.opt("error"))
                    ?: value.optString("message").takeIf { it.isNotBlank() }
            }
            is String -> {
                val text = value.trim()
                if (text.startsWith("{") && text.endsWith("}")) {
                    runCatching { extractErrorMessage(JSONObject(text)) }.getOrNull()
                        ?: text.takeIf { it.isNotBlank() }
                } else {
                    text.takeIf { it.isNotBlank() }
                }
            }
            else -> null
        }
    }

    suspend fun generateVideo(
        prompt: String,
        onStatus: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) {
            throw IOException("视频描述不能为空")
        }
        val safety = ContentSafetyGuard.evaluate(cleanPrompt, ContentSafetyGuard.Surface.VIDEO_PROMPT)
        if (!safety.allowed) {
            throw IOException(safety.userMessage)
        }

        val submitted = executeJson(
            request = Request.Builder()
                .url("${videoGenerationBaseUrl()}/videos/generations")
                .post(JSONObject().apply {
                    put("model", "cogvideox-3")
                    put("prompt", cleanPrompt)
                    put("quality", "speed")
                    put("with_audio", false)
                    put("size", "1280x720")
                    put("fps", 30)
                }.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .withGatewayToken()
                .build()
        )

        val taskId = submitted.optString("id").trim()
        if (taskId.isBlank()) {
            throw IOException("视频任务提交失败：未返回任务 ID")
        }
        onStatus("视频生成任务已提交，任务ID：$taskId")

        var latest = submitted;
        val deadline = System.currentTimeMillis() + VIDEO_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() <= deadline) {
            val status = latest.optString("task_status").ifBlank { "PROCESSING" }
            val url = extractFirstVideoUrl(latest)
            if (!url.isNullOrBlank()) {
                val coverUrl = extractFirstVideoCoverUrl(latest)
                return@withContext buildVideoMessageContent(cleanPrompt, taskId, url, coverUrl)
            }
            if (status.equals("FAIL", ignoreCase = true) ||
                status.equals("FAILED", ignoreCase = true) ||
                status.equals("CANCELED", ignoreCase = true) ||
                status.equals("CANCELLED", ignoreCase = true)
            ) {
                throw IOException("视频生成失败：$status")
            }

            onStatus("视频生成中，请稍候...\n任务ID：$taskId\n状态：$status")
            delay(VIDEO_POLL_INTERVAL_MS)
            latest = executeJson(
                request = Request.Builder()
                    .url("${videoGenerationBaseUrl()}/videos/generations/$taskId")
                    .get()
                    .withGatewayToken()
                    .build()
            )
        }

        throw SocketTimeoutException("视频生成时间较长，请稍后再试。任务ID：$taskId")
    }

    suspend fun generateImageEdit(
        prompt: String,
        imageBase64: String
    ): String = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) throw IOException("图片编辑描述不能为空")
        if (imageBase64.isBlank()) throw IOException("请选择要编辑的图片")
        val safety = ContentSafetyGuard.evaluate(cleanPrompt, ContentSafetyGuard.Surface.IMAGE_PROMPT)
        if (!safety.allowed) {
            throw IOException(safety.userMessage)
        }

        val token = PreferencesManager.resolveGatewayToken(context)
        val baseUrl = PreferencesManager(context).agentclawBaseUrl.trimEnd('/')
        val url = "$baseUrl/images/edits"
        val result = executeMediaJson(
            request = Request.Builder()
                .url(url)
                .post(JSONObject().apply {
                    put("prompt", cleanPrompt)
                    put("image", "data:image/jpeg;base64,$imageBase64")
                }.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .apply {
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }
                .build()
        )
        val imageUrl = extractFirstGeneratedImageUrl(result)
        if (imageUrl.isNullOrBlank()) throw IOException("图片生成完成，但未返回可展示的图片地址")
        buildImageMessageContent(cleanPrompt, imageUrl)
    }

    private fun stripModeMarkers(content: String): String {
        return content
            .replace(IMAGE_MODE_MARKER, "")
            .replace(VIDEO_MODE_MARKER, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun Request.Builder.withGatewayToken(): Request.Builder {
        val token = PreferencesManager.resolveGatewayToken(context)
        if (!token.isNullOrBlank()) {
            header("Authorization", "Bearer $token")
        }
        return this
    }

    private fun videoGenerationBaseUrl(): String {
        return PreferencesManager(context).agentclawBaseUrl.trimEnd('/')
    }

    private fun executeJson(request: Request): JSONObject {
        val call = videoHttpClient.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Gateway returned HTTP ${response.code}: ${bodyText.take(300)}")
                }
                return JSONObject(bodyText)
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun executeMediaJson(request: Request): JSONObject {
        val call = mediaHttpClient.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Gateway returned HTTP ${response.code}: ${bodyText.take(300)}")
                }
                val trimmed = bodyText.trimStart { it.isWhitespace() || it == '﻿' }
                if (!trimmed.startsWith("{")) {
                    val hint = if (trimmed.startsWith("<", ignoreCase = true)) {
                        "接口被拦截或未部署"
                    } else {
                        "图生图接口返回格式不是 JSON"
                    }
                    throw IOException("$hint：${trimmed.take(80)}")
                }
                return JSONObject(trimmed)
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun extractFirstGeneratedImageUrl(json: JSONObject): String? {
        val data = json.optJSONArray("data")
        if (data != null) {
            for (index in 0 until data.length()) {
                val url = data.optJSONObject(index)?.optString("url")?.trim().orEmpty()
                if (url.isNotBlank()) return url
            }
        }

        val images = json.optJSONArray("images")
        if (images != null) {
            for (index in 0 until images.length()) {
                val url = images.optJSONObject(index)?.optString("url")?.trim().orEmpty()
                if (url.isNotBlank()) return url
            }
        }

        val choices = json.optJSONArray("choices")
        val message = choices?.optJSONObject(0)?.optJSONObject("message")
        val messageImages = message?.optJSONArray("images")
        if (messageImages != null) {
            for (index in 0 until messageImages.length()) {
                val url = messageImages.optJSONObject(index)
                    ?.optJSONObject("image_url")
                    ?.optString("url")
                    ?.trim()
                    .orEmpty()
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private fun extractFirstVideoUrl(json: JSONObject): String? {
        val direct = json.optJSONArray("video_result")
        if (direct != null) {
            for (index in 0 until direct.length()) {
                val url = direct.optJSONObject(index)?.optString("url")?.trim().orEmpty()
                if (url.isNotBlank()) return url
            }
        }

        val choices = json.optJSONArray("choices")
        val message = choices?.optJSONObject(0)?.optJSONObject("message")
        val videos = message?.optJSONArray("videos")
        if (videos != null) {
            for (index in 0 until videos.length()) {
                val url = videos.optJSONObject(index)
                    ?.optJSONObject("video_url")
                    ?.optString("url")
                    ?.trim()
                    .orEmpty()
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private fun extractFirstVideoCoverUrl(json: JSONObject): String? {
        val direct = json.optJSONArray("video_result")
        if (direct != null) {
            for (index in 0 until direct.length()) {
                val url = direct.optJSONObject(index)?.optString("cover_image_url")?.trim().orEmpty()
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private fun buildVideoMessageContent(prompt: String, taskId: String, url: String, coverUrl: String?): String {
        return buildString {
            append("已为你生成视频。")
            append("\n\n提示词：").append(prompt)
            append("\n\n任务ID：").append(taskId)
            if (!coverUrl.isNullOrBlank()) {
                append("\n\n视频封面: ").append(coverUrl)
            }
            append("\n\n视频链接1: ").append(url)
        }
    }

    private fun buildImageMessageContent(prompt: String, url: String): String {
        return buildString {
            append("已为你生成图片。")
            append("\n\n提示词：").append(prompt)
            append("\n\n图片链接1: ").append(url)
        }
    }

    private fun parseSse(source: BufferedSource, onChunk: (String) -> Unit) {
        while (!source.exhausted()) {
            val rawLine = source.readUtf8Line() ?: break
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(":")) continue
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            runCatching {
                val json = JSONObject(data)
                val choices = json.optJSONArray("choices") ?: return@runCatching
                if (choices.length() == 0) return@runCatching
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return@runCatching
                val content = delta.optString("content")
                if (content.isNotEmpty()) onChunk(content)
            }
        }
    }

    private fun parseNonStreamJson(body: String, onChunk: (String) -> Unit) {
        runCatching {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return@runCatching
            if (choices.length() == 0) return@runCatching
            val first = choices.getJSONObject(0)
            val message = first.optJSONObject("message")
            val content = when {
                message != null -> message.optString("content")
                else -> first.optJSONObject("delta")?.optString("content").orEmpty()
            }
            if (content.isNotEmpty()) {
                Logger.d(TAG, "agentclaw RESP_CHAT_COMPLETIONS_CONTENT len=${content.length}, preview=${content.take(300)}")
                onChunk(content)
            }
        }.onFailure {
            Logger.e(TAG, "agentclaw PARSE_NON_STREAM_FAIL err=${it.message}, body=${body.take(400)}")
        }
    }
}
