package ai.inmo.openclaw.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import ai.inmo.openclaw.domain.model.SyncedSession

@Entity(tableName = "synced_sessions")
data class SyncedSessionEntity(
    @PrimaryKey val sessionKey: String,
    val title: String,
    val updatedAt: Long,
    val kind: String?
) {
    fun toDomain(): SyncedSession = SyncedSession(
        sessionKey = sessionKey,
        title = title,
        updatedAt = updatedAt,
        kind = kind
    )

    companion object {
        fun fromDomain(session: SyncedSession): SyncedSessionEntity = SyncedSessionEntity(
            sessionKey = session.sessionKey,
            title = session.title,
            updatedAt = session.updatedAt,
            kind = session.kind
        )
    }
}
