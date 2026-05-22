package ai.cjym.agentclaw.ui.configure

import ai.cjym.agentclaw.databinding.ActivityTerminalSessionBinding
import ai.cjym.agentclaw.ui.terminal.BaseTerminalActivity
import ai.cjym.agentclaw.ui.terminal.BaseTerminalViewModel

class ConfigureActivity : BaseTerminalActivity(ActivityTerminalSessionBinding::inflate) {
    override val terminalViewModel: BaseTerminalViewModel by lazy { ConfigureViewModel(this) }
}
