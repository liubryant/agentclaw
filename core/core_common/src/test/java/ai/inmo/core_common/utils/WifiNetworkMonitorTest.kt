package ai.inmo.core_common.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNetworkMonitorTest {

    @Test
    fun evaluateConnectionState_returnsTrue_whenSupportedTransportInternetAndValidated() {
        assertTrue(
            WifiNetworkMonitor.evaluateConnectionState(
                hasSupportedTransport = true,
                hasInternetCapability = true,
                hasValidatedCapability = true
            )
        )
    }

    @Test
    fun evaluateConnectionState_returnsFalse_whenTransportIsNotSupported() {
        assertFalse(
            WifiNetworkMonitor.evaluateConnectionState(
                hasSupportedTransport = false,
                hasInternetCapability = true,
                hasValidatedCapability = true
            )
        )
    }

    @Test
    fun evaluateConnectionState_returnsFalse_whenInternetCapabilityIsMissing() {
        assertFalse(
            WifiNetworkMonitor.evaluateConnectionState(
                hasSupportedTransport = true,
                hasInternetCapability = false,
                hasValidatedCapability = true
            )
        )
    }

    @Test
    fun evaluateConnectionState_returnsTrue_whenValidatedCapabilityIsMissing() {
        assertTrue(
            WifiNetworkMonitor.evaluateConnectionState(
                hasSupportedTransport = true,
                hasInternetCapability = true,
                hasValidatedCapability = false
            )
        )
    }
}
