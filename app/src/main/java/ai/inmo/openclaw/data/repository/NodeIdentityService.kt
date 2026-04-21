package ai.inmo.openclaw.data.repository

import android.content.Context
import ai.inmo.openclaw.data.local.prefs.MmkvStore
import com.tencent.mmkv.MMKV
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import android.util.Log
import java.util.Base64

class NodeIdentityService(context: Context) {
    private val prefs: MMKV = MmkvStore.nodeIdentity(context)

    private lateinit var privateKey: PrivateKey
    private lateinit var publicKey: PublicKey
    private val bcProvider: Provider by lazy { BouncyCastleProvider() }
    private var activeBackend: CryptoBackend? = null

    lateinit var deviceId: String
        private set

    lateinit var publicKeyBase64Url: String
        private set

    @Synchronized
    fun ensureInitialized() {
        if (::privateKey.isInitialized && ::publicKey.isInitialized) return

        val privateEncoded = prefs.decodeString(KEY_PRIVATE, null)
        val publicEncoded = prefs.decodeString(KEY_PUBLIC, null)
        val storedDeviceId = prefs.decodeString(KEY_DEVICE_ID, null)

        if (privateEncoded != null && publicEncoded != null && storedDeviceId != null) {
            val privateBytes = Base64.getDecoder().decode(privateEncoded)
            val publicBytes = Base64.getDecoder().decode(publicEncoded)
            if (loadStoredIdentity(privateBytes, publicBytes, storedDeviceId)) {
                Log.i(TAG, "Loaded stored identity: deviceId=$storedDeviceId, backend=$activeBackend")
                return
            }
            Log.w(TAG, "Stored identity failed to load, regenerating")
            clearStoredIdentity()
        } else {
            Log.i(TAG, "No stored identity found, generating new keypair")
        }

        generateAndStore()
        Log.i(TAG, "Generated new identity: deviceId=$deviceId, backend=$activeBackend")
    }

    fun buildAuthPayload(
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String?,
        nonce: String,
        platform: String?,
        deviceFamily: String?
    ): String {
        ensureInitialized()
        return buildAuthPayloadForTests(
            deviceId = deviceId,
            clientId = clientId,
            clientMode = clientMode,
            role = role,
            scopes = scopes,
            signedAtMs = signedAtMs,
            token = token,
            nonce = nonce,
            platform = platform,
            deviceFamily = deviceFamily
        )
    }

    fun signPayload(payload: String): String {
        ensureInitialized()
        val signer = createSignature(requireNotNull(activeBackend))
        signer.initSign(privateKey)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        return toBase64Url(signer.sign())
    }

    private fun generateAndStore() {
        val generated = generateIdentity()
        val genPrivate = generated.keyPair.private
        val genPublic = generated.keyPair.public
        val rawPublic = extractRawPublicKey(genPublic.encoded)
        val genDeviceId = sha256Hex(rawPublic)
        val genPublicKeyBase64Url = toBase64Url(rawPublic)

        privateKey = genPrivate
        publicKey = genPublic
        activeBackend = generated.backend
        deviceId = genDeviceId
        publicKeyBase64Url = genPublicKeyBase64Url

        prefs.encode(KEY_PRIVATE, Base64.getEncoder().encodeToString(genPrivate.encoded))
        prefs.encode(KEY_PUBLIC, Base64.getEncoder().encodeToString(genPublic.encoded))
        prefs.encode(KEY_DEVICE_ID, genDeviceId)
    }

    private fun loadStoredIdentity(
        privateBytes: ByteArray,
        publicBytes: ByteArray,
        storedDeviceId: String
    ): Boolean {
        val pkcs8 = PKCS8EncodedKeySpec(privateBytes)
        val x509 = X509EncodedKeySpec(publicBytes)
        for (backend in CryptoBackend.entries) {
            val loaded = runCatching {
                val keyFactory = createKeyFactory(backend)
                val loadedPrivateKey = keyFactory.generatePrivate(pkcs8)
                val loadedPublicKey = keyFactory.generatePublic(x509)
                val signer = createSignature(backend)
                signer.initSign(loadedPrivateKey)
                val testBytes = "selftest".toByteArray(Charsets.UTF_8)
                signer.update(testBytes)
                val testSig = signer.sign()
                val verifier = createSignature(backend)
                verifier.initVerify(loadedPublicKey)
                verifier.update(testBytes)
                require(verifier.verify(testSig)) { "keypair self-test failed: private/public key mismatch" }
                LoadedIdentity(
                    backend = backend,
                    privateKey = loadedPrivateKey,
                    publicKey = loadedPublicKey,
                    deviceId = storedDeviceId,
                    publicKeyBase64Url = toBase64Url(extractRawPublicKey(loadedPublicKey.encoded))
                )
            }.getOrNull() ?: continue

            activeBackend = loaded.backend
            privateKey = loaded.privateKey
            publicKey = loaded.publicKey
            deviceId = loaded.deviceId
            publicKeyBase64Url = loaded.publicKeyBase64Url
            return true
        }
        return false
    }

