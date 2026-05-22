package ai.cjym.agentclaw.ui.configure

import ai.cjym.agentclaw.domain.model.TerminalExecutionMode
import ai.cjym.agentclaw.domain.model.TerminalSessionSpec
import ai.cjym.agentclaw.ui.terminal.BaseTerminalViewModel
import android.content.Context

class ConfigureViewModel(context: Context) : BaseTerminalViewModel(context) {
    override fun buildSpec(): TerminalSessionSpec {
        return TerminalSessionSpec(
            title = "Configure",
            subtitle = "Run openclaw configure inside proot",
            command = "printf '=== AgentClaw Configure ===\\n\\n'; openclaw configure; printf '\\nConfiguration finished.\\n'",
            mode = TerminalExecutionMode.SHELL,
            completionMarkers = listOf("Configuration finished."),
            finishOnExit = true,
            preamble = "Use this screen to update OpenClaw gateway configuration.\n\n"
        )
    }
}
