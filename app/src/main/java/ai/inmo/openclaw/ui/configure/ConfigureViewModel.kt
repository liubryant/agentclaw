package ai.inmo.openclaw.ui.configure

import ai.inmo.openclaw.domain.model.TerminalExecutionMode
import ai.inmo.openclaw.domain.model.TerminalSessionSpec
import ai.inmo.openclaw.ui.terminal.BaseTerminalViewModel
import android.content.Context

class ConfigureViewModel(context: Context) : BaseTerminalViewModel(context) {
    override fun buildSpec(): TerminalSessionSpec {
        return TerminalSessionSpec(
            title = "Configure",
            subtitle = "Run openclaw configure inside proot",
            command = "printf '=== INMOClaw Configure ===\\n\\n'; openclaw configure; printf '\\nConfiguration finished.\\n'",
            mode = TerminalExecutionMode.SHELL,
            completionMarkers = listOf("Configuration finished."),
            finishOnExit = true,
            preamble = "Use this screen to update OpenClaw gateway configuration.\n\n"
        )
    }
}
