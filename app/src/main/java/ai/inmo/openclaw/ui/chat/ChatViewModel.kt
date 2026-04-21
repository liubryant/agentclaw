package ai.inmo.openclaw.ui.chat

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.data.repository.ChatRepository
import ai.inmo.openclaw.data.repository.ChatService
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.ChatMessage
import ai.inmo.openclaw.domain.model.ChatRole
import ai.inmo.openclaw.domain.model.GeneratingPhase
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

class ChatViewModel : BaseViewModel() {
    private val repository: ChatRepository = AppGraph.chatRepository
    private val chatService: ChatService = AppGraph.chatService

    private val _state = MutableStateFlow(ChatScreenState())
    val state = _state.asStateFlow()

    private var sessionsJob: Job? = null
    private var messagesJob: Job? = null
    private var streamingAssistant: ChatMessageItem.AssistantMessageItem? = null

    init {
        observeSessions()
    }

    fun start() = Unit

    fun createSession() {
        if (_state.value.isGenerating) return
        launchIo {
            val session = repository.createSession()
            switchSession(session.id, abortCurrent = false)
        }
    }

    fun switchSession(sessionId: String, abortCurrent: Boolean) {
        if (_state.value.selectedSessionId == sessionId) return
        launchIo {
            if (abortCurrent) {
                chatService.cancelGeneration()
                clearStreamingState()
            }
            messagesJob?.cancel()
            _state.value = _state.value.copy(selectedSessionId = sessionId, isLoading = true, errorMessage = null)
            messagesJob = viewModelScope.launch(coroutineDispatchers.io) {
                repository.observeMessages(sessionId).collectLatest { messages ->
                    val items = messages.map(ChatMessageItem::fromLocal) + listOfNotNull(streamingAssistant)
                    _state.value = _state.value.copy(
                        selectedSessionId = sessionId,
                        messages = items,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        launchIo {
            repository.deleteSession(sessionId)
            if (_state.value.selectedSessionId == sessionId) {
                clearStreamingState()
                val next = _state.value.sessions.firstOrNull { it.id != sessionId }
                if (next != null) {
                    switchSession(next.id, abortCurrent = false)
                } else {
                    messagesJob?.cancel()
                    _state.value = _state.value.copy(selectedSessionId = null, messages = emptyList())
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _state.value.isGenerating) return
        launchIo {
            val selected = _state.value.selectedSessionId ?: repository.createSession().also { session ->
                switchSession(session.id, abortCurrent = false)
            }.id
            val trimmed = text.trim()
            repository.insertMessage(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = selected,
                    role = ChatRole.USER,
                    content = trimmed,
                    createdAt = System.currentTimeMillis()
                )
            )
            repository.updateSessionTitleFromFirstUserMessage(selected, trimmed)
            val persisted = repository.loadMessages(selected)
            val assistantId = UUID.randomUUID().toString()
            streamingAssistant = ChatMessageItem.AssistantMessageItem(
                id = assistantId,
                content = "",
                createdAt = System.currentTimeMillis(),
                isStreaming = true
            )
            _state.value = _state.value.copy(
                isGenerating = true,
                generatingPhase = GeneratingPhase.THINKING,
                errorMessage = null,
                messages = persisted.map(ChatMessageItem::fromLocal) + listOfNotNull(streamingAssistant)
            )
            try {
                chatService.sendMessageStream(repository.loadMessages(selected)).collect { chunk ->
                    val current = streamingAssistant ?: return@collect
                    val next = current.copy(content = current.content + chunk, isStreaming = true)
                    streamingAssistant = next
                    _state.value = _state.value.copy(
                        messages = repository.loadMessages(selected).map(ChatMessageItem::fromLocal) + listOf(next)
                    )
                }
            } catch (t: Throwable) {
                if (!isCancellation(t)) {
                    _state.value = _state.value.copy(errorMessage = t.message ?: "Failed to send message")
                }
            } finally {
                finalizeAssistant(selected, assistantId)
            }
        }
    }

    fun stopGeneration() {
        chatService.cancelGeneration()
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun observeSessions() {
        sessionsJob?.cancel()
        sessionsJob = viewModelScope.launch(coroutineDispatchers.io) {
            repository.observeSessions().collectLatest { sessions ->
                val items = sessions.map(ChatSessionItem::fromLocal)
                val selected = _state.value.selectedSessionId
                _state.value = _state.value.copy(sessions = items)
                if (selected == null && items.isNotEmpty()) {
                    switchSession(items.first().id, abortCurrent = false)
                }
            }
        }
    }

    private suspend fun finalizeAssistant(sessionId: String, assistantId: String) {
        val current = streamingAssistant
        if (current != null && current.content.isNotBlank()) {
            repository.upsertAssistantMessage(
                id = assistantId,
                sessionId = sessionId,
                content = current.content,
                createdAt = current.createdAt
            )
        }
        clearStreamingState()
    }

    private fun clearStreamingState() {
        streamingAssistant = null
        _state.value = _state.value.copy(isGenerating = false, generatingPhase = GeneratingPhase.NONE)
    }

    private fun isCancellation(t: Throwable): Boolean {
        return t is IOException && t.message?.contains("canceled", ignoreCase = true) == true
    }
}
