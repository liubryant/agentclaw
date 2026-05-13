package ai.inmo.openclaw.ui.chat

object ChatMediaUtils {
    private val markdownImageRegex = Regex("""!\[[^\]]*]\((https?://[^)\s]+)\)""")
    private val genericUrlRegex = Regex("""https?://\S+""")

    fun extractImageUrls(content: String): List<String> {
        val markdownUrls = markdownImageRegex.findAll(content)
            .mapNotNull { it.groupValues.getOrNull(1)?.let(::normalizeMediaUrl) }
            .toList()
        val directUrls = extractDirectUrls(content).filter(::isImageUrl)
        return (markdownUrls + directUrls).distinct()
    }

    fun extractVideoUrls(content: String): List<String> {
        return extractDirectUrls(content).filter(::isVideoUrl).distinct()
    }

    fun extractFirstImageUrl(content: String): String? = extractImageUrls(content).firstOrNull()

    fun extractFirstVideoUrl(content: String): String? = extractVideoUrls(content).firstOrNull()

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
                    trimmed.startsWith("Video link")
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

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".png") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.contains("ufileos.com/")
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") ||
            lower.endsWith(".mov") ||
            lower.endsWith(".m4v") ||
            lower.endsWith(".webm") ||
            lower.endsWith(".m3u8")
    }
}
