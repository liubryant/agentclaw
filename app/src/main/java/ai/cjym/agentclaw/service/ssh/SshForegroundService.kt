package ai.cjym.agentclaw.service.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.net.NetworkInterface
import ai.cjym.agentclaw.MainActivity

class SshForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "openclaw_ssh"
        const val NOTIFICATION_ID = 5
        const val EXTRA_PORT = "port"
        var isRunning = false
            private set
        var currentPort = 8022
            private set

        fun start(context: Context, port: Int = 8022) {
            val intent = Intent(context, SshForegroundService::class.java).apply {
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SshForegroundService::class.java)
            context.stopService(intent)
        }

        fun getDeviceIps(): List<String> {
            val ips = mutableListOf<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
                for (iface in interfaces) {
                    if (iface.isLoopback || !iface.isUp) continue
                    for (addr in iface.inetAddresses) {
                        if (addr.isLoopbackAddress) continue
                        val hostAddr = addr.hostAddress ?: continue
                        if (hostAddr.contains("%")) continue
                        ips.add(hostAddr)
                    }
                }
            } catch (_: Exception) {}
            return ips
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, 8022) ?: 8022
        currentPort = port
        startForeground(NOTIFICATION_ID, buildNotification("SSH not available"))
        // proot removed — sshd cannot run without the Linux container
        isRunning = false
        updateNotification("SSH not available (Linux VM removed)")
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AgentClaw SSH",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SSH server status"
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

        builder.setContentTitle("AgentClaw SSH")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(false)

        return builder.build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
