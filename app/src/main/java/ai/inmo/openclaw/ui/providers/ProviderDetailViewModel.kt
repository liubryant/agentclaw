package ai.inmo.openclaw.ui.providers

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.AiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProviderDetailViewModel : BaseViewModel() {
    enum class ResultEvent {
        SAVED,
        REMOVED
    }

    data class ProviderDetailUiState(
        val saving: Boolean = false,
        val removing: Boolean = false,
        val configured: Boolean = false,
        val providerName: String? = null,
        val resultEvent: ResultEvent? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(ProviderDetailUiState())
    val state: StateFlow<ProviderDetailUiState> = _state.asStateFlow()

    fun bindExisting(provider: AiProvider) {
        val snapshot = AppGraph.providerConfigService.readConfig()
        _state.value = _state.value.copy(configured = snapshot.providers.containsKey(provider.id))
    }

    fun save(provider: AiProvider, apiKey: String, model: String) {
        _state.value = _state.value.copy(saving = true, providerName = null, resultEvent = null, error = null)
        launchIo {
            runCatching {
                AppGraph.providerConfigService.saveProviderConfig(provider, apiKey, model)
                if (AppGraph.gatewayManager.state.value.isRunning) {
                    AppGraph.gatewayManager.stop()
                    Thread.sleep(500)
                    AppGraph.gatewayManager.start()
                }
            }.onSuccess {
                _state.value = _state.value.copy(
                    saving = false,
                    configured = true,
                    providerName = provider.name,
                    resultEvent = ResultEvent.SAVED,
                    error = null
                )
            }.onFailure {
                _state.value = _state.value.copy(saving = false, error = it.message)
            }
        }
    }

    fun remove(provider: AiProvider) {
        _state.value = _state.value.copy(removing = true, providerName = null, resultEvent = null, error = null)
        launchIo {
            runCatching {
                AppGraph.providerConfigService.removeProviderConfig(provider)
                if (AppGraph.gatewayManager.state.value.isRunning) {
                    AppGraph.gatewayManager.stop()
                    Thread.sleep(500)
                    AppGraph.gatewayManager.start()
                }
            }.onSuccess {
                _state.value = _state.value.copy(
                    removing = false,
                    configured = false,
                    providerName = provider.name,
                    resultEvent = ResultEvent.REMOVED,
                    error = null
                )
            }.onFailure {
                _state.value = _state.value.copy(removing = false, error = it.message)
            }
        }
    }

    fun consumeResultEvent() {
        _state.value = _state.value.copy(resultEvent = null, providerName = null)
    }
}
