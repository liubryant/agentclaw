package ai.inmo.openclaw.data.repository

import ai.inmo.openclaw.domain.model.OptionalPackage
import android.content.Context
import java.io.File

class PackageService(context: Context) {
    private val rootfsDir = File(context.applicationContext.filesDir, "rootfs/ubuntu")

    fun isInstalled(pkg: OptionalPackage): Boolean {
        return File(rootfsDir, pkg.checkPath).exists()
    }

    fun checkAllStatuses(): Map<String, Boolean> {
        return OptionalPackage.ALL.associate { it.id to isInstalled(it) }
    }
}
