package ai.inmo.openclaw.domain.model

enum class NodeStatus {
    DISABLED, DISCONNECTED, CONNECTING, CHALLENGING, PAIRING, PAIRED, ERROR
}

data class NodeState(
    val status: NodeStatus = NodeStatus.DISABLED,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val pairingRequestId: String? = null,
    val gatewayHost: String? = null,
    val gatewayPort: Int? = null,
    val deviceId: String? = null,
    val connectedAt: Long? = null
) {
    val isPaired: Boolean get() = status == NodeStatus.PAIRED
    val isDisabled: Boolean get() = status == NodeStatus.DISABLED
    val isConnecting: Boolean
        get() = status == NodeStatus.CONNECTING || status == NodeStatus.CHALLENGING

    val statusText: String
        get() = when (status) {
            NodeStatus.DISABLED -> "Disabled"
            NodeStatus.DISCONNECTED -> "Disconnected"
            NodeStatus.CONNECTING -> "Connecting..."
            NodeStatus.CHALLENGING -> "Authenticating..."
            NodeStatus.PAIRING -> "Pairing..."
            NodeStatus.PAIRED -> "Paired"
            NodeStatus.ERROR -> "Error"
        }
}
