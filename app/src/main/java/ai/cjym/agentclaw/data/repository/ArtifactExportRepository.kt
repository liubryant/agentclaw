package ai.cjym.agentclaw.data.repository

import ai.cjym.agentclaw.domain.model.ArtifactExportItemResult
import ai.cjym.agentclaw.domain.model.ArtifactExportResult
import ai.cjym.agentclaw.domain.model.GeneratedArtifact
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Collections
import java.util.Locale
import java.security.MessageDigest

class ArtifactExportRepository(context: Context) {
    companion object {
        private const val TAG = "ArtifactExportRepo"
        private const val WORKSPACE_ROOTFS_DIR = "root/.openclaw/workspace"
        private const val EXPORT_STATE_PREFS = "artifact_export_state"
        private const val KEY_EXPORTED_WORKSPACE_PATHS = "exported_workspace_paths"
    }

    /**
     * 仅导出白名单内格式；不在白名单内的一律不导出（如 js / yml / ts 等）。
     */
    private val supportedWorkspaceExtensions = setOf(
        "docx", "pdf", "xlsx", "xlsm", "txt", "pptx",
        "mp3", "html", "png", "jpg", "jpeg", "csv", "svg", "md"
    )

    /**
     * Workspace 启动/系统元数据文件：对用户导出无意义，需排除，避免误触发“点击下载文件”按钮。
     */
    private val excludedWorkspaceFileNames = setOf(
        "agents.md",
        "bootstrap.md",
        "heartbeat.md",
        "identity.md",
        "soul.md",
        "tools.md",
        "user.md"
    )

    private val exportedWorkspacePaths = Collections.synchronizedSet(mutableSetOf<String>())
    private val exportedContentFingerprints = Collections.synchronizedSet(mutableSetOf<String>())

    private val appContext = context.applicationContext
    private val exportStatePrefs = appContext.getSharedPreferences(EXPORT_STATE_PREFS, Context.MODE_PRIVATE)

    init {
        restoreExportedWorkspacePaths()
    }

    fun exportArtifacts(artifacts: List<GeneratedArtifact>): ArtifactExportResult {
        Log.i(
            TAG,
            "exportArtifacts start count=${artifacts.size}, paths=${artifacts.map { it.originalPath }}"
        )
        if (artifacts.isEmpty()) {
            Log.w(TAG, "exportArtifacts called with empty artifacts")
            return ArtifactExportResult(successCount = 0, failureCount = 0, itemResults = emptyList())
        }

        val groupedByPath = artifacts.groupBy { workspaceKeyOf(it.originalPath) }
        val uniqueArtifacts = groupedByPath.values.map { it.first() }

        val uniqueResultsByPath = uniqueArtifacts.associate { artifact ->
            val key = workspaceKeyOf(artifact.originalPath)
            val result = runCatching {
                val bytes = resolveArtifactBytes(artifact.originalPath)
                    ?: throw IllegalStateException("源文件不存在或不可读取")
                val fileName = buildExportFileName(artifact)
                val fingerprint = buildFingerprint(fileName, bytes)
                val duplicated = exportedContentFingerprints.contains(fingerprint)
                val target = if (duplicated) {
                    "duplicate_skipped"
                } else {
                    writeToDownloads(fileName, bytes, artifact.mimeType).also {
                        exportedContentFingerprints.add(fingerprint)
                    }
                }
                Log.i(
                    TAG,
                    "exportArtifacts success artifactId=${artifact.id}, source=${artifact.originalPath}, exportName=$fileName, target=$target, bytes=${bytes.size}, duplicated=$duplicated"
                )
                ArtifactExportItemResult(
                    artifactId = artifact.id,
                    targetUriOrPath = target,
                    success = true
                )
            }.getOrElse { error ->
                Log.e(
                    TAG,
                    "exportArtifacts failed artifactId=${artifact.id}, source=${artifact.originalPath}, error=${error.message}",
                    error
                )
                ArtifactExportItemResult(
                    artifactId = artifact.id,
                    success = false,
                    errorMessage = error.message ?: "导出失败"
                )
            }
            key to result
        }

        val results = artifacts.map { artifact ->
            val key = workspaceKeyOf(artifact.originalPath)
            val base = uniqueResultsByPath[key]
                ?: ArtifactExportItemResult(
                    artifactId = artifact.id,
                    success = false,
                    errorMessage = "导出失败"
                )
            if (base.artifactId == artifact.id) {
                base
            } else {
                base.copy(artifactId = artifact.id)
            }
        }

        val result = ArtifactExportResult(
            successCount = results.count { it.success },
            failureCount = results.count { !it.success },
            itemResults = results
        )
        Log.i(
            TAG,
            "exportArtifacts end success=${result.successCount}, failure=${result.failureCount}"
        )
        return result
    }

    fun collectNewWorkspaceArtifacts(): List<GeneratedArtifact> {
        val all = collectWorkspaceArtifacts()
        pruneStaleExportedWorkspacePaths(all)
        val allKeys = all.map { workspaceKeyOf(it.originalPath) }
        val exportedSnapshot = exportedWorkspacePaths.toSet()
        val matchedExportedKeys = allKeys.filter { exportedSnapshot.contains(it) }
        val pending = all.filter { !exportedSnapshot.contains(workspaceKeyOf(it.originalPath)) }

        Log.i(
            TAG,
            "collectNewWorkspaceArtifacts state allKeys=${allKeys.size}, exportedState=${exportedSnapshot.size}, matched=${matchedExportedKeys.size}, unmatched=${allKeys.size - matchedExportedKeys.size}, exportedSample=${exportedSnapshot.preview()}"
        )
        Log.i(
            TAG,
            "collectNewWorkspaceArtifacts total=${all.size}, pending=${pending.size}, paths=${pending.map { it.originalPath }}"
        )
        return pending
    }

