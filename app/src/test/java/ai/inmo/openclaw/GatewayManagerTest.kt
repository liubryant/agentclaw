package ai.inmo.openclaw

import ai.inmo.openclaw.data.repository.GatewayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayManagerTest {

    @Test
    fun extractTokenizedDashboardUrl_returnsTokenUrlOnly() {
        val line = "dashboard ready at http://127.0.0.1:18789/#token=abc123def456"

        val result = GatewayManager.extractTokenizedDashboardUrl(line)

        assertEquals("http://127.0.0.1:18789/#token=abc123def456", result)
    }

    @Test
    fun extractTokenizedDashboardUrl_ignoresBareUrl() {
        val line = "dashboard ready at http://127.0.0.1:18789/"

        val result = GatewayManager.extractTokenizedDashboardUrl(line)

        assertNull(result)
    }

    @Test
    fun mergeDashboardUrl_keepsExistingTokenUrlWhenLogHasBareUrl() {
        val current = "http://127.0.0.1:18789/#token=abc123"
        val line = "dashboard ready at http://127.0.0.1:18789/"

        val result = GatewayManager.mergeDashboardUrl(current, line)

        assertEquals(current, result)
    }

    @Test
    fun mergeDashboardUrl_prefersTokenizedUrlFromLog() {
        val current = "http://127.0.0.1:18789/#token=oldtoken"
        val line = "dashboard ready at http://localhost:18789/#token=abcdef123456"

        val result = GatewayManager.mergeDashboardUrl(current, line)

        assertEquals("http://localhost:18789/#token=abcdef123456", result)
    }
}
