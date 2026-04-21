package ai.inmo.openclaw.ui.terminal

import ai.inmo.openclaw.databinding.ActivityTerminalSessionBinding

class TerminalActivity : BaseTerminalActivity(ActivityTerminalSessionBinding::inflate) {
    override val terminalViewModel: BaseTerminalViewModel by lazy { TerminalViewModel(this) }
}
