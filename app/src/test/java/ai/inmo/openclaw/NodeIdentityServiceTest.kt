package ai.inmo.openclaw

import ai.inmo.openclaw.data.repository.NodeIdentityService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class NodeIdentityServiceTest {

    @Test
    fun usesExpectedAlgorithmNamesForSystemAndBcProviders() {
        assertEquals("Ed25519", NodeIdentityService.systemAlgorithmForTests())
        assertEquals("ED25519", NodeIdentityService.bouncyCastleAlgorithmForTests())
        assertEquals(listOf("SYSTEM", "BOUNCY_CASTLE"), NodeIdentityService.cryptoBackendsForTests())
    }

    @Test
    fun directBcProviderInstanceSupportsEd25519Signing() {
        val provider = BouncyCastleProvider()
        val generator = KeyPairGenerator.getInstance(NodeIdentityService.bouncyCastleAlgorithmForTests(), provider)
        val pair = generator.generateKeyPair()
        val payload = "node-auth-payload".toByteArray()

        val signer = Signature.getInstance(NodeIdentityService.bouncyCastleAlgorithmForTests(), provider)
        signer.initSign(pair.private)
        signer.update(payload)
        val signature = signer.sign()

        val verifier = Signature.getInstance(NodeIdentityService.bouncyCastleAlgorithmForTests(), provider)
        verifier.initVerify(pair.public)
        verifier.update(payload)

        assertTrue(verifier.verify(signature))
    }

    @Test
    fun bcGeneratedPrivateKeySignsWithBcSignature() {
        val provider = BouncyCastleProvider()
        val generator = KeyPairGenerator.getInstance(NodeIdentityService.bouncyCastleAlgorithmForTests(), provider)
        val pair = generator.generateKeyPair()
        val payload = "provider-consistent-signing".toByteArray()
        val signer = Signature.getInstance(NodeIdentityService.bouncyCastleAlgorithmForTests(), provider)

        signer.initSign(pair.private)
        signer.update(payload)
        val signature = signer.sign()

        val verifier = Signature.getInstance(NodeIdentityService.bouncyCastleAlgorithmForTests(), provider)
        verifier.initVerify(pair.public)
        verifier.update(payload)

        assertTrue(verifier.verify(signature))
    }

    @Test
    fun rawPublicKeyEncodingMatchesGatewayFormat() {
        val provider = BouncyCastleProvider()
        val generator = KeyPairGenerator.getInstance(NodeIdentityService.bouncyCastleAlgorithmForTests(), provider)
        val pair = generator.generateKeyPair()

        val rawPublicKey = NodeIdentityService.extractRawPublicKeyForTests(pair.public.encoded)
        val expectedTail = pair.public.encoded.copyOfRange(pair.public.encoded.size - 32, pair.public.encoded.size)
        val encoded = NodeIdentityService.toBase64UrlForTests(rawPublicKey)

        assertEquals(32, rawPublicKey.size)
        assertArrayEquals(expectedTail, rawPublicKey)
        assertFalse(encoded.contains("="))
    }

    @Test
    fun authPayloadUsesGatewayV3FormatAndNormalizedMetadata() {
        val payload = NodeIdentityService.buildAuthPayloadForTests(
            deviceId = "dev-1",
            clientId = "openclaw-android",
            clientMode = "node",
            role = "node",
            scopes = emptyList(),
            signedAtMs = 1_700_000_000_000,
            token = "tok-123",
            nonce = "nonce-abc",
            platform = "Android",
            deviceFamily = "Pixel Phone"
        )

        assertEquals(
            "v3|dev-1|openclaw-android|node|node||1700000000000|tok-123|nonce-abc|android|pixel phone",
            payload
        )
    }

    @Test
    fun metadataNormalizationLowercasesAsciiAndTrimsWhitespace() {
        assertEquals("android", NodeIdentityService.normalizeMetadataFieldForTests(" Android "))
        assertEquals("pixel 8 pro", NodeIdentityService.normalizeMetadataFieldForTests("  Pixel 8 Pro  "))
        assertEquals("", NodeIdentityService.normalizeMetadataFieldForTests("  "))
    }
}
