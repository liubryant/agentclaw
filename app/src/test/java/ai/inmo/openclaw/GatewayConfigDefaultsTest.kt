package ai.inmo.openclaw

import ai.inmo.openclaw.data.repository.GatewayConfigDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConfigDefaultsTest {

    @Test
    fun mergeConfig_generatesGatewayDefaultsWithoutDroppingProviders() {
        val existing = mutableMapOf<String, Any?>(
            "models" to mutableMapOf(
                "providers" to mutableMapOf(
                    "zai" to mutableMapOf(
                        "apiKey" to "secret",
                        "models" to listOf(mapOf("id" to "glm-4.7"))
                    )
                )
            )
        )

        val result = GatewayConfigDefaults.mergeConfig(existing)

        val gateway = result.config["gateway"] as Map<*, *>
        val auth = gateway["auth"] as Map<*, *>
        val nodes = gateway["nodes"] as Map<*, *>
        val agents = result.config["agents"] as Map<*, *>
        val defaults = agents["defaults"] as Map<*, *>
        val tools = result.config["tools"] as Map<*, *>
        val providers = ((result.config["models"] as Map<*, *>)["providers"] as Map<*, *>)

        assertEquals("local", gateway["mode"])
        assertEquals("token", auth["mode"])
        assertNotNull(auth["token"])
        assertTrue((nodes["allowCommands"] as List<*>).contains("system.autoai.start"))
        assertEquals(emptyList<String>(), nodes["denyCommands"])
        assertEquals(1200, defaults["timeoutSeconds"])
        assertEquals("full", tools["profile"])
        assertTrue(result.dashboardUrl?.contains("#token=") == true)
        assertTrue(providers.containsKey("zai"))
    }

    @Test
    fun mergeConfig_preservesExistingToken() {
        val existing = mutableMapOf<String, Any?>(
            "gateway" to mutableMapOf(
                "auth" to mutableMapOf(
                    "token" to "abc123",
                    "mode" to "token"
                )
            )
        )

        val result = GatewayConfigDefaults.mergeConfig(existing)
        val gateway = result.config["gateway"] as Map<*, *>
        val auth = gateway["auth"] as Map<*, *>

        assertEquals("abc123", auth["token"])
        assertEquals(null, result.token)
        assertEquals("http://127.0.0.1:18789/#token=abc123", result.dashboardUrl)
    }

    @Test
    fun mergeConfig_withoutGenerationKeepsDashboardUnsetWhenTokenMissing() {
        val result = GatewayConfigDefaults.mergeConfig(mutableMapOf(), generateToken = false)

        val gateway = result.config["gateway"] as Map<*, *>
        val auth = gateway["auth"] as Map<*, *>

        assertFalse(auth.containsKey("token"))
        assertEquals(null, result.token)
        assertEquals(null, result.dashboardUrl)
    }

    @Test
    fun mergeConfig_preservesExistingTimeoutSeconds() {
        val existing = mutableMapOf<String, Any?>(
            "agents" to mutableMapOf(
                "defaults" to mutableMapOf(
                    "timeoutSeconds" to 300
                )
            )
        )

        val result = GatewayConfigDefaults.mergeConfig(existing)
        val agents = result.config["agents"] as Map<*, *>
        val defaults = agents["defaults"] as Map<*, *>

        assertEquals(300, defaults["timeoutSeconds"])
    }
}
