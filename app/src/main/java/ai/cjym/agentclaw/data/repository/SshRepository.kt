package ai.cjym.agentclaw.data.repository

import ai.cjym.agentclaw.service.ssh.SshForegroundService
import android.content.Context

class SshRepository(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val packageService = PackageService(appContext)

    fun isInstalled(): Boolean = packageService.isInstalled(ai.cjym.agentclaw.domain.model.OptionalPackage.SSH)

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
        // proot removed — SSH root password change is not available
    }
}
