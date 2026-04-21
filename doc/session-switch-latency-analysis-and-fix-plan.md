# Shell 会话切换与前 50 条默认缓存命中方案

## 摘要

目标从“已访问 session 间切换接近秒开”升级为：

- 打开聊天主界面后，侧栏前 50 个 session 的点击应默认命中缓存。
- 用户首次点击这 50 个 session 中的任意一个时，不应再额外等待远端历史加载后才看到内容。
- 仍然保持当前 session 的后台刷新能力，但刷新不能阻塞首次切换体验，也不能覆盖当前已切到的其他 session。

现状问题：

- 当前只会为“当前 session”读消息缓存，不会在主界面打开后批量预热前 50 个 session 的消息。
- 所以 session 列表虽然有缓存，但大多数 session 的消息历史并未提前落到本地缓存。
- 用户首次点击其他 session 时，仍然需要触发一次额外的历史加载，体验不满足目标。

## 关键改动

### 1. 明确“前 50 条默认命中缓存”的产品定义

- “前 50 条”以聊天主界面展示顺序为准，即 `sessions.list` 返回并排序后的前 50 个 session。
- 命中缓存的定义是：
  - 点击时能够立即从内存或 Room 得到该 session 的首屏消息列表。
  - 不依赖点击后再等待远端返回才显示首屏内容。
- 默认缓存窗口仍为每个 session 最近 50 条消息。

### 2. 在主聊天界面初始化后执行批量预热

- 当聊天主界面可用且 session 列表已拿到后，后台启动一个“session 历史预热任务”。
- 预热范围固定为当前排序后的前 50 个 session。
- 对每个 session 执行：
  - 先检查内存快照是否已存在。
  - 再检查 Room 是否已有最近 50 条缓存。
  - 只有两者都缺失时才请求远端历史并写入缓存。
- 当前选中的 session 仍优先处理，其余 49 个 session 在后台顺序或受控并发预热。

默认实现选择：

- 使用受控并发，最大并发数固定为 `3`。
- 不使用 50 并发，避免压垮 Gateway 和本地 IO。
- 当前选中 session 先完成，再开始其余前 49 个 session 的预热。

### 3. 新增“预热状态”与去重机制

- 在 `SyncedChatWsManager` 中新增：
  - `prewarmedSessionKeys: MutableSet<String>`，标记已完成首屏缓存预热的 session。
  - `sessionPreloadJobs: ConcurrentHashMap<String, Job>`，避免重复预热同一 session。
  - `historyPrewarmVersion: AtomicLong`，用于 session 列表变化后的批次切换。
- 每次 session 列表刷新后：
  - 重新计算最新前 50 个 session。
  - 对已经不在前 50 内的 session 不清缓存，但停止继续排队预热。
  - 对新进入前 50 且尚未预热的 session 加入预热。
- `switchSession()` 点击时优先读取：
  - 内存快照。
  - Room 缓存。
  - 若均未命中，理论上只会发生在不属于前 50 的 session 或预热尚未完成的极短窗口。

### 4. 引入会话级内存快照，确保点击即切换

- 在 `SyncedChatWsManager` 中增加 `sessionSnapshots: ConcurrentHashMap<String, SessionSnapshot>`。
- `SessionSnapshot` 至少包含：
  - `messages: List<SyncedMessage>`
  - `lastSyncedAt: Long`
  - `isWarm: Boolean`
- 预热成功后，不仅写 Room，也写入内存快照。
- `switchSession(sessionKey)` 执行顺序固定为：
  1. 如果已是当前 session，直接返回。
  2. 优先命中 `sessionSnapshots`。
  3. 若未命中，再读 Room。
  4. 若仍未命中，再进入冷加载路径。
- 这样在前 50 个 session 中，已预热项可以做到真正的点击即切换。

### 5. 保留后台刷新，但严格不阻塞切换

- 对前 50 个 session 的预热，只要求“首屏可立即显示”，不要求点击时一定已经拿到最新远端状态。
- 点击后如果命中缓存：
  - 立即显示缓存内容。
  - 后台静默刷新远端最新历史。
- 后台刷新必须带 session 代次保护：
  - 只允许更新当前仍选中的 session 的 UI。
  - 其他 session 只更新内存和 Room，不覆盖当前界面。
- 快速切换 `A -> B -> C` 时，A/B 的慢刷新不得覆盖 C。

### 6. 调整缓存仓库接口，支持批量预热判定

- `SyncedChatCacheRepository` 新增轻量能力：
  - `hasRecentMessages(sessionKey, limit): Boolean`
  - 或 `getCachedMessageCount(sessionKey): Int`
- 目的不是读出完整内容，而是快速判断一个 session 是否已经具备首屏缓存。
- 预热调度先走轻量判定，再决定是否需要完整读取或远端拉取。
- 现有 `cacheMessages()` 需要保留“内容未变化则跳过重写”的逻辑，避免 50 个 session 预热引发不必要的整会话重写。

### 7. UI 行为同步更新

- `ShellActivity` / `ShellChatViewModel` 的目标行为调整为：
  - 主界面进入后，允许后台进行前 50 个 session 的缓存预热。
  - 用户点击前 50 内 session 时，默认不展示 loading 首屏。
  - 如果用户点击时该 session 仍在极短预热窗口内，允许先展示 sidebar 选中态，再以缓存到达即切换，不等待远端。
- 当前的 Markdown 缓存和 RecyclerView 优化继续保留，不新增新的渲染策略。

## 测试方案

- 主界面首次打开：
  - session 列表加载完成后，后台开始预热前 50 个 session 的最近 50 条消息。
  - 当前 session 优先完成，其余 session 继续后台执行。
- 前 50 条命中验证：
  - 在主界面停留到预热完成后，随机点击前 50 个 session 中任意一个。
  - 预期立即显示首屏消息，不出现等待远端的明显空档。
- 第 51 条以后验证：
  - 点击第 51 个及之后的 session。
  - 允许进入冷加载或普通缓存路径，不要求默认命中。
- 快速切换验证：
  - 连续点击多个前 50 session。
  - 界面必须始终显示最后一次点击的 session，旧刷新不得回滚。
- 预热幂等验证：
  - 反复进入聊天主界面或 session 列表刷新。
  - 已预热 session 不应重复发起远端拉取。
- Room 与内存一致性：
  - 杀进程重启后重新进入主界面。
  - 已完成预热的前 50 session 应可直接从 Room 恢复并继续命中。
- 性能验证：
  - logcat 中确认不会在初始化时对 50 个 session 同时发起无限制请求。
  - 预热最大并发固定为 3。
- 回归验证：
  - 新建 session、删除 session、分页加载、发送消息、Gateway 重连后 session 列表变化，预热集合都能正确更新。

## 假设

- “前 50 条默认命中缓存”指前 50 个 session 各自缓存最近 50 条消息，而不是缓存 50 个 session 的全量历史。
- 本轮仍保留现有 Room 表结构，不新增 migration。
- 预热采用最大并发 3 作为默认值。
- 只对主聊天界面展示顺序中的前 50 个 session 提供默认缓存命中保证，超出范围不强制保证。
