package ai.inmo.openclaw.ui.dashboard

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.GatewayState
import kotlinx.coroutines.flow.StateFlow

class GatewayViewModel : BaseViewModel() {
    val state: StateFlow<GatewayState> = AppGraph.gatewayManager.state

    fun start() = AppGraph.gatewayManager.start()

    fun stop() = AppGraph.gatewayManager.stop()

    fun refresh() = AppGraph.gatewayManager.refresh()
}
