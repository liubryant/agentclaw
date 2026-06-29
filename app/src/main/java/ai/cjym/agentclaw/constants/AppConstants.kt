package ai.cjym.agentclaw.constants

import java.util.Locale

object AppConstants {
    const val APP_NAME = "Agent智能体生活助手"
    const val VERSION = "1.0.0"
    const val PACKAGE_NAME = "ai.cjym.agentclaw"

    /** Matches ANSI escape sequences (e.g. color codes in terminal output). */
    val ANSI_ESCAPE = Regex("\u001b\\[[0-9;]*[a-zA-Z]")

    const val AUTHOR_NAME = "AgentClaw Team"
    const val AUTHOR_EMAIL = "support@cjym123.cn"
    const val GITHUB_URL = "https://www.cjym123.cn"
    const val LICENSE = "MIT"

    const val GITHUB_API_LATEST_RELEASE = ""

    const val ORG_NAME = "AgentClaw"
    const val ORG_EMAIL = "support@cjym123.cn"
    const val INSTAGRAM_URL = ""
    const val YOUTUBE_URL = ""
    const val PLAY_STORE_URL = ""

    // Remote gateway fallback (used when user hasn't configured a custom host)
    const val GATEWAY_HOST = "127.0.0.1"
    const val GATEWAY_PORT = 18789

    const val BOT_API_URL_PROD = "https://ai.bot.cjym.com"
    const val BOT_API_URL_TEST = "https://testai.bot.cjym.com"

    const val USER_AGREEMENT_URL =
        "https://www.cjym123.cn/agreement_agentclaw.html"
    const val VIP_AGREEMENT_URL =
        "https://www.cjym123.cn/agreement_agentclaw_vip.html"
    const val PRIVACY_POLICY_URL =
        "https://www.cjym123.cn/privacy_agentclaw.html"

    // Node WebSocket reconnection
    const val WS_RECONNECT_BASE_MS = 350L
    const val WS_RECONNECT_MULTIPLIER = 1.7
    const val WS_RECONNECT_CAP_MS = 8000L
    const val NODE_ROLE = "node"
    const val PAIRING_TIMEOUT_MS = 300000L

    // Agent generation timeout (30 min)
    const val DEFAULT_AGENT_TIMEOUT_SECONDS = 30 * 60

    /** Detect if the user is likely in China based on device locale. */
    fun isChineseLocale(): Boolean {
        return try {
            Locale.getDefault().country == "CN"
        } catch (_: Exception) {
            false
        }
    }
}
