package ai.inmo.openclaw.ui.providers

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.AiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProvidersViewModel : BaseViewModel() {
    data class ProviderItem(
        val provider: AiProvider,
        val configured: Boolean,
        val active: Boolean,
        val currentModel: String?
    )

    data class ProvidersUiState(
        val activeModel: String? = null,
        val items: List<ProviderItem> = emptyList(),
        val loading: Boolean = true
    )

    private val _state = MutableStateFlow(ProvidersUiState())
    val state: StateFlow<ProvidersUiState> = _state.asStateFlow()

    fun refresh() {
        val snapshot = AppGraph.providerConfigService.readConfig()
        val activeModel = snapshot.activeModel
        val items = AiProvider.ALL.map { provider ->
            val providerConfig = snapshot.providers[provider.id] as? Map<*, *>
            val modelList = providerConfig?.get("models") as? List<*>
            val currentModel = (modelList?.firstOrNull() as? Map<*, *>)?.get("id")?.toString()
            val configured = providerConfig != null
            val active = activeModel?.startsWith("${provider.id}/") == true ||
                (!activeModel.isNullOrBlank() && provider.defaultModels.any { activeModel.contains(it) })
            ProviderItem(provider, configured, active, currentModel)
        }
        _state.value = ProvidersUiState(activeModel = activeModel, items = items, loading = false)
    }
}
