package ai.inmo.openclaw.ui.shell

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.core_common.utils.DeviceInfo
import ai.inmo.core_common.utils.Logger
import ai.inmo.openclaw.R
import ai.inmo.openclaw.data.remote.api.TokenUsageRequest
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.core_common.utils.context.AppProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ShellSharedViewModel : BaseViewModel() {
    private val _uiState = MutableStateFlow(ShellUiState())
    val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ShellEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ShellEvent> = _events.asSharedFlow()

    init {
        launchIo {
            AppGraph.syncedChatWsManager.replyFinished.collect {
                loadTokenUsage()
            }
        }
    }

    fun setDestination(destination: ShellDestination, title: String, subtitle: String) {
        _uiState.update {
            it.copy(
                currentDestination = destination,
                topBarTitle = title,
                topBarSubtitle = subtitle
            )
        }
    }

    fun updateSidebarQuery(query: String) {
        _uiState.update { it.copy(sidebarQuery = query) }
    }

    fun updateChatDraft(sessionId: String, draft: String) {
        _uiState.update { state ->
            val trimmedSessionId = sessionId.trim()
            if (trimmedSessionId.isBlank()) {
                return@update state
            }
            val updatedDrafts = state.chatDrafts.toMutableMap().apply {
                if (draft.isBlank()) {
                    remove(trimmedSessionId)
                } else {
                    put(trimmedSessionId, draft)
                }
            }
            state.copy(chatDrafts = updatedDrafts)
        }
    }

    fun launchIdeaIntoChat(promptTemplate: String, sourceId: String) {
        navigateToChatInNewSession(promptTemplate, ideaId = sourceId)
    }

    fun launchIdeaPresetConversation(
        sourceId: String,
        userPrompt: String,
        assistantReply: String
    ) {
        _uiState.update {
            it.copy(
                currentDestination = ShellDestination.CHAT,
                selectedIdeaId = sourceId,
                selectedTaskId = null
            )
        }
        _events.tryEmit(
            ShellEvent.OpenChatInNewSessionWithPresetConversation(
                conversation = PresetConversation(
                    sourceId = sourceId,
                    userPrompt = userPrompt,
                    assistantReply = assistantReply
                )
            )
        )
    }

    fun launchTaskIntoChat(taskContext: String, sourceId: String) {
        navigateToChatInNewSession(taskContext, taskId = sourceId)
    }

    fun clearDraft(sessionId: String) {
        removeSessionDraft(sessionId)
    }

    fun requestClearChatComposerFocus() {
        _events.tryEmit(ShellEvent.ClearChatComposerFocus)
    }

    fun removeSessionDraft(sessionId: String) {
        _uiState.update { state ->
            val trimmedSessionId = sessionId.trim()
            if (trimmedSessionId.isBlank() || !state.chatDrafts.containsKey(trimmedSessionId)) {
                return@update state
            }
            state.copy(chatDrafts = state.chatDrafts - trimmedSessionId)
        }
    }

    fun loadTokenUsage() {
        launchIo {
            val sn = DeviceInfo.sn
            val data = runCatching {
                AppGraph.botApi.getTokenUsage(TokenUsageRequest(sn))
            }.getOrNull()?.data ?: return@launchIo

            val usedWan = data.usedTokens / 10_000
            val remainingPct = if (data.totalQuota > 0)
                (data.totalQuota - data.usedTokens) * 100 / data.totalQuota
            else 0L
            val text = AppProvider.get().getString(
                R.string.chat_usage_hint, usedWan.toString(), (if (remainingPct<0) 0 else remainingPct).toString()
            )
            Logger.d("chat_usage_hint:$text")
            _uiState.update { it.copy(usageText = text) }
        }
    }

    private fun navigateToChatWithDraft(
        draft: String,
        ideaId: String? = null,
        taskId: String? = null
    ) {
        _uiState.update {
            it.copy(
                currentDestination = ShellDestination.CHAT,
                selectedIdeaId = ideaId,
                selectedTaskId = taskId
            )
        }
        _events.tryEmit(ShellEvent.OpenChatDraft(draft = draft, sourceId = ideaId ?: taskId.orEmpty()))
    }

    private fun navigateToChatInNewSession(
        draft: String,
        ideaId: String? = null,
        taskId: String? = null
    ) {
        _uiState.update {
            it.copy(
                currentDestination = ShellDestination.CHAT,
                selectedIdeaId = ideaId,
                selectedTaskId = taskId
            )
        }
        _events.tryEmit(
            ShellEvent.OpenChatInNewSession(
                draft = draft,
                sourceId = ideaId ?: taskId.orEmpty()
            )
        )
    }
}