    private fun generateIdentity(): GeneratedIdentity {
        for (backend in CryptoBackend.entries) {
            val generated = runCatching {
                val generator = createKeyPairGenerator(backend)
                val pair = generator.generateKeyPair()
                val signer = createSignature(backend)
                signer.initSign(pair.private)
                GeneratedIdentity(backend, pair)
            }.getOrNull()
            if (generated != null) {
                return generated
            }
        }
        error("No Ed25519 backend available")
    }

    private fun createKeyFactory(backend: CryptoBackend): KeyFactory =
        when (backend) {
            CryptoBackend.SYSTEM -> KeyFactory.getInstance(SYSTEM_ALGORITHM)
            CryptoBackend.BOUNCY_CASTLE -> KeyFactory.getInstance(BC_ALGORITHM, bcProvider)
        }

    private fun createKeyPairGenerator(backend: CryptoBackend): KeyPairGenerator =
        when (backend) {
            CryptoBackend.SYSTEM -> KeyPairGenerator.getInstance(SYSTEM_ALGORITHM)
            CryptoBackend.BOUNCY_CASTLE -> KeyPairGenerator.getInstance(BC_ALGORITHM, bcProvider)
        }

    private fun createSignature(backend: CryptoBackend): Signature =
        when (backend) {
            CryptoBackend.SYSTEM -> Signature.getInstance(SYSTEM_ALGORITHM)
            CryptoBackend.BOUNCY_CASTLE -> Signature.getInstance(BC_ALGORITHM, bcProvider)
        }

    private fun clearStoredIdentity() {
        prefs.removeValueForKey(KEY_PRIVATE)
        prefs.removeValueForKey(KEY_PUBLIC)
        prefs.removeValueForKey(KEY_DEVICE_ID)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "NodeIdentity"
        private const val KEY_PRIVATE = "node_ed25519_private"
        private const val KEY_PUBLIC = "node_ed25519_public"
        private const val KEY_DEVICE_ID = "node_device_id"
        private const val SYSTEM_ALGORITHM = "Ed25519"
        private const val BC_ALGORITHM = "ED25519"
        private val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
        )

        internal fun systemAlgorithmForTests(): String = SYSTEM_ALGORITHM

        internal fun bouncyCastleAlgorithmForTests(): String = BC_ALGORITHM

        internal fun cryptoBackendsForTests(): List<String> = CryptoBackend.entries.map { it.name }

        internal fun extractRawPublicKeyForTests(encoded: ByteArray): ByteArray = extractRawPublicKey(encoded)

        internal fun toBase64UrlForTests(bytes: ByteArray): String = toBase64Url(bytes)

        internal fun normalizeMetadataFieldForTests(value: String?): String = normalizeMetadataField(value)

        internal fun buildAuthPayloadForTests(
            deviceId: String,
            clientId: String,
            clientMode: String,
            role: String,
            scopes: List<String>,
            signedAtMs: Long,
            token: String?,
            nonce: String,
            platform: String?,
            deviceFamily: String?
        ): String {
            return listOf(
                "v3",
                deviceId,
                clientId,
                clientMode,
                role,
                scopes.joinToString(","),
                signedAtMs.toString(),
                token.orEmpty(),
                nonce,
                normalizeMetadataField(platform),
                normalizeMetadataField(deviceFamily)
            ).joinToString("|")
        }

        private fun extractRawPublicKey(encoded: ByteArray): ByteArray {
            return if (encoded.size >= 32 && encoded.copyOfRange(0, ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)) {
                encoded.copyOfRange(ED25519_SPKI_PREFIX.size, ED25519_SPKI_PREFIX.size + 32)
            } else if (encoded.size >= 32) {
                encoded.copyOfRange(encoded.size - 32, encoded.size)
            } else {
                encoded
            }
        }

        private fun toBase64Url(bytes: ByteArray): String {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun normalizeMetadataField(value: String?): String {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                return ""
            }
            val out = StringBuilder(trimmed.length)
            trimmed.forEach { ch ->
                if (ch in 'A'..'Z') {
                    out.append((ch.code + 32).toChar())
                } else {
                    out.append(ch)
                }
            }
            return out.toString()
        }
    }

    private enum class CryptoBackend {
        SYSTEM,
        BOUNCY_CASTLE
    }

    private data class LoadedIdentity(
        val backend: CryptoBackend,
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val deviceId: String,
        val publicKeyBase64Url: String
    )

    private data class GeneratedIdentity(
        val backend: CryptoBackend,
        val keyPair: KeyPair
    )
}
