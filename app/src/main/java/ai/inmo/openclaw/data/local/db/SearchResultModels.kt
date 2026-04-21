package ai.inmo.openclaw.data.local.db

data class MessageSearchResult(
    val id: Long,
    val sessionKey: String,
    val sessionTitle: String?,
    val messageIndex: Int,
    val content: String,
    val createdAt: Long,
    val snippet: String,
    val sourcePriority: Int
)

data class ToolCallSearchResult(
    val id: Long,
    val name: String,
    val description: String?,
    val resultPreview: String?,
    val messageId: Long,
    val sessionKey: String,
    val sessionTitle: String?,
    val messageIndex: Int,
    val createdAt: Long,
    val snippet: String
)
