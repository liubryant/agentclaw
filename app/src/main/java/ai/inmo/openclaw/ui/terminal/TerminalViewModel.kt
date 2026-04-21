package ai.inmo.openclaw.ui.terminal

import ai.inmo.openclaw.domain.model.TerminalExecutionMode
import ai.inmo.openclaw.domain.model.TerminalSessionSpec
import android.content.Context

class TerminalViewModel(context: Context) : BaseTerminalViewModel(context) {
    override fun buildSpec(): TerminalSessionSpec {
        return TerminalSessionSpec(
            title = "Terminal",
            subtitle = "Interactive proot shell session",
            command = "exec /bin/bash -l",
            mode = TerminalExecutionMode.SHELL,
            preamble = "INMOClaw terminal session started. Commands run inside the proot Ubuntu environment.\n\n"
        )
    }
}
