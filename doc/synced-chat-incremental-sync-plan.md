# Synced Chat 增量同步改造计划

## Context

当前 Synced Chat 在 WebSocket 连接成功后会立即从 gateway 全量拉取会话列表和默认会话历史消息，写入 DB。每次 AI 响应完成后也会全量刷新。这导致不必要的网络请求和 DB 全量替换操作。

**目标**：改为增量同步策略 —— 连接后只拉会话列表不拉历史，用户发消息/AI 回复完成时增量追加单条消息到 DB，切换会话时按需加载。

## 修改文件清单

1. `app/src/main/java/ai/inmo/openclaw/data/local/db/SyncedMessageDao.kt`
2. `app/src/main/java/ai/inmo/openclaw/data/local/db/SyncedToolCallDao.kt`
3. `app/src/main/java/ai/inmo/openclaw/data/local/db/AppDatabase.kt`
4. `app/src/main/java/ai/inmo/openclaw/data/repository/SyncedChatCacheRepository.kt`
5. `app/src/main/java/ai/inmo/openclaw/data/repository/SyncedChatWsManager.kt`

---

## Step 1: SyncedMessageDao — 新增方法

**文件**: `SyncedMessageDao.kt`

新增 2 个方法：

```kotlin
@Insert
suspend fun insert(message: SyncedMessageEntity): Long

@Query("SELECT MAX(messageIndex) FROM synced_messages WHERE sessionKey = :sessionKey")
suspend fun getMaxMessageIndex(sessionKey: String): Int?
```

- `insert`: 单条插入，返回自增 ID（用于后续插入 segments）
- `getMaxMessageIndex`: 获取该会话当前最大 messageIndex，用于计算下一条消息的 index

---

## Step 2: SyncedToolCallDao — 修改返回类型

**文件**: `SyncedToolCallDao.kt`

将 `insertAll` 的返回类型改为 `List<Long>`，以获取插入后的自增 ID（用于 FTS 索引）：

```kotlin
// 从:
@Insert
suspend fun insertAll(toolCalls: List<SyncedToolCallEntity>)

// 改为:
@Insert
suspend fun insertAll(toolCalls: List<SyncedToolCallEntity>): List<Long>
```

---

## Step 3: AppDatabase — 新增增量 FTS 插入方法

**文件**: `AppDatabase.kt`

在 `AppDatabase` 类中新增 2 个方法（与现有 `rebuildSearchIndexes()` 并列）：

```kotlin
fun insertMessageFtsEntry(messageRowId: Long, content: String) {
    val db = openHelper.writableDatabase
    db.execSQL(
        "INSERT INTO fts_synced_messages(rowid, content) VALUES(?, ?)",
        arrayOf(messageRowId, content)
    )
}

fun insertToolCallFtsEntries(toolCallIds: List<Long>, toolCalls: List<SyncedToolCallEntity>) {
    if (toolCalls.isEmpty()) return
    val db = openHelper.writableDatabase
    toolCallIds.zip(toolCalls).forEach { (id, tc) ->
        db.execSQL(
            "INSERT INTO fts_synced_tool_calls(rowid, name, description, resultPreview) VALUES(?, ?, ?, ?)",
            arrayOf(id, tc.name, tc.description ?: "", tc.resultPreview ?: "")
        )
    }
}
```

这些方法在 Room 事务内调用，实现增量 FTS 更新，无需全量 rebuild。

---

## Step 4: SyncedChatCacheRepository — 新增 `appendMessage` 方法

**文件**: `SyncedChatCacheRepository.kt`

新增方法，复用现有 `cacheMessages` 中 segments/toolCalls 的拆解逻辑：

```kotlin
suspend fun appendMessage(sessionKey: String, message: SyncedMessage) {
    database.withTransaction {
        // 1. 计算 messageIndex
        val maxIndex = syncedMessageDao.getMaxMessageIndex(sessionKey) ?: -1
        val nextIndex = maxIndex + 1

        // 2. 插入消息
        val messageId = syncedMessageDao.insert(
            SyncedMessageEntity(
                sessionKey = sessionKey,
                messageIndex = nextIndex,
                role = message.role,
                content = message.content,
                createdAt = message.createdAt,
                isStreaming = message.isStreaming
            )
        )

        // 3. 构建 segments 和 toolCall entities（复用 cacheMessages 同样的逻辑）
        val segmentEntities = mutableListOf<SyncedSegmentEntity>()
        val toolCallBuckets = mutableMapOf<Int, List<SyncedToolCallEntity>>()

        val segments = message.segments.ifEmpty { listOf(ContentSegment.Text(message.content)) }
        segments.forEachIndexed { segmentIndex, segment ->
            segmentEntities += when (segment) {
                is ContentSegment.Text -> SyncedSegmentEntity(
                    messageId = messageId, segmentIndex = segmentIndex,
                    type = SEGMENT_TYPE_TEXT, textContent = segment.text
                )
                is ContentSegment.Tools -> {
                    toolCallBuckets[segmentEntities.size] = segment.tools.mapIndexed { ti, tool ->
                        SyncedToolCallEntity(
                            segmentId = 0, toolIndex = ti, toolCallId = tool.toolCallId,
                            name = tool.name, description = tool.description, resultPreview = null
                        )
                    }
                    SyncedSegmentEntity(
                        messageId = messageId, segmentIndex = segmentIndex,
                        type = SEGMENT_TYPE_TOOLS
                    )
                }
            }
        }

        // 4. 插入 segments
        val segmentIds = if (segmentEntities.isNotEmpty()) {
            syncedSegmentDao.insertAll(segmentEntities)
        } else emptyList()

        // 5. 插入 tool calls
        val allToolCallEntities = mutableListOf<SyncedToolCallEntity>()
        segmentIds.forEachIndexed { idx, segmentId ->
            allToolCallEntities += toolCallBuckets[idx].orEmpty().map { it.copy(segmentId = segmentId) }
        }
        val toolCallIds = if (allToolCallEntities.isNotEmpty()) {
            syncedToolCallDao.insertAll(allToolCallEntities)
        } else emptyList()

        // 6. 增量 FTS 更新
        database.insertMessageFtsEntry(messageId, message.content)
        if (allToolCallEntities.isNotEmpty()) {
            database.insertToolCallFtsEntries(toolCallIds, allToolCallEntities)
        }
    }
}
```

