package ai.inmo.openclaw.ui.splash

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.GatewayStatus

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
