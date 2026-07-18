package ai.cjym.agentclaw.data.local.prefs

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

    var sidebarVisible: Boolean
        get() = prefs.decodeBool(KEY_SIDEBAR_VISIBLE, true)
        set(value) {
            prefs.encode(KEY_SIDEBAR_VISIBLE, value)
        }

    fun getSessionEntryModeRaw(sessionKey: String): String? =
        prefs.decodeString("$KEY_PREFIX_SESSION_ENTRY_MODE$sessionKey", null)

    fun setSessionEntryModeRaw(sessionKey: String, modeRaw: String?) {
        val key = "$KEY_PREFIX_SESSION_ENTRY_MODE$sessionKey"
        if (modeRaw.isNullOrEmpty()) prefs.removeValueForKey(key)
        else prefs.encode(key, modeRaw)
    }

    /** 后台接口地址，切换测试/正式环境用。默认正式服，改为测试服: http://192.168.110.67:8066/v1 */
    var agentclawBaseUrl: String
        get() {
            val stored = prefs.decodeString(KEY_AGENTCLAW_BASE_URL, DEFAULT_AGENTCLAW_BASE_URL)
                ?: DEFAULT_AGENTCLAW_BASE_URL
            val normalized = normalizeAgentclawBaseUrl(stored)
            if (normalized != stored) {
                prefs.encode(KEY_AGENTCLAW_BASE_URL, normalized)
            }
            return normalized
        }
        set(value) {
            prefs.encode(KEY_AGENTCLAW_BASE_URL, normalizeAgentclawBaseUrl(value))
        }

    var termsAccepted: Boolean
        get() = prefs.decodeBool(KEY_TERMS_ACCEPTED, false)
        set(value) {
            prefs.encode(KEY_TERMS_ACCEPTED, value)
        }

    var isVipActive: Boolean
        get() = prefs.decodeBool(KEY_VIP_ACTIVE, false)
        set(value) { prefs.encode(KEY_VIP_ACTIVE, value) }

    var vipExpiresAt: String?
        get() = prefs.decodeString(KEY_VIP_EXPIRES_AT, null)
        set(value) { prefs.encodeOrRemove(KEY_VIP_EXPIRES_AT, value) }

    var quotaVideoDate: String?
        get() = prefs.decodeString(KEY_QUOTA_VIDEO_DATE, null)
        set(value) { prefs.encodeOrRemove(KEY_QUOTA_VIDEO_DATE, value) }

    var quotaVideoCount: Int
        get() = prefs.decodeInt(KEY_QUOTA_VIDEO_COUNT, 0)
        set(value) { prefs.encode(KEY_QUOTA_VIDEO_COUNT, value) }

    var quotaImageDate: String?
        get() = prefs.decodeString(KEY_QUOTA_IMAGE_DATE, null)
        set(value) { prefs.encodeOrRemove(KEY_QUOTA_IMAGE_DATE, value) }

    var quotaImageCount: Int
        get() = prefs.decodeInt(KEY_QUOTA_IMAGE_COUNT, 0)
        set(value) { prefs.encode(KEY_QUOTA_IMAGE_COUNT, value) }

    var freeAgentQuestionCount: Int
        get() = prefs.decodeInt(KEY_FREE_AGENT_QUESTION_COUNT, 0)
        set(value) { prefs.encode(KEY_FREE_AGENT_QUESTION_COUNT, value.coerceAtLeast(0)) }

    // Server-configured quota limits (refreshed from /membership API)
    var serverFreeVideoDaily: Int
        get() = prefs.decodeInt(KEY_SERVER_FREE_VIDEO_DAILY, -1)
        set(value) { prefs.encode(KEY_SERVER_FREE_VIDEO_DAILY, value) }

    var serverFreeImageDaily: Int
        get() = prefs.decodeInt(KEY_SERVER_FREE_IMAGE_DAILY, -1)
        set(value) { prefs.encode(KEY_SERVER_FREE_IMAGE_DAILY, value) }

    var serverVipVideoDaily: Int
        get() = prefs.decodeInt(KEY_SERVER_VIP_VIDEO_DAILY, -1)
        set(value) { prefs.encode(KEY_SERVER_VIP_VIDEO_DAILY, value) }

    var serverVipImageDaily: Int
        get() = prefs.decodeInt(KEY_SERVER_VIP_IMAGE_DAILY, -1)
        set(value) { prefs.encode(KEY_SERVER_VIP_IMAGE_DAILY, value) }

    var serverVipRemindDays: Int
        get() = prefs.decodeInt(KEY_SERVER_VIP_REMIND_DAYS, 3)
        set(value) { prefs.encode(KEY_SERVER_VIP_REMIND_DAYS, value) }

    var isLoggedIn: Boolean
        get() = prefs.decodeBool(KEY_IS_LOGGED_IN, false)
        set(value) {
            prefs.encode(KEY_IS_LOGGED_IN, value)
        }

    var userPhone: String?
        get() = prefs.decodeString(KEY_USER_PHONE, null)
        set(value) {
            prefs.encodeOrRemove(KEY_USER_PHONE, value)
        }

    var userAccessToken: String?
        get() = prefs.decodeString(KEY_USER_ACCESS_TOKEN, null)
        set(value) {
            prefs.encodeOrRemove(KEY_USER_ACCESS_TOKEN, value)
        }

