package ai.inmo.core_common.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNetworkMonitorTest {

    @Test
    fun evaluateConnectionState_returnsTrue_whenWifiInternetAndValidated() {
        assertTrue(
            WifiNetworkMonitor.evaluateConnectionState(
                hasWifiTransport = true,
                hasInternetCapability = true,
                hasValidatedCapability = true
            )
        )
    }

    @Test
    fun evaluateConnectionState_returnsFalse_whenTransportIsNotWifi() {
        assertFalse(
            WifiNetworkMonitor.evaluateConnectionState(
                hasWifiTransport = false,
                hasInternetCapability = true,
                hasValidatedCapability = true
            )
        )
    }

    @Test
    fun evaluateConnectionState_returnsFalse_whenInternetCapabilityIsMissing() {
        assertFalse(
            WifiNetworkMonitor.evaluateConnectionState(
                hasWifiTransport = true,
                hasInternetCapability = false,
                hasValidatedCapability = true
            )
        )
    }

    @Test
    fun evaluateConnectionState_returnsTrue_whenValidatedCapabilityIsMissing() {
        assertTrue(
            WifiNetworkMonitor.evaluateConnectionState(
                hasWifiTransport = true,
                hasInternetCapability = true,
                hasValidatedCapability = false
            )
        )
    }
}
