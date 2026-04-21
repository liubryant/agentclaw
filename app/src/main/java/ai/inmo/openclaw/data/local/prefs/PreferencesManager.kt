package ai.inmo.openclaw.data.local.prefs

import android.content.Context
import com.tencent.mmkv.MMKV

class PreferencesManager(context: Context) {

    private val prefs: MMKV = MmkvStore.preferences(context)
    private val nodeIdentityPrefs: MMKV = MmkvStore.nodeIdentity(context)

    var autoStartGateway: Boolean
        get() = prefs.decodeBool(KEY_AUTO_START, false)
        set(value) {
            prefs.encode(KEY_AUTO_START, value)
        }

    var setupComplete: Boolean
        get() = prefs.decodeBool(KEY_SETUP_COMPLETE, false)
        set(value) {
            prefs.encode(KEY_SETUP_COMPLETE, value)
        }

    var isFirstRun: Boolean
        get() = prefs.decodeBool(KEY_FIRST_RUN, true)
        set(value) {
            prefs.encode(KEY_FIRST_RUN, value)
        }

    var modelPreActivated: Boolean
        get() = prefs.decodeBool(KEY_MODEL_PREACTIVATED, false)
        set(value) {
            prefs.encode(KEY_MODEL_PREACTIVATED, value)
        }

    var dashboardUrl: String?
        get() = prefs.decodeString(KEY_DASHBOARD_URL, null)
        set(value) {
            prefs.encodeOrRemove(KEY_DASHBOARD_URL, value)
        }

    var nodeEnabled: Boolean
        get() = prefs.decodeBool(KEY_NODE_ENABLED, false)
        set(value) {
            prefs.encode(KEY_NODE_ENABLED, value)
        }

    var nodeDeviceToken: String?
        get() = prefs.decodeString(KEY_NODE_DEVICE_TOKEN, null)
        set(value) {
            prefs.encodeOrRemove(KEY_NODE_DEVICE_TOKEN, value)
        }

    var nodeGatewayHost: String?
        get() = prefs.decodeString(KEY_NODE_GATEWAY_HOST, null)
        set(value) {
            prefs.encodeOrRemove(KEY_NODE_GATEWAY_HOST, value)
        }

    val nodePublicKey: String?
        get() = nodeIdentityPrefs.decodeString(KEY_NODE_PUBLIC_KEY, null)

    var nodeGatewayToken: String?
        get() = prefs.decodeString(KEY_NODE_GATEWAY_TOKEN, null)
        set(value) {
            if (!value.isNullOrEmpty()) prefs.encode(KEY_NODE_GATEWAY_TOKEN, value)
            else prefs.removeValueForKey(KEY_NODE_GATEWAY_TOKEN)
        }

    var lastAppVersion: String?
        get() = prefs.decodeString(KEY_LAST_APP_VERSION, null)
        set(value) {
            prefs.encodeOrRemove(KEY_LAST_APP_VERSION, value)
        }

    var lastSelectedChatSessionKey: String?
        get() = prefs.decodeString(KEY_LAST_SELECTED_CHAT_SESSION_KEY, null)
        set(value) {
            prefs.encodeOrRemove(KEY_LAST_SELECTED_CHAT_SESSION_KEY, value)
        }

    var termsAccepted: Boolean
        get() = prefs.decodeBool(KEY_TERMS_ACCEPTED, false)
        set(value) {
            prefs.encode(KEY_TERMS_ACCEPTED, value)
        }

    var nodeGatewayPort: Int?
        get() {
            val v = prefs.decodeInt(KEY_NODE_GATEWAY_PORT, -1)
            return if (v == -1) null else v
        }
        set(value) {
            if (value != null) prefs.encode(KEY_NODE_GATEWAY_PORT, value)
            else prefs.removeValueForKey(KEY_NODE_GATEWAY_PORT)
        }

    companion object {
        private const val KEY_AUTO_START = "auto_start_gateway"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_FIRST_RUN = "first_run"
        private const val KEY_MODEL_PREACTIVATED = "model_preactivated"
        private const val KEY_DASHBOARD_URL = "dashboard_url"
        private const val KEY_NODE_ENABLED = "node_enabled"
        private const val KEY_NODE_DEVICE_TOKEN = "node_device_token"
        private const val KEY_NODE_GATEWAY_HOST = "node_gateway_host"
        private const val KEY_NODE_GATEWAY_PORT = "node_gateway_port"
        private const val KEY_NODE_PUBLIC_KEY = "node_ed25519_public"
        private const val KEY_NODE_GATEWAY_TOKEN = "node_gateway_token"
        private const val KEY_LAST_APP_VERSION = "last_app_version"
        private const val KEY_LAST_SELECTED_CHAT_SESSION_KEY = "last_selected_chat_session_key"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted"

        /**
         * Resolves the gateway token from preferences.
         * Checks manual token first, then extracts from dashboard URL fragment.
         */
        fun resolveGatewayToken(context: Context): String? {
            val prefs = PreferencesManager(context)

            // 1. Manually configured token (remote gateway scenario)
            val manualToken = prefs.nodeGatewayToken
            if (!manualToken.isNullOrEmpty()) return manualToken

            // 2. Extract from dashboard URL fragment (#token=xxx)
            val dashboardUrl = prefs.dashboardUrl
            if (dashboardUrl != null) {
                val match = Regex("[#?&]token=([0-9a-fA-F]+)").find(dashboardUrl)
                if (match != null) return match.groupValues[1]
            }

            return null
        }
    }
}

private fun MMKV.encodeOrRemove(key: String, value: String?) {
    if (value != null) {
        encode(key, value)
    } else {
        removeValueForKey(key)
    }
}
