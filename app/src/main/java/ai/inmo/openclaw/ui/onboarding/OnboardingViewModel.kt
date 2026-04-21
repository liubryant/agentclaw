package ai.inmo.openclaw.ui.onboarding

import ai.inmo.openclaw.domain.model.TerminalExecutionMode
import ai.inmo.openclaw.domain.model.TerminalSessionSpec
import ai.inmo.openclaw.ui.terminal.BaseTerminalViewModel
import android.content.Context

class OnboardingViewModel(context: Context) : BaseTerminalViewModel(context) {
    override fun buildSpec(): TerminalSessionSpec {
        return TerminalSessionSpec(
            title = "Onboarding",
            subtitle = "Run openclaw onboard and capture the dashboard token URL",
            command = "printf '=== INMOClaw Onboarding ===\\nSelect loopback (127.0.0.1) when prompted.\\n\\n'; openclaw onboard; printf '\\nOnboarding finished.\\n'",
            mode = TerminalExecutionMode.SHELL,
            completionMarkers = listOf("Onboarding finished.", "successfully onboarded", "setup complete"),
            finishOnExit = true,
            saveDashboardTokenFromOutput = true,
            preamble = "Use this terminal to complete initial provider and binding setup.\n\n"
        )
    }
}
