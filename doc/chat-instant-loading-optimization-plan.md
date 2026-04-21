# 聊天记录秒开优化计划

## 背景

当前聊天会话和消息历史加载太慢。现有架构每次打开都从 WebSocket 全量拉取数据——无本地缓存、网络请求串行、无分页。目标：后续打开感知延迟 <100ms，500+ 条消息长对话流畅滚动。

## 当前瓶颈链路

```
ChatFragment.initView() → chatViewModel.start() → manager.connect()
  → WebSocket 握手                                    ~50-200ms
  → connect.challenge → sendOperatorConnect()
  → ensureConnected() 轮询等待 (最长 5s!)
  → loadSessions() 串行请求                            ~100-500ms
  → loadHistory(limit=200) 串行请求                     ~200-1000ms
  → extractSegments/extractToolCalls × 200条            ~50-200ms
  → buildMessageItems() 3次 associateBy                 ~10-50ms
  → DiffUtil + RecyclerView 渲染
  → Markwon.setMarkdown() 每个可见消息                   ~20-50ms/条
总计: 典型 500ms-2s+，最差 5s+
```

## 实施阶段

### 阶段一：快速优化 (第 1-2 天)

#### 1.1 并行化 loadSessions() 和 loadHistory()

**文件**: `app/src/main/java/ai/inmo/openclaw/data/repository/SyncedChatWsManager.kt` (第 358-361 行)

将 `sendOperatorConnect()` 中的串行调用改为并行：

```kotlin
if (response.isOk) {
    _connectionState.value = true
    coroutineScope {
        launch { loadSessions() }
        launch { loadHistory(_currentSessionKey.value) }
    }
}
```

#### 1.2 替换 ensureConnected() 忙等轮询

**文件**: `SyncedChatWsManager.kt` (第 822-831 行)

将轮询循环替换为 Flow 挂起等待：

```kotlin
private suspend fun ensureConnected() {
    if (_connectionState.value) return
    connect()
    withTimeoutOrNull(5000) {
        _connectionState.first { it }
    } ?: throw IllegalStateException("Not connected to gateway")
}
```

对于 `sendOperatorConnect()` 内部调用的 loadSessions/loadHistory，连接已建立，提取不检查连接的内部方法直接跳过 `ensureConnected()`。

#### 1.3 初始加载从 200 条减少到 50 条

**文件**: `SyncedChatWsManager.kt` (第 158 行)

将 `"limit" to 200` 改为 `"limit" to 50`。阶段三会加入分页加载更多。

#### 1.4 Markwon 提前初始化

**文件**: `app/src/main/java/ai/inmo/openclaw/di/AppGraph.kt`

在 AppGraph 中添加 lazy Markwon 实例，在 Splash 期间触发初始化。`ChatMessageAdapter` 从 AppGraph 引用。

---

### 阶段二：Room 数据库缓存层 (第 2-5 天)

#### 2.1 新建 Room 实体

**新文件**: `data/local/db/SyncedSessionEntity.kt`

```kotlin
@Entity(tableName = "synced_sessions")
data class SyncedSessionEntity(
    @PrimaryKey val sessionKey: String,
    val title: String,
    val updatedAt: Long,
    val kind: String?
)
```

**新文件**: `data/local/db/SyncedMessageEntity.kt`

```kotlin
@Entity(
    tableName = "synced_messages",
    indices = [Index("sessionKey"), Index("sessionKey", "messageIndex")]
)
data class SyncedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    val messageIndex: Int,
    val role: String,
    val content: String,
    val createdAt: Long,
    val isStreaming: Boolean = false
)
```

**新文件**: `data/local/db/SyncedSegmentEntity.kt` — segments 独立关联表

```kotlin
@Entity(
    tableName = "synced_segments",
    foreignKeys = [ForeignKey(
        entity = SyncedMessageEntity::class,
        parentColumns = ["id"], childColumns = ["messageId"],
        onDelete = CASCADE
    )],
    indices = [Index("messageId")]
)
data class SyncedSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val segmentIndex: Int,
    val type: String,  // "text" 或 "tools"
    val textContent: String? = null
)
```

