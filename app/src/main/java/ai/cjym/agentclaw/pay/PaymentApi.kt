package ai.cjym.agentclaw.pay

import ai.cjym.agentclaw.data.local.prefs.PreferencesManager
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class PaymentApi(context: Context) {

    private val client = OkHttpClient()
    private val baseUrl: String

    init {
        val url = PreferencesManager(context).agentclawBaseUrl.trimEnd('/').removeSuffix("/v1")
        baseUrl = "$url/im/bot/navi/vip"
    }

    private fun isSuccess(json: JSONObject): Boolean {
        val code = json.opt("code")
        return code == 0 || code == 0L || code?.toString() == "0"
    }

    fun getProducts(): List<VipProduct> {
        val response = client.newCall(
            Request.Builder().url("$baseUrl/products").get().build()
        ).execute()
        response.use {
            val raw = it.body?.string() ?: throw Exception("响应为空")
            val json = JSONObject(raw)
            if (!isSuccess(json)) throw Exception(json.optString("msg", "获取商品失败"))
            val data = json.opt("data")
            val arr: JSONArray = when {
                data is JSONArray -> data
                data is org.json.JSONObject -> {
                    data.optJSONArray("list")
                        ?: data.optJSONArray("products")
                        ?: data.optJSONArray("items")
                        ?: throw Exception("无法解析商品列表，字段: ${data.keys().asSequence().toList()}")
                }
                else -> throw Exception("data 字段格式不支持: ${raw.take(200)}")
            }
            return (0 until arr.length()).map { i -> VipProduct.from(arr.getJSONObject(i)) }
        }
    }

    fun createOrder(accessToken: String, productId: String, payChannel: String): JSONObject {
        val body = JSONObject().apply {
            put("productId", productId)
            put("payChannel", payChannel)
            put("appid", AGENTCLAW_ALIPAY_APP_ID)
        }.toString().toRequestBody(JSON_TYPE)
        val response = client.newCall(
            Request.Builder()
                .url("$baseUrl/orders")
                .post(body)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $accessToken")
                .build()
        ).execute()
        response.use {
            val json = JSONObject(it.body?.string() ?: "{}")
            if (!isSuccess(json)) throw Exception(json.optString("msg", "创建订单失败"))
            return json.optJSONObject("data") ?: JSONObject()
        }
    }

    fun getMembership(accessToken: String): JSONObject {
        val response = client.newCall(
            Request.Builder()
                .url("$baseUrl/membership")
                .get()
                .header("Authorization", "Bearer $accessToken")
                .build()
        ).execute()
        response.use {
            val json = JSONObject(it.body?.string() ?: "{}")
            if (!isSuccess(json)) throw Exception(json.optString("msg", "查询会员状态失败"))
            return json.optJSONObject("data") ?: JSONObject()
        }
    }

    fun queryOrder(accessToken: String, orderId: String): JSONObject {
        val response = client.newCall(
            Request.Builder()
                .url("$baseUrl/orders/$orderId")
                .get()
                .header("Authorization", "Bearer $accessToken")
                .build()
        ).execute()
        response.use {
            val json = JSONObject(it.body?.string() ?: "{}")
            if (!isSuccess(json)) throw Exception(json.optString("msg", "查询订单失败"))
            return json.optJSONObject("data") ?: JSONObject()
        }
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        const val AGENTCLAW_ALIPAY_APP_ID = "2021006169619056"
    }
}
