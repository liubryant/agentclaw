package ai.cjym.agentclaw.data.repository

import ai.cjym.agentclaw.constants.AppConstants
import ai.cjym.agentclaw.data.local.prefs.PreferencesManager
import ai.cjym.agentclaw.data.remote.api.NetworkModule
import ai.cjym.agentclaw.domain.model.ChatMessage
import ai.inmo.core_common.utils.Logger
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ChatService(private val context: Context) {
    private companion object {
        private const val TAG = "ChatService"
        private const val IMAGE_MODE_MARKER = "[[OPENCLAW_IMAGE_MODE]]"
        private const val VIDEO_MODE_MARKER = "[[OPENCLAW_VIDEO_MODE]]"
    }

    @Volatile
    private var activeCall: Call? = null

    fun sendMessageStream(
        messages: List<ChatMessage>,
        model: String = "openclaw:main"
    ): Flow<String> = callbackFlow {
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
                            put("content", stripModeMarkers(message.content))
                        })
                    }
                }
            )
        }

        val token = PreferencesManager.resolveGatewayToken(context)
        val url = "${AppConstants.GATEWAY_URL}/v1/chat/completions"
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

        val call = NetworkModule.okHttpClient.newCall(request)
        activeCall = call

        launch(Dispatchers.IO) {
            try {
                val response = call.execute()
                response.use {
                    if (!it.isSuccessful) {
                        val errBody = runCatching { it.body?.string() }.getOrNull().orEmpty().take(400)
                        Logger.e(
                            TAG,
                            "agentclaw RESP_CHAT_COMPLETIONS_FAIL code=${it.code}, message=${it.message}, url=$url, body=$errBody"
                        )
                        throw IOException("Gateway returned HTTP ${it.code}")
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

    private fun stripModeMarkers(content: String): String {
        return content
            .replace(IMAGE_MODE_MARKER, "")
            .replace(VIDEO_MODE_MARKER, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
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
