package ai.inmo.openclaw.domain.model

data class TerminalSessionState(
    val title: String = "",
    val subtitle: String? = null,
    val transcript: String = "",
    val isLoading: Boolean = false,
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val errorMessage: String? = null,
    val exitCode: Int? = null,
    val canSendInput: Boolean = false,
    val latestUrl: String? = null
)
