package ai.inmo.openclaw.ui.logs

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.GatewayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LogsViewModel : BaseViewModel() {
    val gatewayState: StateFlow<GatewayState> = AppGraph.gatewayManager.state
    private val _autoScroll = MutableStateFlow(true)
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    fun toggleAutoScroll() {
        _autoScroll.value = !_autoScroll.value
    }
}
