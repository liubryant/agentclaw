package ai.inmo.openclaw.data.repository

import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.local.prefs.PreferencesManager
import ai.inmo.openclaw.domain.model.AiProvider
import ai.inmo.openclaw.proot.BootstrapManager
import ai.inmo.core_common.utils.Logger
import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.security.SecureRandom

class GatewayConfigService(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    private companion object {
        private const val TAG = "GatewayConfigService"
        private const val CONFIG_PATH = "root/.openclaw/openclaw.json"
        private const val SKILL_ASSET_ROOT = "skills"
        private const val ROOTFS_SKILL_DIR = "root/.openclaw/skills"
    }

    data class PreparedGatewayConfig(
        val dashboardUrl: String?,
        val token: String?
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val bootstrapManager by lazy {
        BootstrapManager(
            context.applicationContext,
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir
        )
    }

    fun prepareForLaunch(): PreparedGatewayConfig {
        val existing = readMutableConfig()
        val merged = GatewayConfigDefaults.mergeConfig(existing)
        bootstrapManager.writeRootfsFile(CONFIG_PATH, gson.toJson(merged.config))
        deployBundledSkills()

        val upstreamSummary = runCatching {
            val models = merged.config["models"] as? Map<*, *>
            val providers = models?.get("providers") as? Map<*, *>
            val inmo = providers?.get(AiProvider.INMOCLAW.id) as? Map<*, *>
            val baseUrl = inmo?.get("baseUrl")?.toString().orEmpty()
            val api = inmo?.get("api")?.toString().orEmpty()
            val primaryModel = (((merged.config["agents"] as? Map<*, *>)
                ?.get("defaults") as? Map<*, *>)
                ?.get("model") as? Map<*, *>)
                ?.get("primary")?.toString().orEmpty()
            "primary=$primaryModel, api=$api, baseUrl=$baseUrl"
        }.getOrDefault("primary=?, api=?, baseUrl=?")
        Logger.d(TAG, "agentclaw GATEWAY_UPSTREAM_CONFIG $upstreamSummary")

        merged.dashboardUrl?.let { preferencesManager.dashboardUrl = it }
        return PreparedGatewayConfig(
            dashboardUrl = merged.dashboardUrl,
            token = merged.token
        )
    }

    fun syncDashboardUrlFromConfig(): String? {
        val merged = GatewayConfigDefaults.mergeConfig(readMutableConfig(), generateToken = false)
        merged.dashboardUrl?.let { preferencesManager.dashboardUrl = it }
        return merged.dashboardUrl
    }

    private fun deployBundledSkills() {
        val skillDirs = context.assets.list(SKILL_ASSET_ROOT).orEmpty()
        for (skillDir in skillDirs) {
            runCatching {
                copyAssetTree(
                    assetPath = "$SKILL_ASSET_ROOT/$skillDir",
                    rootfsPath = "$ROOTFS_SKILL_DIR/$skillDir"
                )
            }
        }
    }

    private fun copyAssetTree(assetPath: String, rootfsPath: String) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            bootstrapManager.writeRootfsBytes(rootfsPath, bytes)
            return
        }

        for (child in children) {
            copyAssetTree(
                assetPath = "$assetPath/$child",
                rootfsPath = "$rootfsPath/$child"
            )
        }
    }

    private fun readMutableConfig(): MutableMap<String, Any?> {
        val raw = bootstrapManager.readRootfsFile(CONFIG_PATH).orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Any?>>() {}.type
            gson.fromJson<MutableMap<String, Any?>>(raw, type) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

}

object GatewayConfigDefaults {
     const val DEFAULT_AGENT_TIMEOUT_SECONDS = 30 * 60 // 30 分钟， openclaw 默认 10 分钟，目前没看到这个超时上层有接口可以监听到

    data class MergeResult(
        val config: MutableMap<String, Any?>,
        val token: String?,
        val dashboardUrl: String?
    )

    private val secureRandom = SecureRandom()

