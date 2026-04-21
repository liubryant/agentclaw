package ai.inmo.openclaw.data.repository

import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.local.prefs.PreferencesManager
import ai.inmo.openclaw.proot.BootstrapManager
import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.Instant

class SnapshotManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val bootstrapManager by lazy {
        BootstrapManager(
            context.applicationContext,
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir
        )
    }

    fun exportVersionSnapshot(version: String): File? {
        val externalDir = context.getExternalFilesDir("snapshots") ?: return null
        val snapshot = buildSnapshot()
        val target = File(externalDir, "openclaw-snapshot-$version.json")
        target.parentFile?.mkdirs()
        target.writeText(gson.toJson(snapshot))
        return target
    }

    fun exportLatestSnapshot(): File? {
        val externalDir = context.getExternalFilesDir("snapshots") ?: return null
        val snapshot = buildSnapshot()
        val target = File(externalDir, "openclaw-snapshot.json")
        target.parentFile?.mkdirs()
        target.writeText(gson.toJson(snapshot))
        return target
    }

    fun importLatestSnapshot(): File? {
        val externalDir = context.getExternalFilesDir("snapshots") ?: return null
        val target = File(externalDir, "openclaw-snapshot.json")
        if (!target.exists()) return null

        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val snapshot = gson.fromJson<Map<String, Any?>>(target.readText(), type) ?: emptyMap()
        restoreSnapshot(snapshot)
        return target
    }

    private fun buildSnapshot(): Map<String, Any?> {
        return mapOf(
            "version" to AppConstants.VERSION,
            "timestamp" to Instant.now().toString(),
            "openclawConfig" to bootstrapManager.readRootfsFile("root/.openclaw/openclaw.json"),
            "dashboardUrl" to preferencesManager.dashboardUrl,
            "autoStart" to preferencesManager.autoStartGateway,
            "nodeEnabled" to preferencesManager.nodeEnabled,
            "nodeDeviceToken" to preferencesManager.nodeDeviceToken,
            "nodeGatewayHost" to preferencesManager.nodeGatewayHost,
            "nodeGatewayPort" to preferencesManager.nodeGatewayPort,
            "nodeGatewayToken" to preferencesManager.nodeGatewayToken
        )
    }

    private fun restoreSnapshot(snapshot: Map<String, Any?>) {
        (snapshot["openclawConfig"] as? String)?.let {
            bootstrapManager.writeRootfsFile("root/.openclaw/openclaw.json", it)
        }
        preferencesManager.dashboardUrl = snapshot["dashboardUrl"] as? String
        (snapshot["autoStart"] as? Boolean)?.let { preferencesManager.autoStartGateway = it }
        (snapshot["nodeEnabled"] as? Boolean)?.let { preferencesManager.nodeEnabled = it }
        preferencesManager.nodeDeviceToken = snapshot["nodeDeviceToken"] as? String
        preferencesManager.nodeGatewayHost = snapshot["nodeGatewayHost"] as? String
        val port = snapshot["nodeGatewayPort"]
        preferencesManager.nodeGatewayPort = when (port) {
            is Number -> port.toInt()
            is String -> port.toIntOrNull()
            else -> null
        }
        preferencesManager.nodeGatewayToken = snapshot["nodeGatewayToken"] as? String
    }
}
