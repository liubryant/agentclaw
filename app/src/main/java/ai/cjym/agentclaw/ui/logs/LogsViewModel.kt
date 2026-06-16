package ai.cjym.agentclaw.ui.logs

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.NodeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LogsViewModel : BaseViewModel() {
    val nodeState: StateFlow<NodeState> = AppGraph.nodeManager.state
    private val _autoScroll = MutableStateFlow(true)
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    fun toggleAutoScroll() {
        _autoScroll.value = !_autoScroll.value
    }
}
