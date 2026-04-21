package ai.inmo.openclaw.ui.search

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.core_common.utils.Logger
import ai.inmo.core_common.utils.context.AppProvider
import ai.inmo.openclaw.data.local.db.AppDatabase
import ai.inmo.openclaw.data.repository.ChatSearchRepository
import ai.inmo.openclaw.data.repository.ChatSearchResults
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class ChatSearchViewModel : BaseViewModel() {
    private val repository = ChatSearchRepository(
        AppDatabase.getInstance(AppProvider.get()).searchDao()
    )

    private val queryFlow = MutableStateFlow("")
    private val _uiState = MutableStateFlow<ChatSearchUiState>(ChatSearchUiState.Empty)
    val uiState: StateFlow<ChatSearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(coroutineDispatchers.io) {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { rawQuery ->
                    val query = rawQuery.trim()
                    when {
                        query.isBlank() -> {
                            Logger.d(TAG, "trigger session list query=<blank>")
                            _uiState.value = ChatSearchUiState.Loading
                            runCatching { repository.search(query) }
                                .onSuccess { results ->
                                    Logger.d(
                                        TAG,
                                        "session list success, sessions=${results.sessionMatches.size}"
                                    )
                                    _uiState.value = if (results.isEmpty()) {
                                        ChatSearchUiState.Empty
                                    } else {
                                        ChatSearchUiState.Results(query, results)
                                    }
                                }
                                .onFailure { throwable ->
                                    Logger.e(TAG, "session list failure: ${throwable.message}")
                                    _uiState.value = ChatSearchUiState.Error(
                                        message = throwable.message.orEmpty()
                                    )
                                }
                        }

                        else -> {
                            Logger.d(TAG, "trigger search query='$query'")
                            _uiState.value = ChatSearchUiState.Loading
                            runCatching { repository.search(query) }
                                .onSuccess { results ->
                                    Logger.d(
                                        TAG,
                                        "search success query='$query', " +
                                            "sessions=${results.sessionMatches.size}, " +
                                            "messageGroups=${results.messageMatches.size}, " +
                                            "toolGroups=${results.toolCallMatches.size}"
                                    )
                                    _uiState.value = if (results.isEmpty()) {
                                        ChatSearchUiState.NoResults(query)
                                    } else {
                                        ChatSearchUiState.Results(query, results)
                                    }
                                }
                                .onFailure { throwable ->
                                    Logger.e(TAG, "search failure query='$query': ${throwable.message}")
                                    _uiState.value = ChatSearchUiState.Error(
                                        message = throwable.message.orEmpty()
                                    )
                                }
                        }
                    }
                }
        }
    }

    fun updateQuery(query: String) {
        queryFlow.update { query }
    }

    companion object {
        private const val TAG = "ChatSearchVM"
    }
}

sealed interface ChatSearchUiState {
    data object Empty : ChatSearchUiState
    data object Loading : ChatSearchUiState
    data class NoResults(val query: String) : ChatSearchUiState
    data class Results(
        val query: String,
        val data: ChatSearchResults
    ) : ChatSearchUiState

    data class Error(val message: String) : ChatSearchUiState
}
