package ai.cjym.agentclaw.ui.dashboard

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.NodeState
import kotlinx.coroutines.flow.StateFlow

/** 原 GatewayViewModel — 网关已移除，转发节点状态供 DashboardActivity 使用。 */
class GatewayViewModel : BaseViewModel() {
    val nodeState: StateFlow<NodeState> = AppGraph.nodeManager.state

    fun refresh() = AppGraph.nodeManager.refresh()
}
