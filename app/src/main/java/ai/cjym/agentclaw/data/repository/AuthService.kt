package ai.cjym.agentclaw.data.repository

import ai.cjym.agentclaw.data.local.prefs.PreferencesManager
import ai.cjym.agentclaw.data.remote.api.NetworkModule
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AuthService(private val context: Context) {

    private val client = NetworkModule.okHttpClient

    private val baseUrl: String
        get() {
            val url = PreferencesManager(context).agentclawBaseUrl.trimEnd('/')
            return url.removeSuffix("/v1")
        }

    data class LoginResult(val phone: String, val isNewUser: Boolean, val accessToken: String = "")

    suspend fun sendSmsCode(phone: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("phone", phone)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl/im/bot/login-code")
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            response.use {
                val json = JSONObject(it.body?.string() ?: "{}")
                if (!isSuccess(json)) {
                    throw Exception(json.optString("msg", "验证码发送失败，请稍后重试").ifEmpty { "验证码发送失败，请稍后重试" })
                }
            }
        }
    }

    suspend fun loginByCode(phone: String, code: String): Result<LoginResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("phone", phone)
                put("code", code)
                put("deviceModel", Build.MODEL)
                put("osVersion", Build.VERSION.RELEASE)
                put("appName", "AgentClaw")
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl/im/bot/login-by-code")
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            response.use {
                val json = JSONObject(it.body?.string() ?: "{}")
                if (!isSuccess(json)) {
                    throw Exception(json.optString("msg", "登录失败，请稍后重试").ifEmpty { "登录失败，请稍后重试" })
                }
                val data = json.optJSONObject("data")
                LoginResult(
                    phone = data?.optString("phone")?.takeIf { p -> p.isNotEmpty() } ?: phone,
                    isNewUser = data?.optBoolean("newUser") ?: false,
                    accessToken = data?.optString("accessToken")?.takeIf { t -> t.isNotEmpty() }
                        ?: data?.optString("token")?.takeIf { t -> t.isNotEmpty() } ?: ""
                )
            }
        }
    }

    suspend fun loginByPassword(phone: String, password: String): Result<LoginResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("phone", phone)
                put("password", password)
                put("deviceModel", Build.MODEL)
                put("osVersion", Build.VERSION.RELEASE)
                put("appName", "AgentClaw")
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl/im/bot/login-by-password")
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            response.use {
                val json = JSONObject(it.body?.string() ?: "{}")
                if (!isSuccess(json)) {
                    throw Exception(json.optString("msg", "登录失败，请稍后重试").ifEmpty { "登录失败，请稍后重试" })
                }
                val data = json.optJSONObject("data")
                LoginResult(
                    phone = data?.optString("phone")?.takeIf { p -> p.isNotEmpty() } ?: phone,
                    isNewUser = data?.optBoolean("newUser") ?: false,
                    accessToken = data?.optString("accessToken")?.takeIf { t -> t.isNotEmpty() }
                        ?: data?.optString("token")?.takeIf { t -> t.isNotEmpty() } ?: ""
                )
            }
        }
    }

    suspend fun setPassword(phone: String, code: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("phone", phone)
                put("code", code)
                put("password", password)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl/im/bot/set-password")
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            response.use {
                val json = JSONObject(it.body?.string() ?: "{}")
                if (!isSuccess(json)) {
                    throw Exception(json.optString("msg", "设置失败，请稍后重试").ifEmpty { "设置失败，请稍后重试" })
                }
            }
        }
    }

    suspend fun deleteAccount(phone: String, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("phone", phone)
                put("code", code)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl/im/bot/remove_account")
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            response.use {
                val json = JSONObject(it.body?.string() ?: "{}")
                if (!isSuccess(json)) {
                    throw Exception(json.optString("msg", "注销失败，请稍后重试").ifEmpty { "注销失败，请稍后重试" })
                }
            }
        }
    }

    private fun isSuccess(json: JSONObject): Boolean {
        val code = json.opt("code")
        return code == 0 || code == 0L || code?.toString() == "0"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
