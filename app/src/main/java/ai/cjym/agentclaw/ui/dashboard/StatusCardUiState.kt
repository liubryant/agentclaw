package ai.cjym.agentclaw.ui.dashboard

data class StatusCardUiState(
    val title: String,
    val status: String,
    val subtitle: String? = null,
    val supporting: String? = null,
    val error: String? = null
)
