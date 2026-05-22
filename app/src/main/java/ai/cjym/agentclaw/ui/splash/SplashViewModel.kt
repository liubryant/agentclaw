package ai.cjym.agentclaw.ui.splash

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.cjym.agentclaw.constants.AppConstants
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.GatewayStatus

class SplashViewModel : BaseViewModel() {
    sealed class Destination {
        data object Startup : Destination()
        data object Shell : Destination()
    }

    fun resolveDestination(onReady: (Destination) -> Unit) {
        launchIo {
            AppGraph.setupCoordinator.refreshStatus()

            if (AppGraph.preferences.termsAccepted.not()) {
                return@launchIo
            }

            val lastVersion = AppGraph.preferences.lastAppVersion
            if (lastVersion != null && lastVersion != AppConstants.VERSION) {
                AppGraph.snapshotManager.exportVersionSnapshot(lastVersion)
            }
            AppGraph.preferences.lastAppVersion = AppConstants.VERSION

            val canResumeShell = AppGraph.preferences.setupComplete &&
                AppGraph.gatewayManager.state.value.status == GatewayStatus.RUNNING &&
                AppGraph.nodeManager.state.value.isPaired

            onReady(if (canResumeShell) Destination.Shell else Destination.Startup)
        }
    }
}
