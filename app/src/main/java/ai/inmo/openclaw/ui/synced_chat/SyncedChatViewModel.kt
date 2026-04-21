package ai.inmo.openclaw.ui.synced_chat

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.core_common.utils.context.AppProvider
import ai.inmo.openclaw.R
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.GatewayState
import ai.inmo.openclaw.domain.model.StreamingToolChain
import ai.inmo.openclaw.domain.model.TimelineEntry
import ai.inmo.openclaw.ui.chat.ChatMessageItem
import ai.inmo.openclaw.ui.chat.ChatScreenState
import ai.inmo.openclaw.ui.chat.ChatSessionItem
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class SyncedChatViewModel : BaseViewModel() {
    private val manager = AppGraph.syncedChatWsManager

    val state: StateFlow<ChatScreenState> = combine(
        AppGraph.gatewayManager.state,
        manager.connectionState,
        manager.sessions,
        manager.currentSessionKey,
        manager.messages,
        manager.isLoading,
        manager.isGenerating,
        manager.generatingPhase,
        manager.errorMessage,
        manager.streamingToolChain,
        manager.activeAssistantMessageId
    ) { values ->
        val gateway = values[0] as GatewayState
        val connected = values[1] as Boolean
        val sessions = values[2] as List<ai.inmo.openclaw.domain.model.SyncedSession>
        val selectedSession = values[3] as String?
        val messages = values[4] as List<ai.inmo.openclaw.domain.model.SyncedMessage>
        val isLoading = values[5] as Boolean
        val isGenerating = values[6] as Boolean
        val generatingPhase = values[7] as ai.inmo.openclaw.domain.model.GeneratingPhase
        val error = values[8] as String?
        val chain = values[9] as StreamingToolChain
        val activeId = values[10] as String?

        val items = ChatMessageItem.buildSyncedList(messages).toMutableList()

        if (isGenerating) {
            if (chain.isActive && chain.entries.isNotEmpty()) {
                val streamEntries = chain.entries.toMutableList()
                if (!chain.pendingText.isNullOrBlank()) {
                    streamEntries.add(TimelineEntry.Text(chain.pendingText))
                }
                val createdAt = System.currentTimeMillis()
                streamEntries.forEachIndexed { index, entry ->
                    val isFirst = index == 0
                    val isLast = index == streamEntries.lastIndex
                    when (entry) {
                        is TimelineEntry.Text -> items.add(
                            ChatMessageItem.ToolTextMessageItem(
                                id = "streaming-tool-text:$index",
                                parentMessageId = activeId ?: "streaming",
                                content = entry.text,
                                createdAt = createdAt,
                                isFirstInChain = isFirst,
                                isLastInChain = isLast,
                                isStreaming = true
                            )
                        )
                        is TimelineEntry.Tool -> items.add(
                            ChatMessageItem.ToolCallMessageItem(
                                id = "streaming-tool-call:$index",
                                parentMessageId = activeId ?: "streaming",
                                tool = entry.tool,
                                createdAt = createdAt,
                                isFirstInChain = isFirst,
                                isLastInChain = isLast,
                                isStreaming = true
                            )
                        )
                    }
                }
            } else if (!chain.isActive && !chain.pendingText.isNullOrBlank()) {
                items.add(
                    ChatMessageItem.AssistantMessageItem(
                        id = "streaming-assistant",
                        content = chain.pendingText,
                        createdAt = System.currentTimeMillis(),
                        isStreaming = true
                    )
                )
            }
        }

        ChatScreenState(
            sessions = sessions.map(ChatSessionItem::fromSynced),
            selectedSessionId = selectedSession,
            messages = items,
            isGenerating = isGenerating,
            isLoading = isLoading,
            canSend = gateway.isRunning && connected,
            errorMessage = error,
            connectionMessage = connectionMessage(gateway.isRunning, connected),
            generatingPhase = generatingPhase
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatScreenState()
    )

    fun start() {
        launchIo { manager.connect() }
    }

    fun createSession() {
        launchIo { manager.resetCurrentSession() }
    }

    fun sendMessage(text: String) {
        launchIo { manager.sendMessage(text) }
    }

    fun switchSession(sessionId: String, abortCurrent: Boolean) {
        launchIo {
            if (abortCurrent) manager.abortRun(clearLocalState = true)
            manager.switchSession(sessionId)
        }
    }

    fun deleteSession(sessionId: String) {
        launchIo { manager.deleteSession(sessionId) }
    }

    fun stopGeneration() {
        launchIo { manager.abortRun(clearLocalState = true) }
    }

    fun dismissError() {
        manager.dismissError()
    }

    fun shouldConfirmSwitch(targetSessionId: String): Boolean {
        val current = state.value
        return current.isGenerating && current.selectedSessionId != null && current.selectedSessionId != targetSessionId
    }

    private fun connectionMessage(isGatewayRunning: Boolean, connected: Boolean): String? {
        val context = AppProvider.get()
        return when {
            !isGatewayRunning -> context.getString(R.string.chat_gateway_not_running)
            !connected -> context.getString(R.string.chat_connecting)
            else -> null
        }
    }
}
