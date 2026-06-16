package ai.cjym.agentclaw.ui.dashboard

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.core_common.utils.context.AppProvider
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.NodeState
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel : BaseViewModel() {
    data class DashboardOverviewState(
        val nodeCard: StatusCardUiState
    )

    val overviewState: StateFlow<DashboardOverviewState> = AppGraph.nodeManager.state
        .map { node ->
            DashboardOverviewState(nodeCard = node.toNodeCard())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DashboardOverviewState(
                nodeCard = AppGraph.nodeManager.state.value.toNodeCard()
            )
        )

    fun refreshNode() = AppGraph.nodeManager.refresh()

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
