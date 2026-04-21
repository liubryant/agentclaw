package ai.inmo.openclaw.ui.chat

import ai.inmo.openclaw.domain.model.ChatMessage
import ai.inmo.openclaw.domain.model.ChatRole
import ai.inmo.openclaw.domain.model.ChatSession
import ai.inmo.openclaw.domain.model.ContentSegment
import ai.inmo.openclaw.domain.model.GeneratingPhase
import ai.inmo.openclaw.domain.model.MessageSendStatus
import ai.inmo.openclaw.domain.model.SyncedMessage
import ai.inmo.openclaw.domain.model.SyncedSession
import ai.inmo.openclaw.domain.model.TimelineEntry
import ai.inmo.openclaw.domain.model.ToolCallUiModel

sealed class ChatMessageItem {
    abstract val id: String
    abstract val createdAt: Long

    data class UserMessageItem(
        override val id: String,
        val content: String,
        override val createdAt: Long,
        val isStreaming: Boolean = false,
        val sendStatus: MessageSendStatus = MessageSendStatus.SENT
    ) : ChatMessageItem()

    data class AssistantMessageItem(
        override val id: String,
        val content: String,
        override val createdAt: Long,
        val isStreaming: Boolean = false
    ) : ChatMessageItem()

    data class ToolTextMessageItem(
        override val id: String,
        val parentMessageId: String,
        val content: String,
        override val createdAt: Long,
        val isFirstInChain: Boolean,
        val isLastInChain: Boolean,
        val isStreaming: Boolean = false
    ) : ChatMessageItem()

    data class ToolCallMessageItem(
        override val id: String,
        val parentMessageId: String,
        val tool: ToolCallUiModel,
        override val createdAt: Long,
        val isFirstInChain: Boolean,
        val isLastInChain: Boolean,
        val isStreaming: Boolean = false
    ) : ChatMessageItem()

    companion object {
        fun fromLocal(message: ChatMessage): ChatMessageItem {
            return when (message.role) {
                ChatRole.USER -> UserMessageItem(
                    id = message.id,
                    content = message.content,
                    createdAt = message.createdAt,
                    isStreaming = message.isStreaming
                )
                ChatRole.ASSISTANT, ChatRole.SYSTEM -> AssistantMessageItem(
                    id = message.id,
                    content = message.content,
                    createdAt = message.createdAt,
                    isStreaming = message.isStreaming
                )
            }
        }

        fun fromSyncedMessage(message: SyncedMessage): ChatMessageItem {
            return if (message.role == "user") {
                UserMessageItem(
                    id = message.id,
                    content = message.content,
                    createdAt = message.createdAt,
                    isStreaming = message.isStreaming,
                    sendStatus = message.sendStatus
                )
            } else {
                AssistantMessageItem(
                    id = message.id,
                    content = message.content,
                    createdAt = message.createdAt,
                    isStreaming = message.isStreaming
                )
            }
        }

        private fun ToolCallUiModel.toRenderModel(): ToolCallUiModel {
            return copy(lastArgs = emptyMap())
        }

        fun buildSyncedList(messages: List<SyncedMessage>): List<ChatMessageItem> {
            return buildList {
                messages.forEach { message ->
                    if (message.role == "user" || message.segments.isEmpty()) {
                        add(fromSyncedMessage(message))
                        return@forEach
                    }

                    val segList = message.segments
                    val hasTools = segList.any { it is ContentSegment.Tools }

                    if (!hasTools) {
                        val text = segList.filterIsInstance<ContentSegment.Text>()
                            .joinToString("\n") { it.text }
                        if (text.isNotBlank()) {
                            add(
                                AssistantMessageItem(
                                    id = message.id,
                                    content = text,
                                    createdAt = message.createdAt
                                )
                            )
                        }
                        return@forEach
                    }

                    val entries = mutableListOf<TimelineEntry>()
                    var trailingText: String? = null

                    for ((i, seg) in segList.withIndex()) {
                        when (seg) {
                            is ContentSegment.Text -> {
                                val nextIsTools = segList.getOrNull(i + 1) is ContentSegment.Tools
                                val toolsAfter = segList.drop(i + 1).any { it is ContentSegment.Tools }
                                if (nextIsTools || toolsAfter) {
                                    if (seg.text.isNotBlank()) {
                                        entries.add(TimelineEntry.Text(seg.text))
                                    }
                                } else {
                                    trailingText = seg.text
                                }
                            }
                            is ContentSegment.Tools -> {
                                seg.tools.forEach { entries.add(TimelineEntry.Tool(it.toRenderModel())) }
                            }
                        }
                    }

                    if (entries.isNotEmpty()) {
                        entries.forEachIndexed { index, entry ->
                            val itemId = "${message.id}:tool:$index"
                            val isFirst = index == 0
                            val isLast = index == entries.lastIndex
                            when (entry) {
                                is TimelineEntry.Text -> add(
                                    ToolTextMessageItem(
                                        id = itemId,
                                        parentMessageId = message.id,
                                        content = entry.text,
                                        createdAt = message.createdAt,
                                        isFirstInChain = isFirst,
                                        isLastInChain = isLast
                                    )
                                )
                                is TimelineEntry.Tool -> add(
                                    ToolCallMessageItem(
                                        id = itemId,
                                        parentMessageId = message.id,
                                        tool = entry.tool,
                                        createdAt = message.createdAt,
                                        isFirstInChain = isFirst,
                                        isLastInChain = isLast
                                    )
                                )
                            }
                        }
                    }

                    if (!trailingText.isNullOrBlank()) {
                        add(
                            AssistantMessageItem(
                                id = "${message.id}:trailing",
                                content = trailingText,
                                createdAt = message.createdAt
                            )
                        )
                    }
                }
            }
        }
    }
}

data class ChatSessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long
) {
    companion object {
        fun fromLocal(session: ChatSession): ChatSessionItem = ChatSessionItem(
            id = session.id,
            title = session.title,
            updatedAt = session.updatedAt
        )

        fun fromSynced(session: SyncedSession): ChatSessionItem = ChatSessionItem(
            id = session.sessionKey,
            title = session.title,
            updatedAt = session.updatedAt
        )
    }
}

sealed class ChatSessionListItem {
    abstract val stableId: String

    data class Header(
        val title: String
    ) : ChatSessionListItem() {
        override val stableId: String = "header:$title"
    }

    data class Session(
        val session: ChatSessionItem,
        val isSelected: Boolean = false
    ) : ChatSessionListItem() {
        override val stableId: String = "session:${session.id}"
    }
}

data class ChatScreenState(
    val sessions: List<ChatSessionItem> = emptyList(),
    val selectedSessionId: String? = null,
    val messages: List<ChatMessageItem> = emptyList(),
    val isGenerating: Boolean = false,
    val isLoading: Boolean = false,
    val canSend: Boolean = true,
    val errorMessage: String? = null,
    val connectionMessage: String? = null,
    val generatingPhase: GeneratingPhase = GeneratingPhase.NONE,
    val showExportSessionFilesButton: Boolean = false
)
