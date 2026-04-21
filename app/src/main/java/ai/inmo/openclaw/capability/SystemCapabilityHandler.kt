package ai.inmo.openclaw.capability

import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.NodeFrame
import android.content.Context

class SystemCapabilityHandler(
    private val context: Context
) : NodeCapabilityHandler {
    private val executor = AppGraph.systemCommandExecutor

    override val name: String = "system"
    override val commands: List<String> = listOf(
        "system.app.open",
        "system.app.close",
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
        "system.media.seekForward",
        "system.media.seekBackward",
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

    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        return runCatching {
            when (command) {
                "system.app.open" -> openApp(params)
                "system.app.close" -> payload(executor.invokeOrFallback("closeForegroundApp") { executor.closeForegroundApp() })
                "system.volume.set" -> {
                    val value = requiredPercent(params, "value")
                    payload(executor.invokeOrFallback("setVolume", mapOf("value" to value)) { executor.setVolumePercent(value) })
                }
                "system.volume.adjust" -> {
                    val delta = requiredDelta(params)
                    payload(executor.invokeOrFallback("adjustVolume", mapOf("delta" to delta)) { executor.adjustVolumePercent(delta) })
                }
                "system.volume.status" -> payload(executor.invokeOrFallback("getVolumeStatus") { executor.getVolumeStatus() })
                "system.brightness.set" -> {
                    val value = requiredPercent(params, "value")
                    payload(executor.invokeOrFallback("setBrightness", mapOf("value" to value)) { executor.setBrightnessPercent(value) })
                }
                "system.brightness.adjust" -> {
                    val delta = requiredDelta(params)
                    payload(executor.invokeOrFallback("adjustBrightness", mapOf("delta" to delta)) { executor.adjustBrightnessPercent(delta) })
                }
                "system.brightness.status" -> payload(executor.invokeOrFallback("getBrightnessStatus") { executor.getBrightnessStatus() })
                "system.wifi.on" -> payload(executor.invokeOrFallback("setWifiEnabled", mapOf("enabled" to true)) { executor.setWifiEnabled(true) })
                "system.wifi.off" -> payload(executor.invokeOrFallback("setWifiEnabled", mapOf("enabled" to false)) { executor.setWifiEnabled(false) })
                "system.wifi.status" -> payload(executor.invokeOrFallback("getWifiStatus") { executor.getWifiStatus() })
                "system.bluetooth.on" -> payload(executor.invokeOrFallback("setBluetoothEnabled", mapOf("enabled" to true)) { executor.setBluetoothEnabled(true) })
                "system.bluetooth.off" -> payload(executor.invokeOrFallback("setBluetoothEnabled", mapOf("enabled" to false)) { executor.setBluetoothEnabled(false) })
                "system.bluetooth.status" -> payload(executor.invokeOrFallback("getBluetoothStatus") { executor.getBluetoothStatus() })
                "system.media.play" -> media("play", params)
                "system.media.pause" -> media("pause", params)
                "system.media.toggle" -> media("toggle", params)
                "system.media.next" -> media("next", params)
                "system.media.previous" -> media("previous", params)
                "system.media.seekForward" -> media("seekForward", params)
                "system.media.seekBackward" -> media("seekBackward", params)
                "system.screenshot" -> payload(executor.invokeOrFallback("takeScreenshot") { executor.takeScreenshot() })
                "system.shutdown" -> payload(executor.invokeOrFallback("shutdown") { executor.shutdown() })
                "system.tasks.clear" -> payload(executor.invokeOrFallback("clearBackgroundTasks") { executor.clearBackgroundTasks() })
                "system.home" -> payload(executor.invokeOrFallback("goHome") { executor.goHome() })
                "system.back" -> payload(executor.invokeOrFallback("goBack") { executor.goBack() })
                "system.battery.status" -> payload(executor.invokeOrFallback("getBatteryStatus") { executor.getBatteryStatus() })
                "system.performance.high" -> payload(executor.invokeOrFallback("setPerformanceMode", mapOf("mode" to "high")) { executor.setPerformanceMode("high") })
                "system.performance.normal" -> payload(executor.invokeOrFallback("setPerformanceMode", mapOf("mode" to "normal")) { executor.setPerformanceMode("normal") })
                "system.performance.status" -> payload(executor.invokeOrFallback("getPerformanceMode") { executor.getPerformanceMode() })
                "system.powerSave.on" -> payload(executor.invokeOrFallback("setPowerSaveMode", mapOf("enabled" to true)) { executor.setPowerSaveMode(true) })
                "system.powerSave.off" -> payload(executor.invokeOrFallback("setPowerSaveMode", mapOf("enabled" to false)) { executor.setPowerSaveMode(false) })
                "system.powerSave.status" -> payload(executor.invokeOrFallback("getPowerSaveStatus") { executor.getPowerSaveStatus() })
                "system.language.set" -> setLanguage(params)
                "system.doNotDisturb.on" -> payload(executor.invokeOrFallback("setDoNotDisturb", mapOf("enabled" to true)) { executor.setDoNotDisturb(true) })
                "system.doNotDisturb.off" -> payload(executor.invokeOrFallback("setDoNotDisturb", mapOf("enabled" to false)) { executor.setDoNotDisturb(false) })
                "system.doNotDisturb.status" -> payload(executor.invokeOrFallback("getDoNotDisturbStatus") { executor.getDoNotDisturbStatus() })
                "system.screenOffNotification.on" -> payload(executor.invokeOrFallback("setScreenOffNotification", mapOf("enabled" to true)) { executor.setScreenOffNotification(true) })
                "system.screenOffNotification.off" -> payload(executor.invokeOrFallback("setScreenOffNotification", mapOf("enabled" to false)) { executor.setScreenOffNotification(false) })
                "system.screenOffNotification.status" -> payload(executor.invokeOrFallback("getScreenOffNotificationStatus") { executor.getScreenOffNotificationStatus() })
                "system.autoai.start" -> startAutoAi(params)
                "system.autoai.status" -> autoAiStatus(params)
                "system.autoai.cancel" -> cancelAutoAi(params)
                else -> error("UNKNOWN_COMMAND", "Unknown system command: $command")
            }
        }.getOrElse { throwable ->
            when (throwable) {
                is CapabilityUnavailableException -> error("SERVICE_UNAVAILABLE", throwable.message ?: "Capability service not connected")
                is SecurityException -> error("PERMISSION_DENIED", throwable.message ?: "Permission denied")
                is IllegalArgumentException -> error("INVALID_PARAM", throwable.message ?: "Invalid params")
                else -> error("SYSTEM_ERROR", throwable.message ?: "System command failed")
            }
        }
    }

    private fun openApp(params: Map<String, Any?>): NodeFrame {
        val appName = (params["appName"] as? String)?.trim().orEmpty()
        if (appName.isBlank()) return error("MISSING_PARAM", "appName is required")
        return payload(executor.invokeOrFallback("openAppByName", mapOf("appName" to appName)) {
            executor.openAppByNameLocal(appName)
        })
    }

    private fun media(action: String, params: Map<String, Any?>): NodeFrame {
        val seconds = optionalSeconds(params)
        val methodParams = buildMap<String, Any?> {
            put("action", action)
            if (seconds != null) put("seconds", seconds)
        }
        return payload(executor.invokeOrFallback("sendMediaAction", methodParams) {
            executor.sendMediaAction(action, seconds)
        })
    }

    private fun setLanguage(params: Map<String, Any?>): NodeFrame {
        val language = (params["language"] as? String)?.trim().orEmpty()
        if (language.isBlank()) return error("MISSING_PARAM", "language is required")
        val country = (params["country"] as? String)?.trim().orEmpty()
        return payload(executor.invokeOrFallback("setLanguage", mapOf("language" to language, "country" to country)) {
            executor.setSystemLanguage(language, country)
        })
    }

    private fun startAutoAi(params: Map<String, Any?>): NodeFrame {
        val prompt = (params["prompt"] as? String)?.trim().orEmpty()
        if (prompt.isBlank()) return error("MISSING_PARAM", "prompt is required")
        val maxSteps = (params["maxSteps"] as? Number)?.toInt() ?: 30
        return payload(executor.invokeOrFallback("startAutoAITask", mapOf("prompt" to prompt, "maxSteps" to maxSteps)))
    }

    private fun autoAiStatus(params: Map<String, Any?>): NodeFrame {
        val taskId = (params["taskId"] as? String)?.trim().orEmpty()
        if (taskId.isBlank()) return error("MISSING_PARAM", "taskId is required")
        return payload(executor.invokeOrFallback("getAutoAITaskStatus", mapOf("taskId" to taskId)))
    }

    private fun cancelAutoAi(params: Map<String, Any?>): NodeFrame {
        val taskId = (params["taskId"] as? String)?.trim().orEmpty()
        if (taskId.isBlank()) return error("MISSING_PARAM", "taskId is required")
        return payload(executor.invokeOrFallback("cancelAutoAITask", mapOf("taskId" to taskId)))
    }

    private fun payload(payload: Map<String, Any?>): NodeFrame {
        return NodeFrame.response("", payload = payload)
    }

    private fun requiredPercent(params: Map<String, Any?>, key: String): Int {
        val value = (params[key] as? Number)?.toInt() ?: throw IllegalArgumentException("$key is required")
        if (value !in 0..100) throw IllegalArgumentException("$key must be between 0 and 100")
        return value
    }

    private fun requiredDelta(params: Map<String, Any?>): Int {
        val delta = (params["delta"] as? Number)?.toInt() ?: throw IllegalArgumentException("delta is required")
        if (delta !in -100..100) throw IllegalArgumentException("delta must be between -100 and 100")
        return delta
    }

    private fun optionalSeconds(params: Map<String, Any?>): Int? {
        val seconds = (params["seconds"] as? Number)?.toInt() ?: return null
        if (seconds <= 0) throw IllegalArgumentException("seconds must be greater than zero")
        return seconds
    }

    private fun error(code: String, message: String): NodeFrame {
        return NodeFrame.response("", error = mapOf("code" to code, "message" to message))
    }
}
