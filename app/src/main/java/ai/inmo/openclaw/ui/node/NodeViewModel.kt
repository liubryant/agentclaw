package ai.inmo.openclaw.ui.node

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.data.repository.NodeManager
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.NodeState
import ai.inmo.openclaw.domain.model.NodeStatus
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

@OptIn(kotlinx.coroutines.FlowPreview::class)
class NodeViewModel : BaseViewModel() {
    data class NodeConnectionUiState(
        val statusText: String,
        val deviceIdText: String?,
        val gatewayText: String,
        val errorText: String?,
        val pairingText: String?,
        val logsText: String,
        val enableButtonEnabled: Boolean,
        val disableButtonEnabled: Boolean,
        val reconnectButtonEnabled: Boolean
    )

    val capabilitiesState: StateFlow<List<NodeManager.CapabilityDescriptor>> =
        AppGraph.nodeManager.state
            .map { AppGraph.nodeManager.capabilityCatalog }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AppGraph.nodeManager.capabilityCatalog
            )

    private val baseConnectionState =
        AppGraph.nodeManager.state
            .map { nodeState -> nodeState.toBaseUiState() }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AppGraph.nodeManager.state.value.toBaseUiState()
            )

    private val logsState =
        AppGraph.nodeManager.state
            .map { nodeState ->
                nodeState.logs.takeIf { it.isNotEmpty() }?.joinToString("\n").orEmpty()
            }
            .sample(250L)
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AppGraph.nodeManager.state.value.logs.takeIf { it.isNotEmpty() }?.joinToString("\n").orEmpty()
            )

    val connectionState: StateFlow<NodeConnectionUiState> =
        combine(baseConnectionState, logsState) { baseState, logsText ->
            baseState.copy(logsText = logsText)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppGraph.nodeManager.state.value.toBaseUiState(
                logsText = AppGraph.nodeManager.state.value.logs.takeIf { it.isNotEmpty() }?.joinToString("\n").orEmpty()
            )
        )

    fun refresh() {
        AppGraph.nodeManager.refresh()
    }

    fun enableLocal() {
        AppGraph.preferences.nodeGatewayHost = "127.0.0.1"
        AppGraph.preferences.nodeGatewayPort = 18789
        AppGraph.preferences.nodeGatewayToken = null
        AppGraph.nodeManager.enable()
    }

    fun connectRemote(host: String, port: Int, token: String?) {
        AppGraph.nodeManager.connectRemote(host, port, token)
    }

    fun disable() {
        AppGraph.nodeManager.disable()
    }

    fun reconnect() {
        AppGraph.nodeManager.reconnect()
    }

    private fun NodeState.toBaseUiState(logsText: String = ""): NodeConnectionUiState {
        return NodeConnectionUiState(
            statusText = statusText,
            deviceIdText = deviceId,
            gatewayText = listOfNotNull(gatewayHost, gatewayPort?.toString()).joinToString(":"),
            errorText = errorMessage,
            pairingText = pairingRequestId,
            logsText = logsText,
            enableButtonEnabled = isDisabled || status == NodeStatus.DISCONNECTED,
            disableButtonEnabled = !isDisabled,
            reconnectButtonEnabled = !isDisabled
        )
    }
}
