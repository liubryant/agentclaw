package ai.cjym.agentclaw.ui.splash

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.cjym.agentclaw.constants.AppConstants
import ai.cjym.agentclaw.di.AppGraph

class SplashViewModel : BaseViewModel() {
    sealed class Destination {
        data object Startup : Destination()
        data object Shell : Destination()
    }

    fun resolveDestination(onReady: (Destination) -> Unit) {
        launchIo {
            if (!AppGraph.preferences.termsAccepted) return@launchIo

            AppGraph.preferences.lastAppVersion = AppConstants.VERSION

            // Node connects asynchronously in the background — do not block on isPaired.
            // Route to Startup only on first run; all other launches go directly to Shell.
            val destination = if (AppGraph.preferences.isFirstRun) {
                Destination.Startup
            } else {
                Destination.Shell
            }

            onReady(destination)
        }
    }
}
