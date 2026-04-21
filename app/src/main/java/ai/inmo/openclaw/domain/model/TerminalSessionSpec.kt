package ai.inmo.openclaw.domain.model

enum class TerminalExecutionMode {
    SHELL,
    INSTALL
}

data class TerminalSessionSpec(
    val title: String,
    val command: String,
    val mode: TerminalExecutionMode,
    val subtitle: String? = null,
    val preamble: String? = null,
    val completionMarkers: List<String> = emptyList(),
    val finishOnExit: Boolean = false,
    val saveDashboardTokenFromOutput: Boolean = false
)
