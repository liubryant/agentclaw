package ai.inmo.openclaw.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "synced_messages",
    indices = [Index("sessionKey"), Index("sessionKey", "messageIndex")]
)
data class SyncedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    val messageIndex: Int,
    val clientMessageId: String? = null,
    val role: String,
    val content: String,
    val createdAt: Long,
    val isStreaming: Boolean = false,
    val sendStatus: String = "SENT"
)
