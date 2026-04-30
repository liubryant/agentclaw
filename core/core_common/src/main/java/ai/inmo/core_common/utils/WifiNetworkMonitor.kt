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

    private data class NetworkState(
        val hasSupportedTransport: Boolean,
        val hasInternetCapability: Boolean,
        val hasValidatedCapability: Boolean
    ) {
        val isConnected: Boolean
            get() = evaluateConnectionState(
                hasSupportedTransport = hasSupportedTransport,
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

    private val trackedNetworks = linkedMapOf<Network, NetworkState>()

    fun isWifiConnected(context: Context): Boolean {
        val appContext = context.applicationContext
        val manager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        @Suppress("DEPRECATION")
        return manager.allNetworks.any { network ->
            createNetworkState(manager.getNetworkCapabilities(network))?.isConnected == true
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
            NetworkRequest.Builder().build(),
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

        trackedNetworks.clear()
        networkCallback = null
        connectivityManager = null
        listener = null
        lastState = null
    }

    @Synchronized
    private fun dispatchCurrentState() {
        val currentListener = listener ?: return
        val isConnected = trackedNetworks.values.any { it.isConnected }
        Logger.d(
            TAG,
            "dispatchCurrentState lastState=$lastState currentState=$isConnected trackedNetworks=${trackedNetworks.size} details=${trackedNetworks.values.joinToString { "supported=${it.hasSupportedTransport}, internet=${it.hasInternetCapability}, validated=${it.hasValidatedCapability}" }}"
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
        return createNetworkState(capabilities)?.isConnected == true
    }

    internal fun evaluateConnectionState(
        hasSupportedTransport: Boolean,
        hasInternetCapability: Boolean,
        hasValidatedCapability: Boolean
    ): Boolean {
        return hasSupportedTransport && hasInternetCapability
    }

    private fun refreshTrackedNetworks(manager: ConnectivityManager) {
        trackedNetworks.clear()
        @Suppress("DEPRECATION")
        manager.allNetworks.forEach { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            val state = createNetworkState(capabilities)
            if (state != null) {
                trackedNetworks[network] = state
                Logger.d(
                    TAG,
                    "refreshTrackedNetworks network=$network ${describeCapabilities(capabilities)}"
                )
            }
        }
    }

    @Synchronized
    private fun updateTrackedNetwork(network: Network, capabilities: NetworkCapabilities?) {
        val state = createNetworkState(capabilities)
        if (state == null) {
            trackedNetworks.remove(network)
            Logger.d(TAG, "updateTrackedNetwork removed network=$network: not a supported internet network")
        } else {
            trackedNetworks[network] = state
            Logger.d(
                TAG,
                "updateTrackedNetwork stored network=$network supported=${state.hasSupportedTransport} internet=${state.hasInternetCapability} validated=${state.hasValidatedCapability}"
            )
        }
        dispatchCurrentState()
    }

    @Synchronized
    private fun removeTrackedNetwork(network: Network) {
        val removed = trackedNetworks.remove(network)
        Logger.d(TAG, "removeTrackedNetwork network=$network removed=${removed != null}")
        dispatchCurrentState()
    }

    private fun createNetworkState(capabilities: NetworkCapabilities?): NetworkState? {
        if (capabilities == null) {
            return null
        }

        val hasSupportedTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        if (!hasSupportedTransport || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return null
        }

        return NetworkState(
            hasSupportedTransport = hasSupportedTransport,
            hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasValidatedCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )
    }

    private fun describeCapabilities(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) {
            return "capabilities=null"
        }

        return "wifi=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
            "cellular=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} " +
            "ethernet=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)} " +
            "bluetooth=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)} " +
            "vpn=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} " +
            "internet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }
}
