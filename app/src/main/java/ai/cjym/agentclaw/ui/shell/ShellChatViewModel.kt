package ai.cjym.agentclaw.ui.shell

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.core_common.utils.Logger
import ai.inmo.core_common.utils.context.AppProvider
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.StreamingToolChain
import ai.cjym.agentclaw.domain.model.SyncedMessage
import ai.cjym.agentclaw.domain.model.TimelineEntry
import ai.cjym.agentclaw.ui.chat.ChatMarkdownProvider
import ai.cjym.agentclaw.ui.chat.ChatMessageItem
import ai.cjym.agentclaw.ui.chat.ChatScreenState
import ai.cjym.agentclaw.ui.chat.ChatSessionItem
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShellChatViewModel : BaseViewModel() {
    private val manager = AppGraph.syncedChatWsManager
    private val artifactExportRepository = AppGraph.artifactExportRepository
    private val _exportMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val exportMessages = _exportMessages.asSharedFlow()
    private val _sessionHasExportArtifacts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _sessionKeepExportButtonVisible = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private companion object {
        private const val TRACE_TAG = "ShellChatTrace"
        private const val ENABLE_TRACE_LOG = false
        private const val TRANSIENT_ASSISTANT_ID = "__transient_assistant__"
        private const val TRANSIENT_TOOL_ID = "__transient_tool__"
        private const val TRANSIENT_PARENT_ID = "__transient_run__"
        private const val MAX_CACHED_SESSIONS = 8
        private const val PRELOAD_BUBBLE_WIDTH_RATIO = 0.78f
        private const val PRELOAD_HORIZONTAL_INSET_DP = 40f
    }

    private data class CachedMessageItems(
        val messages: List<SyncedMessage>,
        val items: List<ChatMessageItem>
    )

    private val messageItemsBySession = linkedMapOf<String, CachedMessageItems>()

    init {
        viewModelScope.launch {
            var wasGenerating = manager.isGenerating.value
            manager.isGenerating
                .collect { isGenerating ->
                    if (!isGenerating && wasGenerating) {
                        refreshCurrentSessionExportState()
                    }
                    wasGenerating = isGenerating
                }
        }
    }

    private fun buildMessageItems(
        sessionKey: String?,
        messages: List<SyncedMessage>
    ): List<ChatMessageItem> {
        if (sessionKey.isNullOrBlank()) {
            return ChatMessageItem.buildSyncedList(messages)
        }
        val cached = messageItemsBySession[sessionKey]
        if (cached != null && cached.messages == messages) {
            return cached.items
        }

        val oldMessages = cached?.messages.orEmpty()
        val oldItems = cached?.items.orEmpty()
        val result = ChatMessageItem.buildSyncedList(messages)

        // Reuse unchanged item references to help DiffUtil short-circuit.
        val optimized = if (oldMessages.isEmpty()) {
            result
        } else {
            val oldSyncedById = oldMessages.associateBy { it.id }
            val newSyncedById = messages.associateBy { it.id }
            val oldItemsById = oldItems.associateBy { it.id }
            result.map { newItem ->
                val sourceId = when (newItem) {
                    is ChatMessageItem.ToolTextMessageItem -> newItem.parentMessageId
                    is ChatMessageItem.ToolCallMessageItem -> newItem.parentMessageId
                    else -> newItem.id
                }
                val oldSynced = oldSyncedById[sourceId]
                if (oldSynced != null && oldSynced == newSyncedById[sourceId]) {
                    oldItemsById[newItem.id] ?: newItem
                } else {
                    newItem
                }
            }
        }

        messageItemsBySession[sessionKey] = CachedMessageItems(
            messages = messages,
            items = optimized
        )
        while (messageItemsBySession.size > MAX_CACHED_SESSIONS) {
            val oldestKey = messageItemsBySession.entries.firstOrNull()?.key ?: break
            messageItemsBySession.remove(oldestKey)
        }
        val appContext = AppProvider.get()
        ChatMarkdownProvider.preload(appContext, optimized)
        val displayMetrics = appContext.resources.displayMetrics
        val availableWidth = (
                displayMetrics.widthPixels -
                        (displayMetrics.density * PRELOAD_HORIZONTAL_INSET_DP * 2f).toInt()
                ).coerceAtLeast(240)
        val bubbleWidthPx = (availableWidth * PRELOAD_BUBBLE_WIDTH_RATIO).toInt().coerceAtLeast(240)
        val assistantTextSizePx = displayMetrics.scaledDensity * 18f
        val assistantLineSpacingPx = displayMetrics.scaledDensity * 18f
        ChatMarkdownProvider.preloadPrecomputed(
            context = appContext,
            items = optimized,
            bubbleWidthPx = bubbleWidthPx,
            textSizePx = assistantTextSizePx,
            lineSpacingExtraPx = assistantLineSpacingPx
        )
        return optimized
    }

    fun bindSessionEntryMode(sessionId: String, entryMode: ChatEntryMode) {
        val trimmedSessionId = sessionId.trim()
        if (trimmedSessionId.isBlank()) return
        manager.bindSessionEntryMode(trimmedSessionId, entryMode)
        messageItemsBySession.remove(trimmedSessionId)
    }

    fun removeSessionEntryMode(sessionId: String) {
        val trimmedSessionId = sessionId.trim()
        if (trimmedSessionId.isBlank()) return
        manager.removeSessionEntryMode(trimmedSessionId)
        messageItemsBySession.remove(trimmedSessionId)
    }

    private fun appendTransientItems(
        items: List<ChatMessageItem>,
        messages: List<SyncedMessage>,
        chain: StreamingToolChain,
        isGenerating: Boolean
    ): List<ChatMessageItem> {
        if (!isGenerating) return items
        val createdAt = (messages.lastOrNull()?.createdAt ?: System.currentTimeMillis()) + 1
        val transientItems = buildTransientItems(
            chain = chain,
            createdAt = createdAt
        )
        if (transientItems.isEmpty()) return items
        if (items.endsWithEquivalentItems(transientItems)) {
            Logger.d(
                TRACE_TAG,
                "transientSuppressed historyAlreadyContainsTail session=${manager.currentSessionKey.value}, " +
                        "history=${items.describeItems()}, transient=${transientItems.describeItems()}"
            )
            return items
        }

        return items + transientItems
    }

    private fun buildTransientItems(
        chain: StreamingToolChain,
        createdAt: Long
    ): List<ChatMessageItem> {
        val pendingText = chain.pendingText.orEmpty()
        if (!chain.isActive && pendingText.isBlank()) {
            return emptyList()
        }

        return buildList {
            if (chain.isActive && chain.entries.isNotEmpty()) {
                val streamEntries = chain.entries.toMutableList()
                if (pendingText.isNotBlank()) {
                    streamEntries.add(TimelineEntry.Text(pendingText))
                }
                streamEntries.forEachIndexed { index, entry ->
                    val isFirst = index == 0
                    val isLast = index == streamEntries.lastIndex
                    when (entry) {
                        is TimelineEntry.Text -> add(
                            ChatMessageItem.ToolTextMessageItem(
                                id = "$TRANSIENT_TOOL_ID:text:$index",
                                parentMessageId = TRANSIENT_PARENT_ID,
                                content = entry.text,
                                createdAt = createdAt,
                                isFirstInChain = isFirst,
                                isLastInChain = isLast,
                                isStreaming = true
                            )
                        )
                        is TimelineEntry.Tool -> add(
                            ChatMessageItem.ToolCallMessageItem(
                                id = "$TRANSIENT_TOOL_ID:tool:$index",
                                parentMessageId = TRANSIENT_PARENT_ID,
                                tool = entry.tool,
                                createdAt = createdAt,
                                isFirstInChain = isFirst,
                                isLastInChain = isLast,
                                isStreaming = true
                            )
                        )
                    }
                }
            } else if (!chain.isActive && pendingText.isNotBlank()) {
                add(
                    ChatMessageItem.AssistantMessageItem(
                        id = TRANSIENT_ASSISTANT_ID,
                        content = pendingText,
                        createdAt = createdAt,
                        isStreaming = true
                    )
                )
            }
        }
    }

    val state: StateFlow<ChatScreenState> = combine(
        manager.connectionState,
        manager.sessions,
        manager.currentSessionKey,
        manager.messages,
        manager.streamingToolChain,
        manager.isLoading,
        manager.isReconnecting,
        manager.isGenerating,
        manager.generatingPhase,
        manager.errorMessage,
        _sessionHasExportArtifacts,
        _sessionKeepExportButtonVisible
    ) { values ->
        val connected = values[0] as Boolean
        val sessions = values[1] as List<ai.cjym.agentclaw.domain.model.SyncedSession>
        val selectedSession = values[2] as String?
        val messages = values[3] as List<ai.cjym.agentclaw.domain.model.SyncedMessage>
        val chain = values[4] as StreamingToolChain
        val isLoading = values[5] as Boolean
        val isReconnecting = values[6] as Boolean
        val isGenerating = values[7] as Boolean
        val generatingPhase = values[8] as ai.cjym.agentclaw.domain.model.GeneratingPhase
        val error = values[9] as String?
        val sessionHasExportArtifacts = values[10] as Map<String, Boolean>
        val sessionKeepExportButtonVisible = values[11] as Map<String, Boolean>

        val historyItems = buildMessageItems(selectedSession, messages)
        val allItems = appendTransientItems(
            items = historyItems,
            messages = messages,
            chain = chain,
            isGenerating = isGenerating
        )
        if (ENABLE_TRACE_LOG) {
            Logger.d(
                TRACE_TAG,
                "vmState session=$selectedSession, " +
                        "generating=$isGenerating, phase=$generatingPhase, " +
                        "history=${historyItems.describeItems()}, " +
                        "chain=${chain.describe()}, " +
                        "all=${allItems.describeItems()}"
            )
        }

        ChatScreenState(
            sessions = sessions.map(ChatSessionItem::fromSynced),
            selectedSessionId = selectedSession,
            messages = allItems,
            isGenerating = isGenerating,
            isLoading = isLoading,
            canSend = true,
            errorMessage = if (isReconnecting) null else error,
            connectionMessage = connectionMessage(connected, isReconnecting),
            generatingPhase = generatingPhase,
            showExportSessionFilesButton = !isGenerating &&
                    messages.isNotEmpty() &&
                    (
                            sessionHasExportArtifacts[selectedSession] == true ||
                                    sessionKeepExportButtonVisible[selectedSession] == true
                            )
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatScreenState()
        )

    fun start() {
        launchIo {
            manager.connect()
            manager.loadSessions()
            ensureCurrentSessionTracked()
        }
    }

    fun createSession(abortCurrent: Boolean = false) {
        launchIo {
            if (abortCurrent) manager.abortRun(clearLocalState = true)
            manager.resetCurrentSession()
            ensureCurrentSessionTracked()
        }
    }

    suspend fun createPersistentSession(abortCurrent: Boolean = false): String {
        if (abortCurrent) manager.abortRun(clearLocalState = true)
        val sessionId = manager.createPersistentSession()
        ensureCurrentSessionTracked()
        return sessionId
    }

    suspend fun createPersistentSessionWithPresetConversation(
        userPrompt: String,
        assistantReply: String,
        abortCurrent: Boolean = false
    ): String {
        if (abortCurrent) manager.abortRun(clearLocalState = true)
        val sessionId = manager.createPersistentSessionWithPresetConversation(
            userPrompt = userPrompt,
            assistantReply = assistantReply
        )
        ensureCurrentSessionTracked()
        return sessionId
    }

    fun sendMessage(text: String, imageBase64: String? = null) {
        launchIo {
            // 用户发起新问题后，重置”下载按钮保持可见”状态，避免按钮持续残留显示。
            updateCurrentSessionKeepExportButtonVisible(keepVisible = false)
            manager.sendMessage(text, imageBase64)
        }
    }

    fun retryMessage(messageId: String) {
        launchIo { manager.retryMessage(messageId) }
    }

    fun switchSession(sessionId: String, abortCurrent: Boolean) {
        launchIo {
            if (abortCurrent) manager.abortRun(clearLocalState = true)
            manager.switchSession(sessionId)
            ensureCurrentSessionTracked()
        }
    }

    fun deleteSession(sessionId: String) {
        launchIo {
            manager.deleteSession(sessionId)
            _sessionHasExportArtifacts.emit(_sessionHasExportArtifacts.value - sessionId)
            _sessionKeepExportButtonVisible.emit(_sessionKeepExportButtonVisible.value - sessionId)
            messageItemsBySession.remove(sessionId)
        }
    }

    fun rememberExportButtonVisibleForCurrentSession() {
        launchIo {
            updateCurrentSessionKeepExportButtonVisible(keepVisible = true)
        }
    }

    fun stopGeneration() {
        launchIo { manager.abortRun(clearLocalState = true) }
    }

    fun dismissError() {
        manager.dismissError()
    }

    fun exportCurrentSessionArtifacts() {
        launchIo {
            exportWorkspaceArtifacts(trigger = "session")
        }
    }

    fun exportArtifactsByMessage(messageId: String) {
        launchIo {
            exportWorkspaceArtifacts(trigger = "message:$messageId")
        }
    }

    fun loadMore() {
        launchIo {
            if (state.value.isGenerating) {
                Logger.d(
                    TRACE_TAG,
                    "loadMoreSkipped session=${manager.currentSessionKey.value}, reason=vm_generating"
                )
                return@launchIo
            }
            val sessionKey = manager.currentSessionKey.value ?: return@launchIo
            val beforeIndex = manager.messages.value.firstOrNull()?.messageIndex ?: -1
            manager.loadMoreHistory(sessionKey, beforeIndex)
        }
    }

    private suspend fun exportWorkspaceArtifacts(trigger: String) {
        val context = AppProvider.get()
        val artifacts = artifactExportRepository.collectNewWorkspaceArtifacts()
        if (artifacts.isEmpty()) {
            updateCurrentSessionExportState(hasPendingArtifacts = false)
            _exportMessages.emit(context.getString(R.string.chat_export_no_new_files))
            return
        }

        updateCurrentSessionExportState(hasPendingArtifacts = true)

        Logger.d(
            TRACE_TAG,
            "exportWorkspaceArtifacts trigger=$trigger, count=${artifacts.size}, paths=${artifacts.map { it.originalPath }}"
        )

        val result = artifactExportRepository.exportArtifacts(artifacts)
        val successMap = result.itemResults
            .associateBy({ it.artifactId }, { it.success })
        val exportedArtifacts = artifacts.filter { successMap[it.id] == true }

        if (exportedArtifacts.isNotEmpty()) {
            artifactExportRepository.markWorkspaceExported(exportedArtifacts)
        }

        val hasPendingArtifacts = artifactExportRepository.collectNewWorkspaceArtifacts().isNotEmpty()
        updateCurrentSessionExportState(hasPendingArtifacts = hasPendingArtifacts)

        val toast = when {
            result.successCount > 0 && result.failureCount == 0 -> {
                context.getString(
                    R.string.chat_export_success_to_downloads,
                    result.successCount
                )
            }
            result.successCount > 0 -> {
                context.getString(
                    R.string.chat_export_result_partial,
                    result.successCount,
                    result.failureCount
                )
            }
            else -> {
                context.getString(R.string.chat_export_failed_retry)
            }
        }
        _exportMessages.emit(toast)
    }

    private suspend fun refreshCurrentSessionExportState() {
        var hasPending = artifactExportRepository.collectNewWorkspaceArtifacts().isNotEmpty()

        if (!hasPending) {
            val lastAssistantContent = manager.messages.value
                .lastOrNull { it.role.equals("assistant", ignoreCase = true) }
                ?.content?.trim().orEmpty()
            val htmlContent = extractHtmlFromContent(lastAssistantContent)
            if (htmlContent != null) {
                hasPending = withContext(Dispatchers.IO) {
                    val title = extractHtmlTitle(htmlContent)
                    val baseName = title?.takeIf { it.isNotBlank() } ?: "document"
                    val fileName = sanitizeArtifactFileName("$baseName.html")
                    artifactExportRepository.injectAndExportArtifact(
                        fileName = fileName,
                        bytes = htmlContent.toByteArray(Charsets.UTF_8),
                        mimeType = "text/html"
                    )
                }
            }
        }

        updateCurrentSessionExportState(hasPendingArtifacts = hasPending)
    }

    private fun extractHtmlFromContent(content: String): String? {
        val trimmed = content.trim()
        val lower = trimmed.lowercase()
        // ```html\n...\n``` 代码围栏
        if (lower.startsWith("```html")) {
            val afterFence = trimmed.drop("```html".length).trimStart('\n', '\r')
            val html = if (afterFence.trimEnd().endsWith("```")) {
                afterFence.trimEnd().dropLast(3).trimEnd()
            } else {
                afterFence
            }
            if (isHtmlContent(html)) return html
        }
        // 裸 HTML
        if (isHtmlContent(trimmed)) return trimmed
        return null
    }

    private fun isHtmlContent(content: String): Boolean {
        if (content.length < 50) return false
        val lower = content.trimStart().lowercase()
        return lower.startsWith("<!doctype html") || lower.startsWith("<html")
    }

    private fun extractHtmlTitle(html: String): String? {
        return Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun sanitizeArtifactFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
    }

    private suspend fun ensureCurrentSessionTracked() {
        val sessionId = manager.currentSessionKey.value
        if (sessionId.isNullOrBlank()) return
        val hasArtifactsMap = _sessionHasExportArtifacts.value
        if (!hasArtifactsMap.containsKey(sessionId)) {
            _sessionHasExportArtifacts.emit(hasArtifactsMap + (sessionId to false))
        }
        val keepVisibleMap = _sessionKeepExportButtonVisible.value
        if (!keepVisibleMap.containsKey(sessionId)) {
            _sessionKeepExportButtonVisible.emit(keepVisibleMap + (sessionId to false))
        }
    }

    private suspend fun updateCurrentSessionExportState(hasPendingArtifacts: Boolean) {
        val sessionId = manager.currentSessionKey.value
        if (sessionId.isNullOrBlank()) return
        val currentMap = _sessionHasExportArtifacts.value
        if (currentMap[sessionId] == hasPendingArtifacts) return
        _sessionHasExportArtifacts.emit(currentMap + (sessionId to hasPendingArtifacts))
    }

    private suspend fun updateCurrentSessionKeepExportButtonVisible(keepVisible: Boolean) {
        val sessionId = manager.currentSessionKey.value
        if (sessionId.isNullOrBlank()) return
        val currentMap = _sessionKeepExportButtonVisible.value
        if (currentMap[sessionId] == keepVisible) return
        _sessionKeepExportButtonVisible.emit(currentMap + (sessionId to keepVisible))
    }

    fun shouldConfirmSwitch(targetSessionId: String): Boolean {
        val current = state.value
        return current.isGenerating &&
                current.selectedSessionId != null &&
                current.selectedSessionId != targetSessionId
    }

    private fun connectionMessage(
        connected: Boolean,
        isReconnecting: Boolean
    ): String? = null

    private fun List<ChatMessageItem>.endsWithEquivalentItems(
        transientItems: List<ChatMessageItem>
    ): Boolean {
        if (transientItems.isEmpty() || size < transientItems.size) return false
        val startIndex = size - transientItems.size
        return transientItems.indices.all { offset ->
            this[startIndex + offset].renderEquivalent(transientItems[offset])
        }
    }

    private fun ChatMessageItem.renderEquivalent(other: ChatMessageItem): Boolean {
        return when {
            this is ChatMessageItem.UserMessageItem && other is ChatMessageItem.UserMessageItem -> {
                content == other.content &&
                        sendStatus == other.sendStatus &&
                        isStreaming == other.isStreaming
            }
            this is ChatMessageItem.AssistantMessageItem && other is ChatMessageItem.AssistantMessageItem -> {
                content == other.content
            }
            this is ChatMessageItem.ToolTextMessageItem && other is ChatMessageItem.ToolTextMessageItem -> {
                content == other.content &&
                        isFirstInChain == other.isFirstInChain &&
                        isLastInChain == other.isLastInChain
            }
            this is ChatMessageItem.ToolCallMessageItem && other is ChatMessageItem.ToolCallMessageItem -> {
                tool.toolCallId == other.tool.toolCallId &&
                        tool.name == other.tool.name &&
                        tool.completed == other.tool.completed &&
                        isFirstInChain == other.isFirstInChain &&
                        isLastInChain == other.isLastInChain
            }
            else -> false
        }
    }

    private fun StreamingToolChain.describe(): String {
        val entrySummary = entries.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { entry ->
            when (entry) {
                is TimelineEntry.Text -> "text#${entry.text.stableHash()}:${entry.text.length}"
                is TimelineEntry.Tool -> "tool#${entry.tool.toolCallId}:${entry.tool.name}:${entry.tool.completed}"
            }
        }
        return "active=$isActive,pendingHash=${pendingText.orEmpty().stableHash()}," +
                "pendingLen=${pendingText?.length ?: 0},entries=$entrySummary"
    }

    private fun List<ChatMessageItem>.describeItems(): String {
        return joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
            when (item) {
                is ChatMessageItem.UserMessageItem -> {
                    "user(id=${item.id},hash=${item.content.stableHash()},len=${item.content.length},stream=${item.isStreaming},status=${item.sendStatus})"
                }
                is ChatMessageItem.AssistantMessageItem -> {
                    "assistant(id=${item.id},hash=${item.content.stableHash()},len=${item.content.length},stream=${item.isStreaming})"
                }
                is ChatMessageItem.ToolTextMessageItem -> {
                    "toolText(id=${item.id},parent=${item.parentMessageId},hash=${item.content.stableHash()},len=${item.content.length},stream=${item.isStreaming})"
                }
                is ChatMessageItem.ToolCallMessageItem -> {
                    "toolCall(id=${item.id},parent=${item.parentMessageId},call=${item.tool.toolCallId},name=${item.tool.name},done=${item.tool.completed},stream=${item.isStreaming})"
                }
            }
        }
    }

    private fun String.stableHash(): String = hashCode().toUInt().toString(16)
}
