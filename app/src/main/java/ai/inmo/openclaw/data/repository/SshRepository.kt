package ai.inmo.openclaw.data.repository

import ai.inmo.openclaw.proot.ProcessManager
import ai.inmo.openclaw.service.ssh.SshForegroundService
import android.content.Context

class SshRepository(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val processManager by lazy {
        ProcessManager(appContext.filesDir.absolutePath, appContext.applicationInfo.nativeLibraryDir)
    }
    private val packageService = PackageService(appContext)

    fun isInstalled(): Boolean = packageService.isInstalled(ai.inmo.openclaw.domain.model.OptionalPackage.SSH)

    fun isRunning(): Boolean = SshForegroundService.isRunning

    fun getPort(): Int = SshForegroundService.currentPort

    fun getIpAddresses(): List<String> = SshForegroundService.getDeviceIps()

    fun start(port: Int) {
        SshForegroundService.start(appContext, port)
    }

    fun stop() {
        SshForegroundService.stop(appContext)
    }

    fun setRootPassword(password: String) {
        val escaped = password.replace("'", "'\\''")
        processManager.runInProotSync("echo 'root:$escaped' | chpasswd", 15)
    }
}
