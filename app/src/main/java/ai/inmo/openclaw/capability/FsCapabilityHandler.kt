package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame
import ai.inmo.openclaw.proot.BootstrapManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.Base64

class FsCapabilityHandler(
    private val context: Context,
    private val bootstrapManager: BootstrapManager
) : NodeCapabilityHandler {
    override val name: String = "fs"
    override val commands: List<String> = listOf(
        "fs.list",
        "fs.stat",
        "fs.readText",
        "fs.readBytes",
        "fs.writeText",
        "fs.writeBytes",
        "fs.mkdir",
        "fs.delete",
        "fs.image.load"
    )

    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        return runCatching {
            when (command) {
                "fs.list" -> list(params)
                "fs.stat" -> stat(params)
                "fs.readText" -> readText(params)
                "fs.readBytes" -> readBytes(params, includeBase64Default = true)
                "fs.writeText" -> writeText(params)
                "fs.writeBytes" -> writeBytes(params)
                "fs.mkdir" -> mkdir(params)
                "fs.delete" -> delete(params)
                "fs.image.load" -> loadImage(params)
                else -> error("UNKNOWN_COMMAND", "Unknown fs command: $command")
            }
        }.getOrElse { error("FS_ERROR", it.message ?: "Filesystem operation failed") }
    }

    private fun list(params: Map<String, Any?>): NodeFrame {
        val scope = requireScope(params)
        val path = sanitizePath(params["path"] as? String ?: "")
        return if (scope == "rootfs") {
            NodeFrame.response("", payload = mapOf(
                "scope" to scope,
                "path" to path,
                "entries" to bootstrapManager.listRootfsDir(path)
            ))
        } else {
            val dir = resolveLocalScope(scope, path)
            if (!dir.exists()) return error("NOT_FOUND", "Path not found")
            if (!dir.isDirectory) return error("NOT_A_DIRECTORY", "Path is not a directory")
            val entries = dir.listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.map { toEntryMap(it, dir) }
                ?: emptyList()
            NodeFrame.response("", payload = mapOf("scope" to scope, "path" to path, "entries" to entries))
        }
    }

    private fun stat(params: Map<String, Any?>): NodeFrame {
        val scope = requireScope(params)
        val path = sanitizePath(params["path"] as? String ?: "")
        return if (scope == "rootfs") {
            val stat = bootstrapManager.statRootfsPath(path) ?: return error("NOT_FOUND", "Path not found")
            NodeFrame.response("", payload = mapOf("scope" to scope) + stat)
        } else {
            val file = resolveLocalScope(scope, path)
            if (!file.exists()) return error("NOT_FOUND", "Path not found")
            NodeFrame.response("", payload = mapOf("scope" to scope) + toEntryMap(file, null))
        }
    }

    private fun readText(params: Map<String, Any?>): NodeFrame {
        val scope = requireScope(params)
        val path = requirePath(params)
        return if (scope == "rootfs") {
            val content = bootstrapManager.readRootfsFile(path) ?: return error("NOT_FOUND", "Path not found")
            NodeFrame.response("", payload = mapOf("scope" to scope, "path" to path, "content" to content, "size" to content.toByteArray().size))
        } else {
            val file = resolveLocalScope(scope, path)
            if (!file.exists() || !file.isFile) return error("NOT_FOUND", "Path not found")
            val content = file.readText()
            NodeFrame.response("", payload = mapOf("scope" to scope, "path" to path, "content" to content, "size" to file.length()))
        }
    }

    private fun readBytes(params: Map<String, Any?>, includeBase64Default: Boolean): NodeFrame {
        val scope = requireScope(params)
        val path = requirePath(params)
        val includeBase64 = params["includeBase64"] as? Boolean ?: includeBase64Default
        val bytes = if (scope == "rootfs") {
            bootstrapManager.readRootfsBytes(path) ?: return error("NOT_FOUND", "Path not found")
        } else {
            val file = resolveLocalScope(scope, path)
            if (!file.exists() || !file.isFile) return error("NOT_FOUND", "Path not found")
            file.readBytes()
        }
        return NodeFrame.response("", payload = buildMap {
            put("scope", scope)
            put("path", path)
            put("size", bytes.size)
            if (includeBase64) put("base64", Base64.getEncoder().encodeToString(bytes))
        })
    }

    private fun writeText(params: Map<String, Any?>): NodeFrame {
        val scope = requireScope(params)
        val path = requirePath(params)
        val content = params["content"] as? String ?: return error("MISSING_PARAM", "content is required")
        if (scope == "rootfs") {
            bootstrapManager.writeRootfsFile(path, content)
        } else {
            val file = resolveLocalScope(scope, path, createParents = true)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return NodeFrame.response("", payload = mapOf("scope" to scope, "path" to path, "written" to true))
    }

    private fun writeBytes(params: Map<String, Any?>): NodeFrame {
        val scope = requireScope(params)
        val path = requirePath(params)
        val base64 = params["base64"] as? String ?: return error("MISSING_PARAM", "base64 is required")
        val bytes = runCatching { Base64.getDecoder().decode(base64) }
            .getOrElse { return error("INVALID_BASE64", "Invalid base64 payload") }
        if (scope == "rootfs") {
            bootstrapManager.writeRootfsBytes(path, bytes)
        } else {
            val file = resolveLocalScope(scope, path, createParents = true)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        return NodeFrame.response("", payload = mapOf("scope" to scope, "path" to path, "written" to true, "size" to bytes.size))
    }

    private fun mkdir(params: Map<String, Any?>): NodeFrame {
        val scope = requireScope(params)
        val path = requirePath(params)
        val recursive = params["recursive"] as? Boolean ?: true
        if (scope == "rootfs") {
            bootstrapManager.mkdirRootfsPath(path, recursive)
        } else {
            val dir = resolveLocalScope(scope, path)
            if (recursive) dir.mkdirs() else dir.mkdir()
        }
        return NodeFrame.response("", payload = mapOf("scope" to scope, "path" to path, "created" to true))
    }

    private fun delete(params: Map<String, Any?>): NodeFrame {
        val scope = requireScope(params)
        val path = requirePath(params)
        val recursive = params["recursive"] as? Boolean ?: false
        if (scope == "rootfs") {
            bootstrapManager.deleteRootfsPath(path, recursive)
        } else {
            val file = resolveLocalScope(scope, path)
            if (!file.exists()) return error("NOT_FOUND", "Path not found")
            if (file.isDirectory && recursive) file.deleteRecursively() else file.delete()
        }
        return NodeFrame.response("", payload = mapOf("scope" to scope, "path" to path, "deleted" to true))
    }

    private fun loadImage(params: Map<String, Any?>): NodeFrame {
        val scope = requireScope(params)
        val path = requirePath(params)
        val raw = if (scope == "rootfs") {
            bootstrapManager.readRootfsBytes(path) ?: return error("NOT_FOUND", "Path not found")
        } else {
            val file = resolveLocalScope(scope, path)
            if (!file.exists() || !file.isFile) return error("NOT_FOUND", "Path not found")
            file.readBytes()
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
        return NodeFrame.response("", payload = mapOf(
            "scope" to scope,
            "path" to path,
            "width" to options.outWidth,
            "height" to options.outHeight,
            "size" to raw.size,
            "base64" to Base64.getEncoder().encodeToString(raw)
        ))
    }

    private fun requireScope(params: Map<String, Any?>): String {
        return (params["scope"] as? String)?.lowercase()?.takeIf { it in setOf("app", "shared", "rootfs") }
            ?: throw IllegalArgumentException("scope must be app, shared, or rootfs")
    }

    private fun requirePath(params: Map<String, Any?>): String {
        return sanitizePath(params["path"] as? String ?: throw IllegalArgumentException("path is required"))
    }

    private fun sanitizePath(path: String): String {
        val cleaned = path.trim().replace('\\', '/')
        if (cleaned.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(cleaned)) {
            throw IllegalArgumentException("Only relative paths are allowed")
        }
        val parts = mutableListOf<String>()
        for (segment in cleaned.split('/')) {
            if (segment.isBlank() || segment == ".") continue
            if (segment == "..") {
                if (parts.isEmpty()) throw IllegalArgumentException("Path escapes scope root")
                parts.removeAt(parts.lastIndex)
            } else {
                parts += segment
            }
        }
        return parts.joinToString(File.separator)
    }

    private fun resolveLocalScope(scope: String, relativePath: String, createParents: Boolean = false): File {
        val root = when (scope) {
            "app" -> context.filesDir
            "shared" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    throw IllegalStateException("Shared storage access not granted")
                }
                Environment.getExternalStorageDirectory()
            }
            else -> context.filesDir
        }.canonicalFile

        val target = if (relativePath.isBlank()) root else File(root, relativePath)
        val resolved = if (target.exists()) target.canonicalFile else target.absoluteFile
        if (!resolved.toPath().normalize().startsWith(root.toPath())) {
            throw IllegalArgumentException("Path escapes scope root")
        }
        if (createParents) {
            resolved.parentFile?.mkdirs()
        }
        return resolved
    }

    private fun toEntryMap(file: File, parent: File?): Map<String, Any?> {
        val path = if (parent == null) {
            file.name
        } else {
            file.relativeTo(parent).path
        }.replace('\\', '/')
        return mapOf(
            "name" to file.name,
            "path" to path,
            "type" to when {
                file.isDirectory -> "directory"
                file.isFile -> "file"
                else -> "other"
            },
            "size" to if (file.isFile) file.length() else null,
            "modifiedAt" to file.lastModified()
        )
    }

    private fun error(code: String, message: String): NodeFrame {
        return NodeFrame.response("", error = mapOf("code" to code, "message" to message))
    }
}