    fun hasWorkspaceArtifacts(): Boolean {
        return collectWorkspaceArtifacts().isNotEmpty()
    }

    fun markWorkspaceExported(artifacts: List<GeneratedArtifact>) {
        if (artifacts.isEmpty()) return
        var changed = false
        val newlyAddedKeys = mutableListOf<String>()
        artifacts.forEach {
            val key = workspaceKeyOf(it.originalPath)
            if (exportedWorkspacePaths.add(key)) {
                changed = true
                newlyAddedKeys.add(key)
            }
        }
        if (changed) {
            persistExportedWorkspacePaths()
        }
        Log.i(
            TAG,
            "markWorkspaceExported count=${artifacts.size}, newlyAdded=${newlyAddedKeys.size}, newlyAddedSample=${newlyAddedKeys.preview()}, totalExportedKeys=${exportedWorkspacePaths.size}"
        )
    }

    private fun restoreExportedWorkspacePaths() {
        val persisted = exportStatePrefs.getStringSet(KEY_EXPORTED_WORKSPACE_PATHS, emptySet())
            ?.map { it.trim().replace('\\', '/').lowercase(Locale.US) }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (persisted.isNotEmpty()) {
            exportedWorkspacePaths.addAll(persisted)
        }
        Log.i(
            TAG,
            "restoreExportedWorkspacePaths restored=${persisted.size}, restoredSample=${persisted.preview()}"
        )
    }

    private fun persistExportedWorkspacePaths() {
        val snapshot = exportedWorkspacePaths.toSet()
        exportStatePrefs.edit()
            .putStringSet(KEY_EXPORTED_WORKSPACE_PATHS, snapshot)
            .apply()
        Log.d(
            TAG,
            "persistExportedWorkspacePaths size=${snapshot.size}, sample=${snapshot.preview()}"
        )
    }

    private fun pruneStaleExportedWorkspacePaths(currentArtifacts: List<GeneratedArtifact>) {
        val existingKeys = currentArtifacts
            .map { workspaceKeyOf(it.originalPath) }
            .toSet()
        val staleKeys = exportedWorkspacePaths.filter { it !in existingKeys }
        if (staleKeys.isEmpty()) return
        exportedWorkspacePaths.removeAll(staleKeys.toSet())
        persistExportedWorkspacePaths()
        Log.i(
            TAG,
            "pruneStaleExportedWorkspacePaths removed=${staleKeys.size}, removedSample=${staleKeys.preview()}, remain=${exportedWorkspacePaths.size}"
        )
    }

    private fun Collection<String>.preview(limit: Int = 5): String {
        if (isEmpty()) return "[]"
        val head = take(limit)
        val suffix = if (size > limit) " ...(+${size - limit})" else ""
        return head.joinToString(prefix = "[", postfix = "]$suffix")
    }

    fun injectAndExportArtifact(fileName: String, bytes: ByteArray, mimeType: String): Boolean {
        return runCatching {
            val fingerprint = buildFingerprint(fileName, bytes)
            if (exportedContentFingerprints.contains(fingerprint)) {
                Log.i(TAG, "injectAndExportArtifact skip duplicate fileName=$fileName")
                return@runCatching false
            }
            writeToDownloads(fileName, bytes, mimeType)
            exportedContentFingerprints.add(fingerprint)
            Log.i(TAG, "injectAndExportArtifact saved fileName=$fileName, bytes=${bytes.size}")
            true
        }.getOrElse { e ->
            Log.e(TAG, "injectAndExportArtifact failed fileName=$fileName, err=${e.message}")
            false
        }
    }

    private fun collectWorkspaceArtifacts(): List<GeneratedArtifact> {
        // rootfs 已移除，workspace 扫描不再可用
        return emptyList()
    }

    private fun workspaceKeyOf(path: String): String {
        return path.trim().replace('\\', '/').lowercase(Locale.US)
    }

    private fun resolveArtifactBytes(path: String): ByteArray? {
        // rootfs 已移除，无法读取 workspace 文件
        Log.w(TAG, "resolveArtifactBytes skipped (rootfs removed): $path")
        return null
    }

    private fun buildExportFileName(artifact: GeneratedArtifact): String {
        val safeName = sanitizeFileName(
            artifact.displayName.ifBlank {
                artifact.originalPath.substringAfterLast('/').ifBlank { "artifact.txt" }
            }
        )
        return safeName
    }

    private fun shouldExportWorkspaceFile(fileName: String): Boolean {
        if (excludedWorkspaceFileNames.contains(fileName.lowercase(Locale.US))) {
            return false
        }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isBlank()) return false
        return supportedWorkspaceExtensions.contains(ext)
    }

    private fun buildFingerprint(fileName: String, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        return "${fileName.lowercase(Locale.US)}:$hash"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun writeToDownloads(fileName: String, bytes: ByteArray, mimeType: String): String {
        val resolvedMimeType = mimeType.ifBlank { guessMimeType(fileName) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/AgentClaw/"
            val existingUri = findExistingDownloadUri(fileName, relativePath)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, resolvedMimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
            val uri = existingUri ?: appContext.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("无法创建导出文件")
            if (existingUri != null) {
                appContext.contentResolver.update(uri, values, null, null)
            }
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(bytes)
            } ?: throw IllegalStateException("无法写入导出文件")
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadDir, "AgentClaw").apply { mkdirs() }
            val targetFile = File(targetDir, fileName)
            targetFile.writeBytes(bytes)
            targetFile.absolutePath
        }
    }

    private fun findExistingDownloadUri(fileName: String, relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(fileName, relativePath)
        appContext.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
