package ai.inmo.openclaw.data.repository

import ai.inmo.core_common.utils.Logger
import ai.inmo.openclaw.data.local.db.SearchDao
import ai.inmo.openclaw.data.local.db.SyncedSessionEntity
import kotlinx.coroutines.coroutineScope

class ChatSearchRepository(
    private val searchDao: SearchDao
) {
    suspend fun search(query: String): ChatSearchResults = coroutineScope {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            val sessionMatches = searchDao.getAllSessions().map(::toSessionMatch)
            Logger.d(TAG, "search query=<blank>, sessions=${sessionMatches.size}")
            return@coroutineScope ChatSearchResults(sessionMatches = sessionMatches)
        }

        val likeQuery = escapeLike(normalizedQuery)

        val sessionMatches = searchDao.searchSessionsByTitle(likeQuery).map(::toSessionMatch)

        // Full-text message and tool-call search are temporarily disabled.
        // Keep the result shape unchanged so the existing UI can continue to render title matches.
        val messageMatches = emptyList<SearchSessionGroup>()
        val toolCallMatches = emptyList<SearchSessionGroup>()

        Logger.d(
            TAG,
            "search query='$normalizedQuery', like='$likeQuery', sessions=${sessionMatches.size}"
        )

        ChatSearchResults(
            sessionMatches = sessionMatches,
            messageMatches = messageMatches,
            toolCallMatches = toolCallMatches
        )
    }

    private fun toSessionMatch(session: SyncedSessionEntity): SearchSessionMatch {
        return SearchSessionMatch(
            sessionKey = session.sessionKey,
            sessionTitle = session.title,
            updatedAt = session.updatedAt
        )
    }

    private fun escapeLike(query: String): String {
        return query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    companion object {
        private const val TAG = "ChatSearch"
    }
}

data class ChatSearchResults(
    val sessionMatches: List<SearchSessionMatch> = emptyList(),
    val messageMatches: List<SearchSessionGroup> = emptyList(),
    val toolCallMatches: List<SearchSessionGroup> = emptyList()
) {
    fun isEmpty(): Boolean {
        return sessionMatches.isEmpty() && messageMatches.isEmpty() && toolCallMatches.isEmpty()
    }
}

data class SearchSessionMatch(
    val sessionKey: String,
    val sessionTitle: String,
    val updatedAt: Long
)

data class SearchSessionGroup(
    val sessionKey: String,
    val sessionTitle: String,
    val matches: List<SearchMatchItem>
)

data class SearchMatchItem(
    val snippet: String,
    val createdAt: Long,
    val matchType: SearchMatchType,
    val targetMessageIndex: Int
)

enum class SearchMatchType {
    MESSAGE,
    TOOL_CALL
}
