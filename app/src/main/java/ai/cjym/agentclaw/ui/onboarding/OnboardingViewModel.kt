package ai.cjym.agentclaw.ui.onboarding

import ai.cjym.agentclaw.domain.model.TerminalExecutionMode
import ai.cjym.agentclaw.domain.model.TerminalSessionSpec
import ai.cjym.agentclaw.ui.terminal.BaseTerminalViewModel
import android.content.Context

class OnboardingViewModel(context: Context) : BaseTerminalViewModel(context) {
    override fun buildSpec(): TerminalSessionSpec {
        return TerminalSessionSpec(
            title = "Onboarding",
            subtitle = "Run openclaw onboard and capture the dashboard token URL",
            command = "printf '=== AgentClaw Onboarding ===\\nSelect loopback (127.0.0.1) when prompted.\\n\\n'; openclaw onboard; printf '\\nOnboarding finished.\\n'",
            mode = TerminalExecutionMode.SHELL,
            completionMarkers = listOf("Onboarding finished.", "successfully onboarded", "setup complete"),
            finishOnExit = true,
            saveDashboardTokenFromOutput = true,
            preamble = "Use this terminal to complete initial provider and binding setup.\n\n"
        )
    }
}
