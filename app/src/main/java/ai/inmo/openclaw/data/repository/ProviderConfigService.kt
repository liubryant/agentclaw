package ai.inmo.openclaw.data.repository

import ai.inmo.openclaw.domain.model.AiProvider
import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File

class ProviderConfigService(
    private val context: Context
) {
    private val gson = Gson()
    private val configFile: File
        get() = File(context.filesDir, "rootfs/ubuntu/root/.openclaw/openclaw.json")

    data class ProviderConfigSnapshot(
        val activeModel: String?,
        val providers: Map<String, Any?>
    )

    fun readConfig(): ProviderConfigSnapshot {
        if (!configFile.exists()) {
            return ProviderConfigSnapshot(activeModel = null, providers = emptyMap())
        }

        return try {
            val root = readMutableRoot()
            val agents = root["agents"] as? Map<*, *>
            val defaults = agents?.get("defaults") as? Map<*, *>
            val model = defaults?.get("model") as? Map<*, *>
            val activeModel = model?.get("primary") as? String

            val models = root["models"] as? Map<*, *>
            val providers = models?.get("providers") as? Map<String, Any?> ?: emptyMap()
            ProviderConfigSnapshot(activeModel = activeModel, providers = providers)
        } catch (_: JsonSyntaxException) {
            ProviderConfigSnapshot(activeModel = null, providers = emptyMap())
        } catch (_: Exception) {
            ProviderConfigSnapshot(activeModel = null, providers = emptyMap())
        }
    }

    fun saveProviderConfig(provider: AiProvider, apiKey: String, model: String) {
        val root = readMutableRoot()
        val models = (root.getOrPut("models") { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>)
        val providers =
            (models.getOrPut("providers") { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>)

        providers[provider.id] = mutableMapOf(
            "apiKey" to apiKey,
            "baseUrl" to provider.baseUrl,
            "models" to listOf(
                mutableMapOf(
                    "id" to model,
                    "name" to model
                )
            )
        ).apply {
            if (provider.apiType != null) {
                put("api", provider.apiType)
            }
            if (provider.authHeader) {
                put("authHeader", true)
            }
        }

//        val isInmoClaw = (provider.id == AiProvider.INMOCLAW.id)
//        val effectiveApiKey = if (isInmoClaw) "\${${AiProvider.ENV_INMOCLAW_API_KEY}}" else apiKey
//        val effectiveBaseUrl = if (isInmoClaw) "\${${AiProvider.ENV_INMOCLAW_BASE_URL}}" else provider.baseUrl
//
//        providers[provider.id] = mutableMapOf(
//            "apiKey" to effectiveApiKey,
//            "baseUrl" to effectiveBaseUrl,
//            "models" to listOf(
//                mutableMapOf(
//                    "id" to model,
//                    "name" to model
//                )
//            )
//        ).apply {
//            if (provider.apiType != null) {
//                put("api", provider.apiType)
//            }
//            if (provider.authHeader) {
//                put("authHeader", true)
//            }
//        }

        val agents = (root.getOrPut("agents") { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>)
        val defaults =
            (agents.getOrPut("defaults") { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>)
        val modelMap =
            (defaults.getOrPut("model") { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>)
        modelMap["primary"] = "${provider.id}/$model"

        persist(root)
    }

    fun removeProviderConfig(provider: AiProvider) {
        val root = readMutableRoot()
        val models = root["models"] as? MutableMap<String, Any?>
        val providers = models?.get("providers") as? MutableMap<String, Any?>
        providers?.remove(provider.id)

        val snapshot = readConfigFromRoot(root)
        val activeModel = snapshot.activeModel
        if (activeModel != null && activeModel.startsWith("${provider.id}/")) {
            val nextProviderEntry = providers?.entries?.firstOrNull()
            val nextModel = (nextProviderEntry?.value as? Map<*, *>)
                ?.get("models") as? List<*>
            val nextModelId = (nextModel?.firstOrNull() as? Map<*, *>)?.get("id")?.toString()
            val agents = root["agents"] as? MutableMap<String, Any?>
            val defaults = agents?.get("defaults") as? MutableMap<String, Any?>
            val modelMap = defaults?.get("model") as? MutableMap<String, Any?>
            if (nextProviderEntry != null && !nextModelId.isNullOrBlank()) {
                modelMap?.put("primary", "${nextProviderEntry.key}/$nextModelId")
            } else {
                modelMap?.remove("primary")
            }
        }

        persist(root)
    }

    private fun readMutableRoot(): MutableMap<String, Any?> {
        if (!configFile.exists()) return mutableMapOf()
        val json = configFile.readText()
        val type = object : TypeToken<MutableMap<String, Any?>>() {}.type
        return gson.fromJson(json, type) ?: mutableMapOf()
    }

    private fun readConfigFromRoot(root: MutableMap<String, Any?>): ProviderConfigSnapshot {
        val agents = root["agents"] as? Map<*, *>
        val defaults = agents?.get("defaults") as? Map<*, *>
        val model = defaults?.get("model") as? Map<*, *>
        val activeModel = model?.get("primary") as? String
        val models = root["models"] as? Map<*, *>
        val providers = models?.get("providers") as? Map<String, Any?> ?: emptyMap()
        return ProviderConfigSnapshot(activeModel = activeModel, providers = providers)
    }

    private fun persist(root: MutableMap<String, Any?>) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(gson.toJson(root))
    }
}
