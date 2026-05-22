package ai.cjym.agentclaw.ui.terminal

import ai.cjym.agentclaw.domain.model.TerminalExecutionMode
import ai.cjym.agentclaw.domain.model.TerminalSessionSpec
import android.content.Context

class TerminalViewModel(context: Context) : BaseTerminalViewModel(context) {
    override fun buildSpec(): TerminalSessionSpec {
        return TerminalSessionSpec(
            title = "Terminal",
            subtitle = "Interactive proot shell session",
            command = "exec /bin/bash -l",
            mode = TerminalExecutionMode.SHELL,
            preamble = "AgentClaw terminal session started. Commands run inside the proot Ubuntu environment.\n\n"
        )
    }
}
