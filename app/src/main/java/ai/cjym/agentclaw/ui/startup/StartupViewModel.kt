package ai.cjym.agentclaw.ui.startup

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.StartupStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StartupViewModel : BaseViewModel() {
    data class StepUiState(
        val label: String,
        val active: Boolean,
        val complete: Boolean
    )

    data class StartupUiState(
        val step: StartupStep = StartupStep.COMPLETE,
        val progress: Double = 0.0,
        val message: String = "Preparing startup",
        val errorMessage: String? = null,
        val completed: Boolean = false,
        val isRunning: Boolean = false,
        val steps: List<StepUiState> = defaultSteps()
    )

    private val _state = MutableStateFlow(StartupUiState())
    val state: StateFlow<StartupUiState> = _state.asStateFlow()

    fun runStartup(onComplete: () -> Unit) {
        if (_state.value.isRunning) return
        launchIo {
            _state.value = StartupUiState(
                step = StartupStep.COMPLETE,
                progress = 1.0,
                message = "Starting up...",
                completed = false,
                isRunning = true,
                steps = listOf(StepUiState(label = "Initializing", active = true, complete = false))
            )

            // Mark setup complete — node connects asynchronously in the background
            // once the user configures or the existing gateway credentials are used.
            AppGraph.preferences.isFirstRun = false
            AppGraph.preferences.nodeEnabled = true

            _state.value = StartupUiState(
                step = StartupStep.COMPLETE,
                progress = 1.0,
                message = "Ready",
                completed = true,
                isRunning = false,
                steps = listOf(StepUiState(label = "Initializing", active = false, complete = true))
            )
            onComplete()
        }
    }

    companion object {
        private fun defaultSteps(): List<StepUiState> = listOf(
            StepUiState(label = "Initializing", active = true, complete = false)
        )
    }
}
