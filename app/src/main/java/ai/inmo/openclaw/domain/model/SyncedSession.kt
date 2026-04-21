package ai.inmo.openclaw.domain.model

data class SyncedSession(
    val sessionKey: String,
    val title: String,
    val updatedAt: Long,
    val kind: String? = null
) {
    companion object {
        fun fromPayload(map: Map<String, Any?>): SyncedSession {
            val key = (map["key"] as? String)
                ?: (map["sessionKey"] as? String)
                ?: ""
            val sessionId = map["sessionId"] as? String
            val label = map["label"] as? String
            val derivedTitle = (map["derivedTitle"] as? String)
                ?: (map["title"] as? String)
            val displayName = map["displayName"] as? String

            val title = when {
                !label.isNullOrEmpty() -> label
                !derivedTitle.isNullOrEmpty() -> derivedTitle
                !displayName.isNullOrEmpty() -> displayName
                !sessionId.isNullOrEmpty() -> sessionId.take(8)
                key.isNotEmpty() -> key
                else -> "Untitled"
            }

            return SyncedSession(
                sessionKey = key,
                title = title,
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
                kind = map["kind"] as? String
            )
        }
    }
}
