package ai.inmo.openclaw.ui.settings

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.proot.ArchUtils
import ai.inmo.openclaw.proot.ProcessManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel : BaseViewModel() {
    enum class SnapshotAction {
        EXPORTED,
        IMPORTED,
        EXPORT_FAILED,
        IMPORT_FAILED
    }

    data class SettingsUiState(
        val autoStartGateway: Boolean = false,
        val nodeEnabled: Boolean = false,
        val dashboardUrl: String? = null,
        val arch: String = "",
        val prootPath: String = "",
        val rootfsInstalled: Boolean = false,
        val nodeInstalled: Boolean = false,
        val openClawInstalled: Boolean = false,
        val goInstalled: Boolean = false,
        val brewInstalled: Boolean = false,
        val sshInstalled: Boolean = false,
        val statusMessage: String? = null,
        val snapshotAction: SnapshotAction? = null
    )

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun refresh() {
        val context = ai.inmo.core_common.utils.context.AppProvider.get().applicationContext
        val pm = ProcessManager(context.filesDir.absolutePath, context.applicationInfo.nativeLibraryDir)
        val filesDir = context.filesDir
        _state.value = SettingsUiState(
            autoStartGateway = AppGraph.preferences.autoStartGateway,
            nodeEnabled = AppGraph.preferences.nodeEnabled,
            dashboardUrl = AppGraph.preferences.dashboardUrl,
            arch = ArchUtils.getArch(),
            prootPath = pm.getProotPath(),
            rootfsInstalled = java.io.File(filesDir, "rootfs/ubuntu").exists(),
            nodeInstalled = java.io.File(filesDir, "rootfs/ubuntu/usr/bin/node").exists(),
            openClawInstalled = java.io.File(filesDir, "rootfs/ubuntu/usr/bin/openclaw").exists(),
            goInstalled = AppGraph.packageService.isInstalled(ai.inmo.openclaw.domain.model.OptionalPackage.GO),
            brewInstalled = AppGraph.packageService.isInstalled(ai.inmo.openclaw.domain.model.OptionalPackage.BREW),
            sshInstalled = AppGraph.packageService.isInstalled(ai.inmo.openclaw.domain.model.OptionalPackage.SSH),
            statusMessage = _state.value.statusMessage
        )
    }

    fun toggleAutoStart(enabled: Boolean) {
        AppGraph.preferences.autoStartGateway = enabled
        _state.value = _state.value.copy(autoStartGateway = enabled)
    }

    fun toggleNode(enabled: Boolean) {
        if (enabled) AppGraph.nodeManager.enable() else AppGraph.nodeManager.disable()
        _state.value = _state.value.copy(nodeEnabled = enabled)
    }

    fun exportSnapshot() {
        val file = AppGraph.snapshotManager.exportLatestSnapshot()
        _state.value = _state.value.copy(
            statusMessage = file?.absolutePath,
            snapshotAction = if (file != null) SnapshotAction.EXPORTED else SnapshotAction.EXPORT_FAILED
        )
    }

    fun importSnapshot() {
        val file = AppGraph.snapshotManager.importLatestSnapshot()
        _state.value = _state.value.copy(
            statusMessage = file?.absolutePath,
            snapshotAction = if (file != null) SnapshotAction.IMPORTED else SnapshotAction.IMPORT_FAILED
        )
        refresh()
    }

    fun consumeSnapshotAction() {
        _state.value = _state.value.copy(snapshotAction = null)
    }
}
