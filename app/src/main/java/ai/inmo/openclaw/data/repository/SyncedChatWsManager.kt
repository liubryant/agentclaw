package ai.inmo.openclaw.data.repository

import ai.inmo.core_common.utils.Logger
import ai.inmo.core_common.utils.WifiNetworkMonitor
import ai.inmo.openclaw.R
import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.local.db.AppDatabase
import ai.inmo.openclaw.data.local.prefs.PreferencesManager
import ai.inmo.openclaw.data.remote.api.NetworkModule
import ai.inmo.openclaw.domain.model.ContentSegment
import ai.inmo.openclaw.domain.model.GeneratingPhase
import ai.inmo.openclaw.domain.model.MessageSendStatus
import ai.inmo.openclaw.domain.model.NodeFrame
import ai.inmo.openclaw.domain.model.StreamingToolChain
import ai.inmo.openclaw.domain.model.SyncedMessage
import ai.inmo.openclaw.domain.model.SyncedSession
import ai.inmo.openclaw.domain.model.TimelineEntry
import ai.inmo.openclaw.domain.model.ToolCallUiModel
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

class SyncedChatWsManager(
    context: Context,
    private val preferencesManager: PreferencesManager,
    cacheRepository: SyncedChatCacheRepository? = null
) {
    private enum class SessionCreateMode {
        DRAFT,
        PERSISTENT_PENDING
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<NodeFrame>>()
    private val draftSessionKeys = linkedSetOf<String>()
    private val cacheRepo = cacheRepository ?: run {
        val database = AppDatabase.getInstance(appContext)
        SyncedChatCacheRepository(
            database = database,
            syncedSessionDao = database.syncedSessionDao(),
            syncedMessageDao = database.syncedMessageDao(),
            syncedSegmentDao = database.syncedSegmentDao(),
            syncedToolCallDao = database.syncedToolCallDao()
        )
    }

    private var webSocket: WebSocket? = null
    private var isSocketConnected = false
    private var isSocketConnecting = false
    private var reconnectAttempt = 0
    private var shouldReconnect = false
    private var currentRunId: String? = null
    private var activeUserMessageId: String? = null

    private var deltaFlushJob: kotlinx.coroutines.Job? = null
    private var generationTimeoutJob: kotlinx.coroutines.Job? = null
    private var generationTimeoutStartedAtMs: Long? = null
    private val deltaFlushIntervalMs = 80L

    private val roundDeltaBuffer = StringBuilder()
    private val totalAgentText = StringBuilder()

    private val _streamingToolChain = MutableStateFlow(StreamingToolChain())
    val streamingToolChain: StateFlow<StreamingToolChain> = _streamingToolChain.asStateFlow()

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _sessions = MutableStateFlow<List<SyncedSession>>(emptyList())
    val sessions: StateFlow<List<SyncedSession>> = _sessions.asStateFlow()

    private val _messages = MutableStateFlow<List<SyncedMessage>>(emptyList())
    val messages: StateFlow<List<SyncedMessage>> = _messages.asStateFlow()

    private val _currentSessionKey = MutableStateFlow<String?>(null)
    val currentSessionKey: StateFlow<String?> = _currentSessionKey.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _generatingPhase = MutableStateFlow(GeneratingPhase.NONE)
    val generatingPhase: StateFlow<GeneratingPhase> = _generatingPhase.asStateFlow()

    private val _activeAssistantMessageId = MutableStateFlow<String?>(null)
    val activeAssistantMessageId: StateFlow<String?> = _activeAssistantMessageId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _replyFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val replyFinished: SharedFlow<Unit> = _replyFinished.asSharedFlow()

    private val loadedHistoryCount = ConcurrentHashMap<String, Int>()

    @Volatile
    private var isLoadingMoreHistory = false

    fun connect() {
        if (_connectionState.value || isSocketConnected || isSocketConnecting) return
        shouldReconnect = true
        _isReconnecting.value = false
        openSocket()
    }

    fun disconnect() {
        shouldReconnect = false
        isSocketConnecting = false
        isSocketConnected = false
        _connectionState.value = false
        _isReconnecting.value = false
        cancelGenerationTimeoutWatchdog()
        val closingSocket = webSocket
        webSocket = null
        closingSocket?.close(1000, "bye")
        pendingRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(
                IllegalStateException("Disconnected")
            )
        }
        pendingRequests.clear()
    }

    suspend fun loadSessions() {
        loadSessionsAndCache()
    }

    suspend fun loadHistory(sessionKey: String? = _currentSessionKey.value) {
        if (sessionKey.isNullOrBlank()) return
        loadHistoryFromCache(sessionKey, showLoading = true)
    }

    private suspend fun loadHistoryFromCache(
        sessionKey: String,
        showLoading: Boolean,
        limit: Int = INITIAL_HISTORY_LIMIT
    ) {
        if (showLoading) {
            _isLoading.value = true
        }
        _errorMessage.value = null
        try {
            val messages = cacheRepo.getCachedMessages(sessionKey, limit)
            publishSessionMessages(sessionKey, messages)
            updateSessionTitleFromMessages(sessionKey, messages)
            loadedHistoryCount[sessionKey] = messages.size
            cacheRepo.cacheSessions(_sessions.value)
        } finally {
            if (showLoading) {
                _isLoading.value = false
            }
        }
    }

    suspend fun switchSession(sessionKey: String) {
        if (_currentSessionKey.value == sessionKey) {
            persistSelectedSessionKey(sessionKey)
            return
        }
        setCurrentSessionKey(sessionKey)
        cancelGenerationTimeoutWatchdog()
        clearTransientRunState()
        _activeAssistantMessageId.value = null
        _isGenerating.value = false
        _generatingPhase.value = GeneratingPhase.NONE
        _errorMessage.value = null
        currentRunId = null
        activeUserMessageId = null
        _messages.value =
            emptyList()  // Clear old session's messages before publishing new session's
        val cachedMessages = cacheRepo.getCachedMessages(sessionKey, INITIAL_HISTORY_LIMIT)
        publishSessionMessages(sessionKey, cachedMessages)
        updateSessionTitleFromMessages(sessionKey, cachedMessages)
        loadedHistoryCount[sessionKey] = cachedMessages.size
        _isLoading.value = false
    }

    suspend fun loadMoreHistory(
        sessionKey: String,
        beforeIndex: Int,
        limit: Int = PAGE_SIZE
    ) {
        if (_isGenerating.value) {
            Logger.d(
                TRACE_TAG,
                "loadMoreSkipped session=$sessionKey, reason=generating, beforeIndex=$beforeIndex"
            )
            return
        }
        if (_isLoading.value || isLoadingMoreHistory || sessionKey != _currentSessionKey.value) return
        isLoadingMoreHistory = true
        try {
            val cachedOlder = if (beforeIndex >= 0) {
                cacheRepo.getOlderCachedMessages(sessionKey, beforeIndex, limit)
            } else {
                emptyList()
            }
            if (cachedOlder.isNotEmpty()) {
                prependMessages(sessionKey, cachedOlder)
                cacheRepo.cacheSessions(_sessions.value)
            }
        } finally {
            isLoadingMoreHistory = false
        }
    }

    suspend fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return
        _errorMessage.value = null

        val sessionKey = ensureCurrentSessionForCompose()
        draftSessionKeys.remove(sessionKey)
        val now = System.currentTimeMillis()
        val localUserMessage = SyncedMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = text.trim(),
            createdAt = now,
            sendStatus = if (hasAvailableNetwork()) {
                MessageSendStatus.SENT
            } else {
                MessageSendStatus.PENDING_RETRY_OFFLINE
            },
            segments = listOf(ContentSegment.Text(text.trim()))
        )
        appendLocalUserMessage(sessionKey, localUserMessage)

        if (localUserMessage.sendStatus == MessageSendStatus.PENDING_RETRY_OFFLINE) {
            return
        }

        sendExistingUserMessage(
            sessionKey = sessionKey,
            uiMessageId = localUserMessage.id,
            transportRunId = localUserMessage.id,
            text = localUserMessage.content
        )
    }

    suspend fun retryMessage(messageId: String) {
        if (_isGenerating.value) return
        val sessionKey = _currentSessionKey.value ?: return
        val pendingMessage = cacheRepo.getMessageByClientMessageId(messageId) ?: return
        if (!pendingMessage.role.equals("user", ignoreCase = true)) return
        if (!pendingMessage.sendStatus.isRetryablePendingStatus()) return
        if (!hasAvailableNetwork()) return
        _errorMessage.value = null
        when (pendingMessage.sendStatus) {
            MessageSendStatus.PENDING_RETRY_OFFLINE -> {
                sendExistingUserMessage(
                    sessionKey = sessionKey,
                    uiMessageId = pendingMessage.id,
                    transportRunId = pendingMessage.id,
                    text = pendingMessage.content
                )
            }

            MessageSendStatus.PENDING_RETRY_TIMEOUT -> {
                updateUserMessageSendStatus(
                    messageId = pendingMessage.id,
                    sendStatus = MessageSendStatus.SENT
                )
                sendExistingUserMessage(
                    sessionKey = sessionKey,
                    uiMessageId = pendingMessage.id,
                    transportRunId = UUID.randomUUID().toString(),
                    text = pendingMessage.content
                )
            }

            else -> Unit
        }
    }

    private suspend fun sendExistingUserMessage(
        sessionKey: String,
        uiMessageId: String,
        transportRunId: String,
        text: String
    ) {
        val assistantId = UUID.randomUUID().toString()
        try {
            ensureConnected()
            Logger.d(
                TRACE_TAG,
                "sendMessage session=$sessionKey, run=${currentRunId.orEmpty()}, " +
                        "textHash=${text.trim().stableHash()}, textLen=${text.trim().length}, " +
                        "clientRunId=$transportRunId, localUserId=$uiMessageId, assistantId=$assistantId, " +
                        "messages=${_messages.value.describeMessages()}"
            )
            _activeAssistantMessageId.value = assistantId
            activeUserMessageId = uiMessageId
            _isGenerating.value = true
            _generatingPhase.value = GeneratingPhase.THINKING
            clearTransientRunState()
            currentRunId = transportRunId
            draftSessionKeys.remove(sessionKey)
            val response = sendRequest(
                NodeFrame.request(
                    "chat.send",
                    mapOf(
                        "sessionKey" to sessionKey,
                        "message" to text.trim(),
                        "idempotencyKey" to transportRunId
                    )
                ),
                timeoutMs = GatewayConfigDefaults.DEFAULT_AGENT_TIMEOUT_SECONDS.toLong()
            )
            if (response.isError) {
                finishRunWithError(
                    response.error?.get("message")?.toString() ?: "Failed to send message"
                )
            } else {
                updateUserMessageSendStatus(
                    messageId = uiMessageId,
                    sendStatus = MessageSendStatus.SENT
                )
                markSessionAsRemoteBacked(sessionKey)
                startGenerationTimeoutWatchdog(
                    sessionKey = sessionKey,
                    runId = transportRunId
                )
                Logger.d(
                    TRACE_TAG,
                    "chatSendAck session=$sessionKey, clientRunId=$transportRunId, localUserId=$uiMessageId, gatewayRunId=${
                        response.payload?.get("runId") as? String
                    }"
                )
            }
        } catch (t: Throwable) {
            if (hasAvailableNetwork()) {
                finishRunWithError(t.message ?: "Failed to send message")
            } else {
                markActiveUserMessagePendingRetry()
                stopRunLocally(emitReplyFinished = false)
            }
        }
    }

    suspend fun abortRun(clearLocalState: Boolean = false) {
        if (!_isGenerating.value) return
        val sessionKey = _currentSessionKey.value ?: return
        val runId = currentRunId
        requestAbortRun(sessionKey, runId)
        if (clearLocalState) {
            stopRunLocally(emitReplyFinished = false)
        }
    }

    suspend fun resetCurrentSession() {
        ensureConnected()
        if (_isGenerating.value) {
            abortRun(clearLocalState = true)
        }

        createDraftSession()
    }

    suspend fun createPersistentSession(): String {
        ensureConnected()
        if (_isGenerating.value) {
            abortRun(clearLocalState = true)
        }
        return createSession(SessionCreateMode.PERSISTENT_PENDING)
    }

    suspend fun createPersistentSessionWithPresetConversation(
        userPrompt: String,
        assistantReply: String
    ): String {
        ensureConnected()
        if (_isGenerating.value) {
            abortRun(clearLocalState = true)
        }

        val sessionKey = createSession(SessionCreateMode.PERSISTENT_PENDING)
        val now = System.currentTimeMillis()
        val presetMessages = listOf(
            SyncedMessage(
                id = UUID.randomUUID().toString(),
                role = "user",
                content = userPrompt.trim(),
                createdAt = now,
                messageIndex = 0,
                segments = listOf(ContentSegment.Text(userPrompt.trim()))
            ),
            SyncedMessage(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = assistantReply.trim(),
                createdAt = now + 1,
                messageIndex = 1,
                segments = listOf(ContentSegment.Text(assistantReply.trim()))
            )
        )

        _messages.value = presetMessages
        loadedHistoryCount[sessionKey] = presetMessages.size
        upsertSessionTitle(userPrompt.trim())
        cacheRepo.cacheSessions(_sessions.value)
        cacheRepo.cacheMessages(sessionKey, presetMessages)
        return sessionKey
    }

    suspend fun deleteSession(sessionKey: String) {
        ensureConnected()
        if (_currentSessionKey.value == sessionKey && _isGenerating.value) {
            abortRun(clearLocalState = true)
        }

        val isDraftSession = isDraftSession(sessionKey)
        val isPendingPersistentSession = isPendingPersistentSession(sessionKey)
        if (!isDraftSession && !isPendingPersistentSession) {
            val response = sendAdminScopedRequest(
                NodeFrame.request(
                    "sessions.delete",
                    mapOf("key" to sessionKey, "deleteTranscript" to true)
                )
            )
            if (response.isError) {
                val message = response.error?.get("message")?.toString().orEmpty()
                throw IllegalStateException(normalizeSessionMutationError(message, "delete"))
            }
        }

        draftSessionKeys.remove(sessionKey)
        clearPersistedSessionKeyIfMatches(sessionKey)
        cacheRepo.clearSession(sessionKey)
        loadedHistoryCount.remove(sessionKey)
        _sessions.value = _sessions.value.filterNot { it.sessionKey == sessionKey }
        if (_currentSessionKey.value == sessionKey) {
            val next = _sessions.value.firstOrNull()
            if (next != null) {
                switchSession(next.sessionKey)
            } else {
                clearCurrentSessionSelection()
            }
        } else if (!isDraftSession && !isPendingPersistentSession) {
            loadSessions()
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    private fun openSocket() {
        if (isSocketConnected || isSocketConnecting) return
        isSocketConnecting = true
        _isReconnecting.value = false
        webSocket?.cancel()
        val request =
            Request.Builder().url("ws://${AppConstants.GATEWAY_HOST}:${AppConstants.GATEWAY_PORT}")
                .build()
        webSocket = NetworkModule.okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@SyncedChatWsManager.webSocket) {
                    webSocket.close(1000, "superseded")
                    return
                }
                isSocketConnected = true
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@SyncedChatWsManager.webSocket) return
                val frame = runCatching { NodeFrame.decode(text) }.getOrNull() ?: return
                when {
                    frame.isResponse && frame.id != null -> pendingRequests.remove(frame.id)
                        ?.complete(frame)

                    frame.isEvent -> handleEvent(frame)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect(webSocket, t.message ?: "WebSocket failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect(webSocket, reason.ifBlank { "Socket closed" })
            }
        })
    }

    private fun handleEvent(frame: NodeFrame) {
        when (frame.event) {
            "connect.challenge" -> scope.launch { sendOperatorConnect() }
            "chat" -> handleChatEvent(frame.payload ?: return)
            "agent" -> handleAgentEvent(frame.payload ?: return)
            "_disconnected" -> handleDisconnect(webSocket, "Gateway disconnected")
        }
    }

    private suspend fun sendOperatorConnect() {
        val token = PreferencesManager.resolveGatewayToken(appContext)
        val response = sendRequest(NodeFrame.request("connect", buildMap {
            put("minProtocol", 3)
            put("maxProtocol", 3)
            put(
                "client",
                mapOf(
                    "id" to "openclaw-android",
                    "displayName" to "OpenClaw Chat",
                    "version" to AppConstants.VERSION,
                    "platform" to "android",
                    "mode" to "ui"
                )
            )
            put("role", "operator")
            put("scopes", listOf("operator.read", "operator.write"))
            put("caps", listOf("tool-events"))
            if (!token.isNullOrBlank()) put("auth", mapOf("token" to token))
        }))
        if (response.isOk) {
            isSocketConnecting = false
            _connectionState.value = true
            _isReconnecting.value = false
            _errorMessage.value = null
            cacheRepo.cleanupLegacyEmptySessions(NEW_CHAT_TITLE)
            val cachedSessions = cacheRepo.getCachedSessions()
            if (cachedSessions.isNotEmpty()) {
                _sessions.value = cachedSessions
            }
            val cachedSelectedSessionKey = resolveRestoredSessionKey(cachedSessions)
            if (cachedSelectedSessionKey != null) {
                setCurrentSessionKey(cachedSelectedSessionKey)
            } else {
                clearCurrentSessionSelection()
            }
            val currentSessionKey = _currentSessionKey.value
            if (!currentSessionKey.isNullOrBlank()) {
                val cachedMessages =
                    cacheRepo.getCachedMessages(currentSessionKey, INITIAL_HISTORY_LIMIT)
                if (cachedMessages.isNotEmpty()) {
                    _messages.value = cachedMessages
                    loadedHistoryCount[currentSessionKey] = cachedMessages.size
                }
            }
            _isLoading.value = false
            loadSessionsAndCache(skipEnsureConnected = true)
        } else {
            shouldReconnect = false
            handleDisconnect(
                webSocket,
                response.error?.get("message")?.toString() ?: "Authentication failed"
            )
        }
    }

    private suspend fun sendAdminScopedRequest(
        request: NodeFrame,
        timeoutMs: Long = 15_000L
    ): NodeFrame {
        val token = PreferencesManager.resolveGatewayToken(appContext)
            ?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalStateException("Missing gateway token for operator.admin request")

        val connectRequest = NodeFrame.request("connect", buildMap {
            put("minProtocol", 3)
            put("maxProtocol", 3)
            put(
                "client",
                mapOf(
                    "id" to "openclaw-android",
                    "displayName" to "OpenClaw Chat",
                    "version" to AppConstants.VERSION,
                    "platform" to "android",
                    "mode" to "ui"
                )
            )
            put("role", "operator")
            put("scopes", listOf("operator.admin"))
            put("caps", listOf("tool-events"))
            put("auth", mapOf("token" to token))
        })

        val connectDeferred = CompletableDeferred<NodeFrame>()
        val requestDeferred = CompletableDeferred<NodeFrame>()
        val socketClosed = CompletableDeferred<Unit>()
        val socket = NetworkModule.okHttpClient.newWebSocket(
            Request.Builder().url("ws://${AppConstants.GATEWAY_HOST}:${AppConstants.GATEWAY_PORT}")
                .build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = runCatching { NodeFrame.decode(text) }.getOrNull() ?: return
                    when {
                        frame.isEvent && frame.event == "connect.challenge" -> {
                            webSocket.send(connectRequest.encode())
                        }

                        frame.isResponse && frame.id == connectRequest.id -> {
                            connectDeferred.complete(frame)
                            if (frame.isOk) {
                                webSocket.send(request.encode())
                            }
                        }

                        frame.isResponse && frame.id == request.id -> {
                            requestDeferred.complete(frame)
                            webSocket.close(1000, "done")
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!connectDeferred.isCompleted) connectDeferred.completeExceptionally(t)
                    if (!requestDeferred.isCompleted) requestDeferred.completeExceptionally(t)
                    if (!socketClosed.isCompleted) socketClosed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!socketClosed.isCompleted) socketClosed.complete(Unit)
                }
            }
        )

        return try {
            val connectResponse = withTimeout(timeoutMs) { connectDeferred.await() }
            if (connectResponse.isError) {
                throw IllegalStateException(
                    normalizeAdminConnectError(
                        connectResponse.error?.get("message")?.toString().orEmpty()
                    )
                )
            }
            withTimeout(timeoutMs) { requestDeferred.await() }
        } finally {
            socket.close(1000, "done")
            withTimeout(2_000L) {
                runCatching { socketClosed.await() }
            }
        }
    }

    private suspend fun loadSessionsAndCache(skipEnsureConnected: Boolean = false) {
        if (!skipEnsureConnected) {
            ensureConnected()
        }
        val fetchedSessions = fetchSessionsFromGateway()
        val restoredSessionKey = resolveRestoredSessionKey(fetchedSessions)
        when {
            restoredSessionKey != null && restoredSessionKey != _currentSessionKey.value -> {
                setCurrentSessionKey(restoredSessionKey)
            }

            restoredSessionKey == null -> {
                clearCurrentSessionSelection()
            }
        }
        val nextSessions = reconcileSessionTitles(fetchedSessions)
        _sessions.value = nextSessions
        cacheRepo.cacheSessions(nextSessions)
    }

    private suspend fun reconcileSessionTitles(sessions: List<SyncedSession>): List<SyncedSession> {
        if (sessions.isEmpty()) return sessions
        return sessions.map { session ->
            val sourceMessages = when {
                session.sessionKey == _currentSessionKey.value && _messages.value.isNotEmpty() -> _messages.value
                else -> cacheRepo.getCachedMessages(session.sessionKey, INITIAL_HISTORY_LIMIT)
            }
            session.copy(title = deriveSessionTitle(session, sourceMessages))
        }
    }

    private suspend fun fetchSessionsFromGateway(): List<SyncedSession> {
        val response = sendRequest(
            NodeFrame.request(
                "sessions.list",
                mapOf("agentId" to "main", "includeDerivedTitles" to true)
            )
        )
        if (response.isError) {
            throw IllegalStateException(
                response.error?.get("message")?.toString() ?: "Failed to list sessions"
            )
        }

        val list = response.payload?.get("sessions") as? List<*> ?: emptyList<Any>()
        val remoteSessions = list.mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            (entry as? Map<String, Any?>)?.let(SyncedSession::fromPayload)
        }.toMutableList()

        val localSessions = _sessions.value
        val remoteKeys = remoteSessions.mapTo(mutableSetOf()) { it.sessionKey }
        draftSessionKeys.removeAll(remoteKeys)

        draftSessionKeys.forEach { draftKey ->
            if (remoteKeys.contains(draftKey)) return@forEach
            val localDraft = localSessions.firstOrNull { it.sessionKey == draftKey }
                ?: SyncedSession(
                    sessionKey = draftKey,
                    title = NEW_CHAT_TITLE,
                    updatedAt = System.currentTimeMillis()
                )
            remoteSessions.add(localDraft)
        }

        localSessions
            .filter { it.kind == KIND_LOCAL_PENDING_PERSISTENT && !remoteKeys.contains(it.sessionKey) }
            .forEach(remoteSessions::add)

        val nextSessions = remoteSessions
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.sessionKey }
        return nextSessions
    }

    private fun resolveRestoredSessionKey(sessions: List<SyncedSession>): String? {
        if (sessions.isEmpty()) return null
        val persisted = preferencesManager.lastSelectedChatSessionKey?.trim().orEmpty()
        return when {
            persisted.isNotEmpty() && sessions.any { it.sessionKey == persisted } -> persisted
            !(_currentSessionKey.value.isNullOrBlank()) && sessions.any { it.sessionKey == _currentSessionKey.value } -> _currentSessionKey.value
            else -> sessions.firstOrNull()?.sessionKey
        }
    }

    private fun setCurrentSessionKey(sessionKey: String) {
        _currentSessionKey.value = sessionKey
        persistSelectedSessionKey(sessionKey)
    }

    private suspend fun ensureCurrentSessionForCompose(): String {
        val currentSessionKey = _currentSessionKey.value
        if (!currentSessionKey.isNullOrBlank() && hasTrackedSession(currentSessionKey)) {
            return currentSessionKey
        }
        return createDraftSession()
    }

    private fun hasTrackedSession(sessionKey: String): Boolean {
        return _sessions.value.any { it.sessionKey == sessionKey } || draftSessionKeys.contains(
            sessionKey
        )
    }

    private fun createDraftSession(): String {
        return createSession(SessionCreateMode.DRAFT)
    }

    private fun createSession(mode: SessionCreateMode): String {
        val sessionKey = createRemoteSessionKey()
        if (mode == SessionCreateMode.DRAFT) {
            draftSessionKeys.add(sessionKey)
        }
        setCurrentSessionKey(sessionKey)
        _messages.value = emptyList()
        loadedHistoryCount[sessionKey] = 0
        cancelGenerationTimeoutWatchdog()
        clearTransientRunState()
        _activeAssistantMessageId.value = null
        _isGenerating.value = false
        _generatingPhase.value = GeneratingPhase.NONE
        _errorMessage.value = null
        currentRunId = null
        activeUserMessageId = null

        val sessions = _sessions.value.toMutableList()
        sessions.removeAll { it.sessionKey == sessionKey }
        sessions.add(
            0,
            SyncedSession(
                sessionKey = sessionKey,
                title = NEW_CHAT_TITLE,
                updatedAt = System.currentTimeMillis(),
                kind = if (mode == SessionCreateMode.PERSISTENT_PENDING) {
                    KIND_LOCAL_PENDING_PERSISTENT
                } else {
                    null
                }
            )
        )
        _sessions.value = sessions
        if (mode != SessionCreateMode.DRAFT) {
            scope.launch {
                cacheRepo.cacheSessions(sessions)
            }
        }
        return sessionKey
    }

    private fun clearCurrentSessionSelection() {
        _currentSessionKey.value = null
        preferencesManager.lastSelectedChatSessionKey = null
        _messages.value = emptyList()
        cancelGenerationTimeoutWatchdog()
        clearTransientRunState()
        _activeAssistantMessageId.value = null
        _isGenerating.value = false
        _generatingPhase.value = GeneratingPhase.NONE
        _isLoading.value = false
        _errorMessage.value = null
        currentRunId = null
        activeUserMessageId = null
    }

    private fun persistSelectedSessionKey(sessionKey: String) {
        preferencesManager.lastSelectedChatSessionKey = sessionKey
    }

    private fun clearPersistedSessionKeyIfMatches(sessionKey: String) {
        if (preferencesManager.lastSelectedChatSessionKey == sessionKey) {
            preferencesManager.lastSelectedChatSessionKey = null
        }
    }

    private fun isPendingPersistentSession(sessionKey: String): Boolean {
        return _sessions.value.any {
            it.sessionKey == sessionKey && it.kind == KIND_LOCAL_PENDING_PERSISTENT
        }
    }

    private fun markSessionAsRemoteBacked(sessionKey: String) {
        val sessions = _sessions.value.toMutableList()
        val index = sessions.indexOfFirst { it.sessionKey == sessionKey }
        if (index < 0) return
        val existing = sessions[index]
        if (existing.kind != KIND_LOCAL_PENDING_PERSISTENT) return
        sessions[index] = existing.copy(
            kind = null,
            updatedAt = System.currentTimeMillis()
        )
        _sessions.value = sessions
        scope.launch {
            cacheRepo.cacheSessions(sessions)
        }
    }

    private fun prependMessages(sessionKey: String, olderMessages: List<SyncedMessage>) {
        if (olderMessages.isEmpty()) return
        val existingIds = _messages.value.mapTo(mutableSetOf()) { it.id }
        val deduped = olderMessages.filterNot { existingIds.contains(it.id) }
        if (deduped.isEmpty()) return
        publishSessionMessages(sessionKey, deduped + _messages.value)
        updateSessionTitleFromMessages(sessionKey, deduped + _messages.value)
        loadedHistoryCount[sessionKey] = _messages.value.size
    }

    private fun publishSessionMessages(sessionKey: String, nextMessages: List<SyncedMessage>) {
        if (_currentSessionKey.value != sessionKey) return
        val previousMessages = _messages.value
        val equivalent = areMessagesRenderEquivalent(previousMessages, nextMessages)
        Logger.d(
            TRACE_TAG,
            "publishMessages session=$sessionKey, equivalent=$equivalent, " +
                    "old=${previousMessages.describeMessages()}, new=${nextMessages.describeMessages()}, " +
                    "published=${nextMessages.describeMessages()}"
        )
        if (!equivalent) {
            _messages.value = nextMessages
        }
    }

    private fun hasAvailableNetwork(): Boolean {
        return WifiNetworkMonitor.isWifiConnected(appContext)
    }

    private fun stopRunLocally(emitReplyFinished: Boolean) {
        cancelGenerationTimeoutWatchdog()
        clearTransientRunState()
        _isGenerating.value = false
        _generatingPhase.value = GeneratingPhase.NONE
        _activeAssistantMessageId.value = null
        currentRunId = null
        activeUserMessageId = null
        if (emitReplyFinished) {
            _replyFinished.tryEmit(Unit)
        }
    }

    private fun startGenerationTimeoutWatchdog(sessionKey: String, runId: String) {
        cancelGenerationTimeoutWatchdog()
        generationTimeoutStartedAtMs = System.currentTimeMillis()
        Logger.d(
            TRACE_TAG,
            "generationTimeoutStart session=$sessionKey, run=$runId, timeoutMs=${GatewayConfigDefaults.DEFAULT_AGENT_TIMEOUT_SECONDS}"
        )
        generationTimeoutJob = scope.launch {
            delay(GatewayConfigDefaults.DEFAULT_AGENT_TIMEOUT_SECONDS * 1000L)
            handleGenerationBusinessTimeout(sessionKey, runId)
        }
    }

    private fun cancelGenerationTimeoutWatchdog() {
        val startedAt = generationTimeoutStartedAtMs
        val elapsedMs = startedAt?.let { System.currentTimeMillis() - it } ?: 0L
        if (generationTimeoutJob != null || startedAt != null) {
            Logger.d(
                TRACE_TAG,
                "generationTimeoutCancel session=${_currentSessionKey.value.orEmpty()}, run=${currentRunId.orEmpty()}, elapsedMs=$elapsedMs"
            )
        }
        generationTimeoutJob?.cancel()
        generationTimeoutJob = null
        generationTimeoutStartedAtMs = null
    }

    private fun handleGenerationBusinessTimeout(sessionKey: String, runId: String) {
        val startedAt = generationTimeoutStartedAtMs
        val elapsedMs = startedAt?.let { System.currentTimeMillis() - it }
            ?: GatewayConfigDefaults.DEFAULT_AGENT_TIMEOUT_SECONDS
        val currentSessionKey = _currentSessionKey.value
        val activeRunId = currentRunId
        val isStillGenerating = _isGenerating.value
        if (!isStillGenerating || currentSessionKey != sessionKey || activeRunId != runId) {
            Logger.d(
                TRACE_TAG,
                "generationTimeoutSkip session=$sessionKey, run=$runId, elapsedMs=$elapsedMs, currentSession=${currentSessionKey.orEmpty()}, currentRun=${activeRunId.orEmpty()}, generating=$isStillGenerating"
            )
            return
        }
        Logger.d(
            TRACE_TAG,
            "generationTimeoutFire session=$sessionKey, run=$runId, elapsedMs=$elapsedMs, phase=${_generatingPhase.value}"
        )
        finishRunWithSyntheticAssistantReply(
            sessionKey = sessionKey,
            errorMessage = "Generation business timeout",
            assistantText = appContext.getString(R.string.content_generation_timed_out),
            isReportErrorMessage = false
        )
    }

    private fun updateUserMessageSendStatus(
        messageId: String,
        sendStatus: MessageSendStatus
    ) {
        val nextMessages = _messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(sendStatus = sendStatus)
            } else {
                message
            }
        }
        _messages.value = nextMessages
        scope.launch {
            runCatching { cacheRepo.updateMessageSendStatus(messageId, sendStatus) }
                .onFailure {
                    Logger.w(
                        TAG,
                        "Failed to update message send status: id=$messageId, status=$sendStatus, reason=${it.message}"
                    )
                }
        }
    }

    private fun appendLocalUserMessage(sessionKey: String, message: SyncedMessage) {
        _messages.value = _messages.value + message
        upsertSessionTitle(message.content)
        scope.launch {
            runCatching {
                cacheRepo.cacheSessions(_sessions.value)
                cacheRepo.appendMessage(sessionKey, message)
            }
                .onFailure { Logger.w(TAG, "Failed to append user message: ${it.message}") }
        }
    }

    private fun markActiveUserMessagePendingRetry() {
        val messageId = activeUserMessageId ?: return
        updateUserMessageSendStatus(messageId, MessageSendStatus.PENDING_RETRY_OFFLINE)
    }

    private fun markActiveUserMessagePendingRetryTimeout() {
        val messageId = activeUserMessageId ?: return
        updateUserMessageSendStatus(messageId, MessageSendStatus.PENDING_RETRY_TIMEOUT)
    }

    private fun areMessagesRenderEquivalent(
        oldMessages: List<SyncedMessage>,
        newMessages: List<SyncedMessage>
    ): Boolean {
        if (oldMessages === newMessages) return true
        if (oldMessages.size != newMessages.size) return false
        return oldMessages.indices.all { index ->
            oldMessages[index].renderEquivalent(newMessages[index])
        }
    }

    private fun SyncedMessage.renderEquivalent(other: SyncedMessage): Boolean {
        return id == other.id &&
                role == other.role &&
                content == other.content &&
                createdAt == other.createdAt &&
                isStreaming == other.isStreaming &&
                sendStatus == other.sendStatus &&
                segments.renderSegmentsEquivalent(other.segments)
    }

    private fun List<ContentSegment>.renderSegmentsEquivalent(other: List<ContentSegment>): Boolean {
        if (size != other.size) return false
        return indices.all { index ->
            this[index].renderEquivalent(other[index])
        }
    }

    private fun ContentSegment.renderEquivalent(other: ContentSegment): Boolean {
        return when {
            this is ContentSegment.Text && other is ContentSegment.Text -> text == other.text
            this is ContentSegment.Tools && other is ContentSegment.Tools -> tools.renderToolsEquivalent(
                other.tools
            )

            else -> false
        }
    }

    private fun List<ToolCallUiModel>.renderToolsEquivalent(other: List<ToolCallUiModel>): Boolean {
        if (size != other.size) return false
        return indices.all { index ->
            this[index].renderEquivalent(other[index])
        }
    }

    private fun ToolCallUiModel.renderEquivalent(other: ToolCallUiModel): Boolean {
        return toolCallId == other.toolCallId &&
                name == other.name &&
                description == other.description &&
                iconResId == other.iconResId &&
                completed == other.completed
    }

    private fun handleChatEvent(payload: Map<String, Any?>) {
        Logger.d("cjym", "handleChatEvent:${payload}")
        val sessionKey = payload["sessionKey"] as? String ?: _currentSessionKey.value ?: return
        if (sessionKey != _currentSessionKey.value) return
        val state = payload["state"] as? String ?: "delta"
        Logger.d(
            TRACE_TAG,
            "chatEvent session=$sessionKey, run=${currentRunId.orEmpty()}, state=$state, " +
                    "payloadTextHash=${extractFinalPayloadText(payload).stableHash()}, " +
                    "payloadTextLen=${extractFinalPayloadText(payload).length}, activeAssistant=${_activeAssistantMessageId.value.orEmpty()}"
        )
        when (state) {
            "delta" -> {
                // 过滤掉 chat delta，文本通过 agent stream=assistant 获取
            }

            "final" -> {
                val finalizedMessage = finalizeCurrentAssistantMessage(payload)
                stopRunLocally(emitReplyFinished = true)
                scope.launch {
                    if (finalizedMessage != null) {
                        Logger.d(
                            TRACE_TAG,
                            "chatFinal session=$sessionKey, finalizedLocally=true, " +
                                    "assistant=${
                                        finalizedMessage.describeMessage()
                                    }, messages=${_messages.value.describeMessages()}"
                        )
                        persistAssistantMessage(sessionKey, finalizedMessage)
                        runCatching { loadSessions() }
                            .onFailure { Logger.w(TAG, "Session refresh failed: ${it.message}") }
                    } else {
                        Logger.d(
                            TRACE_TAG,
                            "chatFinal session=$sessionKey, finalizedLocally=false"
                        )
                        runCatching { loadSessions() }
                            .onFailure { Logger.w(TAG, "Session refresh failed: ${it.message}") }
                    }
                }
            }

            "aborted" -> {
                val finalizedMessage = finalizeCurrentAssistantMessage(emptyMap())
                stopRunLocally(emitReplyFinished = false)
                if (finalizedMessage != null) {
                    scope.launch { persistAssistantMessage(sessionKey, finalizedMessage) }
                }
            }

            "error" -> handleChatErrorEvent(
                sessionKey = sessionKey,
                message = payload["errorMessage"] as? String ?: "Generation failed"
            )
        }
    }

    private fun handleAgentEvent(payload: Map<String, Any?>) {
        Logger.d("cjym", "handleAgentEvent:${payload}")
        val stream = payload["stream"] as? String ?: return
        val data = payload["data"] as? Map<String, Any?> ?: return

        when (stream) {
            "assistant" -> {
                val delta = data["delta"] as? String ?: return
                if (delta.isEmpty()) return
                if (_activeAssistantMessageId.value == null) return
                synchronized(roundDeltaBuffer) { roundDeltaBuffer.append(delta) }
                Logger.d(
                    TRACE_TAG,
                    "agentAssistant session=${_currentSessionKey.value}, run=${currentRunId.orEmpty()}, " +
                            "assistantId=${_activeAssistantMessageId.value.orEmpty()}, deltaHash=${delta.stableHash()}, " +
                            "deltaLen=${delta.length}, pendingLen=${roundDeltaBuffer.length}, chain=${_streamingToolChain.value.describeChain()}"
                )
                if (_generatingPhase.value != GeneratingPhase.CALLING_TOOL) {
                    _generatingPhase.value = GeneratingPhase.THINKING
                }
                scheduleRoundDeltaFlush()
            }

            "tool" -> {
                val phase = data["phase"] as? String ?: return
                val toolCallId = data["toolCallId"] as? String ?: UUID.randomUUID().toString()
                val toolName = data["name"] as? String ?: "Tool"
                val chain = _streamingToolChain.value
                val existingTools = chain.entries
                    .filterIsInstance<TimelineEntry.Tool>()
                    .map { it.tool }
                val toolIndex = existingTools.indexOfFirst { it.toolCallId == toolCallId }
                val previousItem = existingTools.getOrNull(toolIndex)
                val next = buildToolCallUiModel(
                    block = data,
                    fallbackName = toolName,
                    fallbackToolCallId = toolCallId,
                    fallbackText = data["text"]?.toString(),
                    completed = phase == "result",
                    previousItem = previousItem
                )
                val newEntries = chain.entries.toMutableList()

                if (phase == "start") {
                    deltaFlushJob?.cancel()
                    val roundText = synchronized(roundDeltaBuffer) {
                        val t = roundDeltaBuffer.toString()
                        roundDeltaBuffer.clear()
                        t
                    }
                    if (roundText.isNotBlank()) {
                        synchronized(totalAgentText) { totalAgentText.append(roundText) }
                        newEntries.add(TimelineEntry.Text(roundText))
                    }
                    newEntries.add(TimelineEntry.Tool(next))
                } else {
                    val idx = newEntries.indexOfLast {
                        it is TimelineEntry.Tool && it.tool.toolCallId == toolCallId
                    }
                    if (idx >= 0) {
                        newEntries[idx] = TimelineEntry.Tool(next)
                    } else {
                        newEntries.add(TimelineEntry.Tool(next))
                    }
                }

                _streamingToolChain.value = StreamingToolChain(
                    entries = newEntries,
                    pendingText = null,
                    isActive = true
                )
                Logger.d(
                    TRACE_TAG,
                    "agentTool session=${_currentSessionKey.value}, run=${currentRunId.orEmpty()}, phase=$phase, " +
                            "toolCallId=$toolCallId, toolName=$toolName, chain=${_streamingToolChain.value.describeChain()}"
                )
                _generatingPhase.value = GeneratingPhase.CALLING_TOOL
            }
        }
    }

    private fun scheduleRoundDeltaFlush() {
        if (deltaFlushJob?.isActive == true) return
        deltaFlushJob = scope.launch {
            delay(deltaFlushIntervalMs)
            flushRoundDeltaPending()
        }
    }

    private fun flushRoundDeltaPending() {
        val pending = synchronized(roundDeltaBuffer) { roundDeltaBuffer.toString() }
        if (pending.isEmpty()) return
        val chain = _streamingToolChain.value
        _streamingToolChain.value = chain.copy(pendingText = pending)
        Logger.d(
            TRACE_TAG,
            "flushRoundDelta session=${_currentSessionKey.value}, run=${currentRunId.orEmpty()}, " +
                    "pendingHash=${pending.stableHash()}, pendingLen=${pending.length}, chain=${_streamingToolChain.value.describeChain()}"
        )
    }

    private fun flushRoundDeltaImmediately(): StreamingToolChain {
        deltaFlushJob?.cancel()
        flushRoundDeltaPending()
        return _streamingToolChain.value
    }

    private fun finalizeCurrentAssistantMessage(payload: Map<String, Any?>): SyncedMessage? {
        val assistantId = _activeAssistantMessageId.value ?: return null
        val chain = flushRoundDeltaImmediately()
        val finalText = resolveFinalAssistantText(payload, chain)
        val finalSegments = buildFinalSegments(chain, finalText)
        val toolCalls = finalSegments
            .filterIsInstance<ContentSegment.Tools>()
            .flatMap { it.tools }

        if (finalText.isBlank() && finalSegments.isEmpty()) {
            Logger.d(
                TRACE_TAG,
                "finalizeAssistant session=${_currentSessionKey.value}, run=${currentRunId.orEmpty()}, " +
                        "assistantId=$assistantId, skipped=true, chain=${chain.describeChain()}"
            )
            return null
        }

        val finalMessage = SyncedMessage(
            id = assistantId,
            role = "assistant",
            content = finalText,
            createdAt = System.currentTimeMillis(),
            messageIndex = _messages.value.size,
            isStreaming = false,
            toolCalls = toolCalls,
            segments = finalSegments
        )
        Logger.d(
            TRACE_TAG,
            "finalizeAssistant session=${_currentSessionKey.value}, run=${currentRunId.orEmpty()}, " +
                    "assistant=${finalMessage.describeMessage()}, payloadTextHash=${
                        extractFinalPayloadText(
                            payload
                        ).stableHash()
                    }, " +
                    "chain=${chain.describeChain()}"
        )
        upsertFinalAssistantMessage(finalMessage)
        return finalMessage
    }

    private suspend fun persistAssistantMessage(sessionKey: String, message: SyncedMessage) {
        runCatching {
            cacheRepo.appendMessage(sessionKey, message)
            cacheRepo.cacheSessions(_sessions.value)
        }.onFailure {
            Logger.w(TAG, "Failed to append assistant message: ${it.message}")
        }
    }

    private fun resolveFinalAssistantText(
        payload: Map<String, Any?>,
        chain: StreamingToolChain
    ): String {
        val payloadText = extractFinalPayloadText(payload)
        val streamedTail = chain.pendingText.orEmpty()
        val hasToolChain = chain.entries.any { it is TimelineEntry.Tool }

        if (!hasToolChain) {
            return payloadText.takeIf { it.isNotBlank() } ?: streamedTail
        }

        val prefix = synchronized(totalAgentText) { totalAgentText.toString() }
        if (payloadText.isBlank()) {
            return streamedTail
        }
        return if (prefix.isNotEmpty() &&
            payloadText.length >= prefix.length &&
            payloadText.startsWith(prefix)
        ) {
            payloadText.substring(prefix.length)
        } else {
            payloadText
        }
    }

    private fun buildFinalSegments(
        chain: StreamingToolChain,
        finalAssistantText: String
    ): List<ContentSegment> {
        val hasToolChain = chain.entries.any { it is TimelineEntry.Tool }
        if (!hasToolChain) {
            return finalAssistantText.takeIf { it.isNotBlank() }
                ?.let { listOf(ContentSegment.Text(it)) }
                ?: emptyList()
        }

        val segments = mutableListOf<ContentSegment>()
        val pendingTools = mutableListOf<ToolCallUiModel>()

        fun flushTools() {
            if (pendingTools.isNotEmpty()) {
                segments.add(ContentSegment.Tools(pendingTools.toList()))
                pendingTools.clear()
            }
        }

        chain.entries.forEach { entry ->
            when (entry) {
                is TimelineEntry.Text -> {
                    flushTools()
                    if (entry.text.isNotBlank()) {
                        segments.add(ContentSegment.Text(entry.text))
                    }
                }

                is TimelineEntry.Tool -> pendingTools.add(entry.tool)
            }
        }
        flushTools()

        if (finalAssistantText.isNotBlank()) {
            segments.add(ContentSegment.Text(finalAssistantText))
        }
        return segments
    }

    private fun extractFinalPayloadText(payload: Map<String, Any?>): String {
        val directCandidates = listOf("finalText", "text", "content", "finalContent")
        directCandidates.forEach { key ->
            val text = extractText(payload[key]).trim()
            if (text.isNotEmpty()) return text
        }

        val nestedCandidates = listOf("message", "finalMessage", "assistant", "data")
        nestedCandidates.forEach { key ->
            val map = payload[key] as? Map<*, *> ?: return@forEach
            val text = extractText(
                map["content"]
                    ?: map["text"]
                    ?: map["finalText"]
            ).trim()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun upsertFinalAssistantMessage(message: SyncedMessage) {
        val current = _messages.value.toMutableList()
        val existingIndex = current.indexOfLast { it.id == message.id }
        if (existingIndex >= 0) {
            current[existingIndex] = message
        } else {
            current.add(message)
        }
        Logger.d(
            TRACE_TAG,
            "upsertAssistant session=${_currentSessionKey.value}, action=${if (existingIndex >= 0) "replace" else "add"}, " +
                    "target=${message.describeMessage()}, before=${_messages.value.describeMessages()}, after=${current.describeMessages()}"
        )
        _messages.value = current
    }

    private fun handleChatErrorEvent(sessionKey: String, message: String) {
        if (isRateLimitError(message)) {
            finishRunWithSyntheticAssistantReply(
                sessionKey = sessionKey,
                errorMessage = message,
                assistantText = appContext.getString(R.string.rate_limit_fallback_tip)
            )
            return
        } else if (isQuotaExhaustedError(message)) {
            finishRunWithSyntheticAssistantReply(
                sessionKey = sessionKey,
                errorMessage = message,
                assistantText = appContext.getString(R.string.api_call_quota_exhausted)
            )
            return
        } else if (isModelTimeoutError(message)) {
            finishRunWithSyntheticAssistantReply(
                sessionKey = sessionKey,
                errorMessage = message,
                assistantText = appContext.getString(R.string.content_generation_timed_out),
                isReportErrorMessage = false
            )
            return
        }
        if (isLLmRequestTimedOutError(message) || isTerminatedError(message)) {
            markActiveUserMessagePendingRetryTimeout()
            scope.launch {
                abortRun(clearLocalState = true)
            }
            return
        }

        finishRunWithError(message)
    }

    private fun finishRunWithSyntheticAssistantReply(
        sessionKey: String,
        errorMessage: String,
        assistantText: String,
        isReportErrorMessage: Boolean = true
    ) {
        val runId = currentRunId
        val assistantId = _activeAssistantMessageId.value ?: UUID.randomUUID().toString()
        val syntheticMessage = SyncedMessage(
            id = assistantId,
            role = "assistant",
            content = assistantText,
            createdAt = System.currentTimeMillis(),
            messageIndex = _messages.value.size,
            isStreaming = false,
            segments = listOf(ContentSegment.Text(assistantText))
        )
        Logger.d(
            TRACE_TAG,
            "syntheticAssistant session=$sessionKey, run=${runId.orEmpty()}, " +
                    "reason=${errorMessage.stableHash()}, assistant=${syntheticMessage.describeMessage()}"
        )
        scope.launch {
            runCatching { requestAbortRun(sessionKey, runId) }
                .onFailure { Logger.w(TAG, "Failed to abort run after chat error: ${it.message}") }
        }
        if (isReportErrorMessage) {
            _errorMessage.value = errorMessage
        }
        stopRunLocally(emitReplyFinished = false)
        upsertFinalAssistantMessage(syntheticMessage)
        _replyFinished.tryEmit(Unit)
        scope.launch {
            persistAssistantMessage(sessionKey, syntheticMessage)
        }
    }

    private fun isRateLimitError(message: String): Boolean {
        return message.contains("rate limit", ignoreCase = true)
    }

    private fun isQuotaExhaustedError(message: String): Boolean {
        return message.contains("quota exhausted", ignoreCase = true)
    }

    private fun isLLmRequestTimedOutError(message: String): Boolean {
        return message.contains("LLM request timed out", ignoreCase = true)
    }

    private fun isTerminatedError(message: String): Boolean {
        return message.contains("terminated", ignoreCase = true)
    }

    private fun isModelTimeoutError(message: String): Boolean {
        return message.contains("model timeout", ignoreCase = true)
    }

    private fun MessageSendStatus.isRetryablePendingStatus(): Boolean {
        return this == MessageSendStatus.PENDING_RETRY_OFFLINE ||
                this == MessageSendStatus.PENDING_RETRY_TIMEOUT
    }

    private fun describeToolCall(
        name: String,
        args: Map<String, Any?>,
        fallback: String? = null
    ): String {
        val resolvedName = name.trim().ifEmpty { "tool" }
        val resolvedFallback = fallback?.takeIf { it.isNotBlank() }

        fun truncate(value: String, max: Int): String {
            return if (value.length > max) "${value.substring(0, max)}..." else value
        }

        fun basename(path: String): String {
            val normalized = path.replace('\\', '/').trim().trimEnd('/')
            if (normalized.isEmpty()) return normalized
            val index = normalized.lastIndexOf('/')
            return if (index >= 0) normalized.substring(index + 1) else normalized
        }

        fun argString(key: String): String = args[key]?.toString().orEmpty()

        return when (resolvedName) {
            "exec" -> "执行: ${truncate(argString("command"), 60)}"
            "process" -> "进程: ${argString("action")}"
            "read" -> "读取: ${basename(argString("path"))}"
            "write" -> "写入: ${basename(argString("path"))}"
            "edit" -> "编辑: ${basename(argString("path"))}"
            "apply_patch" -> "应用补丁"
            "web_fetch" -> "抓取: ${truncate(argString("url"), 50)}"
            "web_search" -> "搜索: ${truncate(argString("query"), 40)}"
            "image" -> "分析图片"
            "pdf" -> "分析 PDF"
            "tts" -> "文字转语音"
            "message" -> "发送消息: ${argString("action").ifBlank { "send" }}"
            "sessions_send" -> "发送到会话: ${truncate(argString("target"), 30)}"
            "sessions_spawn" -> "创建会话"
            "sessions_list" -> "列出会话"
            "sessions_history" -> "获取历史记录"
            "nodes" -> "设备: ${argString("action")}"
            "cron" -> "定时任务: ${argString("action")}"
            "memory_search" -> "搜索记忆: ${truncate(argString("query"), 30)}"
            "memory_get" -> "读取记忆: ${basename(argString("path"))}"
            "gateway" -> "网关: ${argString("action")}"
            "browser" -> "浏览器: ${argString("action")}"
            "canvas" -> "画布绘制"
            "agents_list" -> "列出代理"
            "subagents" -> "子代理: ${argString("action")}"
            else -> resolvedFallback ?: resolvedName
        }
    }

    private fun finishRunWithError(message: String) {
        _errorMessage.value = message
        stopRunLocally(emitReplyFinished = false)
    }

    private suspend fun requestAbortRun(sessionKey: String, runId: String?) {
        runCatching {
            sendRequest(NodeFrame.request("chat.abort", buildMap {
                put("sessionKey", sessionKey)
                if (!runId.isNullOrBlank()) put("runId", runId)
            }))
        }
    }

    private fun clearTransientRunState() {
        deltaFlushJob?.cancel()
        synchronized(roundDeltaBuffer) { roundDeltaBuffer.clear() }
        synchronized(totalAgentText) { totalAgentText.clear() }
        _streamingToolChain.value = StreamingToolChain()
    }

    private fun normalizeSessionMutationError(message: String, action: String): String {
        if (message.contains("missing scope: operator.admin", ignoreCase = true)) {
            return "Current gateway token is not granted operator.admin, so remote sessions cannot be $action."
        }
        if (message.contains("webchat clients cannot", ignoreCase = true)) {
            return "The current gateway client identity is not allowed to mutate remote sessions."
        }
        if (message.contains("No session found", ignoreCase = true)) {
            return "The session no longer exists on the gateway."
        }
        return message.ifBlank {
            when (action) {
                "create" -> "Failed to create session"
                "delete" -> "Failed to delete session"
                else -> "Failed to update session"
            }
        }
    }

    private fun normalizeAdminConnectError(message: String): String {
        if (message.contains("missing scope: operator.admin", ignoreCase = true)) {
            return "Current gateway token is not granted operator.admin, so remote sessions cannot be created or deleted."
        }
        if (message.contains("webchat clients cannot", ignoreCase = true)) {
            return "The current gateway client identity is not allowed to mutate remote sessions."
        }
        return message.ifBlank { "Failed to authorize remote session mutation." }
    }

    private suspend fun sendRequest(request: NodeFrame, timeoutMs: Long = 15_000L): NodeFrame {
        ensureSocketOpen()
        val deferred = CompletableDeferred<NodeFrame>()
        pendingRequests[request.id!!] = deferred
        webSocket?.send(request.encode())
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingRequests.remove(request.id)
        }
    }

    private suspend fun ensureConnected() {
        if (_connectionState.value) return
        connect()
        withTimeoutOrNull(5_000L) {
            _connectionState.first { it }
        } ?: throw IllegalStateException("Not connected to gateway")
    }

    private fun ensureSocketOpen() {
        if (!isSocketConnected || webSocket == null) throw IllegalStateException("WebSocket not connected")
    }

    private fun handleDisconnect(socket: WebSocket?, reason: String) {
        if (socket != null && socket !== webSocket) return
        val disconnectedWhileGenerating = _isGenerating.value
        isSocketConnecting = false
        isSocketConnected = false
        webSocket = null
        _connectionState.value = false
        if (disconnectedWhileGenerating && !hasAvailableNetwork()) {
            markActiveUserMessagePendingRetry()
            stopRunLocally(emitReplyFinished = false)
        } else {
            clearTransientRunState()
        }
        val normalizedReason = reason.ifBlank { "WebSocket disconnected" }
        val isRecoverable = shouldReconnect && isRecoverableDisconnect(normalizedReason)
        _isReconnecting.value = isRecoverable
        if (isRecoverable) {
            _errorMessage.value = null
        } else if (normalizedReason.isNotBlank()) {
            _errorMessage.value = normalizedReason
        }
        pendingRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(
                IllegalStateException(normalizedReason)
            )
        }
        pendingRequests.clear()
        if (shouldReconnect) {
            scope.launch {
                val delayMs = min(
                    (AppConstants.WS_RECONNECT_BASE_MS * AppConstants.WS_RECONNECT_MULTIPLIER.pow(
                        reconnectAttempt.toDouble()
                    )).toLong(), AppConstants.WS_RECONNECT_CAP_MS
                )
                reconnectAttempt++
                delay(delayMs)
                if (shouldReconnect) {
                    openSocket()
                }
            }
        }
    }

    private fun isRecoverableDisconnect(reason: String): Boolean {
        return when {
            reason.equals("Gateway disconnected", ignoreCase = true) -> true
            reason.equals("Socket closed", ignoreCase = true) -> true
            reason.equals("WebSocket disconnected", ignoreCase = true) -> true
            reason.equals("WebSocket failure", ignoreCase = true) -> true
            reason.equals("Disconnected", ignoreCase = true) -> true
            reason.contains("timeout", ignoreCase = true) -> true
            reason.contains("canceled", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun extractText(content: Any?): String {
        return when (content) {
            is String -> content
            is List<*> -> content.mapNotNull { block ->
                @Suppress("UNCHECKED_CAST")
                val map = block as? Map<String, Any?>
                when {
                    map?.get("type") == "text" -> map["text"] as? String
                    block is String -> block
                    else -> null
                }
            }.joinToString("\n")

            is JSONArray -> buildList {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i)
                    if (item?.optString("type") == "text") add(item.optString("text"))
                }
            }.joinToString("\n")

            else -> content?.toString().orEmpty()
        }
    }

    private fun extractToolCalls(content: Any?): List<ToolCallUiModel> {
        val blocks: List<Any?> = when (content) {
            is List<*> -> content
            is JSONArray -> buildList {
                for (index in 0 until content.length()) {
                    add(content.opt(index))
                }
            }

            else -> emptyList()
        }

        return blocks.mapNotNull { raw ->
            val block = normalizeToolCallBlock(raw) ?: return@mapNotNull null
            buildToolCallUiModel(
                block = block,
                fallbackText = block["text"]?.toString(),
                completed = true
            )
        }
    }

    private fun normalizeToolCallBlock(raw: Any?): Map<String, Any?>? {
        val block = when (raw) {
            is Map<*, *> -> raw
            is JSONObject -> raw.keys().asSequence().associateWith { key -> raw.opt(key) }
            else -> return null
        }
        val type = block["type"]?.toString()?.trim()?.lowercase().orEmpty()
        val hasToolShape =
            block["name"] != null && (block["arguments"] != null || block["input"] != null)
        if (type !in setOf("toolcall", "tool_call", "tooluse", "tool_use") && !hasToolShape) {
            return null
        }
        return block.entries.associate { (key, value) -> key.toString() to value }
    }

    private fun normalizeArgs(raw: Any?): Map<String, Any?> {
        return when (raw) {
            is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
            is JSONObject -> raw.keys().asSequence().associateWith { key -> raw.opt(key) }
            else -> emptyMap()
        }
    }

    private fun extractToolArgs(block: Map<String, Any?>): Map<String, Any?> {
        return normalizeArgs(
            block["args"]
                ?: block["arguments"]
                ?: block["input"]
        )
    }

    private fun buildToolCallUiModel(
        block: Map<String, Any?>,
        fallbackName: String? = null,
        fallbackToolCallId: String? = null,
        fallbackText: String? = null,
        completed: Boolean,
        previousItem: ToolCallUiModel? = null
    ): ToolCallUiModel {
        val name = block["name"]?.toString().orEmpty().ifBlank {
            fallbackName?.takeIf { it.isNotBlank() }
                ?: previousItem?.name?.takeIf { it.isNotBlank() }
                ?: "tool"
        }
        val resolvedText = fallbackText?.takeIf { it.isNotBlank() }
        val incomingArgs = extractToolArgs(block)
        val args = incomingArgs.takeIf { it.isNotEmpty() } ?: previousItem?.lastArgs.orEmpty()
        val description = if (args.isNotEmpty() || !resolvedText.isNullOrBlank()) {
            describeToolCall(name, args, fallback = resolvedText)
        } else {
            previousItem?.description ?: describeToolCall(name, emptyMap())
        }
        return ToolCallUiModel(
            toolCallId = block["id"]?.toString().orEmpty().ifBlank {
                fallbackToolCallId?.takeIf { it.isNotBlank() }
                    ?: previousItem?.toolCallId?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
            },
            name = name,
            description = description,
            iconResId = resolveToolIcon(name),
            completed = completed || previousItem?.completed == true,
            lastArgs = args
        )
    }

    private fun extractSegments(content: Any?): List<ContentSegment> {
        val blocks: List<Any?> = when (content) {
            is List<*> -> content
            is JSONArray -> buildList {
                for (i in 0 until content.length()) add(content.opt(i))
            }

            is String -> return if (content.isNotBlank()) listOf(ContentSegment.Text(content)) else emptyList()
            else -> return emptyList()
        }

        val segments = mutableListOf<ContentSegment>()
        val pendingText = StringBuilder()
        val pendingTools = mutableListOf<ToolCallUiModel>()

        fun flushText() {
            val text = pendingText.toString().trim()
            if (text.isNotEmpty()) segments.add(ContentSegment.Text(text))
            pendingText.clear()
        }

        fun flushTools() {
            if (pendingTools.isNotEmpty()) {
                segments.add(ContentSegment.Tools(pendingTools.toList()))
                pendingTools.clear()
            }
        }

        for (raw in blocks) {
            val block = when (raw) {
                is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
                is JSONObject -> raw.keys().asSequence().associateWith { key -> raw.opt(key) }
                is String -> {
                    if (pendingTools.isNotEmpty()) flushTools()
                    if (pendingText.isNotEmpty()) pendingText.append("\n")
                    pendingText.append(raw)
                    continue
                }

                else -> continue
            }

            val type = block["type"]?.toString()?.trim()?.lowercase().orEmpty()
            if (type == "text") {
                if (pendingTools.isNotEmpty()) flushTools()
                val text = block["text"]?.toString().orEmpty()
                if (text.isNotBlank()) {
                    if (pendingText.isNotEmpty()) pendingText.append("\n")
                    pendingText.append(text)
                }
            } else if (normalizeToolCallBlock(raw) != null) {
                if (pendingText.isNotEmpty()) flushText()
                val toolBlock = normalizeToolCallBlock(raw)!!
                pendingTools.add(
                    buildToolCallUiModel(
                        block = toolBlock,
                        fallbackText = toolBlock["text"]?.toString(),
                        completed = true
                    )
                )
            }
        }
        flushText()
        flushTools()
        return segments
    }

    private fun upsertSessionTitle(text: String) {
        val currentSessionKey = _currentSessionKey.value ?: return
        val sessions = _sessions.value.toMutableList()
        val index = sessions.indexOfFirst { it.sessionKey == currentSessionKey }
        if (index < 0) return
        val existing = sessions[index]
        val nextTitle = if (isGenericSessionTitle(existing.title, existing.sessionKey)) {
            formatSessionTitle(text)
        } else existing.title
        sessions[index] = existing.copy(title = nextTitle, updatedAt = System.currentTimeMillis())
        _sessions.value = sessions
    }

    private fun updateSessionTitleFromMessages(
        sessionKey: String,
        messages: List<SyncedMessage>
    ) {
        val sessions = _sessions.value.toMutableList()
        val index = sessions.indexOfFirst { it.sessionKey == sessionKey }
        if (index < 0) return
        val existing = sessions[index]
        val nextTitle = deriveSessionTitle(existing, messages)
        if (existing.title == nextTitle) return
        sessions[index] = existing.copy(title = nextTitle)
        _sessions.value = sessions
    }

    private fun deriveSessionTitle(
        session: SyncedSession,
        messages: List<SyncedMessage>
    ): String {
        val firstUserMessage = messages
            .asSequence()
            .filter { it.role.equals("user", ignoreCase = true) }
            .map { it.content.trim() }
            .firstOrNull { it.isNotEmpty() }
        return firstUserMessage?.let(::formatSessionTitle) ?: session.title
    }

    private fun formatSessionTitle(text: String): String {
        val normalized = text.trim()
        return if (normalized.length > 30) {
            "${normalized.take(30)}..."
        } else {
            normalized
        }
    }

    private fun createRemoteSessionKey(): String {
        return "agent:main:app-${UUID.randomUUID().toString().replace("-", "").take(8)}"
    }

    private fun isDraftSession(sessionKey: String): Boolean {
        return draftSessionKeys.contains(sessionKey)
    }

    private fun isGenericSessionTitle(title: String, sessionKey: String): Boolean {
        val normalized = title.trim()
        return normalized.isEmpty() ||
                normalized.equals("OpenClaw Chat", ignoreCase = true) ||
                normalized.equals(NEW_CHAT_TITLE, ignoreCase = true) ||
                normalized.equals("Untitled", ignoreCase = true) ||
                normalized.equals(sessionKey, ignoreCase = true)
    }

    private fun List<SyncedMessage>.describeMessages(): String {
        return joinToString(prefix = "[", postfix = "]", separator = ",") { it.describeMessage() }
    }

    private fun SyncedMessage.describeMessage(): String {
        return "msg(id=$id,role=$role,status=$sendStatus,hash=${content.stableHash()},len=${content.length},ts=$createdAt,segments=${segments.describeSegments()})"
    }

    private fun List<ContentSegment>.describeSegments(): String {
        return joinToString(prefix = "[", postfix = "]", separator = ",") { segment ->
            when (segment) {
                is ContentSegment.Text -> "text#${segment.text.stableHash()}:${segment.text.length}"
                is ContentSegment.Tools -> {
                    val toolSummary = segment.tools.joinToString(
                        prefix = "[",
                        postfix = "]",
                        separator = ","
                    ) { tool ->
                        "${tool.toolCallId}:${tool.name}:${tool.completed}"
                    }
                    "tools$toolSummary"
                }
            }
        }
    }

    private fun StreamingToolChain.describeChain(): String {
        val entrySummary =
            entries.joinToString(prefix = "[", postfix = "]", separator = ",") { entry ->
                when (entry) {
                    is TimelineEntry.Text -> "text#${entry.text.stableHash()}:${entry.text.length}"
                    is TimelineEntry.Tool -> "${entry.tool.toolCallId}:${entry.tool.name}:${entry.tool.completed}"
                }
            }
        return "active=$isActive,pendingHash=${pendingText.orEmpty().stableHash()}," +
                "pendingLen=${pendingText?.length ?: 0},entries=$entrySummary"
    }

    private fun String.stableHash(): String = hashCode().toUInt().toString(16)

    companion object {
        private const val TAG = "SyncedChatWsManager"
        private const val TRACE_TAG = "ShellChatTrace"
        const val DEFAULT_SESSION_KEY = "agent:main:main"
        private const val NEW_CHAT_TITLE = "新对话"
        private const val KIND_LOCAL_PENDING_PERSISTENT = "local_pending_persistent"
        private const val INITIAL_HISTORY_LIMIT = 50
        private const val PAGE_SIZE = 50
        private const val RATE_LIMIT_FALLBACK_TEXT = "提问过于频繁，请稍后再试～"
        internal fun resolveToolIcon(name: String): Int {
            return when (name.trim()) {
                "write" -> R.drawable.ic_tool_write
                "edit" -> R.drawable.ic_tool_edit
                "read" -> R.drawable.ic_tool_read
                "memory_get" -> R.drawable.ic_tool_memory_get
                "sessions_list" -> R.drawable.ic_tool_sessions_list
                "sessions_history" -> R.drawable.ic_tool_sessions_history
                "agents_list" -> R.drawable.ic_tool_agents_list
                "browser", "web_fetch" -> R.drawable.ic_tool_browse
                "web_search" -> R.drawable.ic_tool_web_search
                "image" -> R.drawable.ic_tool_image
                "pdf" -> R.drawable.ic_tool_pdf
                "memory_search" -> R.drawable.ic_tool_memory_search
                else -> R.drawable.ic_tool_exe
            }
        }
    }
}
