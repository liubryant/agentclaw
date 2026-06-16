package ai.cjym.agentclaw.ui.settings

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.cjym.agentclaw.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel : BaseViewModel() {
    data class SettingsUiState(
        val autoStartGateway: Boolean = false,
        val nodeEnabled: Boolean = false,
        val dashboardUrl: String? = null,
        val statusMessage: String? = null
    )

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun refresh() {
        _state.value = SettingsUiState(
            autoStartGateway = AppGraph.preferences.autoStartGateway,
            nodeEnabled = AppGraph.preferences.nodeEnabled,
            dashboardUrl = AppGraph.preferences.dashboardUrl,
            statusMessage = _state.value.statusMessage
        )
    }

    fun toggleAutoStart(enabled: Boolean) {
        AppGraph.preferences.autoStartGateway = enabled
        _state.value = _state.value.copy(autoStartGateway = enabled)
    }

    fun toggleNode(enabled: Boolean) {
        if (enabled) AppGraph.nodeManager.enable() else AppGraph.nodeManager.disable()
        _state.value = _state.value.copy(nodeEnabled = enabled)
    }
}