**新文件**: `data/local/db/SyncedToolCallEntity.kt` — toolCalls 独立关联表

```kotlin
@Entity(
    tableName = "synced_tool_calls",
    foreignKeys = [ForeignKey(
        entity = SyncedSegmentEntity::class,
        parentColumns = ["id"], childColumns = ["segmentId"],
        onDelete = CASCADE
    )],
    indices = [Index("segmentId")]
)
data class SyncedToolCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentId: Long,
    val toolIndex: Int,
    val toolCallId: String?,
    val name: String,
    val description: String?,
    val resultPreview: String?
)
```

#### 2.2 新建 DAO

**新文件**: `data/local/db/SyncedSessionDao.kt`

- `getAllSessionsList(): List<SyncedSessionEntity>` — 挂起函数，按 updatedAt 降序
- `upsertAll(sessions: List<SyncedSessionEntity>)` — @Upsert 批量更新插入
- `deleteByKey(sessionKey: String)` — 删除指定会话
- `deleteAll()` — 清空所有

**新文件**: `data/local/db/SyncedMessageDao.kt`

- `getBySession(sessionKey: String): List<SyncedMessageEntity>` — 获取某会话全部消息
- `getRecentBySession(sessionKey: String, limit: Int): List<SyncedMessageEntity>` — 分页查询，ORDER BY messageIndex DESC LIMIT
- `upsertAll(messages: List<SyncedMessageEntity>): List<Long>` — 返回生成的 ID
- `deleteBySession(sessionKey: String)` — 删除某会话所有消息

**新文件**: `data/local/db/SyncedSegmentDao.kt`

- `getByMessageIds(messageIds: List<Long>): List<SyncedSegmentEntity>` — 批量查询
- `insertAll(segments: List<SyncedSegmentEntity>): List<Long>` — 返回生成的 ID
- `deleteByMessageIds(messageIds: List<Long>)`

**新文件**: `data/local/db/SyncedToolCallDao.kt`

- `getBySegmentIds(segmentIds: List<Long>): List<SyncedToolCallEntity>` — 批量查询
- `insertAll(toolCalls: List<SyncedToolCallEntity>)`
- `deleteBySegmentIds(segmentIds: List<Long>)`

#### 2.3 数据库迁移

**文件**: `data/local/db/AppDatabase.kt`

- `@Database(entities = [...])` 中添加 4 个新实体
- 版本从 1 升级到 2
- 添加 `Migration(1, 2)` 创建 4 张新表
- 添加新 DAO 的 abstract 方法

#### 2.4 缓存仓库

**新文件**: `data/repository/SyncedChatCacheRepository.kt`

封装所有 Room 读写逻辑：

- `getCachedSessions(): List<SyncedSession>` — 从 Room 读取，映射为领域模型
- `getCachedMessages(sessionKey, limit): List<SyncedMessage>` — 读取消息 + segments + toolCalls，在内存中组装，映射为领域模型
- `cacheSessions(sessions: List<SyncedSession>)` — 写入 Room
- `cacheMessages(sessionKey, messages: List<SyncedMessage>)` — 事务操作：先删除该会话旧消息，再插入新消息 + segments + toolCalls
- `clearSession(sessionKey: String)` — 删除会话，级联删除消息

#### 2.5 SyncedChatWsManager 缓存优先策略

**文件**: `SyncedChatWsManager.kt`

添加 `SyncedChatCacheRepository` 依赖（通过 AppGraph 注入）。

修改 `sendOperatorConnect()`：

