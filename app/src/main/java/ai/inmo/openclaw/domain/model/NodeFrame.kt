package ai.inmo.openclaw.domain.model

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Gateway Protocol v3 frame.
 * Types: "req", "res", "event"
 */
data class NodeFrame(
    val type: String,
    val id: String? = null,
    val method: String? = null,
    val params: Map<String, Any?>? = null,
    val ok: Boolean? = null,
    val payload: Map<String, Any?>? = null,
    val error: Map<String, Any?>? = null,
    val event: String? = null
) {
    val isRequest: Boolean get() = type == "req"
    val isResponse: Boolean get() = type == "res"
    val isEvent: Boolean get() = type == "event"
    val isError: Boolean get() = ok == false || error != null
    val isOk: Boolean get() = ok == true

    fun encode(): String = gson.toJson(toJsonMap())

    private fun toJsonMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>("type" to type)
        id?.let { map["id"] = it }
        method?.let { map["method"] = it }
        params?.let { map["params"] = it }
        ok?.let { map["ok"] = it }
        payload?.let { map["payload"] = it }
        error?.let { map["error"] = it }
        event?.let { map["event"] = it }
        return map
    }

    companion object {
        private val gson = Gson()
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

        fun request(method: String, params: Map<String, Any?>? = null): NodeFrame {
            return NodeFrame(
                type = "req",
                id = UUID.randomUUID().toString(),
                method = method,
                params = params ?: emptyMap()
            )
        }

        fun response(
            id: String,
            payload: Map<String, Any?>? = null,
            error: Map<String, Any?>? = null
        ): NodeFrame {
            return NodeFrame(
                type = "res",
                id = id,
                ok = error == null,
                payload = payload,
                error = error
            )
        }

        fun event(event: String, payload: Map<String, Any?>? = null): NodeFrame {
            return NodeFrame(
                type = "event",
                event = event,
                payload = payload
            )
        }

        fun decode(raw: String): NodeFrame {
            val json: Map<String, Any?> = gson.fromJson(raw, mapType)
            @Suppress("UNCHECKED_CAST")
            return NodeFrame(
                type = json["type"] as? String ?: "res",
                id = json["id"] as? String,
                method = json["method"] as? String,
                params = json["params"] as? Map<String, Any?>,
                ok = json["ok"] as? Boolean,
                payload = json["payload"] as? Map<String, Any?>,
                error = json["error"] as? Map<String, Any?>,
                event = json["event"] as? String
            )
        }
    }
}
