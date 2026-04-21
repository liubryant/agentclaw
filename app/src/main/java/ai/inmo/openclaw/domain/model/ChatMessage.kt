package ai.inmo.openclaw.domain.model

enum class ChatRole {
    SYSTEM, USER, ASSISTANT;

    val apiName: String get() = name.lowercase()
}

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: ChatRole,
    val content: String = "",
    val createdAt: Long,
    val isStreaming: Boolean = false
) {
    fun toApiMessage(): Map<String, String> = mapOf(
        "role" to role.apiName,
        "content" to content
    )
}