```kotlin
if (response.isOk) {
    _connectionState.value = true

    // 1. 立即展示缓存数据
    val cachedSessions = cacheRepo.getCachedSessions()
    if (cachedSessions.isNotEmpty()) _sessions.value = cachedSessions
    val cachedMessages = cacheRepo.getCachedMessages(_currentSessionKey.value, 50)
    if (cachedMessages.isNotEmpty()) _messages.value = cachedMessages
    _isLoading.value = false  // UI 立即渲染

    // 2. 后台同步最新数据
    coroutineScope {
        launch { loadSessionsAndCache() }
        launch { loadHistoryAndCache(_currentSessionKey.value) }
    }
}
```

新方法 `loadSessionsAndCache()` / `loadHistoryAndCache()`：
- 从 WebSocket 拉取（同现有逻辑）
- 更新 StateFlow（UI 自动刷新差异部分）
- 通过 `cacheRepo` 持久化到 Room

修改 `switchSession()`：先加载缓存消息，再后台刷新。

#### 2.6 AppGraph 接线

**文件**: `di/AppGraph.kt`

```kotlin
val syncedSessionDao by lazy { AppDatabase.getInstance(appContext).syncedSessionDao() }
val syncedMessageDao by lazy { AppDatabase.getInstance(appContext).syncedMessageDao() }
val syncedSegmentDao by lazy { AppDatabase.getInstance(appContext).syncedSegmentDao() }
val syncedToolCallDao by lazy { AppDatabase.getInstance(appContext).syncedToolCallDao() }
val syncedChatCacheRepo by lazy {
    SyncedChatCacheRepository(syncedSessionDao, syncedMessageDao, syncedSegmentDao, syncedToolCallDao)
}
```

---

### 阶段三：分页 + 懒渲染优化 (第 5-7 天)

#### 3.1 滚动加载更多（分页）

**文件**: `SyncedChatWsManager.kt`

新增 `loadMoreHistory(sessionKey: String, beforeIndex: Int, limit: Int)`：
- 先尝试从 Room 缓存读取更早的消息
- 如果缓存不足，从 Gateway 请求更大 limit + offset
- 将旧消息前置到现有 `_messages.value`

**文件**: `ChatFragment.kt`

给 `messagesRecycler` 添加 `OnScrollListener`：

```kotlin
binding.messagesRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        if (lm.findFirstVisibleItemPosition() <= PREFETCH_THRESHOLD) {
            chatViewModel.loadMore()
        }
    }
})
```

**文件**: `ShellChatViewModel.kt`

新增 `loadMore()`，调用 manager 并在保持滚动位置的情况下前置旧消息。

#### 3.2 Markwon 解析结果缓存

**文件**: `ChatMessageAdapter.kt`

添加 LruCache 缓存已解析的 Spanned 对象：

```kotlin
companion object {
    private val spannedCache = LruCache<String, Spanned>(100)
}
```

在 `bindAssistant()` 中：先按 messageId 查缓存，命中则直接设置 `textView.text`；未命中才调用 `Markwon.setMarkdown()` 并存入缓存。

#### 3.3 ChatSessionAdapter 改用 DiffUtil

**文件**: `ui/chat/ChatSessionAdapter.kt`

将 `notifyDataSetChanged()` 替换为 `ListAdapter` + `DiffUtil.ItemCallback`。`ChatSessionItem` 是 data class，`areContentsTheSame` 直接用 `==`。

#### 3.4 RecyclerView 调优

**文件**: `ChatFragment.kt`

```kotlin
binding.messagesRecycler.setItemViewCacheSize(10)
binding.messagesRecycler.recycledViewPool.setMaxRecycledViews(TYPE_ASSISTANT, 8)
```

---

### 阶段四：WebSocket 预热连接 (第 1 天，与阶段一并行)

#### 4.1 Gateway 就绪后立即连接

**文件**: `data/repository/GatewayManager.kt`（或 `GatewayService`）

Gateway 健康检查通过后，立即触发：

```kotlin
AppGraph.syncedChatWsManager.connect()
```

用户打开聊天时，WebSocket 已认证完成、sessions 已加载、缓存历史已就绪。

#### 4.2 防止重复连接

