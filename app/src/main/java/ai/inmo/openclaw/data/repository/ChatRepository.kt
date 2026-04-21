package ai.inmo.openclaw.data.repository

import ai.inmo.openclaw.data.local.db.AppDatabase
import ai.inmo.openclaw.data.local.db.ChatMessageEntity
import ai.inmo.openclaw.data.local.db.ChatSessionEntity
import ai.inmo.openclaw.domain.model.ChatMessage
import ai.inmo.openclaw.domain.model.ChatRole
import ai.inmo.openclaw.domain.model.ChatSession
import android.content.Context
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ChatRepository(context: Context) {
    private val database = AppDatabase.getInstance(context.applicationContext)
    private val sessionDao = database.chatSessionDao()
    private val messageDao = database.chatMessageDao()

    fun observeSessions(): Flow<List<ChatSession>> {
        return sessionDao.getAllSessions()
            .asFlow()
            .map { list -> list.map(ChatSessionEntity::toDomain) }
    }

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> {
        return messageDao.getMessages(sessionId)
            .asFlow()
            .map { list -> list.map(ChatMessageEntity::toDomain) }
    }

    suspend fun createSession(title: String = NEW_CHAT_TITLE): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now
        )
        sessionDao.insert(ChatSessionEntity.fromDomain(session))
        return session
    }

    suspend fun loadMessages(sessionId: String): List<ChatMessage> {
        return messageDao.getMessagesList(sessionId).map(ChatMessageEntity::toDomain)
    }

    suspend fun getSession(sessionId: String): ChatSession? {
        return sessionDao.getById(sessionId)?.toDomain()
    }

    suspend fun updateSession(session: ChatSession) {
        sessionDao.update(ChatSessionEntity.fromDomain(session))
    }

    suspend fun updateSessionTitleFromFirstUserMessage(sessionId: String, text: String) {
        val existing = getSession(sessionId) ?: return
        val now = System.currentTimeMillis()
        val title = if (existing.title == NEW_CHAT_TITLE) {
            text.trim().let { input ->
                if (input.length > 30) "${input.take(30)}..." else input
            }
        } else {
            existing.title
        }
        updateSession(existing.copy(title = title, updatedAt = now))
    }

    suspend fun touchSession(sessionId: String) {
        val existing = getSession(sessionId) ?: return
        updateSession(existing.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun insertMessage(message: ChatMessage) {
        messageDao.insert(ChatMessageEntity.fromDomain(message))
    }

    suspend fun upsertAssistantMessage(
        id: String,
        sessionId: String,
        content: String,
        createdAt: Long
    ): ChatMessage {
        val message = ChatMessage(
            id = id,
            sessionId = sessionId,
            role = ChatRole.ASSISTANT,
            content = content,
            createdAt = createdAt,
            isStreaming = false
        )
        messageDao.insert(ChatMessageEntity.fromDomain(message))
        return message
    }

    suspend fun deleteSession(sessionId: String) {
        sessionDao.delete(sessionId)
    }

    companion object {
        const val NEW_CHAT_TITLE = "新对话"
    }
}
