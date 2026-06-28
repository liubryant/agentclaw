package ai.cjym.agentclaw.pay

import org.json.JSONObject

data class VipProduct(
    val id: String,
    val name: String,
    val price: String,
    val description: String
) {
    companion object {
        fun from(json: JSONObject) = VipProduct(
            id = json.optString("id"),
            name = json.optString("name"),
            price = json.optString("price"),
            description = json.optString("description")
        )
    }
}
