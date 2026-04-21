package ai.inmo.openclaw.service.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import ai.inmo.openclaw.MainActivity
import ai.inmo.openclaw.domain.model.AiProvider
import ai.inmo.openclaw.proot.BootstrapManager
import ai.inmo.openclaw.proot.ProcessManager
import ai.inmo.core_common.utils.DeviceInfo
import ai.inmo.core_common.utils.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque

class GatewayService : Service() {
    companion object {
        private const val TAG = "GatewayService"
        const val CHANNEL_ID = "openclaw_gateway"
        const val NOTIFICATION_ID = 1
        var isRunning = false
            private set
        @Volatile
        var lastErrorMessage: String? = null
            private set

        private val _logFlow = MutableSharedFlow<String>(
            replay = 100,
            extraBufferCapacity = 500
        )
        val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()
        private val recentFailureLines = ArrayDeque<String>()

        private var instance: GatewayService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun isProcessAlive(): Boolean {
            val inst = instance ?: return false
            if (!isRunning) return false
            val proc = inst.gatewayProcess
            if (proc != null) return proc.isAlive
            val thread = inst.gatewayThread
            if (thread != null && thread.isAlive) return true
            val elapsed = System.currentTimeMillis() - inst.startTime
            return elapsed < 120_000
        }

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            context.stopService(intent)
        }

        fun clearLastError() {
            lastErrorMessage = null
            synchronized(recentFailureLines) {
                recentFailureLines.clear()
            }
        }

