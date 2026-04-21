package ai.inmo.openclaw.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification

@Dao
@SkipQueryVerification
interface SearchDao {
    @Query(
        "SELECT merged.id AS id, merged.sessionKey AS sessionKey, ss.title AS sessionTitle, " +
            "merged.messageIndex AS messageIndex, merged.content AS content, merged.createdAt AS createdAt, " +
            "merged.snippet AS snippet, merged.sourcePriority AS sourcePriority " +
            "FROM (" +
            "SELECT sm.id AS id, sm.sessionKey AS sessionKey, sm.messageIndex AS messageIndex, " +
            "sm.content AS content, sm.createdAt AS createdAt, " +
            "snippet(fts_synced_messages, '<b>', '</b>', '...', 0, 12) AS snippet, 0 AS sourcePriority " +
            "FROM fts_synced_messages " +
            "JOIN synced_messages sm ON sm.id = fts_synced_messages.rowid " +
            "WHERE fts_synced_messages MATCH :query " +
            "UNION ALL " +
            "SELECT sm.id AS id, sm.sessionKey AS sessionKey, sm.messageIndex AS messageIndex, " +
            "sm.content AS content, sm.createdAt AS createdAt, " +
            "sm.content AS snippet, 1 AS sourcePriority " +
            "FROM synced_messages sm " +
            "WHERE sm.content LIKE '%' || :escapedQuery || '%' ESCAPE '\\' " +
            "UNION ALL " +
            "SELECT sm.id AS id, sm.sessionKey AS sessionKey, sm.messageIndex AS messageIndex, " +
            "sm.content AS content, sm.createdAt AS createdAt, " +
            "seg.textContent AS snippet, 2 AS sourcePriority " +
            "FROM synced_segments seg " +
            "JOIN synced_messages sm ON sm.id = seg.messageId " +
            "WHERE seg.type = 'text' " +
            "AND seg.textContent LIKE '%' || :escapedQuery || '%' ESCAPE '\\'" +
            ") AS merged " +
            "LEFT JOIN synced_sessions ss ON ss.sessionKey = merged.sessionKey " +
            "ORDER BY merged.createdAt DESC, merged.sourcePriority ASC LIMIT :limit"
    )
    suspend fun searchMessages(
        query: String,
        escapedQuery: String,
        limit: Int = 50
    ): List<MessageSearchResult>

    @Query(
        "SELECT stc.id AS id, stc.name AS name, stc.description AS description, " +
            "stc.resultPreview AS resultPreview, sm.id AS messageId, sm.sessionKey AS sessionKey, " +
            "ss.title AS sessionTitle, sm.messageIndex AS messageIndex, sm.createdAt AS createdAt, " +
            "snippet(fts_synced_tool_calls, '<b>', '</b>', '...', -1, 10) AS snippet " +
            "FROM fts_synced_tool_calls " +
            "JOIN synced_tool_calls stc ON stc.id = fts_synced_tool_calls.rowid " +
            "JOIN synced_segments seg ON seg.id = stc.segmentId " +
            "JOIN synced_messages sm ON sm.id = seg.messageId " +
            "LEFT JOIN synced_sessions ss ON ss.sessionKey = sm.sessionKey " +
            "WHERE fts_synced_tool_calls MATCH :query " +
            "ORDER BY sm.createdAt DESC, stc.toolIndex ASC LIMIT :limit"
    )
    suspend fun searchToolCalls(query: String, limit: Int = 30): List<ToolCallSearchResult>

    @Query(
        "SELECT * FROM synced_sessions " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun getAllSessions(
        limit: Int = 200
    ): List<SyncedSessionEntity>

    @Query(
        "SELECT * FROM synced_sessions " +
            "WHERE title LIKE '%' || :escapedQuery || '%' ESCAPE '\\' " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun searchSessionsByTitle(
        escapedQuery: String,
        limit: Int = 20
    ): List<SyncedSessionEntity>
}
