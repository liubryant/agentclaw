package ai.inmo.openclaw.ui.packages

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.data.repository.TerminalSessionManager
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.OptionalPackage
import ai.inmo.openclaw.domain.model.TerminalExecutionMode
import ai.inmo.openclaw.domain.model.TerminalSessionSpec
import ai.inmo.openclaw.domain.model.TerminalSessionState
import android.content.Context
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PackageInstallViewModel(context: Context) : BaseViewModel() {
    data class PackageUiState(
        val statuses: Map<String, Boolean> = emptyMap(),
        val selectedPackageId: String = OptionalPackage.ALL.first().id
    ) {
        fun isInstalled(id: String): Boolean = statuses[id] == true
    }

    private val sessionManager = TerminalSessionManager(context.applicationContext, AppGraph.preferences)
    private val _packageState = MutableStateFlow(
        PackageUiState(statuses = AppGraph.packageService.checkAllStatuses())
    )
    val packageState: StateFlow<PackageUiState> = _packageState.asStateFlow()
    val terminalState: StateFlow<TerminalSessionState> = sessionManager.state
    val sessionFlow: StateFlow<TerminalSession?> = sessionManager.sessionFlow

    fun selectPackage(packageId: String) {
        _packageState.value = _packageState.value.copy(selectedPackageId = packageId)
    }

    fun refreshStatuses() {
        _packageState.value = _packageState.value.copy(statuses = AppGraph.packageService.checkAllStatuses())
    }

    fun runSelectedAction(uninstall: Boolean = false) {
        val selected = OptionalPackage.ALL.first { it.id == _packageState.value.selectedPackageId }
        val command = if (uninstall) selected.uninstallCommand else selected.installCommand
        val marker = if (uninstall) selected.uninstallSentinel else selected.completionSentinel
        sessionManager.start(
            TerminalSessionSpec(
                title = if (uninstall) "Uninstall ${selected.name}" else "Install ${selected.name}",
                subtitle = selected.description,
                command = command,
                mode = TerminalExecutionMode.INSTALL,
                completionMarkers = listOf(marker),
                finishOnExit = true,
                preamble = "${if (uninstall) "Removing" else "Installing"} ${selected.name}...\n\n"
            )
        )
    }

    fun restart() = sessionManager.restart()

    fun sendBytes(bytes: ByteArray) = sessionManager.writeBytes(bytes)

    fun pasteText(text: String) = sessionManager.pasteText(text)

    fun copyAllText(): String = sessionManager.getTranscriptText()

    fun latestUrl(): String? = sessionManager.getLatestUrl()

    override fun onDestroy() {
        sessionManager.stop()
        super.onDestroy()
    }
}
