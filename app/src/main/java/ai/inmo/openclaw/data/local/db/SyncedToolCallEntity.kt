package ai.inmo.openclaw.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "synced_tool_calls",
    foreignKeys = [
        ForeignKey(
            entity = SyncedSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("segmentId")]
)
data class SyncedToolCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentId: Long,
    val toolIndex: Int,
    val toolCallId: String?,
    val name: String,
    val description: String?,
    val resultPreview: String?
)
