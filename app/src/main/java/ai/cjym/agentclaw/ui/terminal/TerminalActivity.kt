package ai.cjym.agentclaw.ui.terminal

import ai.cjym.agentclaw.databinding.ActivityTerminalSessionBinding

class TerminalActivity : BaseTerminalActivity(ActivityTerminalSessionBinding::inflate) {
    override val terminalViewModel: BaseTerminalViewModel by lazy { TerminalViewModel(this) }
}
