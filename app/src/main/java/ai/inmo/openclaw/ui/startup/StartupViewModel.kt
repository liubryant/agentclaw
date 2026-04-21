package ai.inmo.openclaw.ui.startup

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.core_common.utils.DeviceInfo
import ai.inmo.openclaw.domain.model.AiProvider
import ai.inmo.openclaw.domain.model.GatewayStatus
import ai.inmo.openclaw.domain.model.NodeStatus
import ai.inmo.openclaw.domain.model.SetupStep
import ai.inmo.openclaw.domain.model.StartupStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class StartupViewModel : BaseViewModel() {
    data class StepUiState(
        val label: String,
        val active: Boolean,
        val complete: Boolean
    )

    data class StartupUiState(
        val step: StartupStep = StartupStep.ENVIRONMENT_PRELOAD,
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
            try {
                emitPhaseStart(
                    step = StartupStep.ENVIRONMENT_PRELOAD,
                    progress = 0.0,
                    message = "Checking environment preload..."
                )
                runEnvironmentPreload()

                emitPhaseStart(
                    step = StartupStep.MODEL_PREACTIVATE,
                    progress = 0.25,
                    message = "Checking model pre-activation..."
                )
                runModelPreActivation()

                emitPhaseStart(
                    step = StartupStep.GATEWAY_START,
                    progress = 0.50,
                    message = "Starting gateway..."
                )
                runGatewayStart()

                emitPhaseStart(
                    step = StartupStep.NODE_PAIRING,
                    progress = 0.75,
                    message = "Pairing node capabilities..."
                )
                runNodePairing()

                AppGraph.preferences.setupComplete = true
                AppGraph.preferences.isFirstRun = false
                AppGraph.preferences.autoStartGateway = true
                AppGraph.preferences.nodeEnabled = true

                _state.value = stateFor(
                    step = StartupStep.COMPLETE,
                    progress = 1.0,
                    message = "Startup complete",
                    completed = true,
                    isRunning = false
                )
                onComplete()
            } catch (t: Throwable) {
                val failedStep = _state.value.step.takeUnless {
                    it == StartupStep.COMPLETE || it == StartupStep.ERROR
                } ?: StartupStep.ENVIRONMENT_PRELOAD
                _state.value = stateFor(
                    step = StartupStep.ERROR,
                    progress = progressFloorFor(failedStep),
                    message = "Startup failed",
                    errorMessage = friendlyError(t.message ?: "Unknown startup error"),
                    completed = false,
                    isRunning = false
                )
            }
        }
    }

    private suspend fun runEnvironmentPreload() {
        if (AppGraph.setupCoordinator.isBootstrapComplete()) {
            AppGraph.preferences.setupComplete = true
            emitProgress(
                step = StartupStep.ENVIRONMENT_PRELOAD,
                progress = 0.25,
                message = "Environment preload already complete"
            )
            return
        }

        AppGraph.setupCoordinator.runSetup()
        val progressJob = launch {
            AppGraph.setupCoordinator.state.collect { setupState ->
                val mapped = (setupState.progress * 0.25).coerceIn(0.0, 0.25)
                emitProgress(
                    step = StartupStep.ENVIRONMENT_PRELOAD,
                    progress = mapped,
                    message = setupState.message.ifBlank { "Preparing environment..." }
                )
            }
        }

        try {
            val result = withTimeoutOrNull(300_000L) {
                AppGraph.setupCoordinator.state.first { it.step == SetupStep.COMPLETE || it.step == SetupStep.ERROR }
            } ?: throw IllegalStateException("Environment preload timed out (5 minutes)")

            if (result.hasError) {
                throw IllegalStateException(result.error ?: "Environment preload failed")
            }

            emitProgress(
                step = StartupStep.ENVIRONMENT_PRELOAD,
                progress = 0.25,
                message = "Environment preload complete"
            )
        } finally {
            progressJob.cancel()
        }
    }

    private fun runModelPreActivation() {
        val currentConfig = AppGraph.providerConfigService.readConfig()
        if (currentConfig.providers.isNotEmpty() && AppGraph.preferences.modelPreActivated) {
            emitProgress(
                step = StartupStep.MODEL_PREACTIVATE,
                progress = 0.50,
                message = "Model pre-activation already complete"
            )
            return
        }

        emitProgress(
            step = StartupStep.MODEL_PREACTIVATE,
            progress = 0.32,
            message = "Verifying provider configuration..."
        )

        if (currentConfig.providers.isEmpty()) {
            emitProgress(
                step = StartupStep.MODEL_PREACTIVATE,
                progress = 0.40,
                message = "Applying default provider preset..."
            )

//            AppGraph.providerConfigService.saveProviderConfig(
//                provider = AiProvider.ZHIPU,
//                apiKey = "dc62fccfc4ce49e8abe45891cef55e28.uxIODI5BPiqdQkJM",
////                apiKey = "46d1de3b8d18496abc2988780ec139be.zEpgjMV2gJ8fzQjk",
//                model = "glm-4.7"
//            )
            AppGraph.providerConfigService.saveProviderConfig(
                provider = AiProvider.INMOCLAW,
                apiKey = DeviceInfo.sn /*"YM00FCE5600128"*/,
                model = "glm-4.7"
            )
        }

        AppGraph.preferences.modelPreActivated = true
        emitProgress(
            step = StartupStep.MODEL_PREACTIVATE,
            progress = 0.50,
            message = "Model pre-activation complete"
        )
    }

    private suspend fun runGatewayStart() {
        emitProgress(
            step = StartupStep.GATEWAY_START,
            progress = 0.58,
            message = "Preparing gateway launch..."
        )
        if (!AppGraph.gatewayManager.state.value.isRunning) {
            AppGraph.gatewayManager.start()
        }
        waitForGateway()
        emitProgress(
            step = StartupStep.GATEWAY_START,
            progress = 0.75,
            message = "Gateway running"
        )
    }

    private suspend fun runNodePairing() {
        val currentState = AppGraph.nodeManager.state.value
        if (currentState.isPaired) {
            emitProgress(
                step = StartupStep.NODE_PAIRING,
                progress = 1.0,
                message = "Node capabilities already paired"
            )
            return
        }

        emitProgress(
            step = StartupStep.NODE_PAIRING,
            progress = 0.82,
            message = "Connecting node..."
        )
        AppGraph.nodeManager.ensurePaired()
        waitForNodePaired()
        emitProgress(
            step = StartupStep.NODE_PAIRING,
            progress = 1.0,
            message = "Node capabilities paired"
        )
    }

    private suspend fun waitForGateway() {
        val healthy = withTimeoutOrNull(120_000L) {
            AppGraph.gatewayManager.state.first { it.isRunning || it.status == GatewayStatus.ERROR }
        }
        if (healthy == null) {
            val lastState = AppGraph.gatewayManager.state.value
            val detail = lastState.errorMessage ?: lastState.statusText
            throw IllegalStateException("Gateway did not become healthy within 120s. Status: $detail")
        }
        if (healthy.status == GatewayStatus.ERROR) {
            throw IllegalStateException(healthy.errorMessage ?: "Gateway failed to start")
        }
    }

    private suspend fun waitForNodePaired() {
        val currentState = AppGraph.nodeManager.state.value
        if (currentState.isPaired) {
            return
        }

        val result = withTimeoutOrNull(90_000L) {
            AppGraph.nodeManager.state.first { state ->
                state.isPaired || state.status == NodeStatus.ERROR
            }
        } ?: run {
            val latestState = AppGraph.nodeManager.state.value
            val detail = latestState.errorMessage ?: latestState.statusText
            throw IllegalStateException(
                "Node capability pairing timed out (90 seconds). Status: ${latestState.status}. Detail: $detail"
            )
        }

        if (result.status == NodeStatus.ERROR) {
            throw IllegalStateException(result.errorMessage ?: "Node capability pairing failed")
        }
        if (!result.isPaired) {
            throw IllegalStateException("Node capability pairing did not complete")
        }
    }

    private fun emitPhaseStart(step: StartupStep, progress: Double, message: String) {
        _state.value = stateFor(
            step = step,
            progress = progress,
            message = message,
            completed = false,
            isRunning = true
        )
    }

    private fun emitProgress(step: StartupStep, progress: Double, message: String) {
        _state.update {
            stateFor(
                step = step,
                progress = progress,
                message = message,
                completed = false,
                isRunning = true
            )
        }
    }

    private fun stateFor(
        step: StartupStep,
        progress: Double,
        message: String,
        completed: Boolean,
        isRunning: Boolean,
        errorMessage: String? = null
    ): StartupUiState {
        return StartupUiState(
            step = step,
            progress = progress,
            message = message,
            errorMessage = errorMessage,
            completed = completed,
            isRunning = isRunning,
            steps = buildStepStates(step, progress, completed)
        )
    }

    private fun buildStepStates(step: StartupStep, progress: Double, completed: Boolean): List<StepUiState> {
        val activeStep = if (step == StartupStep.ERROR) null else step
        return listOf(
            StartupStep.ENVIRONMENT_PRELOAD to "Environment preload",
            StartupStep.MODEL_PREACTIVATE to "Model pre-activation",
            StartupStep.GATEWAY_START to "Start gateway",
            StartupStep.NODE_PAIRING to "Node capability pairing"
        ).map { (itemStep, label) ->
            StepUiState(
                label = label,
                active = activeStep == itemStep && !completed,
                complete = completed || progressFloorFor(step) > progressFloorFor(itemStep) ||
                    (activeStep == itemStep && progress >= progressCeilingFor(itemStep))
            )
        }
    }

    private fun progressFloorFor(step: StartupStep): Double {
        return when (step) {
            StartupStep.ENVIRONMENT_PRELOAD -> 0.0
            StartupStep.MODEL_PREACTIVATE -> 0.25
            StartupStep.GATEWAY_START -> 0.50
            StartupStep.NODE_PAIRING -> 0.75
            StartupStep.COMPLETE -> 1.0
            StartupStep.ERROR -> _state.value.progress
        }
    }

    private fun progressCeilingFor(step: StartupStep): Double {
        return when (step) {
            StartupStep.ENVIRONMENT_PRELOAD -> 0.25
            StartupStep.MODEL_PREACTIVATE -> 0.50
            StartupStep.GATEWAY_START -> 0.75
            StartupStep.NODE_PAIRING -> 1.0
            StartupStep.COMPLETE -> 1.0
            StartupStep.ERROR -> _state.value.progress
        }
    }

    private fun friendlyError(message: String): String {
        return when {
            message.contains("SERVICE_START_BLOCKED") ||
                message.contains("mAllowStartForeground false") ||
                message.contains("startForegroundService() not allowed") ->
                "Android blocked foreground service start. Keep the app in foreground and screen on, then retry."

            else -> message
        }
    }

    companion object {
        private fun defaultSteps(): List<StepUiState> {
            return listOf(
                StepUiState(label = "Environment preload", active = true, complete = false),
                StepUiState(label = "Model pre-activation", active = false, complete = false),
                StepUiState(label = "Start gateway", active = false, complete = false),
                StepUiState(label = "Node capability pairing", active = false, complete = false)
            )
        }
    }
}
