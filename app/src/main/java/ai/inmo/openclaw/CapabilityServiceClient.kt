package ai.inmo.openclaw

import ai.inmo.openclaw.capability.ICapabilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class CapabilityServiceClient(private val context: Context) {
    private var service: ICapabilityService? = null
    private var bound = false
    private var retryCount = 0
    private val handler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ICapabilityService.Stub.asInterface(binder)
            bound = true
            retryCount = 0
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            scheduleReconnect()
        }
    }

    fun bind() {
        val startIntent = Intent(ACTION_BIND).apply {
            component = ComponentName(SERVICE_PACKAGE, "$SERVICE_PACKAGE.service.CapabilityService")
        }
        runCatching { context.startService(startIntent) }

        val bindIntent = Intent(ACTION_BIND).apply {
            setPackage(SERVICE_PACKAGE)
        }
        runCatching { context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { scheduleReconnect() }
    }

    fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
        service = null
    }

    fun invoke(method: String, paramsJson: String): String? {
        return try {
            service?.invoke(method, paramsJson)
        } catch (_: DeadObjectException) {
            service = null
            bound = false
            scheduleReconnect()
            null
        } catch (_: Exception) {
            null
        }
    }

    fun isConnected(): Boolean {
        return try {
            service?.isAlive == true
        } catch (_: Exception) {
            false
        }
    }

    private fun scheduleReconnect() {
        if (retryCount >= MAX_RETRIES) return
        val delayMs = (1000L * (1 shl retryCount)).coerceAtMost(30_000L)
        retryCount += 1
        handler.postDelayed({ bind() }, delayMs)
    }

    companion object {
        private const val ACTION_BIND = "ai.inmo.openclaw.capability.BIND"
        private const val SERVICE_PACKAGE = "ai.inmo.openclaw.capability"
        private const val MAX_RETRIES = 5
    }
}
