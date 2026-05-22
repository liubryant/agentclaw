package ai.cjym.agentclaw.ui.onboarding

import ai.cjym.agentclaw.databinding.ActivityTerminalSessionBinding
import ai.cjym.agentclaw.ui.terminal.BaseTerminalActivity
import ai.cjym.agentclaw.ui.terminal.BaseTerminalViewModel

class OnboardingActivity : BaseTerminalActivity(ActivityTerminalSessionBinding::inflate) {
    override val terminalViewModel: BaseTerminalViewModel by lazy { OnboardingViewModel(this) }
}