    private val allowCommands = listOf(
        "camera.snap",
        "camera.clip",
        "camera.list",
        "canvas.navigate",
        "canvas.eval",
        "canvas.snapshot",
        "fs.list",
        "fs.stat",
        "fs.readText",
        "fs.readBytes",
        "fs.writeText",
        "fs.writeBytes",
        "fs.mkdir",
        "fs.delete",
        "fs.image.load",
        "location.get",
        "system.app.open",
        "system.volume.set",
        "system.volume.adjust",
        "system.volume.status",
        "system.brightness.set",
        "system.brightness.adjust",
        "system.brightness.status",
        "system.wifi.on",
        "system.wifi.off",
        "system.wifi.status",
        "system.bluetooth.on",
        "system.bluetooth.off",
        "system.bluetooth.status",
        "system.media.play",
        "system.media.pause",
        "system.media.toggle",
        "system.media.next",
        "system.media.previous",
        "system.screenshot",
        "system.shutdown",
        "system.tasks.clear",
        "system.home",
        "system.back",
        "system.battery.status",
        "system.performance.high",
        "system.performance.normal",
        "system.performance.status",
        "system.powerSave.on",
        "system.powerSave.off",
        "system.powerSave.status",
        "system.language.set",
        "system.doNotDisturb.on",
        "system.doNotDisturb.off",
        "system.doNotDisturb.status",
        "system.screenOffNotification.on",
        "system.screenOffNotification.off",
        "system.screenOffNotification.status",
        "system.autoai.start",
        "system.autoai.status",
        "system.autoai.cancel"
    )

    fun mergeConfig(
        existing: MutableMap<String, Any?>,
        generateToken: Boolean = true
    ): MergeResult {
        val gateway = existing.mutableChild("gateway")
        val nodes = gateway.mutableChild("nodes")
        val tools = existing.mutableChild("tools")
        val agents = existing.mutableChild("agents")
        val defaults = agents.mutableChild("defaults")
        val model = defaults.mutableChild("model")
        val models = existing.mutableChild("models")
        val providers = models.mutableChild("providers")
        val http = gateway.mutableChild("http")
        val endpoints = http.mutableChild("endpoints")
        val chatCompletions = endpoints.mutableChild("chatCompletions")
        val auth = gateway.mutableChild("auth")

        nodes["denyCommands"] = emptyList<String>()
        nodes["allowCommands"] = allowCommands
        // 不再强制 full 工具集，避免每次请求都注入超大 tools schema，
        // 导致上游首包慢、触发 embedded LLM request timed out。
        tools.remove("profile")
        defaults.putIfAbsent("timeoutSeconds", DEFAULT_AGENT_TIMEOUT_SECONDS)
        // clawbootdo 当前联调基线固定 glm-4.7（Postman 已验证可用），
        // 这里显式覆盖，避免历史配置残留为 glm-5.1 导致网关侧超时/异常。
        model["primary"] = "${AiProvider.INMOCLAW.id}/glm-4.7"
        chatCompletions["enabled"] = true
        gateway.putIfAbsent("mode", "local")
        auth.putIfAbsent("mode", "token")

        val inmoProvider = providers.mutableChild(AiProvider.INMOCLAW.id)
        inmoProvider["baseUrl"] = AiProvider.INMOCLAW.baseUrl
        inmoProvider["api"] = "openai-completions"
        inmoProvider.putIfAbsent("apiKey", "")
        // 修复：openclaw 当前配置 schema 不支持 timeoutMs，若存在会导致网关启动失败。
        inmoProvider.remove("timeoutMs")
        inmoProvider["models"] = listOf(
            mutableMapOf("id" to "glm-5.1", "name" to "glm-5.1"),
            mutableMapOf("id" to "glm-4.7", "name" to "glm-4.7")
        )

        val existingToken = auth["token"]?.toString()?.takeIf { it.isNotBlank() }
        val effectiveToken = when {
            existingToken != null -> existingToken
            generateToken -> randomToken().also { auth["token"] = it }
            else -> null
        }

        return MergeResult(
            config = existing,
            token = if (existingToken == null) effectiveToken else null,
            dashboardUrl = effectiveToken?.let { "${AppConstants.GATEWAY_URL}/#token=$it" }
        )
    }

    private fun MutableMap<String, Any?>.mutableChild(key: String): MutableMap<String, Any?> {
        val existingValue = this[key] as? MutableMap<String, Any?>
        if (existingValue != null) return existingValue

        val copied = (this[key] as? Map<*, *>)?.entries?.associate { entry ->
            entry.key.toString() to entry.value
        }?.toMutableMap()

        return (copied ?: mutableMapOf()).also { this[key] = it }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