        fun recordLaunchFailure(message: String) {
            lastErrorMessage = message
            synchronized(recentFailureLines) {
                recentFailureLines.addLast(message)
                while (recentFailureLines.size > 8) {
                    recentFailureLines.removeFirst()
                }
            }
        }
    }

    private var gatewayProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var restartCount = 0
    private val maxRestarts = 5
    private var startTime: Long = 0
    private var processStartTime: Long = 0
    private var uptimeThread: Thread? = null
    private var watchdogThread: Thread? = null
    private var gatewayThread: Thread? = null
    private val lock = Object()
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        if (isRunning) {
            updateNotificationRunning()
            return START_STICKY
        }
        stopping = false
        acquireWakeLock()
        startGateway()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        uptimeThread?.interrupt()
        uptimeThread = null
        watchdogThread?.interrupt()
        watchdogThread = null
        stopGateway()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun isPortInUse(port: Int = 18789): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun startGateway() {
        synchronized(lock) {
            if (stopping) return
            if (gatewayProcess?.isAlive == true) return
            isRunning = true
            instance = this
            startTime = System.currentTimeMillis()
            clearLastError()
        }

        gatewayThread = Thread {
            try {
                if (isPortInUse()) {
                    emitLog("[INFO] Gateway already running on port 18789, adopting existing instance")
                    updateNotificationRunning()
                    startUptimeTicker()
                    startWatchdog()
                    return@Thread
                }

                emitLog("[INFO] Setting up environment...")
                val filesDir = applicationContext.filesDir.absolutePath
                val nativeLibDir = applicationContext.applicationInfo.nativeLibraryDir
                val pm = ProcessManager(filesDir, nativeLibDir)

                val bootstrapManager = BootstrapManager(applicationContext, filesDir, nativeLibDir)
                try {
                    bootstrapManager.setupDirectories()
                    emitLog("[INFO] Directories ready")
                } catch (e: Exception) {
                    emitLog("[WARN] setupDirectories failed: ${e.message}")
                }
                try {
                    // 每次启动 gateway 都强制刷新 hook/bypass，避免 rootfs 里残留旧脚本
                    // 导致看不到最新 agentclaw 日志或 stream 改写逻辑不生效。
                    bootstrapManager.installBionicBypass()
                    emitLog("agentclaw HOOK_SYNC_OK")
                } catch (e: Exception) {
                    emitLog("agentclaw HOOK_SYNC_FAIL ${e.message}")
                }
                try {
                    bootstrapManager.writeResolvConf()
                } catch (e: Exception) {
                    emitLog("[WARN] writeResolvConf failed: ${e.message}")
                }

                val resolvContent = "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
                try {
                    val resolvFile = File(filesDir, "config/resolv.conf")
                    if (!resolvFile.exists() || resolvFile.length() == 0L) {
                        resolvFile.parentFile?.mkdirs()
                        resolvFile.writeText(resolvContent)
                        emitLog("[INFO] resolv.conf created (inline fallback)")
                    }
                } catch (e: Exception) {
                    emitLog("[WARN] inline resolv.conf fallback failed: ${e.message}")
                }
                try {
                    val rootfsResolv = File(filesDir, "rootfs/ubuntu/etc/resolv.conf")
                    if (!rootfsResolv.exists() || rootfsResolv.length() == 0L) {
                        rootfsResolv.parentFile?.mkdirs()
                        rootfsResolv.writeText(resolvContent)
                    }
                } catch (_: Exception) {}

                if (stopping) return@Thread

                if (isPortInUse()) {
                    emitLog("Gateway already running on port 18789, skipping launch")
                    updateNotificationRunning()
                    startUptimeTicker()
                    startWatchdog()
                    return@Thread
                }

                emitLog("[INFO] Spawning proot process...")
                synchronized(lock) {
                    if (stopping) return@Thread
                    processStartTime = System.currentTimeMillis()
                    gatewayProcess = pm.startProotProcess("openclaw gateway")
//                    val gatewayEnv = mapOf(
//                        AiProvider.ENV_INMOCLAW_API_KEY to "YM00FCE5600128",
//                        AiProvider.ENV_INMOCLAW_BASE_URL to AiProvider.INMOCLAW.baseUrl
//                    )
//                    gatewayProcess = pm.startProotProcess("openclaw gateway --verbose", gatewayEnv)
                }
                updateNotificationRunning()
                emitLog("[INFO] Gateway process spawned")
                startUptimeTicker()
                startWatchdog()

                val proc = gatewayProcess!!
                val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
                Thread {
                    try {
                        var line: String?
                        while (stdoutReader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            emitLog(l)
                        }
                    } catch (_: Exception) {}
                }.start()

                val stderrReader = BufferedReader(InputStreamReader(proc.errorStream))
                val currentRestartCount = restartCount
                Thread {
                    try {
                        var line: String?
                        while (stderrReader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            rememberFailureLine(l)
                            if (currentRestartCount == 0 ||
                                (!l.contains("proot warning") && !l.contains("can't sanitize"))) {
                                emitLog("[ERR] $l")
                            }
                        }
                    } catch (_: Exception) {}
                }.start()

                val exitCode = proc.waitFor()
                val uptimeMs = System.currentTimeMillis() - processStartTime
                val uptimeSec = uptimeMs / 1000
                emitLog("[INFO] Gateway exited with code $exitCode (uptime: ${uptimeSec}s)")
                if (exitCode != 0) {
                    lastErrorMessage = buildFailureSummary("Gateway exited with code $exitCode")
                }

                if (stopping) return@Thread

                if (uptimeMs > 60_000) {
                    restartCount = 0
                }

                if (isRunning && restartCount < maxRestarts) {
                    restartCount++
                    val delayMs = minOf(2000L * (1 shl (restartCount - 1)), 16000L)
                    emitLog("[INFO] Auto-restarting in ${delayMs / 1000}s (attempt $restartCount/$maxRestarts)...")
                    updateNotification("Restarting in ${delayMs / 1000}s (attempt $restartCount)...")
                    Thread.sleep(delayMs)
                    if (!stopping) {
                        startTime = System.currentTimeMillis()
                        startGateway()
                    }
                } else if (restartCount >= maxRestarts) {
                    emitLog("[WARN] Max restarts reached. Gateway stopped.")
                    updateNotification("Gateway stopped (crashed)")
                    isRunning = false
                    if (lastErrorMessage.isNullOrBlank()) {
                        lastErrorMessage = buildFailureSummary("Gateway stopped after repeated crashes")
                    }
                }
            } catch (e: Exception) {
                if (!stopping) {
                    emitLog("[ERROR] Gateway error: ${e.message}")
                    Logger.e(TAG, "Gateway error: ${e.message}")
                    isRunning = false
                    recordLaunchFailure(e.message ?: "Gateway error")
                    updateNotification("Gateway error")
                }
            }
        }.also { it.start() }
    }

    private fun stopGateway() {
        synchronized(lock) {
            stopping = true
            restartCount = maxRestarts
            uptimeThread?.interrupt()
            uptimeThread = null
            watchdogThread?.interrupt()
            watchdogThread = null
            gatewayProcess?.let {
                try { it.destroyForcibly() } catch (_: Exception) {}
                gatewayProcess = null
            }
        }
        emitLog("Gateway stopped by user")
    }

    private fun startWatchdog() {
        watchdogThread?.interrupt()
        watchdogThread = Thread {
            try {
                Thread.sleep(45_000)
                while (!Thread.interrupted() && isRunning && !stopping) {
                    val proc = gatewayProcess
                    if (proc != null && !proc.isAlive) {
                        emitLog("[WARN] Watchdog: gateway process not alive")
                        break
                    }
                    if (proc != null && !isPortInUse()) {
                        emitLog("[WARN] Watchdog: port 18789 not responding")
                    }
                    Thread.sleep(15_000)
                }
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
    }

    private fun startUptimeTicker() {
        uptimeThread?.interrupt()
        uptimeThread = Thread {
            try {
                while (!Thread.interrupted() && isRunning) {
                    Thread.sleep(60_000)
                    if (isRunning) updateNotificationRunning()
                }
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
    }

    private fun formatUptime(): String {
        val elapsed = System.currentTimeMillis() - startTime
        val seconds = elapsed / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    private fun updateNotificationRunning() {
        updateNotification("Running on port 18789 ? ${formatUptime()}")
    }

    private fun emitLog(message: String) {
        try {
            if (isGatewayNoiseLine(message)) return
            val ts = java.time.Instant.now().toString()
            val formatted = "$ts $message"
            Logger.d(TAG, message)
            _logFlow.tryEmit(formatted)
        } catch (_: Exception) {}
    }

    private fun isGatewayNoiseLine(message: String): Boolean {
        // verbose 输出会打印大量 JSON schema 行，刷爆 logcat；保留关键错误与状态日志。
        val line = normalizeGatewayLine(message)
        if (line.isBlank()) return true
        if (isImportantGatewaySignal(line)) return false

        if (line.length <= 2 && (line == "{" || line == "}" || line == "}," || line == "],")) {
            return true
        }

        // 过滤 OpenClaw 请求体/工具 schema 的 JSON 明细行，仅保留 URL/错误/状态类关键信号。
        if (line.startsWith("\"") || line.startsWith("{") || line.startsWith("}") || line.startsWith("[") || line.startsWith("]")) {
            return true
        }
        val schemaNoiseTokens = listOf(
            "\"type\":",
            "\"properties\":",
            "\"required\":",
            "\"description\":",
            "\"enum\":",
            "\"items\":",
            "\"parameters\":",
            "\"function\":",
            "\"strict\":",
            "\"name\":",
            "\"content\":",
            "\"tools\":",
            "\"messages\":"
        )
        if (schemaNoiseTokens.any { line.contains(it) }) {
            return true
        }

        return false
    }

    private fun isImportantGatewaySignal(line: String): Boolean {
        val lower = line.lowercase()
        val keepTokens = listOf(
            "agentclaw",
            "openclaw 发出请求",
            "url:",
            "\"model\":",
            "\"stream\":",
            "\"temperature\":",
            "[err]",
            "[error]",
            "[warn]",
            "fail",
            "timed out",
            "/v1/chat/completions",
            "chat.abort",
            "phase=error",
            "resp_chat_completions",
            "req_chat_completions",
            "gateway_upstream_config"
        )
        return keepTokens.any { lower.contains(it) }
    }

    private fun normalizeGatewayLine(raw: String): String {
        val noAnsi = raw.replace(Regex("\\u001B\\[[;\\d]*m"), "")
        return noAnsi.trim()
    }

    private fun rememberFailureLine(line: String) {
        if (line.isBlank()) return
        synchronized(recentFailureLines) {
            recentFailureLines.addLast(line)
            while (recentFailureLines.size > 8) {
                recentFailureLines.removeFirst()
            }
        }
    }

    private fun buildFailureSummary(prefix: String): String {
        val tail = synchronized(recentFailureLines) {
            recentFailureLines.toList().takeLast(3).joinToString(" | ")
        }
        return if (tail.isBlank()) prefix else "$prefix: $tail"
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClaw::GatewayWakeLock"
        )
        wakeLock?.acquire(24 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenClaw Gateway",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the OpenClaw gateway running in the background"
            }
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("OpenClaw Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (isRunning && startTime > 0) {
            builder.setWhen(startTime)
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
        }

        return builder.build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}










