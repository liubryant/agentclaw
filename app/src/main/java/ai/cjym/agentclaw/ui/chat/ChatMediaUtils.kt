package ai.cjym.agentclaw.ui.chat

object ChatMediaUtils {
    private val markdownImageRegex = Regex("""!\[[^\]]*]\((https?://[^)\s]+)\)""")
    private val genericUrlRegex = Regex("""https?://\S+""")
    private val imageLinkLineRegex = Regex("""(?i)^\s*(?:图片链接|image link)\d*\s*[:：]\s*(https?://\S+)\s*$""")
    private val videoCoverLineRegex = Regex("""(?i)^\s*(?:视频封面|cover_image_url|cover image)\s*[:：]\s*(https?://\S+)\s*$""")
    private val videoCoverJsonRegex = Regex(""""cover_image_url"\s*:\s*"(https?://[^"]+)"""")
    private val videoTaskLineRegex = Regex("""(?i)(?:任务ID|任务\s*id|task[_\s-]*id)\s*[:：]\s*([A-Za-z0-9_-]+)""")
    private val videoTaskJsonRegex = Regex(""""id"\s*:\s*"([^"]+)"""")

    fun extractImageUrls(content: String): List<String> {
        val markdownUrls = markdownImageRegex.findAll(content)
            .mapNotNull { it.groupValues.getOrNull(1)?.let(::normalizeMediaUrl) }
            .filterNot(::isVideoUrl)
            .toList()
        val coverUrls = extractVideoCoverUrls(content).toSet()
        val generatedImageUrls = extractGeneratedImageLinkUrls(content)
        val directUrls = extractDirectUrls(content)
            .filter(::isImageUrl)
            .filterNot { it in coverUrls }
        return (markdownUrls + generatedImageUrls + directUrls).distinct()
    }

    fun extractVideoUrls(content: String): List<String> {
        return extractDirectUrls(content).filter(::isVideoUrl).distinct()
    }

    fun extractFirstImageUrl(content: String): String? = extractImageUrls(content).firstOrNull()

    fun extractFirstVideoUrl(content: String): String? = extractVideoUrls(content).firstOrNull()

    fun extractFirstVideoCoverUrl(content: String): String? = extractVideoCoverUrls(content).firstOrNull()

    fun extractVideoTaskId(content: String): String? {
        videoTaskLineRegex.find(content)?.groupValues?.getOrNull(1)?.let { taskId ->
            if (taskId.isNotBlank()) return taskId
        }
        return videoTaskJsonRegex.find(content)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    fun stripMedia(content: String): String {
        return content
            .replace(markdownImageRegex, "")
            .lineSequence()
            .map { line ->
                line.replace(genericUrlRegex) { match ->
                    val url = normalizeMediaUrl(match.value)
                    if (isImageUrl(url) || isVideoUrl(url)) "" else match.value
                }.trimEnd()
            }
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.isEmpty() ||
                    trimmed.startsWith("鍥剧墖閾炬帴") ||
                    trimmed.startsWith("Image link") ||
                    trimmed.startsWith("视频链接") ||
                    trimmed.startsWith("Video link") ||
                    trimmed.startsWith("视频封面") ||
                    trimmed.startsWith("cover_image_url") ||
                    trimmed.startsWith("Cover image")
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun normalizeMediaUrl(url: String): String {
        return url.trim().trimEnd('.', ',', ';', ')', ']', '>')
    }

    private fun extractDirectUrls(content: String): List<String> {
        return genericUrlRegex.findAll(content)
            .map { normalizeMediaUrl(it.value) }
            .toList()
    }

    private fun extractGeneratedImageLinkUrls(content: String): List<String> {
        return content.lineSequence()
            .mapNotNull { line ->
                imageLinkLineRegex.find(line)?.groupValues?.getOrNull(1)?.let(::normalizeMediaUrl)
            }
            .filterNot(::isVideoUrl)
            .toList()
    }

    private fun extractVideoCoverUrls(content: String): List<String> {
        val lineUrls = content.lineSequence()
            .mapNotNull { line ->
                videoCoverLineRegex.find(line)?.groupValues?.getOrNull(1)?.let(::normalizeMediaUrl)
            }
            .filter(::isImageUrl)
            .toList()
        val jsonUrls = videoCoverJsonRegex.findAll(content)
            .mapNotNull { it.groupValues.getOrNull(1)?.let(::normalizeMediaUrl) }
            .filter(::isImageUrl)
            .toList()
        return (lineUrls + jsonUrls).distinct()
    }

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (isVideoUrl(lower)) {
            return false
        }
        val path = lower.substringBefore('?')
        return path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".webp") ||
            path.endsWith(".gif")
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        return path.endsWith(".mp4") ||
            path.endsWith(".mov") ||
            path.endsWith(".m4v") ||
            path.endsWith(".webm") ||
            path.endsWith(".m3u8")
    }
}