注意：如果 `message.segments` 为空（如用户消息），自动包装为单个 `ContentSegment.Text`。

---

## Step 5: SyncedChatWsManager — 4 处修改

**文件**: `SyncedChatWsManager.kt`

### 5a. `sendOperatorConnect()` (约 L405-414) — 去掉历史消息拉取

```kotlin
// 修改前：并发拉取 sessions + history
coroutineScope {
    launch { loadSessionsAndCache(skipEnsureConnected = true) }
    launch { loadHistoryAndCache(...) }
}

// 修改后：只拉取 sessions
loadSessionsAndCache(skipEnsureConnected = true)
```

保留 L395-403 的缓存读取逻辑（从 DB 读取缓存的 sessions 和 messages 显示到 UI）。

### 5b. `sendMessage()` (约 L233-240) — 用户消息增量写 DB

在 L240 `cacheRepo.cacheSessions(...)` 之后追加：

```kotlin
val userMessage = SyncedMessage(
    id = SyncedChatCacheRepository.buildStableMessageId(sessionKey, "user", now, text.trim()),
    role = "user",
    content = text.trim(),
    createdAt = now,
    segments = listOf(ContentSegment.Text(text.trim()))
)
scope.launch {
    runCatching { cacheRepo.appendMessage(sessionKey, userMessage) }
        .onFailure { Logger.w(TAG, "Failed to append user message: ${it.message}") }
}
```

### 5c. `handleChatEvent` "final" 分支 (约 L717-732) — AI 消息增量写 DB，去掉全量刷新

```kotlin
// 修改前
"final" -> {
    val finalizedLocally = finalizeCurrentAssistantMessage(payload)
    val localSnapshot = _messages.value.toList()
    clearTransientRunState()
    ...
    scope.launch {
        if (finalizedLocally) silentlyRefreshHistoryAndSessions(sessionKey, localSnapshot)
        else { loadHistory(sessionKey); loadSessions() }
    }
}

// 修改后
"final" -> {
    val finalizedLocally = finalizeCurrentAssistantMessage(payload)
    clearTransientRunState()
    _isGenerating.value = false
    _generatingPhase.value = GeneratingPhase.NONE
    _activeAssistantMessageId.value = null
    currentRunId = null
    scope.launch {
        if (finalizedLocally) {
            // 增量追加 AI 消息到 DB
            val aiMsg = _messages.value.lastOrNull { it.role == "assistant" }
            if (aiMsg != null) {
                runCatching {
                    cacheRepo.appendMessage(sessionKey, aiMsg)
                    cacheRepo.cacheSessions(_sessions.value)
                }.onFailure { Logger.w(TAG, "Failed to append assistant message: ${it.message}") }
            }
        } else {
            // 兜底：无法本地构建 AI 消息时，从 gateway 全量拉取
            runCatching { loadHistoryAndCache(sessionKey, showLoading = false) }
            runCatching { loadSessions() }
        }
    }
}
```

### 5d. `switchSession()` (约 L173-182) — 按需加载

```kotlin
// 修改前：无条件后台拉远程
scope.launch {
    runCatching { loadHistoryAndCache(sessionKey, showLoading = cachedMessages.isEmpty()) }
    ...
}

// 修改后：只在无缓存时才拉
if (cachedMessages.isEmpty()) {
    scope.launch {
        runCatching { loadHistoryAndCache(sessionKey, showLoading = true) }
            .onFailure { Logger.w(TAG, "Session switch fetch failed: ${it.message}") }
    }
}
```

---

## 边界情况处理

| 场景 | 处理方式 |
|------|---------|
| 用户消息 segments 为空 | `appendMessage` 自动包装为 `ContentSegment.Text` |
| messageIndex 并发 | 在 Room 事务内计算 `MAX+1`，事务锁保证串行 |
| 新建会话还没有 session 行 | `synced_messages` 与 `synced_sessions` 无 FK 约束，可安全插入 |
| `finalizeCurrentAssistantMessage` 返回 false | 保留全量 fallback，从 gateway 拉取 |
| `loadMoreHistory` | 不变，仍用全量替换（拉历史旧消息的场景） |
| 重连 | `sendOperatorConnect` 只拉 sessions，消息从 DB 缓存读取 |

---

## 验证方式

1. **基本流程**：连接 gateway → 发送消息 → 检查 DB 中是否增量写入了用户消息 → AI 回复完成 → 检查 DB 中是否增量写入了 AI 消息（含 segments 和 tool calls）
2. **杀进程恢复**：发消息、收到 AI 回复后杀掉 App → 重新打开 → 验证消息从 DB 缓存正确加载
3. **切换会话**：在有缓存的会话间切换 → 验证无 gateway 请求；切换到无缓存会话 → 验证从 gateway 加载
4. **搜索**：发送消息后搜索消息内容 → 验证 FTS 增量索引生效
5. **loadMoreHistory**：验证加载更多历史仍正常工作（全量替换路径未受影响）
