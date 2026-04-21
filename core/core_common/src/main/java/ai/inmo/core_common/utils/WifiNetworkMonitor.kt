package ai.inmo.core_common.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

object WifiNetworkMonitor {

    private const val TAG = "WifiNetworkMonitor"

    interface Listener {
        fun onWifiConnectionChanged(isConnected: Boolean)
    }

    private data class WifiNetworkState(
        val hasInternetCapability: Boolean,
        val hasValidatedCapability: Boolean
    ) {
        val isConnected: Boolean
            get() = evaluateConnectionState(
                hasWifiTransport = true,
                hasInternetCapability = hasInternetCapability,
                hasValidatedCapability = hasValidatedCapability
            )
    }

    @Volatile
    private var connectivityManager: ConnectivityManager? = null

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastState: Boolean? = null

    private val wifiNetworks = linkedMapOf<Network, WifiNetworkState>()

    fun isWifiConnected(context: Context): Boolean {
        val appContext = context.applicationContext
        val manager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        @Suppress("DEPRECATION")
        return manager.allNetworks.any { network ->
            createWifiNetworkState(manager.getNetworkCapabilities(network))?.isConnected == true
        }
    }

    @Synchronized
    fun register(context: Context, listener: Listener) {
        unregister()

        val appContext = context.applicationContext
        val manager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: run {
                    this.listener = listener
                    lastState = false
                    Logger.w(TAG, "register failed: ConnectivityManager unavailable")
                    listener.onWifiConnectionChanged(false)
                    return
                }

        this.listener = listener
        connectivityManager = manager
        Logger.d(TAG, "register start")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager?.getNetworkCapabilities(network)
                Logger.d(TAG, "onAvailable network=$network ${describeCapabilities(capabilities)}")
                updateTrackedNetwork(network, capabilities)
            }

            override fun onLost(network: Network) {
                Logger.d(TAG, "onLost network=$network")
                removeTrackedNetwork(network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                Logger.d(
                    TAG,
                    "onCapabilitiesChanged network=$network ${describeCapabilities(networkCapabilities)}"
                )
                updateTrackedNetwork(network, networkCapabilities)
            }
        }

        networkCallback = callback
        lastState = null
        manager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            callback
        )
        refreshTrackedNetworks(manager)
        dispatchCurrentState()
    }

    @Synchronized
    fun unregister() {
        val manager = connectivityManager
        val callback = networkCallback

        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback)
                Logger.d(TAG, "unregister success")
            } catch (e: Exception) {
                Logger.w(TAG, "unregister callback failed: ${e.message}")
            }
        }

        wifiNetworks.clear()
        networkCallback = null
        connectivityManager = null
        listener = null
        lastState = null
    }

    @Synchronized
    private fun dispatchCurrentState() {
        val currentListener = listener ?: return
        val isConnected = wifiNetworks.values.any { it.isConnected }
        Logger.d(
            TAG,
            "dispatchCurrentState lastState=$lastState currentState=$isConnected trackedNetworks=${wifiNetworks.size} details=${wifiNetworks.values.joinToString { "internet=${it.hasInternetCapability}, validated=${it.hasValidatedCapability}" }}"
        )

        if (lastState == isConnected) {
            Logger.d(TAG, "dispatchCurrentState skipped: unchanged")
            return
        }

        lastState = isConnected
        Logger.d(TAG, "dispatchCurrentState notify listener state=$isConnected")
        currentListener.onWifiConnectionChanged(isConnected)
    }

    internal fun isValidatedWifi(capabilities: NetworkCapabilities?): Boolean {
        return createWifiNetworkState(capabilities)?.isConnected == true
    }

    internal fun evaluateConnectionState(
        hasWifiTransport: Boolean,
        hasInternetCapability: Boolean,
        hasValidatedCapability: Boolean
    ): Boolean {
        return hasWifiTransport && hasInternetCapability
    }

    private fun refreshTrackedNetworks(manager: ConnectivityManager) {
        wifiNetworks.clear()
        @Suppress("DEPRECATION")
        manager.allNetworks.forEach { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            val state = createWifiNetworkState(capabilities)
            if (state != null) {
                wifiNetworks[network] = state
                Logger.d(
                    TAG,
                    "refreshTrackedNetworks network=$network ${describeCapabilities(capabilities)}"
                )
            }
        }
    }

    @Synchronized
    private fun updateTrackedNetwork(network: Network, capabilities: NetworkCapabilities?) {
        val state = createWifiNetworkState(capabilities)
        if (state == null) {
            wifiNetworks.remove(network)
            Logger.d(TAG, "updateTrackedNetwork removed network=$network: not a wifi network")
        } else {
            wifiNetworks[network] = state
            Logger.d(
                TAG,
                "updateTrackedNetwork stored network=$network internet=${state.hasInternetCapability} validated=${state.hasValidatedCapability}"
            )
        }
        dispatchCurrentState()
    }

    @Synchronized
    private fun removeTrackedNetwork(network: Network) {
        val removed = wifiNetworks.remove(network)
        Logger.d(TAG, "removeTrackedNetwork network=$network removed=${removed != null}")
        dispatchCurrentState()
    }

    private fun createWifiNetworkState(capabilities: NetworkCapabilities?): WifiNetworkState? {
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }

        return WifiNetworkState(
            hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasValidatedCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )
    }

    private fun describeCapabilities(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) {
            return "capabilities=null"
        }

        return "wifi=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
            "internet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }
}
