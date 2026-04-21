package ai.inmo.openclaw.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SyncedToolCallDao {
    @Query("SELECT * FROM synced_tool_calls WHERE segmentId IN (:segmentIds) ORDER BY toolIndex ASC")
    suspend fun getBySegmentIds(segmentIds: List<Long>): List<SyncedToolCallEntity>

    @Insert
    suspend fun insertAll(toolCalls: List<SyncedToolCallEntity>): List<Long>

    @Query("DELETE FROM synced_tool_calls WHERE segmentId IN (:segmentIds)")
    suspend fun deleteBySegmentIds(segmentIds: List<Long>)
}
