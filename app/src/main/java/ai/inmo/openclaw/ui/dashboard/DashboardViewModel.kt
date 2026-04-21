package ai.inmo.openclaw.ui.dashboard

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.core_common.utils.context.AppProvider
import ai.inmo.openclaw.R
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.GatewayState
import ai.inmo.openclaw.domain.model.NodeState
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel : BaseViewModel() {
    data class DashboardOverviewState(
        val gatewayCard: StatusCardUiState,
        val nodeCard: StatusCardUiState
    )

    val overviewState: StateFlow<DashboardOverviewState> = combine(
        AppGraph.gatewayManager.state,
        AppGraph.nodeManager.state
    ) { gateway, node ->
        DashboardOverviewState(
            gatewayCard = gateway.toGatewayCard(node),
            nodeCard = node.toNodeCard()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardOverviewState(
            gatewayCard = AppGraph.gatewayManager.state.value.toGatewayCard(AppGraph.nodeManager.state.value),
            nodeCard = AppGraph.nodeManager.state.value.toNodeCard()
        )
    )

    fun refreshNode() = AppGraph.nodeManager.refresh()

    private fun GatewayState.toGatewayCard(nodeState: NodeState): StatusCardUiState {
        val context = AppProvider.get()
        val subtitle = dashboardUrl?.let {
            context.getString(R.string.dashboard_gateway_subtitle_ready, it)
        } ?: context.getString(R.string.dashboard_gateway_subtitle_pending)
        val supporting = context.getString(R.string.dashboard_gateway_supporting, nodeState.statusText)
        return StatusCardUiState(
            title = context.getString(R.string.gateway_title),
            status = statusText,
            subtitle = subtitle,
            supporting = supporting,
            error = errorMessage
        )
    }

    private fun NodeState.toNodeCard(): StatusCardUiState {
        val context = AppProvider.get()
        val subtitle = when {
            gatewayHost.isNullOrBlank() -> context.getString(R.string.dashboard_node_subtitle_pending)
            gatewayPort != null -> context.getString(R.string.dashboard_node_subtitle_target, gatewayHost, gatewayPort.toString())
            else -> context.getString(R.string.dashboard_node_subtitle_host_only, gatewayHost)
        }
        return StatusCardUiState(
            title = context.getString(R.string.dashboard_node),
            status = statusText,
            subtitle = subtitle,
            supporting = if (isDisabled) {
                context.getString(R.string.dashboard_node_supporting_disabled)
            } else {
                context.getString(R.string.dashboard_node_supporting_active)
            },
            error = errorMessage
        )
    }
}
