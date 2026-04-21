package ai.inmo.openclaw.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncedMessageDao {
    @Query("SELECT * FROM synced_messages WHERE sessionKey = :sessionKey ORDER BY messageIndex ASC")
    suspend fun getBySession(sessionKey: String): List<SyncedMessageEntity>

    @Query(
        "SELECT * FROM synced_messages WHERE sessionKey = :sessionKey " +
            "ORDER BY messageIndex DESC LIMIT :limit"
    )
    suspend fun getRecentBySession(sessionKey: String, limit: Int): List<SyncedMessageEntity>

    @Query(
        "SELECT * FROM synced_messages WHERE sessionKey = :sessionKey AND messageIndex < :beforeIndex " +
            "ORDER BY messageIndex DESC LIMIT :limit"
    )
    suspend fun getOlderBySession(sessionKey: String, beforeIndex: Int, limit: Int): List<SyncedMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<SyncedMessageEntity>): List<Long>

    @Insert
    suspend fun insert(message: SyncedMessageEntity): Long

    @Query("SELECT MAX(messageIndex) FROM synced_messages WHERE sessionKey = :sessionKey")
    suspend fun getMaxMessageIndex(sessionKey: String): Int?

    @Query("SELECT * FROM synced_messages WHERE clientMessageId = :messageId LIMIT 1")
    suspend fun getByClientMessageId(messageId: String): SyncedMessageEntity?

    @Query("UPDATE synced_messages SET sendStatus = :sendStatus WHERE clientMessageId = :messageId")
    suspend fun updateSendStatus(messageId: String, sendStatus: String)

    @Query("DELETE FROM synced_messages WHERE sessionKey = :sessionKey")
    suspend fun deleteBySession(sessionKey: String)
}
