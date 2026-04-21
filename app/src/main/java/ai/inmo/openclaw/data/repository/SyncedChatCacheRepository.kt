package ai.inmo.openclaw.data.repository

import ai.inmo.core_common.utils.Logger
import ai.inmo.openclaw.data.local.db.AppDatabase
import ai.inmo.openclaw.data.local.db.SyncedMessageDao
import ai.inmo.openclaw.data.local.db.SyncedMessageEntity
import ai.inmo.openclaw.data.local.db.SyncedSegmentDao
import ai.inmo.openclaw.data.local.db.SyncedSegmentEntity
import ai.inmo.openclaw.data.local.db.SyncedSessionDao
import ai.inmo.openclaw.data.local.db.SyncedSessionEntity
import ai.inmo.openclaw.data.local.db.SyncedToolCallDao
import ai.inmo.openclaw.data.local.db.SyncedToolCallEntity
import ai.inmo.openclaw.domain.model.ContentSegment
import ai.inmo.openclaw.domain.model.MessageSendStatus
import ai.inmo.openclaw.domain.model.SyncedMessage
import ai.inmo.openclaw.domain.model.SyncedSession
import ai.inmo.openclaw.domain.model.ToolCallUiModel
import androidx.room.withTransaction
import java.util.UUID

class SyncedChatCacheRepository(
    private val database: AppDatabase,
    private val syncedSessionDao: SyncedSessionDao,
    private val syncedMessageDao: SyncedMessageDao,
    private val syncedSegmentDao: SyncedSegmentDao,
    private val syncedToolCallDao: SyncedToolCallDao
) {
    suspend fun getCachedSessions(): List<SyncedSession> {
        return syncedSessionDao.getAllSessionsList().map(SyncedSessionEntity::toDomain)
    }

    suspend fun getCachedMessages(sessionKey: String, limit: Int): List<SyncedMessage> {
        val messageEntities = syncedMessageDao.getRecentBySession(sessionKey, limit)
            .sortedBy { it.messageIndex }
        return hydrateMessages(sessionKey, messageEntities)
    }

    suspend fun getOlderCachedMessages(
        sessionKey: String,
        beforeIndex: Int,
        limit: Int
    ): List<SyncedMessage> {
        val messageEntities = syncedMessageDao.getOlderBySession(sessionKey, beforeIndex, limit)
            .sortedBy { it.messageIndex }
        return hydrateMessages(sessionKey, messageEntities)
    }

    suspend fun cacheSessions(sessions: List<SyncedSession>) {
        syncedSessionDao.upsertAll(sessions.map(SyncedSessionEntity::fromDomain))
    }

    suspend fun cleanupLegacyEmptySessions(title: String) {
        val deletedCount = database.withTransaction {
            syncedSessionDao.deleteLegacyEmptySessionsByTitle(title)
        }
        if (deletedCount <= 0) return
        Logger.d(TAG, "cleanupLegacyEmptySessions deleted=$deletedCount, title=$title")
        database.rebuildSearchIndexes()
    }

    suspend fun cacheMessages(sessionKey: String, messages: List<SyncedMessage>) {
        try {
            database.withTransaction {
                try {
                    Logger.d(
                        TAG,
                        "cacheMessages session=$sessionKey, messageCount=${messages.size}"
                    )
                    Logger.d(TAG, "cacheMessages deleteBySession:start session=$sessionKey")
                    syncedMessageDao.deleteBySession(sessionKey)
                    Logger.d(TAG, "cacheMessages deleteBySession:done session=$sessionKey")
                    if (messages.isEmpty()) {
                        return@withTransaction
                    }

                    Logger.d(TAG, "cacheMessages upsertMessages:start count=${messages.size}")
                    val messageIds = syncedMessageDao.upsertAll(
                        messages.mapIndexed { index, message ->
                            SyncedMessageEntity(
                                sessionKey = sessionKey,
                                messageIndex = index,
                                clientMessageId = message.id,
                                role = message.role,
                                content = message.content,
                                createdAt = message.createdAt,
                                isStreaming = message.isStreaming,
                                sendStatus = message.sendStatus.name
                            )
                        }
                    )
                    Logger.d(TAG, "cacheMessages upsertMessages:done inserted=${messageIds.size}")

                    val segmentEntities = mutableListOf<SyncedSegmentEntity>()
                    val toolCallBuckets = mutableMapOf<Int, List<SyncedToolCallEntity>>()
                    messages.forEachIndexed { messageIndex, message ->
                        val messageId = messageIds[messageIndex]
                        val textSegments = message.segments.filterIsInstance<ContentSegment.Text>()
                        if (textSegments.isNotEmpty()) {
                            val segmentPreview = textSegments.joinToString(" | ") { it.text.take(DEBUG_PREVIEW) }
                            Logger.d(
                                TAG,
                                "cacheMessage[$messageIndex] role=${message.role}, content='${message.content.take(DEBUG_PREVIEW)}', " +
                                    "textSegments=${textSegments.size}, segmentPreview=$segmentPreview"
                            )
                        }
                        message.segments.forEachIndexed { segmentIndex, segment ->
                            val segmentEntity = when (segment) {
                                is ContentSegment.Text -> SyncedSegmentEntity(
                                    messageId = messageId,
                                    segmentIndex = segmentIndex,
                                    type = SEGMENT_TYPE_TEXT,
                                    textContent = segment.text
                                )
                                is ContentSegment.Tools -> SyncedSegmentEntity(
                                    messageId = messageId,
                                    segmentIndex = segmentIndex,
                                    type = SEGMENT_TYPE_TOOLS
                                )
                            }
                            segmentEntities += segmentEntity
                            if (segment is ContentSegment.Tools) {
                                toolCallBuckets[segmentEntities.lastIndex] = segment.tools.mapIndexed { toolIndex, tool ->
                                    SyncedToolCallEntity(
                                        segmentId = 0,
                                        toolIndex = toolIndex,
                                        toolCallId = tool.toolCallId,
                                        name = tool.name,
                                        description = tool.description,
                                        resultPreview = null
                                    )
                                }
                            }
                        }
                    }

                    if (segmentEntities.isEmpty()) {
                        Logger.d(TAG, "cacheMessages noSegments session=$sessionKey")
                        return@withTransaction
                    }

                    Logger.d(TAG, "cacheMessages insertSegments:start count=${segmentEntities.size}")
                    val segmentIds = syncedSegmentDao.insertAll(segmentEntities)
                    Logger.d(TAG, "cacheMessages insertSegments:done inserted=${segmentIds.size}")

                    val toolCallEntities = mutableListOf<SyncedToolCallEntity>()
                    segmentIds.forEachIndexed { segmentEntityIndex, segmentId ->
                        val toolCalls = toolCallBuckets[segmentEntityIndex].orEmpty()
                        toolCallEntities += toolCalls.map { it.copy(segmentId = segmentId) }
                    }
                    if (toolCallEntities.isNotEmpty()) {
                        Logger.d(TAG, "cacheMessages insertToolCalls:start count=${toolCallEntities.size}")
                        syncedToolCallDao.insertAll(toolCallEntities)
                        Logger.d(TAG, "cacheMessages insertToolCalls:done inserted=${toolCallEntities.size}")
                    } else {
                        Logger.d(TAG, "cacheMessages noToolCalls session=$sessionKey")
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "cacheMessages failed session=$sessionKey\n${t.stackTraceToString()}")
                    throw t
                }
            }
            Logger.d(TAG, "cacheMessages rebuildSearchIndexes:start session=$sessionKey")
            database.rebuildSearchIndexes()
            Logger.d(TAG, "cacheMessages rebuildSearchIndexes:done session=$sessionKey")
        } catch (t: Throwable) {
            Logger.e(TAG, "cacheMessages failed session=$sessionKey\n${t.stackTraceToString()}")
            throw t
        }
    }

    suspend fun appendMessage(sessionKey: String, message: SyncedMessage) {
        try {
            database.withTransaction {
                val maxIndex = syncedMessageDao.getMaxMessageIndex(sessionKey) ?: -1
                val nextIndex = maxIndex + 1
                val content = message.content
                val messageId = syncedMessageDao.insert(
                    SyncedMessageEntity(
                        sessionKey = sessionKey,
                        messageIndex = nextIndex,
                        clientMessageId = message.id,
                        role = message.role,
                        content = content,
                        createdAt = message.createdAt,
                        isStreaming = message.isStreaming,
                        sendStatus = message.sendStatus.name
                    )
                )

                val segmentEntities = mutableListOf<SyncedSegmentEntity>()
                val toolCallBuckets = mutableMapOf<Int, List<SyncedToolCallEntity>>()
                val segments = message.segments.ifEmpty { listOf(ContentSegment.Text(content)) }
                segments.forEachIndexed { segmentIndex, segment ->
                    when (segment) {
                        is ContentSegment.Text -> {
                            segmentEntities += SyncedSegmentEntity(
                                messageId = messageId,
                                segmentIndex = segmentIndex,
                                type = SEGMENT_TYPE_TEXT,
                                textContent = segment.text
                            )
                        }

                        is ContentSegment.Tools -> {
                            toolCallBuckets[segmentEntities.size] = segment.tools.mapIndexed { toolIndex, tool ->
                                SyncedToolCallEntity(
                                    segmentId = 0,
                                    toolIndex = toolIndex,
                                    toolCallId = tool.toolCallId,
                                    name = tool.name,
                                    description = tool.description,
                                    resultPreview = null
                                )
                            }
                            segmentEntities += SyncedSegmentEntity(
                                messageId = messageId,
                                segmentIndex = segmentIndex,
                                type = SEGMENT_TYPE_TOOLS
                            )
                        }
                    }
                }

                val segmentIds = if (segmentEntities.isNotEmpty()) {
                    syncedSegmentDao.insertAll(segmentEntities)
                } else {
                    emptyList()
                }

                val allToolCallEntities = mutableListOf<SyncedToolCallEntity>()
                segmentIds.forEachIndexed { index, segmentId ->
                    allToolCallEntities += toolCallBuckets[index].orEmpty().map { it.copy(segmentId = segmentId) }
                }
                val toolCallIds = if (allToolCallEntities.isNotEmpty()) {
                    syncedToolCallDao.insertAll(allToolCallEntities)
                } else {
                    emptyList()
                }

                database.insertMessageFtsEntry(messageId, content)
                if (allToolCallEntities.isNotEmpty()) {
                    database.insertToolCallFtsEntries(toolCallIds, allToolCallEntities)
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "appendMessage failed session=$sessionKey\n${t.stackTraceToString()}")
            throw t
        }
    }

    suspend fun clearSession(sessionKey: String) {
        try {
            database.withTransaction {
                syncedSessionDao.deleteByKey(sessionKey)
                syncedMessageDao.deleteBySession(sessionKey)
            }
            Logger.d(TAG, "clearSession rebuildSearchIndexes:start session=$sessionKey")
            database.rebuildSearchIndexes()
            Logger.d(TAG, "clearSession rebuildSearchIndexes:done session=$sessionKey")
        } catch (t: Throwable) {
            Logger.e(TAG, "clearSession failed session=$sessionKey\n${t.stackTraceToString()}")
            throw t
        }
    }

    suspend fun getMessageByClientMessageId(messageId: String): SyncedMessage? {
        val entity = syncedMessageDao.getByClientMessageId(messageId) ?: return null
        return hydrateMessages(entity.sessionKey, listOf(entity)).firstOrNull()
    }

    suspend fun updateMessageSendStatus(messageId: String, sendStatus: MessageSendStatus) {
        syncedMessageDao.updateSendStatus(messageId, sendStatus.name)
    }

    private suspend fun hydrateMessages(
        sessionKey: String,
        messageEntities: List<SyncedMessageEntity>
    ): List<SyncedMessage> {
        if (messageEntities.isEmpty()) return emptyList()
        val messageIds = messageEntities.map { it.id }
        val segmentEntities = syncedSegmentDao.getByMessageIds(messageIds)
        val segmentIds = segmentEntities.map { it.id }
        val toolCallsBySegmentId = if (segmentIds.isEmpty()) {
            emptyMap()
        } else {
            syncedToolCallDao.getBySegmentIds(segmentIds).groupBy { it.segmentId }
        }
        val segmentsByMessageId = segmentEntities.groupBy { it.messageId }

        return messageEntities.map { entity ->
            val segments = segmentsByMessageId[entity.id].orEmpty()
                .sortedBy { it.segmentIndex }
                .map { segment ->
                    when (segment.type) {
                        SEGMENT_TYPE_TOOLS -> ContentSegment.Tools(
                            toolCallsBySegmentId[segment.id].orEmpty()
                                .sortedBy { it.toolIndex }
                                .map(::toolCallFromEntity)
                        )
                        else -> ContentSegment.Text(segment.textContent.orEmpty())
                    }
                }
            val toolCalls = segments.filterIsInstance<ContentSegment.Tools>().flatMap { it.tools }
            SyncedMessage(
                id = entity.clientMessageId ?: buildStableMessageId(
                    sessionKey,
                    entity.role,
                    entity.createdAt,
                    entity.content
                ),
                role = entity.role,
                content = entity.content,
                createdAt = entity.createdAt,
                messageIndex = entity.messageIndex,
                isStreaming = entity.isStreaming,
                sendStatus = entity.sendStatus.toMessageSendStatus(),
                toolCalls = toolCalls,
                segments = segments
            )
        }
    }

    private fun toolCallFromEntity(entity: SyncedToolCallEntity): ToolCallUiModel {
        return ToolCallUiModel(
            toolCallId = entity.toolCallId.orEmpty().ifBlank { "cached-${entity.segmentId}-${entity.toolIndex}" },
            name = entity.name,
            description = entity.description.orEmpty(),
            iconResId = SyncedChatWsManager.resolveToolIcon(entity.name),
            completed = true
        )
    }

    companion object {
        private const val TAG = "SyncedChatCache"
        private const val DEBUG_PREVIEW = 80
        private const val SEGMENT_TYPE_TEXT = "text"
        private const val SEGMENT_TYPE_TOOLS = "tools"

        fun buildStableMessageId(
            sessionKey: String,
            role: String,
            createdAt: Long,
            content: String
        ): String {
            val raw = "$sessionKey|$role|$createdAt|$content"
            return UUID.nameUUIDFromBytes(raw.toByteArray()).toString()
        }
    }
}

private fun String.toMessageSendStatus(): MessageSendStatus {
    return runCatching { MessageSendStatus.valueOf(this) }.getOrDefault(MessageSendStatus.SENT)
}
