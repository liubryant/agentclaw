package ai.cjym.agentclaw.ui.avatar

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DuixAssetInstaller {
    private const val VERSION = "lily-1"

    suspend fun install(context: Context, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val duixRoot = requireNotNull(context.getExternalFilesDir("duix"))
        val modelRoot = File(duixRoot, "model")
        val markerRoot = File(modelRoot, "tmp")
        val versionFile = File(markerRoot, ".bundled-version")
        val baseTarget = File(modelRoot, "gj_dh_res")
        val lilyTarget = File(modelRoot, "Lily")
        // Remove the model used by older app versions from app-private storage.
        File(modelRoot, "Leo").deleteRecursively()
        File(markerRoot, "Leo").deleteRecursively()
        File(modelRoot, "Sofia").deleteRecursively()
        File(markerRoot, "Sofia").deleteRecursively()
        if (versionFile.readTextOrEmpty() == VERSION && baseTarget.isDirectory && lilyTarget.isDirectory) {
            onProgress(100)
            return@withContext lilyTarget
        }

        modelRoot.mkdirs()
        markerRoot.mkdirs()
        val assets = context.assets
        val files = mutableListOf<Pair<String, File>>()
        collectAssets(context, "duix/gj_dh_res", baseTarget, files)
        collectAssets(context, "duix/Lily", lilyTarget, files)
        files.forEachIndexed { index, (assetPath, destination) ->
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> destination.outputStream().use(input::copyTo) }
            onProgress(((index + 1) * 100 / files.size.coerceAtLeast(1)))
        }
        File(markerRoot, "gj_dh_res").mkdirs()
        File(markerRoot, "Lily").mkdirs()
        versionFile.writeText(VERSION)
        lilyTarget
    }

    private fun collectAssets(context: Context, path: String, target: File, out: MutableList<Pair<String, File>>) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            out += path to target
        } else {
            children.forEach { child -> collectAssets(context, "$path/$child", File(target, child), out) }
        }
    }

    private fun File.readTextOrEmpty(): String = runCatching { readText() }.getOrDefault("")
}
