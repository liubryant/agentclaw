package ai.inmo.openclaw.data.repository

import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.local.prefs.PreferencesManager
import ai.inmo.openclaw.domain.model.SetupState
import ai.inmo.openclaw.domain.model.SetupStep
import ai.inmo.openclaw.proot.BootstrapManager
import ai.inmo.openclaw.service.setup.SetupService
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SetupCoordinator(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    @Volatile
    private var runJob: Job? = null

    private fun bootstrapManager(): BootstrapManager {
        return BootstrapManager(
            context = context,
            filesDir = context.filesDir.absolutePath,
            nativeLibDir = context.applicationInfo.nativeLibraryDir
        )
    }

    fun refreshStatus() {
        val manager = bootstrapManager()
        val status = manager.getBootstrapStatus()
        val complete = status["complete"] as? Boolean ?: false
        _state.value = if (complete) {
            SetupState(
                step = SetupStep.COMPLETE,
                progress = 1.0,
                message = "Environment ready"
            )
        } else {
            SetupState(
                step = SetupStep.CHECKING_STATUS,
                progress = 0.0,
                message = "Bootstrap not completed"
            )
        }
    }

    fun isBootstrapComplete(): Boolean = bootstrapManager().isBootstrapComplete()

    fun runSetup() {
        if (runJob?.isActive == true) return
        runJob = scope.launch {
            SetupService.start(context)
            emit(SetupStep.CHECKING_STATUS, 0.02, "Checking bootstrap status")
            val manager = bootstrapManager()

            // Step 1: Setup directories and DNS
            manager.setupDirectories()
            manager.writeResolvConf()

            // Step 2: Check if rootfs needs extraction
            val rootfsBash = File(context.filesDir, "rootfs/ubuntu/bin/bash")
            if (!rootfsBash.exists()) {
                // Try to extract from bundled asset
                if (manager.hasBundledAsset(AppConstants.BUNDLED_ROOTFS_ASSET)) {
                    emit(SetupStep.EXTRACTING_ROOTFS, 0.05, "Extracting rootfs...")

                    // Launch a coroutine to poll extraction progress
                    val progressJob = launch {
                        while (isActive) {
                            delay(500)
                            val detail = manager.getExtractionProgressDetail()
                            val percent = detail["percent"] as? Int ?: 0
                            val entriesDone = detail["entriesDone"] as? Int ?: 0
                            val currentEntry = detail["currentEntry"] as? String ?: ""
                            val done = detail["done"] as? Boolean ?: false
                            if (done) break
                            val progress = 0.05 + (percent / 100.0) * 0.55
                            val shortEntry = if (currentEntry.length > 40) {
                                "..." + currentEntry.takeLast(37)
                            } else {
                                currentEntry
                            }
                            val msg = if (entriesDone > 0) {
                                "Extracting rootfs ${percent}% ($entriesDone files) $shortEntry"
                            } else {
                                "Extracting rootfs..."
                            }
                            emit(SetupStep.EXTRACTING_ROOTFS, progress, msg)
                        }
                    }

                    try {
                        manager.extractRootfsFromAsset(AppConstants.BUNDLED_ROOTFS_ASSET)
                    } catch (e: Exception) {
                        progressJob.cancel()
                        emitError("Rootfs extraction failed: ${e.message}")
                        return@launch
                    }
                    progressJob.cancel()
                    emit(SetupStep.EXTRACTING_ROOTFS, 0.60, "Rootfs extraction complete")
                } else {
                    emitError(
                        "Rootfs asset is not bundled in this build. " +
                            "Add ${AppConstants.BUNDLED_ROOTFS_ASSET} or provision the environment before setup."
                    )
                    return@launch
                }
            }

            // Step 3: Extract .deb packages if apt cache exists
            val archivesDir = File(context.filesDir, "rootfs/ubuntu/var/cache/apt/archives")
            val debFiles = archivesDir.listFiles { f -> f.name.endsWith(".deb") }
            if (debFiles != null && debFiles.isNotEmpty()) {
                emit(SetupStep.INSTALLING_NODE, 0.65, "Installing bundled packages...")
                try {
                    val count = manager.extractDebPackages()
                    emit(SetupStep.INSTALLING_NODE, 0.75, "Installed $count packages")
                } catch (e: Exception) {
                    emitError("Package installation failed: ${e.message}")
                    return@launch
                }
            }

            // Step 4: (Re)install bionic bypass on every setup run.
            // 说明：bypassInstalled 仅检查文件是否存在，无法感知 hook 内容是否升级。
            // 为确保 hook.js 的 stream 策略变更可落地，这里始终覆盖写入最新脚本。
            emit(SetupStep.CONFIGURING_BYPASS, 0.80, "Installing runtime compatibility layer")
            try {
                manager.installBionicBypass()
            } catch (e: Exception) {
                emitError("Bionic bypass installation failed: ${e.message}")
                return@launch
            }

            // Step 5: Create bin wrappers for openclaw
            emit(SetupStep.CONFIGURING_BYPASS, 0.90, "Creating binary wrappers")
            try {
                manager.createBinWrappers("openclaw")
            } catch (_: Exception) {
                // Non-fatal: wrappers are optional
            }

            // Final check
            if (manager.isBootstrapComplete()) {
                preferencesManager.setupComplete = true
                emit(SetupStep.COMPLETE, 1.0, "Setup complete")
            } else {
                val finalStatus = manager.getBootstrapStatus()
                val missing = mutableListOf<String>()
                if (finalStatus["nodeInstalled"] != true) missing.add("Node.js")
                if (finalStatus["openclawInstalled"] != true) missing.add("OpenClaw")
                if (finalStatus["bypassInstalled"] != true) missing.add("Bionic bypass")
                emitError("Setup incomplete. Missing: ${missing.joinToString(", ")}")
            }
        }
    }

    private suspend fun emit(step: SetupStep, progress: Double, message: String) {
        val state = SetupState(
            step = step,
            progress = progress,
            message = message,
            error = null
        )
        _state.value = state
        withContext(Dispatchers.Main) {
            SetupService.updateNotification(message, (progress * 100).toInt())
        }
        if (step == SetupStep.COMPLETE || step == SetupStep.ERROR) {
            withContext(Dispatchers.Main) {
                SetupService.stop(context)
            }
        }
    }

    private suspend fun emitError(message: String) {
        emit(SetupStep.ERROR, 0.0, message)
        _state.value = SetupState(
            step = SetupStep.ERROR,
            progress = 0.0,
            message = "Setup failed",
            error = message
        )
    }
}