//    切测试服：DEFAULT_AGENTCLAW_BASE_URL = "http://192.168.1.37:8066/v1"
//    切正式服：DEFAULT_AGENTCLAW_BASE_URL = "https://www.cjym123.cn/v1"
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
        private const val LEGACY_PROD_BASE_URL = "http://39.108.144.196:8066/v1"
        private const val LEGACY_PROD_BASE_URL_NO_V1 = "http://39.108.144.196:8066"
        private const val LEGACY_PROD_HTTPS_BASE_URL = "https://39.108.144.196:8066/v1"
        private const val LEGACY_PROD_HTTPS_BASE_URL_NO_V1 = "https://39.108.144.196:8066"
        private const val LEGACY_TEST_BASE_URL = "http://192.168.1.37:8066/v1"
        private const val LEGACY_TEST_BASE_URL_NO_V1 = "http://192.168.1.37:8066"
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
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_USER_ACCESS_TOKEN = "user_access_token"
        private const val KEY_AGENTCLAW_BASE_URL = "inmoclaw_base_url"
        private const val KEY_SIDEBAR_VISIBLE = "sidebar_visible"
        private const val KEY_PREFIX_SESSION_ENTRY_MODE = "session_entry_mode:"
        private const val KEY_VIP_ACTIVE = "vip_active"
        private const val KEY_VIP_EXPIRES_AT = "vip_expires_at"
        private const val KEY_QUOTA_VIDEO_DATE = "quota_video_date"
        private const val KEY_QUOTA_VIDEO_COUNT = "quota_video_count"
        private const val KEY_QUOTA_IMAGE_DATE = "quota_image_date"
        private const val KEY_QUOTA_IMAGE_COUNT = "quota_image_count"
        private const val KEY_FREE_AGENT_QUESTION_COUNT = "free_agent_question_count"
        private const val KEY_SERVER_FREE_VIDEO_DAILY = "server_free_video_daily"
        private const val KEY_SERVER_FREE_IMAGE_DAILY = "server_free_image_daily"
        private const val KEY_SERVER_VIP_VIDEO_DAILY = "server_vip_video_daily"
        private const val KEY_SERVER_VIP_IMAGE_DAILY = "server_vip_image_daily"
        private const val KEY_SERVER_VIP_REMIND_DAYS = "server_vip_remind_days"
        const val DEFAULT_AGENTCLAW_BASE_URL = "https://www.cjym123.cn/v1"

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

            // 3. Device token assigned by backend during pairing
            val deviceToken = prefs.nodeDeviceToken
            if (!deviceToken.isNullOrEmpty()) return deviceToken

            return null
        }
    }

    private fun normalizeAgentclawBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return when (trimmed.lowercase()) {
            LEGACY_PROD_BASE_URL,
            LEGACY_PROD_BASE_URL_NO_V1,
            LEGACY_PROD_HTTPS_BASE_URL,
            LEGACY_PROD_HTTPS_BASE_URL_NO_V1,
            LEGACY_TEST_BASE_URL,
            LEGACY_TEST_BASE_URL_NO_V1 -> DEFAULT_AGENTCLAW_BASE_URL
            else -> trimmed
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
