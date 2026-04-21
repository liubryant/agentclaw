package ai.inmo.openclaw.domain.model

enum class GeneratingPhase {
    NONE,
    THINKING,
    CALLING_TOOL
}

enum class MessageSendStatus {
    SENT,
    PENDING_RETRY_OFFLINE,
    PENDING_RETRY_TIMEOUT
}

enum class ArtifactSourceType {
    TOOL_GENERATED,
    ASSISTANT_BLOCK
}

enum class ArtifactStatus {
    DETECTED,
    EXPORTED,
    FAILED
}

sealed class ContentSegment {
    data class Text(val text: String) : ContentSegment()
    data class Tools(val tools: List<ToolCallUiModel>) : ContentSegment()
}

sealed class TimelineEntry {
    data class Text(val text: String) : TimelineEntry()
    data class Tool(val tool: ToolCallUiModel) : TimelineEntry()
}

data class StreamingToolChain(
    val isActive: Boolean = false,
    val entries: List<TimelineEntry> = emptyList(),
    val pendingText: String? = null
)

data class GeneratedArtifact(
    val id: String,
    val sessionKey: String,
    val messageId: String,
    val toolCallId: String,
    val toolName: String,
    val sourceType: ArtifactSourceType,
    val originalPath: String,
    val displayName: String,
    val mimeType: String,
    val createdAt: Long,
    val status: ArtifactStatus = ArtifactStatus.DETECTED,
    val errorMessage: String? = null
)

data class ArtifactExportItemResult(
    val artifactId: String,
    val targetUriOrPath: String? = null,
    val success: Boolean,
    val errorMessage: String? = null,
    val exportedAt: Long = System.currentTimeMillis()
)

data class ArtifactExportResult(
    val successCount: Int,
    val failureCount: Int,
    val itemResults: List<ArtifactExportItemResult>
) {
    val hasFailure: Boolean get() = failureCount > 0
}

data class SyncedMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val messageIndex: Int = -1,
    val isStreaming: Boolean = false,
    val sendStatus: MessageSendStatus = MessageSendStatus.SENT,
    val segments: List<ContentSegment> = emptyList(),
    val toolCalls: List<ToolCallUiModel> = emptyList()
)

data class ToolCallUiModel(
    val toolCallId: String,
    val name: String,
    val description: String,
    val iconResId: Int,
    val completed: Boolean = false,
    val lastArgs: Map<String, Any?> = emptyMap()
)
