package ai.cjym.agentclaw.ui.dashboard

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.GatewayState
import kotlinx.coroutines.flow.StateFlow

class GatewayViewModel : BaseViewModel() {
    val state: StateFlow<GatewayState> = AppGraph.gatewayManager.state

    fun start() = AppGraph.gatewayManager.start()

    fun stop() = AppGraph.gatewayManager.stop()

    fun refresh() = AppGraph.gatewayManager.refresh()
}
