package ai.inmo.openclaw.domain.model

enum class GatewayStatus {
    STOPPED, STARTING, RUNNING, ERROR
}

data class GatewayState(
    val status: GatewayStatus = GatewayStatus.STOPPED,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val startedAt: Long? = null,
    val dashboardUrl: String? = null
) {
    val isRunning: Boolean get() = status == GatewayStatus.RUNNING
    val isStopped: Boolean get() = status == GatewayStatus.STOPPED

    val statusText: String
        get() = when (status) {
            GatewayStatus.STOPPED -> "Stopped"
            GatewayStatus.STARTING -> "Starting..."
            GatewayStatus.RUNNING -> "Running"
            GatewayStatus.ERROR -> "Error"
        }
}
