package ai.inmo.openclaw.capability

import ai.inmo.openclaw.CapabilityServiceClient
import android.app.ActivityManager
import android.app.Instrumentation
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class SystemCommandExecutor(
    private val context: Context,
    private val capabilityClient: CapabilityServiceClient
) {
    fun invokeOrFallback(
        method: String,
        params: Map<String, Any?> = emptyMap(),
        localFallback: (() -> Map<String, Any?>)? = null
    ): Map<String, Any?> {
        val paramsJson = JSONObject(params).toString()
        if (capabilityClient.isConnected()) {
            val json = capabilityClient.invoke(method, paramsJson)
            if (!json.isNullOrBlank()) {
                return jsonToMap(json)
            }
        }

        if (localFallback != null) {
            return localFallback()
        }

        throw CapabilityUnavailableException("Capability service not connected for $method")
    }

    fun openAppByNameLocal(appName: String): Map<String, Any?> {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(launchIntent, 0)
        val exactMatches = apps.filter {
            it.loadLabel(context.packageManager).toString().equals(appName, ignoreCase = true)
        }
        val candidates = exactMatches.ifEmpty {
            apps.filter {
                it.loadLabel(context.packageManager).toString().contains(appName, ignoreCase = true)
            }
        }
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("No app found matching: $appName")
        }
        if (candidates.size > 1 && exactMatches.isEmpty()) {
            val names = candidates.map { it.loadLabel(context.packageManager).toString() }
            throw IllegalArgumentException("Multiple apps match '$appName': ${names.joinToString(", ")}")
        }
        val pkg = candidates.first().activityInfo.packageName
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw IllegalArgumentException("No launch intent for matched app")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(intent)
        return mapOf("appName" to appName, "launched" to true)
    }

    fun getVolumeStatus(): Map<String, Any?> {
        val audioManager = audioManager()
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val percent = ((current * 100f) / max).roundToInt()
        return mapOf(
            "stream" to "music",
            "current" to current,
            "max" to max,
            "percent" to percent,
            "muted" to (current == 0)
        )
    }

    fun setVolumePercent(percent: Int): Map<String, Any?> {
        val audioManager = audioManager()
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = ((percent.coerceIn(0, 100) / 100f) * max).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return getVolumeStatus() + ("status" to "set")
    }

    fun adjustVolumePercent(delta: Int): Map<String, Any?> {
        val currentPercent = (getVolumeStatus()["percent"] as? Number)?.toInt() ?: 0
        return setVolumePercent((currentPercent + delta).coerceIn(0, 100)) + ("status" to "adjusted")
    }

    fun getBrightnessStatus(): Map<String, Any?> {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 127)
        val mode = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        val percent = ((raw * 100f) / 255f).roundToInt().coerceIn(0, 100)
        return mapOf(
            "raw" to raw,
            "percent" to percent,
            "mode" to if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "automatic" else "manual",
            "canWrite" to Settings.System.canWrite(context)
        )
    }

    fun setBrightnessPercent(percent: Int): Map<String, Any?> {
        if (!Settings.System.canWrite(context)) {
            throw SecurityException("WRITE_SETTINGS permission not granted")
        }
        val raw = ((percent.coerceIn(0, 100) / 100f) * 255f).roundToInt().coerceIn(0, 255)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        return getBrightnessStatus() + ("status" to "set")
    }

    fun adjustBrightnessPercent(delta: Int): Map<String, Any?> {
        val currentPercent = (getBrightnessStatus()["percent"] as? Number)?.toInt() ?: 0
        return setBrightnessPercent((currentPercent + delta).coerceIn(0, 100)) + ("status" to "adjusted")
    }

    fun getWifiStatus(): Map<String, Any?> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return mapOf(
            "enabled" to wifiManager.isWifiEnabled,
            "state" to wifiStateName(wifiManager.wifiState)
        )
    }

    @Suppress("DEPRECATION")
    fun setWifiEnabled(enabled: Boolean): Map<String, Any?> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val changed = wifiManager.setWifiEnabled(enabled)
        return getWifiStatus() + mapOf(
            "status" to if (changed) "updated" else "unchanged",
            "requestedEnabled" to enabled
        )
    }

    fun getBluetoothStatus(): Map<String, Any?> {
        val adapter = bluetoothAdapter()
        return mapOf(
            "enabled" to adapter.isEnabled,
            "state" to bluetoothStateName(adapter.state)
        )
    }

    @Suppress("DEPRECATION")
    fun setBluetoothEnabled(enabled: Boolean): Map<String, Any?> {
        val adapter = bluetoothAdapter()
        val changed = if (enabled) adapter.enable() else adapter.disable()
        return getBluetoothStatus() + mapOf(
            "status" to if (changed) "updated" else "unchanged",
            "requestedEnabled" to enabled
        )
    }

    fun sendMediaAction(action: String, seconds: Int?): Map<String, Any?> {
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "seekForward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "seekBackward" -> KeyEvent.KEYCODE_MEDIA_REWIND
            else -> throw IllegalArgumentException("Unsupported media action: $action")
        }
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager().dispatchMediaKeyEvent(down)
        audioManager().dispatchMediaKeyEvent(up)
        return mapOf(
            "action" to action,
            "seconds" to (seconds ?: 10),
            "status" to "sent"
        )
    }

    fun takeScreenshot(): Map<String, Any?> {
        Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_SYSRQ)
        return mapOf("success" to true)
    }

    fun shutdown(): Map<String, Any?> {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val method = PowerManager::class.java.getDeclaredMethod(
            "shutdown",
            Boolean::class.java,
            String::class.java,
            Boolean::class.java
        )
        method.isAccessible = true
        method.invoke(powerManager, false, "user_request", false)
        return mapOf("triggered" to true)
    }

    fun clearBackgroundTasks(): Map<String, Any?> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = activityManager.runningAppProcesses ?: emptyList()
        processes.forEach { process ->
            if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                activityManager.killBackgroundProcesses(process.processName)
            }
        }
        context.sendBroadcast(Intent("com.inmo.nexusconsole.CLEAN_BACKGROUND_APPS"))
        return mapOf("success" to true)
    }

    fun goHome(): Map<String, Any?> {
        Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
        return mapOf("success" to true)
    }

    fun goBack(): Map<String, Any?> {
        Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        return mapOf("success" to true)
    }

    @Suppress("DEPRECATION")
    fun closeForegroundApp(): Map<String, Any?> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = activityManager.getRunningTasks(1)
        val foregroundPackage = tasks?.firstOrNull()?.topActivity?.packageName
            ?: return mapOf("success" to false, "reason" to "no_foreground_app")
        if (foregroundPackage == context.packageName) {
            return mapOf("success" to false, "reason" to "cannot_close_self")
        }
        try {
            val method = ActivityManager::class.java.getDeclaredMethod("forceStopPackage", String::class.java)
            method.isAccessible = true
            method.invoke(activityManager, foregroundPackage)
        } catch (_: Exception) {
            activityManager.killBackgroundProcesses(foregroundPackage)
        }
        return mapOf("success" to true, "package" to foregroundPackage)
    }

    fun getBatteryStatus(): Map<String, Any?> {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) batteryManager.isCharging else false
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return mapOf(
            "level" to level,
            "charging" to charging,
            "powerConnected" to (plugged != 0),
            "powerSaveMode" to powerManager.isPowerSaveMode
        )
    }

    fun setPerformanceMode(mode: String): Map<String, Any?> {
        val success = Settings.System.putString(context.contentResolver, "performance_level", mode)
        return mapOf("success" to success, "mode" to mode)
    }

    fun getPerformanceMode(): Map<String, Any?> {
        val mode = Settings.System.getString(context.contentResolver, "performance_level") ?: "normal"
        return mapOf("mode" to mode)
    }

    fun setPowerSaveMode(enabled: Boolean): Map<String, Any?> {
        val success = Settings.Global.putInt(context.contentResolver, "low_power", if (enabled) 1 else 0)
        return mapOf("success" to success, "enabled" to enabled)
    }

    fun getPowerSaveStatus(): Map<String, Any?> {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return mapOf("enabled" to powerManager.isPowerSaveMode)
    }

    fun setSystemLanguage(language: String, country: String): Map<String, Any?> {
        val target = java.util.Locale(language, country)
        val localePickerClass = Class.forName("com.android.internal.app.LocalePicker")
        val getLocalesMethod = localePickerClass.getDeclaredMethod("getLocales")
        val current = getLocalesMethod.invoke(null) as android.os.LocaleList
        val locales = ArrayList<java.util.Locale>(current.size() + 1)
        locales.add(target)
        for (index in 0 until current.size()) {
            val locale = current[index]
            val same = locale.language.equals(target.language, true) &&
                locale.country.equals(target.country, true)
            if (!same) locales.add(locale)
        }
        val updateLocalesMethod = localePickerClass.getDeclaredMethod(
            "updateLocales",
            android.os.LocaleList::class.java
        )
        updateLocalesMethod.invoke(null, android.os.LocaleList(*locales.toTypedArray()))
        return mapOf("success" to true, "language" to language, "country" to country)
    }

    fun setDoNotDisturb(enabled: Boolean): Map<String, Any?> {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            val setZenMode = NotificationManager::class.java.getDeclaredMethod(
                "setZenMode",
                Int::class.java,
                Uri::class.java,
                String::class.java
            )
            setZenMode.isAccessible = true
            setZenMode.invoke(notificationManager, if (enabled) 1 else 0, null, "OpenClaw")
        } catch (_: Exception) {
            notificationManager.setInterruptionFilter(
                if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
        return mapOf("success" to true, "enabled" to enabled)
    }

    fun getDoNotDisturbStatus(): Map<String, Any?> {
        val zenMode = Settings.Global.getInt(context.contentResolver, "zen_mode", 0)
        return mapOf("enabled" to (zenMode != 0), "mode" to zenMode)
    }

    fun setScreenOffNotification(enabled: Boolean): Map<String, Any?> {
        Settings.Secure.putInt(context.contentResolver, "doze_enabled", if (enabled) 1 else 0)
        return mapOf("success" to true, "enabled" to enabled)
    }

    fun getScreenOffNotificationStatus(): Map<String, Any?> {
        val enabled = Settings.Secure.getInt(context.contentResolver, "doze_enabled", 0) == 1
        return mapOf("enabled" to enabled)
    }

    private fun audioManager(): AudioManager {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun bluetoothAdapter(): BluetoothAdapter {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter ?: throw IllegalStateException("Bluetooth not supported")
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter() ?: throw IllegalStateException("Bluetooth not supported")
        }
    }

    private fun wifiStateName(state: Int): String {
        return when (state) {
            WifiManager.WIFI_STATE_DISABLED -> "disabled"
            WifiManager.WIFI_STATE_DISABLING -> "disabling"
            WifiManager.WIFI_STATE_ENABLED -> "enabled"
            WifiManager.WIFI_STATE_ENABLING -> "enabling"
            else -> "unknown"
        }
    }

    private fun bluetoothStateName(state: Int): String {
        return when (state) {
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
            else -> "unknown"
        }
    }

    private fun jsonToMap(json: String): Map<String, Any?> {
        return jsonObjectToMap(JSONObject(json))
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = jsonValue(obj.get(key))
        }
        return map
    }

    private fun jsonArrayToList(array: JSONArray): List<Any?> {
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(jsonValue(array.get(index)))
            }
        }
    }

    private fun jsonValue(value: Any?): Any? {
        return when (value) {
            JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> jsonArrayToList(value)
            else -> value
        }
    }
}

class CapabilityUnavailableException(message: String) : IllegalStateException(message)
