# Synced Chat `chat.final` 局部收敛优化方案

## Summary
当前 [`SyncedChatWsManager.kt`](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/repository/SyncedChatWsManager.kt) 的 `handleChatEvent()` 在收到 `state = "final"` 时，会直接：

1. 清空 transient 状态
2. 调用 `loadHistory(_currentSessionKey.value)`
3. 重新生成完整消息列表

这会导致同步聊天页在最终收口时触发整段列表重建，RecyclerView 刷新成本高，表现为布局明显卡顿。

本方案的目标是：

- `chat.final` 到来时优先在本地完成最终消息收敛
- 只更新最后一条 assistant / tool-chain 相关 item
- 后台静默 `loadHistory()` 做一致性校准，不再阻塞前台 UI

## Current Problem
- 流式阶段，UI 通过 `streamingToolChain` 和 `activeAssistantMessageId` 展示临时 assistant/tool-chain item。
- 最终阶段，`handleChatEvent(state = "final")` 没有复用本地已累计的流式文本和工具链，而是直接 `loadHistory()`。
- [`SyncedChatViewModel.kt`](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/ui/synced_chat/SyncedChatViewModel.kt) 会基于新的 `_messages` 重新调用 `ChatMessageItem.buildSyncedList(messages)`，导致末尾一次对话被整段重组。
- 如果 `final` 事件里的文本本质上只是流式文本的最终补齐，那么直接全量拉 history 属于高成本低收益。

## Proposed Behavior
### 1. `chat.final` 先做本地局部收敛
在 [`SyncedChatWsManager.kt`](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/repository/SyncedChatWsManager.kt) 中，`state = "final"` 时先执行本地收敛逻辑，而不是立即 `loadHistory()`：

- 先 flush 当前 `roundDeltaBuffer`
- 将 `streamingToolChain.entries` 与 `pendingText` 合并成最终 tool timeline
- 用当前 run 的稳定 id，也就是 `_activeAssistantMessageId.value`，生成最终 assistant `SyncedMessage`
- 将最终消息直接写入 `_messages`
- 然后结束 generating 状态

这样 UI 会只更新末尾少量 item，而不是整页重建。

### 2. final assistant 文本使用“尾段裁剪”策略
如果 `chat.final` payload 提供的是完整 assistant 文本 `finalText`，而本地已经累计了工具前/工具中的 assistant 文本 `totalAgentText`，则最终 assistant 文本优先使用：

```kotlin
finalText.substring(totalAgentText.length)
```

也就是只截取最终回答的尾段，而不是把前面已经体现在 tool-chain 中的文本再重复塞进 assistant item。

但这个裁剪必须带保护条件：

- `finalText.length >= totalAgentText.length`
- `finalText.startsWith(totalAgentText.toString())`

只有满足这两个条件时才允许 `substring(totalAgentText.length)`。

否则回退为：

- 直接使用完整 `finalText`，或
- 仅使用本地累积的最终尾段，随后交给静默 `loadHistory()` 校正

禁止无校验直接 `substring()`，避免越界或截错。

### 3. 不直接绕过消息模型去“塞布局”
不建议绕开消息模型直接把“截图后的数据”塞进 `ASSISTANT` 布局类型。

正确做法是：

- 先把本地 final 结果落成标准 `SyncedMessage`
- `content` 填最终 assistant 文本
- `segments` 填最终的 `ContentSegment.Text/Tools`
- 再继续复用 [`ChatUiModels.kt`](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/ui/chat/ChatUiModels.kt) 的 `buildSyncedList()` 映射出 `AssistantMessageItem` / `ToolChainMessageItem`

这样可以保持：

- 数据层和展示层职责清晰
- DiffUtil 能正常比较 item 内容
- 后续静默 history 校准时更容易对齐服务端结果

## Implementation Changes
### `SyncedChatWsManager`
新增或整理以下私有逻辑：

- `flushPendingAssistantDelta()`
  - 将 `roundDeltaBuffer` 中未 flush 的文本并入当前链路
- `buildFinalSegments(...)`
  - 基于 `streamingToolChain.entries`、`pendingText` 和最终 assistant 尾段，生成最终 `segments`
- `finalizeAssistantMessage(payload: Map<String, Any?>)`
  - 在本地完成最终 assistant 消息构造和 `_messages` 更新
- `scheduleSilentHistoryRefresh()`
  - `chat.final` 后后台异步 `loadHistory()` 和 `loadSessions()`，只做校准

`handleChatEvent(state = "final")` 调整为：

1. 本地 finalize
2. 清理 transient run state
3. 关闭 generating 状态
4. 后台静默 history refresh

### 消息落地规则
最终 assistant 消息落地为标准 `SyncedMessage`：

```kotlin
SyncedMessage(
    id = activeAssistantMessageId,
    role = "assistant",
    content = finalAssistantText,
    createdAt = System.currentTimeMillis(),
    isStreaming = false,
    toolCalls = finalToolCalls,
    segments = finalSegments
)
```

其中：

- `content` 只保存最终 assistant 文本
- `segments` 保存完整最终展示结构
- 工具前文本、工具链、工具后工作文本继续由 `segments` 决定拆分方式

### `ChatUiModels.buildSyncedList()`
保持现有拆分思路，但要保证历史 item id 唯一且稳定：

- tool-chain item 使用 `${message.id}:tools`
- trailing assistant item 使用 `${message.id}:trailing`
- 未来如果一个 message 会拆出多个 text/tools item，需要继续带上 index 后缀

目标是避免同一 `SyncedMessage` 拆出多个 UI item 时复用同一个 id，减少 DiffUtil 错绑和重绘放大。

## Silent Reconciliation
默认策略采用“局部收敛 + 静默校准”。

也就是：

- 前台先信任本地流式累计结果，立即完成 UI 收口
- 后台再异步执行 `loadHistory(currentSessionKey)` 与 `loadSessions()`
- 如果服务端历史与本地一致，UI 基本无感
- 如果服务端最终存档和本地拼接有差异，以 history 结果覆盖本地

这样可以同时兼顾：

- 最终收口流畅度
- 与服务端持久化结果的一致性

## Test Cases
### 无工具场景
- `assistant delta -> chat.final`
- final 后只更新末尾 assistant item
- 不触发整段历史闪动

### 单工具场景
- `text -> tool -> final`
- tool-chain item 保留中间文本和工具链
- assistant item 只显示 `finalText.substring(totalAgentText.length)` 得到的尾段

### 多工具场景
- `text -> tool1 -> text -> tool2 -> final`
- tool-chain 顺序不乱
- 最终 assistant 不重复显示已经并入 tool-chain 的中间文本

### 回退场景
- `finalText` 长度小于 `totalAgentText.length`
- `finalText` 不是 `totalAgentText` 前缀
- payload 缺失 final 文本字段

这些场景下不得进行危险 `substring()`，应回退为完整 `finalText` 或静默 history 校准。

### 中断场景
- `aborted`
- `error`

两种情况下继续只清空 transient，不落最终 assistant。

## Assumptions
- Gateway 协议未必总能提供结构化的“最终 assistant 尾段”，所以本方案允许优先本地收敛、再后台校准。
- `totalAgentText` 表示已经进入 tool-chain 语义范围的 assistant 文本累计值，因此它适合作为 `finalText` 的前缀裁剪基线。
- “不能直接拿截图后的数据放到 ASSISTANT 布局类型里面去吗”这个诉求，本质上可以满足，但应该通过标准 `SyncedMessage -> ChatMessageItem.AssistantMessageItem` 链路完成，而不是直接操作 Adapter 布局层。
