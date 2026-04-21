package ai.inmo.openclaw.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ai.inmo.openclaw.domain.model.ChatMessage
import ai.inmo.openclaw.domain.model.ChatRole

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id")]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
) {
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        sessionId = sessionId,
        role = ChatRole.valueOf(role.uppercase()),
        content = content,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(message: ChatMessage): ChatMessageEntity = ChatMessageEntity(
            id = message.id,
            sessionId = message.sessionId,
            role = message.role.apiName,
            content = message.content,
            createdAt = message.createdAt
        )
    }
}
