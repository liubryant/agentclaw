package ai.inmo.openclaw.data.repository

import android.content.Context
import ai.inmo.openclaw.R
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GithubSkillImportService(
    context: Context,
    private val client: OkHttpClient = OkHttpClient()
) {
    data class ImportedSkill(
        val skillName: String,
        val sourceUrl: String,
        val owner: String,
        val repo: String,
        val ref: String,
        val repoSkillPath: String,
        val installedAt: Long,
        val enabled: Boolean = true,
        val fileCount: Int = 0
    )

    data class ImportResult(
        val skillName: String,
        val fileCount: Int
    )

    private data class ParsedGithubSkillUrl(
        val owner: String,
        val repo: String,
        val ref: String,
        val path: String
    )

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val rootfsRoot = File(appContext.filesDir, "rootfs/ubuntu")
    private val openclawRoot = File(rootfsRoot, "root/.openclaw")
    private val skillsDir = File(openclawRoot, "skills")
    private val disabledSkillsDir = File(openclawRoot, "skills_disabled")
    private val tmpSkillsDir = File(openclawRoot, "tmp/skills")
    private val metadataFile = File(openclawRoot, "config/imported-skills.json")

    @Synchronized
    fun importFromGithub(url: String): ImportResult {
        val parsed = parseGithubSkillUrl(url)
        val skillName = parsed.path.substringAfterLast('/').ifBlank { "github-skill" }
        ensureDirs()

        val tmpDir = File(tmpSkillsDir, "${skillName}-${System.currentTimeMillis()}")
        tmpDir.mkdirs()

        val fileCount = try {
            downloadGithubTree(parsed, tmpDir)
        } catch (e: Exception) {
            tmpDir.deleteRecursivelySafe()
            throw IllegalStateException(
                appContext.getString(R.string.setting_import_failed_with_reason, e.message.orEmpty())
            )
        }

        val skillMd = File(tmpDir, "SKILL.md")
        if (!skillMd.exists()) {
            tmpDir.deleteRecursivelySafe()
            throw IllegalStateException(appContext.getString(R.string.setting_import_missing_skill_md))
        }

        val finalDir = File(skillsDir, skillName)
        val disabledDir = File(disabledSkillsDir, skillName)
        val backupDir = File(skillsDir, "$skillName.bak.${System.currentTimeMillis()}")

        try {
            disabledDir.deleteRecursivelySafe()
            if (finalDir.exists()) {
                if (!finalDir.renameTo(backupDir)) {
                    copyRecursively(finalDir, backupDir)
                    finalDir.deleteRecursivelySafe()
                }
            }

            if (!tmpDir.renameTo(finalDir)) {
                copyRecursively(tmpDir, finalDir)
                tmpDir.deleteRecursivelySafe()
            }

            backupDir.deleteRecursivelySafe()
        } catch (e: Exception) {
            finalDir.deleteRecursivelySafe()
            if (backupDir.exists()) {
                backupDir.renameTo(finalDir)
            }
            tmpDir.deleteRecursivelySafe()
            throw IllegalStateException(
                appContext.getString(R.string.setting_import_write_skill_dir_failed, e.message.orEmpty())
            )
        }

        upsertMetadata(
            ImportedSkill(
                skillName = skillName,
                sourceUrl = url,
                owner = parsed.owner,
                repo = parsed.repo,
                ref = parsed.ref,
                repoSkillPath = parsed.path,
                installedAt = System.currentTimeMillis(),
                enabled = true,
                fileCount = fileCount
            )
        )

        return ImportResult(skillName = skillName, fileCount = fileCount)
    }

    @Synchronized
    fun listImportedSkills(): List<ImportedSkill> {
        if (!metadataFile.exists()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ImportedSkill>>() {}.type
            gson.fromJson<List<ImportedSkill>>(metadataFile.readText(), type).orEmpty()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        val list = listImportedSkills().toMutableList()
        val idx = list.indexOfFirst { it.skillName == skillName }
        if (idx < 0) return
        val activeDir = File(skillsDir, skillName)
        val disabledDir = File(disabledSkillsDir, skillName)

        if (enabled) {
            if (!activeDir.exists() && disabledDir.exists()) {
                if (!disabledDir.renameTo(activeDir)) {
                    copyRecursively(disabledDir, activeDir)
                    disabledDir.deleteRecursivelySafe()
                }
            }
        } else {
            if (activeDir.exists()) {
                disabledDir.parentFile?.mkdirs()
                if (!activeDir.renameTo(disabledDir)) {
                    copyRecursively(activeDir, disabledDir)
                    activeDir.deleteRecursivelySafe()
                }
            }
        }

        list[idx] = list[idx].copy(enabled = enabled)
        metadataFile.writeText(gson.toJson(list))
    }

    @Synchronized
    fun removeImportedSkill(skillName: String) {
        val list = listImportedSkills().toMutableList()
        val removed = list.removeAll { it.skillName == skillName }
        if (!removed) return

        File(skillsDir, skillName).deleteRecursivelySafe()
        File(disabledSkillsDir, skillName).deleteRecursivelySafe()
        metadataFile.writeText(gson.toJson(list))
    }

    fun resolveImportedSkillDescription(skillName: String): String? {
        val activeSkillMd = File(skillsDir, "$skillName/SKILL.md")
        val disabledSkillMd = File(disabledSkillsDir, "$skillName/SKILL.md")
        val skillMd = when {
            activeSkillMd.exists() -> activeSkillMd
            disabledSkillMd.exists() -> disabledSkillMd
            else -> return null
        }

        val text = runCatching { skillMd.readText() }.getOrNull().orEmpty()
        if (text.isBlank()) return null
        return extractDescriptionFromSkillMarkdown(text)
    }

    private fun ensureDirs() {
        skillsDir.mkdirs()
        disabledSkillsDir.mkdirs()
        tmpSkillsDir.mkdirs()
        metadataFile.parentFile?.mkdirs()
    }

    private fun parseGithubSkillUrl(url: String): ParsedGithubSkillUrl {
        val normalized = url.trim().removeSuffix("/")
        val regex = Regex("""^https://github\.com/([^/]+)/([^/]+)/tree/([^/]+)/(.+)$""")
        val match = regex.find(normalized)
            ?: throw IllegalArgumentException(appContext.getString(R.string.setting_github_url_format_error))

        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        val ref = match.groupValues[3]
        val path = match.groupValues[4]
        return ParsedGithubSkillUrl(owner, repo, ref, path)
    }

    private fun downloadGithubTree(parsed: ParsedGithubSkillUrl, targetDir: File): Int {
        var fileCount = 0
        val stack = ArrayDeque<Pair<String, File>>()
        stack.add(parsed.path to targetDir)

        while (stack.isNotEmpty()) {
            val (repoPath, localDir) = stack.removeLast()
            localDir.mkdirs()

            val apiUrl = buildString {
                append("https://api.github.com/repos/")
                append(parsed.owner)
                append('/')
                append(parsed.repo)
                append("/contents/")
                append(urlEncodePath(repoPath))
                append("?ref=")
                append(URLEncoder.encode(parsed.ref, StandardCharsets.UTF_8.name()))
            }

            val json = getJson(apiUrl)
            val root = JsonParser.parseString(json)

            when {
                root.isJsonArray -> {
                    root.asJsonArray.forEach { entry ->
                        fileCount += handleEntry(entry, localDir, stack)
                    }
                }
                root.isJsonObject -> {
                    fileCount += handleEntry(root, localDir, stack)
                }
                else -> throw IllegalStateException("GitHub API 返回格式异常")
            }
        }

        return fileCount
    }

    private fun handleEntry(
        entry: JsonElement,
        localDir: File,
        stack: ArrayDeque<Pair<String, File>>
    ): Int {
        val obj = entry.asJsonObject
        val type = obj.get("type")?.asString.orEmpty()
        val name = obj.get("name")?.asString.orEmpty()
        val path = obj.get("path")?.asString.orEmpty()

        return when (type) {
            "dir" -> {
                stack.add(path to File(localDir, name))
                0
            }
            "file" -> {
                val downloadUrl = obj.get("download_url")?.asString
                    ?: throw IllegalStateException("文件 $name 缺少 download_url")
                val bytes = getBytes(downloadUrl)
                File(localDir, name).apply {
                    parentFile?.mkdirs()
                    writeBytes(bytes)
                }
                1
            }
            else -> 0
        }
    }

    private fun getJson(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "INMOClawX-SkillImporter")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub API 请求失败: HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun getBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "INMOClawX-SkillImporter")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载文件失败: HTTP ${response.code}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun upsertMetadata(item: ImportedSkill) {
        val list = listImportedSkills().toMutableList()
        val idx = list.indexOfFirst { it.skillName == item.skillName }
        if (idx >= 0) {
            list[idx] = item
        } else {
            list.add(0, item)
        }
        metadataFile.writeText(gson.toJson(list))
    }

    private fun copyRecursively(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) target.mkdirs()
            source.listFiles().orEmpty().forEach { child ->
                copyRecursively(child, File(target, child.name))
            }
        } else {
            target.parentFile?.mkdirs()
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun File.deleteRecursivelySafe() {
        runCatching { deleteRecursively() }
    }

    private fun urlEncodePath(path: String): String {
        return path.split('/').joinToString("/") {
            URLEncoder.encode(it, StandardCharsets.UTF_8.name())
        }
    }

    private fun extractDescriptionFromSkillMarkdown(markdown: String): String {
        val normalized = markdown.replace("\r\n", "\n")
        val frontMatterRegex = Regex("""(?s)^---\n(.*?)\n---\n?""")
        val frontMatter = frontMatterRegex.find(normalized)?.groupValues?.getOrNull(1).orEmpty()

        if (frontMatter.isNotBlank()) {
            val descriptionRegex = Regex("""(?im)^description\s*:\s*(.+)$""")
            val value = descriptionRegex.find(frontMatter)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (value.isNotBlank()) {
                return value.trim().trim('"', '\'')
            }
        }

        val body = normalized.substringAfter("\n---\n", normalized)
        val lines = body.lines().map { it.trim() }
        val fallback = lines.firstOrNull { line ->
            line.isNotBlank() &&
                !line.startsWith("#") &&
                !line.startsWith("name:", ignoreCase = true) &&
                !line.startsWith("license:", ignoreCase = true)
        }
        return fallback ?: appContext.getString(R.string.setting_github_import_tag)
    }
}
