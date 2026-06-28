package ai.cjym.agentclaw.pay

import org.json.JSONObject

data class WeChatPayParams(
    val appId: String,
    val partnerId: String,
    val prepayId: String,
    val packageValue: String,
    val nonceStr: String,
    val timeStamp: String,
    val sign: String,
    val signType: String = "RSA"
) {
    fun isValid(): Boolean {
        if (appId.isBlank() || partnerId.isBlank()) return false
        if (prepayId.isBlank() || nonceStr.isBlank() || sign.isBlank()) return false
        return true
    }

    companion object {
        fun from(json: JSONObject): WeChatPayParams {
            return WeChatPayParams(
                appId = json.optString("appId").ifEmpty { json.optString("appid") },
                partnerId = json.optString("partnerId").ifEmpty { json.optString("partnerid") },
                prepayId = json.optString("prepayId").ifEmpty { json.optString("prepayid") },
                packageValue = json.optString("packageValue").ifEmpty { json.optString("package") },
                nonceStr = json.optString("nonceStr").ifEmpty { json.optString("noncestr") },
                timeStamp = json.optString("timeStamp").ifEmpty { json.optString("timestamp") },
                sign = json.optString("sign"),
                signType = json.optString("signType").ifEmpty { json.optString("signtype").ifEmpty { "RSA" } }
            )
        }
    }
}
