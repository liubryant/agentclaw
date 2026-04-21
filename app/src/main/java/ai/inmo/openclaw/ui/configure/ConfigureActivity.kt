package ai.inmo.openclaw.ui.configure

import ai.inmo.openclaw.databinding.ActivityTerminalSessionBinding
import ai.inmo.openclaw.ui.terminal.BaseTerminalActivity
import ai.inmo.openclaw.ui.terminal.BaseTerminalViewModel

class ConfigureActivity : BaseTerminalActivity(ActivityTerminalSessionBinding::inflate) {
    override val terminalViewModel: BaseTerminalViewModel by lazy { ConfigureViewModel(this) }
}
