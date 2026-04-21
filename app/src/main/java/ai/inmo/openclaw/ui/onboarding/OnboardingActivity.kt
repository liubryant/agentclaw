package ai.inmo.openclaw.ui.onboarding

import ai.inmo.openclaw.databinding.ActivityTerminalSessionBinding
import ai.inmo.openclaw.ui.terminal.BaseTerminalActivity
import ai.inmo.openclaw.ui.terminal.BaseTerminalViewModel

class OnboardingActivity : BaseTerminalActivity(ActivityTerminalSessionBinding::inflate) {
    override val terminalViewModel: BaseTerminalViewModel by lazy { OnboardingViewModel(this) }
}
