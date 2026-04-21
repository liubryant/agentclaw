package ai.inmo.openclaw.data.repository

import ai.inmo.openclaw.capability.CameraCapabilityHandler
import ai.inmo.openclaw.capability.FlashCapabilityHandler
import ai.inmo.openclaw.capability.FsCapabilityHandler
import ai.inmo.openclaw.capability.LocationCapabilityHandler
import ai.inmo.openclaw.capability.NodeCapabilityHandler
import ai.inmo.openclaw.capability.ScreenCapabilityHandler
import ai.inmo.openclaw.capability.SensorCapabilityHandler
import ai.inmo.openclaw.capability.SerialCapabilityHandler
import ai.inmo.openclaw.capability.SystemCapabilityHandler
import ai.inmo.openclaw.capability.VibrationCapabilityHandler
import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.local.prefs.PreferencesManager
import ai.inmo.openclaw.data.remote.websocket.NodeWsManager
import ai.inmo.openclaw.domain.model.NodeFrame
import ai.inmo.openclaw.domain.model.NodeState
import ai.inmo.openclaw.domain.model.NodeStatus
import ai.inmo.openclaw.proot.BootstrapManager
import ai.inmo.openclaw.proot.ProcessManager
import ai.inmo.openclaw.service.node.NodeForegroundService
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NodeManager(
    context: Context,
    private val preferencesManager: PreferencesManager
) {
    data class CapabilityDescriptor(
        val name: String,
        val summary: String,
        val enabled: Boolean
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsManager = NodeWsManager()
    private val identityService = NodeIdentityService(appContext)
    private val processManager = ProcessManager(appContext.filesDir.absolutePath, appContext.applicationInfo.nativeLibraryDir)
    private val bootstrapManager = BootstrapManager(appContext, appContext.filesDir.absolutePath, appContext.applicationInfo.nativeLibraryDir)
    private val capabilityHandlers = linkedMapOf<String, NodeCapabilityHandler>()
    private var identityReady = false
    private val connectionMutex = Mutex()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<NodeState> = _state.asStateFlow()

    val capabilityCatalog: List<CapabilityDescriptor>
        get() = listOf(
            CapabilityDescriptor("camera", "Camera device discovery is wired; capture remains interactive.", capabilityHandlers.containsKey("camera")),
            CapabilityDescriptor("fs", "App/shared/rootfs file access is available.", capabilityHandlers.containsKey("fs")),
            CapabilityDescriptor("location", "Current device location can be resolved.", capabilityHandlers.containsKey("location")),
            CapabilityDescriptor("system", "AIDL-backed system controls are available for app launch, media, wifi, bluetooth, screenshot, navigation, power, language, DND, and AutoAI.", capabilityHandlers.containsKey("system")),
            CapabilityDescriptor("screen", "Interactive screen recording is available through the MediaProjection consent flow.", capabilityHandlers.containsKey("screen")),
            CapabilityDescriptor("sensor", "Single-shot accelerometer, gyroscope, magnetometer, and barometer reads are available.", capabilityHandlers.containsKey("sensor")),
            CapabilityDescriptor("vibration", "Haptic vibration commands are available through the system vibrator.", capabilityHandlers.containsKey("haptic")),
            CapabilityDescriptor("serial", "USB serial and Bluetooth SPP connections are available when hardware and permissions are present.", capabilityHandlers.containsKey("serial")),
            CapabilityDescriptor("flash", "Rear camera torch control is available.", capabilityHandlers.containsKey("flash"))
        )

    init {
        registerCapabilities()
        scope.launch {
            identityReady = runCatching {
                identityService.ensureInitialized()
                true
            }.getOrDefault(false)
            _state.value = buildState()
        }
        scope.launch {
            wsManager.frames.collect { frame ->
                scope.launch { handleFrame(frame) }
            }
        }
    }

    fun refresh() {
        scope.launch {
            identityReady = runCatching {
                identityService.ensureInitialized()
                true
            }.getOrDefault(identityReady)
            _state.value = buildState(logs = _state.value.logs)
        }
    }

    fun enable() {
        scope.launch { ensurePaired() }
    }

    fun connectRemote(host: String, port: Int, token: String?) {
        scope.launch {
            ensurePairedInternal(
                host = host,
                port = port,
                token = token,
                forceReconnect = true
            )
        }
    }

    fun reconnect() {
        if (!preferencesManager.nodeEnabled) return
        scope.launch {
            ensurePairedInternal(
                host = preferencesManager.nodeGatewayHost ?: AppConstants.GATEWAY_HOST,
                port = preferencesManager.nodeGatewayPort ?: AppConstants.GATEWAY_PORT,
                token = preferencesManager.nodeGatewayToken,
                forceReconnect = true
            )
        }
    }

    fun disable() {
        preferencesManager.nodeEnabled = false
        wsManager.disconnect()
        NodeForegroundService.stop(appContext)
        _state.value = buildState(
            status = NodeStatus.DISABLED,
            logs = _state.value.logs + "[NODE] Disabled"
        )
    }

    suspend fun ensurePaired() {
        ensurePairedInternal(
            host = preferencesManager.nodeGatewayHost ?: AppConstants.GATEWAY_HOST,
            port = preferencesManager.nodeGatewayPort ?: AppConstants.GATEWAY_PORT,
            token = preferencesManager.nodeGatewayToken,
            forceReconnect = false
        )
    }

    private suspend fun ensurePairedInternal(
        host: String,
        port: Int,
        token: String?,
        forceReconnect: Boolean
    ) {
        connectionMutex.withLock {
            if (!preferencesManager.nodeEnabled) {
                preferencesManager.nodeEnabled = true
            }
            if (!token.isNullOrBlank()) {
                preferencesManager.nodeGatewayToken = token
            }
            preferencesManager.nodeGatewayHost = host
            preferencesManager.nodeGatewayPort = port
            NodeForegroundService.start(appContext)

            val currentState = _state.value
            if (!forceReconnect && currentState.status == NodeStatus.PAIRED && wsManager.hasActiveConnection && !wsManager.isStale) {
                return
            }

            val shouldForceReset = forceReconnect ||
                wsManager.isStale ||
                (currentState.status == NodeStatus.PAIRED && !wsManager.hasActiveConnection) ||
                (wsManager.hasActiveConnection && currentState.status != NodeStatus.PAIRED)

            if (shouldForceReset) {
                updateStatus(
                    status = NodeStatus.CONNECTING,
                    error = null,
                    host = host,
                    port = port,
                    appendLog = "[NODE] Resetting node connection to $host:$port"
                )
                wsManager.disconnect()
                delay(300)
                connectInternal(host, port, token)
                return
            }

            if (wsManager.hasPendingConnection || wsManager.isReconnectPending) {
                if (currentState.status != NodeStatus.PAIRED) {
                    updateStatus(
                        status = NodeStatus.CONNECTING,
                        error = null,
                        host = host,
                        port = port,
                        appendLog = "[NODE] Waiting for in-flight connection"
                    )
                    NodeForegroundService.updateStatus("Node connecting...")
                }
                return
            }

            connectInternal(host, port, token)
        }
    }

    private suspend fun connectInternal(host: String, port: Int, token: String?) {
        runCatching { identityService.ensureInitialized() }
            .onFailure {
                updateStatus(NodeStatus.ERROR, "Identity init failed: ${it.message}")
                return
            }

        if (!preferencesManager.nodeEnabled) {
            preferencesManager.nodeEnabled = true
        }
        if (!token.isNullOrBlank()) {
            preferencesManager.nodeGatewayToken = token
        }
        preferencesManager.nodeGatewayHost = host
        preferencesManager.nodeGatewayPort = port

        updateStatus(
            status = NodeStatus.CONNECTING,
            error = null,
            host = host,
            port = port,
            appendLog = "[NODE] Connecting to $host:$port"
        )
        NodeForegroundService.updateStatus("Node connecting...")
        wsManager.connect(host, port)
    }

    private suspend fun handleFrame(frame: NodeFrame) {
        when {
            frame.isEvent && frame.event == "_disconnected" -> {
                if (preferencesManager.nodeEnabled) {
                    if (wsManager.isReconnectPending) {
                        updateStatus(
                            status = NodeStatus.CONNECTING,
                            error = null,
                            appendLog = "[NODE] Transport disconnected, retrying"
                        )
                        NodeForegroundService.updateStatus("Node reconnecting...")
                    } else {
                        updateStatus(
                            status = NodeStatus.DISCONNECTED,
                            error = null,
                            appendLog = "[NODE] Disconnected"
                        )
                        NodeForegroundService.updateStatus("Node disconnected")
                    }
                }
            }

            frame.isEvent && frame.event == "connect.challenge" -> {
                val nonce = frame.payload?.get("nonce")?.toString()
                if (nonce.isNullOrBlank()) {
                    updateStatus(NodeStatus.ERROR, "Gateway challenge nonce missing", appendLog = "[NODE] connect.challenge missing nonce")
                    return
                }
                updateStatus(NodeStatus.CHALLENGING, appendLog = "[NODE] Challenge received")
                sendConnect(nonce)
            }

            frame.isEvent && frame.event == "node.invoke.request" -> {
                handleInvoke(frame.payload ?: emptyMap())
            }
        }
    }

    private suspend fun sendConnect(nonce: String) {
        val deviceToken = preferencesManager.nodeDeviceToken?.trim().takeUnless { it.isNullOrEmpty() }
        val sharedToken = PreferencesManager.resolveGatewayToken(appContext)?.trim().takeUnless { it.isNullOrEmpty() }
        val authToken = deviceToken ?: sharedToken
        val signedAt = System.currentTimeMillis()
        val scopes = emptyList<String>()
        val clientId = "openclaw-android"
        val clientMode = "node"
        val platform = "android"
        val deviceFamily = "Android"
        val payload = identityService.buildAuthPayload(
            clientId = clientId,
            clientMode = clientMode,
            role = AppConstants.NODE_ROLE,
            scopes = scopes,
            signedAtMs = signedAt,
            token = authToken,
            nonce = nonce,
            platform = platform,
            deviceFamily = deviceFamily
        )
        val signature = identityService.signPayload(payload)
        val commands = capabilityHandlers.values.flatMap { it.commands }.distinct()
        val caps = commands.map { it.substringBefore('.') }.distinct()

        val connectFrame = NodeFrame.request(
            method = "connect",
            params = buildMap {
                put("minProtocol", 3)
                put("maxProtocol", 3)
                put("client", mapOf(
                    "id" to clientId,
                    "displayName" to "INMOClaw Node",
                    "version" to AppConstants.VERSION,
                    "platform" to platform,
                    "deviceFamily" to deviceFamily,
                    "modelIdentifier" to resolveModelIdentifier(),
                    "mode" to clientMode
                ))
                put("role", AppConstants.NODE_ROLE)
                put("scopes", scopes)
                put("caps", caps)
                put("commands", commands)
                put("permissions", emptyMap<String, Any?>())
                if (!authToken.isNullOrBlank()) {
                    put(
                        "auth",
                        if (deviceToken != null) {
                            mapOf("deviceToken" to authToken)
                        } else {
                            mapOf("token" to authToken)
                        }
                    )
                }
                put("device", mapOf(
                    "id" to identityService.deviceId,
                    "publicKey" to identityService.publicKeyBase64Url,
                    "signature" to signature,
                    "nonce" to nonce,
                    "signedAt" to signedAt
                ))
            }
        )

        runCatching { wsManager.sendRequest(connectFrame, timeoutMs = 30_000L) }
            .onSuccess { response ->
                if (response.isOk) {
                    val auth = response.payload?.get("auth") as? Map<*, *>
                    val deviceToken = auth?.get("deviceToken")?.toString()
                    if (!deviceToken.isNullOrBlank()) {
                        preferencesManager.nodeDeviceToken = deviceToken
                    }
                    onConnected()
                } else {
                    val code = response.error?.get("code")?.toString() ?: response.payload?.get("code")?.toString().orEmpty()
                    val message = response.error?.get("message")?.toString() ?: response.payload?.get("message")?.toString() ?: "Connect failed"
                    if (isDeviceAuthFailure(code, message)) {
                        recoverFromDeviceAuthFailure(message, attemptedDeviceToken = deviceToken != null)
                    } else {
                        updateStatus(NodeStatus.ERROR, message, appendLog = "[NODE] Connect rejected: $message")
                    }
                }
            }
            .onFailure {
                val message = it.message ?: "Connect failed"
                if (isDeviceAuthFailure(message = message, attemptedDeviceToken = deviceToken != null)) {
                    recoverFromDeviceAuthFailure(message, attemptedDeviceToken = deviceToken != null)
                } else {
                    updateStatus(
                        NodeStatus.ERROR,
                        message,
                        appendLog = "[NODE] Connect failure: $message"
                    )
                }
            }
    }

    private fun onConnected() {
        updateStatus(
            status = NodeStatus.PAIRED,
            error = null,
            pairingRequestId = null,
            connectedAt = System.currentTimeMillis(),
            appendLog = "[NODE] Paired and connected"
        )
        NodeForegroundService.updateStatus("Node connected")
    }

    private suspend fun requestPairing() {
        updateStatus(NodeStatus.PAIRING, error = null, appendLog = "[NODE] Requesting pairing")
        val commands = capabilityHandlers.values.flatMap { it.commands }.distinct()
        val caps = commands.map { it.substringBefore('.') }.distinct()
        val request = NodeFrame.request(
            "node.pair.request",
            buildMap<String, Any?> {
                put("nodeId", identityService.deviceId)
                put("displayName", "INMOClaw Node")
                put("platform", "android")
                put("version", AppConstants.VERSION)
                put("deviceFamily", "Android")
                put("modelIdentifier", resolveModelIdentifier())
                put("caps", caps)
                put("commands", commands)
            }
        )
        runCatching { wsManager.sendRequest(request, timeoutMs = AppConstants.PAIRING_TIMEOUT_MS) }
            .onSuccess { response ->
                if (response.isError) {
                    val message = response.error?.get("message")?.toString() ?: "Pairing failed"
                    updateStatus(NodeStatus.ERROR, message, appendLog = "[NODE] Pairing failed: $message")
                    return@onSuccess
                }

                val requestPayload = response.payload?.get("request") as? Map<*, *>
                val requestId = requestPayload?.get("requestId")?.toString()
                    ?: response.payload?.get("requestId")?.toString()

                if (requestId.isNullOrBlank()) {
                    updateStatus(
                        NodeStatus.ERROR,
                        "Pairing request ID missing",
                        appendLog = "[NODE] Pairing response missing requestId"
                    )
                    return@onSuccess
                }

                updateStatus(
                    NodeStatus.PAIRING,
                    error = null,
                    pairingRequestId = requestId,
                    appendLog = "[NODE] Pairing request pending: $requestId"
                )

                val isLocalGateway = (preferencesManager.nodeGatewayHost ?: AppConstants.GATEWAY_HOST) in setOf("127.0.0.1", "localhost")
                if (isLocalGateway) {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                processManager.runInProotSync("openclaw nodes approve $requestId", 30)
                            }
                        }.onSuccess {
                            updateStatus(NodeStatus.CONNECTING, error = null, appendLog = "[NODE] Auto-approved local pairing request")
                            wsManager.disconnect()
                            delay(500)
                            connectInternal(
                                host = preferencesManager.nodeGatewayHost ?: AppConstants.GATEWAY_HOST,
                                port = preferencesManager.nodeGatewayPort ?: AppConstants.GATEWAY_PORT,
                                token = preferencesManager.nodeGatewayToken
                            )
                        }.onFailure {
                            appendLog("[NODE] Auto-approve failed for request $requestId: ${it.message}")
                        }
                    }
                }
            }
            .onFailure {
                updateStatus(NodeStatus.ERROR, it.message ?: "Pairing request failed", appendLog = "[NODE] Pairing request error: ${it.message}")
            }
    }

    private suspend fun recoverFromDeviceAuthFailure(
        message: String,
        attemptedDeviceToken: Boolean
    ) {
        preferencesManager.nodeDeviceToken = null
        appendLog("[NODE] Cleared stale device token: $message")
        if (attemptedDeviceToken) {
            updateStatus(
                status = NodeStatus.CONNECTING,
                error = null,
                appendLog = "[NODE] Device auth rejected, retrying without stored device token"
            )
            wsManager.disconnect()
            delay(300)
            connectInternal(
                host = preferencesManager.nodeGatewayHost ?: AppConstants.GATEWAY_HOST,
                port = preferencesManager.nodeGatewayPort ?: AppConstants.GATEWAY_PORT,
                token = preferencesManager.nodeGatewayToken
            )
            return
        }
        requestPairing()
    }

    private fun isDeviceAuthFailure(
        code: String? = null,
        message: String,
        attemptedDeviceToken: Boolean = true
    ): Boolean {
        if (!attemptedDeviceToken && code.isNullOrBlank()) {
            return false
        }
        return code in setOf(
            "TOKEN_INVALID",
            "NOT_PAIRED",
            "DEVICE_NOT_PAIRED",
            "INVALID_REQUEST",
            "DEVICE_AUTH_INVALID",
            "IDENTITY_MISMATCH"
        ) || message.contains("device nonce mismatch", ignoreCase = true) ||
            message.contains("device-auth-invalid", ignoreCase = true) ||
            message.contains("identity mismatch", ignoreCase = true)
    }

    private suspend fun handleInvoke(payload: Map<String, Any?>) {
        val requestId = payload["id"]?.toString() ?: return
        val command = payload["command"]?.toString() ?: return
        val nodeId = payload["nodeId"]?.toString() ?: identityService.deviceId
        val params = decodeParams(payload["paramsJSON"]?.toString())
        appendLog("[NODE] Invoke: $command")

        val handler = capabilityHandlers[command.substringBefore('.')]
        if (handler == null || command !in handler.commands) {
            sendInvokeResult(requestId, nodeId, false, error = mapOf(
                "code" to "NOT_SUPPORTED",
                "message" to "Capability $command not available"
            ))
            return
        }

        val result = runCatching { handler.handle(command, params) }
            .getOrElse {
                NodeFrame.response("", error = mapOf("code" to "INVOKE_ERROR", "message" to (it.message ?: "Invoke failed")))
            }

        if (result.isError) {
            sendInvokeResult(requestId, nodeId, false, error = result.error ?: mapOf("code" to "INVOKE_ERROR", "message" to "Invoke failed"))
        } else {
            sendInvokeResult(requestId, nodeId, true, payload = result.payload)
        }
    }

    private suspend fun sendInvokeResult(
        requestId: String,
        nodeId: String,
        ok: Boolean,
        payload: Map<String, Any?>? = null,
        error: Map<String, Any?>? = null
    ) {
        val params = buildMap<String, Any?> {
            put("id", requestId)
            put("nodeId", nodeId)
            put("ok", ok)
            if (payload != null) {
                put("payloadJSON", JSONObject(payload).toString())
            }
            if (error != null) {
                put("error", error)
            }
        }
        runCatching { wsManager.sendRequest(NodeFrame.request("node.invoke.result", params), timeoutMs = 15_000L) }
            .onFailure { appendLog("[NODE] Failed to send invoke result: ${it.message}") }
    }

    private fun decodeParams(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key -> json.get(key) }
        }.getOrDefault(emptyMap())
    }

    private fun registerCapabilities() {
        listOf(
            CameraCapabilityHandler(appContext),
            FsCapabilityHandler(appContext, bootstrapManager),
            LocationCapabilityHandler(appContext),
            SystemCapabilityHandler(appContext),
            ScreenCapabilityHandler(appContext),
            SensorCapabilityHandler(appContext),
            FlashCapabilityHandler(appContext),
            VibrationCapabilityHandler(appContext),
            SerialCapabilityHandler(appContext)
        ).forEach { capability ->
            capabilityHandlers[capability.name] = capability
        }
    }

    private fun appendLog(line: String) {
        val currentState = _state.value
        if (currentState.logs.lastOrNull() == line) {
            return
        }
        val nextLogs = (currentState.logs + line).takeLast(300)
        val nextState = currentState.copy(logs = nextLogs)
        if (nextState != currentState) {
            _state.value = nextState
        }
    }

    private fun updateStatus(
        status: NodeStatus,
        error: String? = _state.value.errorMessage,
        pairingRequestId: String? = _state.value.pairingRequestId,
        host: String? = _state.value.gatewayHost,
        port: Int? = _state.value.gatewayPort,
        connectedAt: Long? = _state.value.connectedAt,
        appendLog: String? = null
    ) {
        val currentState = _state.value
        val nextLogs = when {
            appendLog == null -> currentState.logs
            currentState.logs.lastOrNull() == appendLog -> currentState.logs
            else -> (currentState.logs + appendLog).takeLast(300)
        }
        val nextState = currentState.copy(
            status = status,
            logs = nextLogs,
            errorMessage = error,
            pairingRequestId = pairingRequestId,
            gatewayHost = host,
            gatewayPort = port,
            deviceId = identityValue(),
            connectedAt = connectedAt
        )
        if (nextState != currentState) {
            _state.value = nextState
        }
    }

    private fun buildState(
        status: NodeStatus? = null,
        logs: List<String> = emptyList()
    ): NodeState {
        val current = _state.value
        val host = preferencesManager.nodeGatewayHost ?: AppConstants.GATEWAY_HOST
        val port = preferencesManager.nodeGatewayPort ?: AppConstants.GATEWAY_PORT
        val resolvedStatus = status ?: when {
            !preferencesManager.nodeEnabled -> NodeStatus.DISABLED
            current.status == NodeStatus.PAIRED -> NodeStatus.PAIRED
            NodeForegroundService.isRunning -> NodeStatus.CONNECTING
            else -> NodeStatus.DISCONNECTED
        }
        return NodeState(
            status = resolvedStatus,
            logs = logs,
            gatewayHost = host,
            gatewayPort = port,
            deviceId = identityValue(),
            pairingRequestId = current.pairingRequestId,
            errorMessage = current.errorMessage,
            connectedAt = current.connectedAt
        )
    }

    private fun initialState(): NodeState {
        return NodeState(
            status = if (preferencesManager.nodeEnabled) NodeStatus.DISCONNECTED else NodeStatus.DISABLED,
            gatewayHost = preferencesManager.nodeGatewayHost ?: AppConstants.GATEWAY_HOST,
            gatewayPort = preferencesManager.nodeGatewayPort ?: AppConstants.GATEWAY_PORT
        )
    }

    private fun resolveModelIdentifier(): String? {
        return listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifEmpty { null }
    }

    private fun identityValue(): String? {
        return if (identityReady) identityService.deviceId else null
    }
}
