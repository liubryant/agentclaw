package ai.inmo.openclaw.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SyncedSegmentDao {
    @Query("SELECT * FROM synced_segments WHERE messageId IN (:messageIds) ORDER BY segmentIndex ASC")
    suspend fun getByMessageIds(messageIds: List<Long>): List<SyncedSegmentEntity>

    @Insert
    suspend fun insertAll(segments: List<SyncedSegmentEntity>): List<Long>

    @Query("DELETE FROM synced_segments WHERE messageId IN (:messageIds)")
    suspend fun deleteByMessageIds(messageIds: List<Long>)
}
