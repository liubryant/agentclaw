package ai.inmo.openclaw.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncedSessionDao {
    @Query("SELECT * FROM synced_sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessionsList(): List<SyncedSessionEntity>

    @Upsert
    suspend fun upsertAll(sessions: List<SyncedSessionEntity>)

    @Query("DELETE FROM synced_sessions WHERE sessionKey = :sessionKey")
    suspend fun deleteByKey(sessionKey: String)

    @Query(
        "DELETE FROM synced_sessions " +
            "WHERE title = :title AND kind IS NULL " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM synced_messages sm WHERE sm.sessionKey = synced_sessions.sessionKey" +
            ")"
    )
    suspend fun deleteLegacyEmptySessionsByTitle(title: String): Int

    @Query("DELETE FROM synced_sessions")
    suspend fun deleteAll()
}
