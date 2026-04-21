package ai.inmo.openclaw.data.remote.websocket

import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.remote.api.NetworkModule
import ai.inmo.openclaw.domain.model.NodeFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

class NodeWsManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<NodeFrame>>()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isConnecting = false
    private var shouldReconnect = false
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var targetUrl: String? = null
    private var lastActivityAt: Long = 0L

    private val _frames = MutableSharedFlow<NodeFrame>(extraBufferCapacity = 64)
    val frames: SharedFlow<NodeFrame> = _frames.asSharedFlow()

    val isStale: Boolean
        get() = isConnected && lastActivityAt > 0L && System.currentTimeMillis() - lastActivityAt > 90_000L

    val hasActiveConnection: Boolean
        get() = isConnected

    val hasPendingConnection: Boolean
        get() = isConnecting

    val isReconnectPending: Boolean
        get() = shouldReconnect && (isConnecting || reconnectScheduled)

    fun connect(host: String, port: Int) {
        targetUrl = "ws://$host:$port"
        shouldReconnect = true
        openSocket()
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectAttempt = 0
        reconnectScheduled = false
        isConnecting = false
        isConnected = false
        val closingSocket = webSocket
        webSocket = null
        closingSocket?.close(1000, "bye")
        failPending("Disconnected")
    }

    suspend fun sendRequest(frame: NodeFrame, timeoutMs: Long = 15_000L): NodeFrame {
        val socket = webSocket ?: throw IllegalStateException("Node socket not connected")
        val requestId = frame.id ?: throw IllegalArgumentException("Request frame must have id")
        val deferred = CompletableDeferred<NodeFrame>()
        pendingRequests[requestId] = deferred
        if (!socket.send(frame.encode())) {
            pendingRequests.remove(requestId)
            throw IllegalStateException("Failed to send WebSocket frame")
        }
        return withTimeout(timeoutMs) { deferred.await() }
    }

    fun send(frame: NodeFrame): Boolean {
        return webSocket?.send(frame.encode()) == true
    }

    private fun openSocket() {
        val url = targetUrl ?: return
        if (isConnected || isConnecting) return
        reconnectScheduled = false
        webSocket?.cancel()
        webSocket = null
        isConnecting = true
        val request = Request.Builder().url(url).build()
        val socket = NetworkModule.okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (webSocket !== this@NodeWsManager.webSocket) {
                        webSocket.close(1000, "superseded")
                        return
                    }
                    isConnecting = false
                    isConnected = true
                    reconnectAttempt = 0
                    lastActivityAt = System.currentTimeMillis()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== this@NodeWsManager.webSocket) return
                    lastActivityAt = System.currentTimeMillis()
                    runCatching { NodeFrame.decode(text) }
                        .onSuccess { frame ->
                            if (frame.isResponse && frame.id != null) {
                                val pending = pendingRequests.remove(frame.id)
                                if (pending != null) {
                                    pending.complete(frame)
                                    return
                                }
                            }
                            _frames.tryEmit(frame)
                        }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleDisconnect(webSocket, reason.ifBlank { "WebSocket disconnected" })
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    handleDisconnect(webSocket, reason.ifBlank { "WebSocket disconnected" })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    handleDisconnect(webSocket, t.message ?: "WebSocket failure")
                }
            }
        )
        webSocket = socket
    }

    private fun handleDisconnect(socket: WebSocket?, reason: String) {
        if (socket != null && socket !== webSocket) return
        if (!isConnected && !isConnecting && webSocket == null) return
        reconnectScheduled = false
        isConnecting = false
        isConnected = false
        webSocket = null
        failPending(reason)
        if (shouldReconnect) {
            _frames.tryEmit(NodeFrame.event("_disconnected"))
        }
        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        val delayMs = min(
            (AppConstants.WS_RECONNECT_BASE_MS *
                AppConstants.WS_RECONNECT_MULTIPLIER.pow(reconnectAttempt.toDouble())).toLong(),
            AppConstants.WS_RECONNECT_CAP_MS.toLong()
        )
        reconnectAttempt += 1
        reconnectScheduled = true
        scope.launch {
            delay(delayMs)
            reconnectScheduled = false
            if (shouldReconnect) {
                openSocket()
            }
        }
    }

    private fun failPending(message: String) {
        pendingRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(IllegalStateException(message))
        }
        pendingRequests.clear()
    }
}
