package ai.cjym.agentclaw.ui.synced_chat

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.StreamingToolChain
import ai.cjym.agentclaw.domain.model.TimelineEntry
import ai.cjym.agentclaw.ui.chat.ChatMessageItem
import ai.cjym.agentclaw.ui.chat.ChatScreenState
import ai.cjym.agentclaw.ui.chat.ChatSessionItem
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class SyncedChatViewModel : BaseViewModel() {
    private val manager = AppGraph.syncedChatWsManager

    val state: StateFlow<ChatScreenState> = combine(
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
        val connected = values[0] as Boolean
        val sessions = values[1] as List<ai.cjym.agentclaw.domain.model.SyncedSession>
        val selectedSession = values[2] as String?
        val messages = values[3] as List<ai.cjym.agentclaw.domain.model.SyncedMessage>
        val isLoading = values[4] as Boolean
        val isGenerating = values[5] as Boolean
        val generatingPhase = values[6] as ai.cjym.agentclaw.domain.model.GeneratingPhase
        val error = values[7] as String?
        val chain = values[8] as StreamingToolChain
        val activeId = values[9] as String?

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
            canSend = !isGenerating,
            errorMessage = error,
            connectionMessage = null,
            generatingPhase = generatingPhase
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatScreenState()
    )

    fun start() {
        launchIo {
            manager.connect()
            manager.loadSessions()
        }
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
}