**文件**: `SyncedChatWsManager.kt`

确保 `connect()` 是幂等的——如果已连接或正在连接，直接返回。当前 ViewModel 的 `start()` 会调用 connect，需要处理预热已建立连接的情况。

---

## 需要修改的关键文件

| 文件 | 阶段 | 变更内容 |
|------|------|---------|
| `data/repository/SyncedChatWsManager.kt` | 1,2,3 | 并行加载、缓存优先、分页 |
| `data/local/db/AppDatabase.kt` | 2 | 新实体、迁移 v1→v2 |
| `data/local/db/SyncedSessionEntity.kt` | 2 | 新文件 |
| `data/local/db/SyncedMessageEntity.kt` | 2 | 新文件 |
| `data/local/db/SyncedSegmentEntity.kt` | 2 | 新文件 |
| `data/local/db/SyncedToolCallEntity.kt` | 2 | 新文件 |
| `data/local/db/SyncedSessionDao.kt` | 2 | 新文件 |
| `data/local/db/SyncedMessageDao.kt` | 2 | 新文件 |
| `data/local/db/SyncedSegmentDao.kt` | 2 | 新文件 |
| `data/local/db/SyncedToolCallDao.kt` | 2 | 新文件 |
| `data/repository/SyncedChatCacheRepository.kt` | 2 | 新文件 — 缓存读写逻辑 |
| `di/AppGraph.kt` | 2,4 | 接线 DAO、缓存仓库、Markwon |
| `ui/shell/chat/ChatFragment.kt` | 3 | 滚动监听器（分页） |
| `ui/shell/ShellChatViewModel.kt` | 3 | loadMore() 方法 |
| `ui/chat/ChatMessageAdapter.kt` | 3 | Markwon Spanned 缓存 |
| `ui/chat/ChatSessionAdapter.kt` | 3 | DiffUtil 改造 |
| `data/repository/GatewayManager.kt` | 4 | 预热触发 |

## 复用现有代码

- `AppDatabase` 单例模式 → `data/local/db/AppDatabase.kt`
- `AppGraph` 服务定位器 → `di/AppGraph.kt` — 沿用现有 `lazy` 模式
- `SyncedMessage`、`SyncedSession` 领域模型 → `domain/model/SyncedChatModels.kt`
- `ContentSegment`、`ToolCallUiModel` → `domain/model/` — 用于实体映射
- `ChatMessageItem.buildSyncedList()` → `ui/chat/ChatMessageItem.kt` — 直接复用
- `BaseListAdapter` → `core_common` — 作为 SessionAdapter DiffUtil 改造参考
- `GatewayManager.gatewayState` StateFlow — 订阅此状态触发预热

## 验证方案

### 阶段一验证
1. 构建: `./gradlew assembleDebug`
2. 安装并打开聊天 — 确认 sessions 和 messages 并行加载（无串行延迟）
3. 关闭 Gateway 测试 — 确认超时错误在 5s 内出现
4. 确认消息数约 50 条，而非 200 条

### 阶段二验证
1. 构建: `./gradlew assembleDebug`
2. 打开聊天，等待完全加载，然后杀掉 App
3. 重新打开 App → 聊天应立即显示缓存消息（WebSocket 连接之前）
4. 用 App Inspection 工具检查 Room 数据库 — 确认 4 张新表有数据
5. 切换会话 — 缓存消息应立即显示，后台同步后自动更新差异

### 阶段三验证
1. 创建一个 100+ 条消息的会话
2. 打开该会话 — 初始只显示约 50 条
3. 向上滚动 — 更多消息自动加载
4. 确认滚动无卡顿（Markwon 缓存生效）
5. 检查侧边栏会话列表更新平滑（无全列表闪烁）

### 阶段四验证
1. 启动 App，等待 Gateway 初始化
2. 在 logcat 中确认 WebSocket 连接在打开聊天前已建立
3. 打开聊天 — 应近乎瞬间完成（无 WebSocket 握手延迟）
