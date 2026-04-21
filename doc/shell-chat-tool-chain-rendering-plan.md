# Shell Chat 中间态与最终回答拆分方案

## Summary
当前问题不是单点，而是两层一起偏了：

- Android 端把“流式中间态”和“持久化最终态”都塞进同一个 `SyncedMessage` 里，再靠 `segments` 二次拆 UI，导致语义不稳定。核心问题在 `app/src/main/java/ai/inmo/openclaw/data/repository/SyncedChatWsManager.kt`。
- `buildSyncedList()` 会把同一条 assistant 消息拆成多个 `AssistantMessageItem(id = message.id)`，直接违反 DiffUtil 的唯一性假设，导致复用和刷新异常。核心问题在 `app/src/main/java/ai/inmo/openclaw/ui/chat/ChatUiModels.kt`。
- 上游 `openclaw` 的正确方向不是“猜哪段 assistant 是最终回答”，而是把“streaming assistant text”和“pending tool calls”作为临时 UI 单独维护，终态再回到历史消息。参考 `D:/WorkFiles/Web/openclaw/apps/android/app/src/main/java/ai/openclaw/app/chat/ChatController.kt`。

默认按这个目标实现：

`assistant(工具前流式)` 在工具开始前可以单独显示；一旦首个 tool 开始，后续所有中间 assistant 文本都并入 TOOL item；只有 `chat.final` 之后的最终回答才单独成为 ASSISTANT item。

## Key Changes
- 重构 `SyncedChatWsManager` 的职责：
  - `messages` 只保存历史/最终消息，不再把中间态 `segments` 写回流式 assistant 消息。
  - 新增独立 transient 状态流，至少包含：
    - `streamingAssistantText`: 首个 tool 开始前的流式 assistant 文本
    - `toolLeadText`: 首个 tool 开始时冻结的 assistant 文本
    - `toolWorkingText`: tool 开始后到 `chat.final` 前收到的 assistant 文本
    - `pendingToolCalls`: 当前工具链
  - 状态规则固定为：
    - 未见 tool 之前：`chat.delta` 只更新 `streamingAssistantText`
    - 首个 `agent.tool start` 到来时：把当前 `streamingAssistantText` 冻结到 `toolLeadText`，清空 `streamingAssistantText`
    - 已见 tool 之后：后续 assistant delta 不再生成 assistant item，只更新 `toolWorkingText`
    - `chat.final`：清空全部 transient 状态，用 final payload 或 `chat.history` 刷新最终 assistant 消息
    - `chat.aborted/error`：清空 transient 状态，不保留中间 assistant 气泡
- 调整 ViewModel 合成逻辑：
  - `ShellChatViewModel` 不再只基于 `messages` 生成列表，而是 `history items + transient items` 合成最终 `ChatScreenState.messages`
  - 合成规则固定为：
    - 没有 tool 时：末尾追加一个 streaming `AssistantMessageItem`
    - 有 tool 后：末尾只追加一个 streaming `ToolChainMessageItem`
    - terminal 事件后：不再追加 transient item，只显示 history 拆出来的最终列表
- 修改 `ToolChainMessageItem` 结构：
  - 保留 `preToolText`
  - 新增 `workingText` 或 `postToolText`
  - 可选新增 `isStreaming`
  - TOOL item 负责承载“工具前说明 + 工具链 + 工具后中间文本”
- 修改 TOOL item 布局与绑定：
  - 在 `item_chat_message_tool_chain.xml` 新增一个底部文本区域，例如 `postToolTextView`
  - `ChatMessageAdapter.bindToolChain()` 同时绑定 `preToolText` 和 `workingText`
  - 这样中间 assistant 文本都留在 TOOL 卡片内，不再额外冒出 assistant 气泡
- 收敛 `ChatUiModels.buildSyncedList()` 的职责：
  - 只处理持久化历史消息的拆分
  - 每个拆出来的 item 使用稳定唯一 id，例如 `${message.id}:text:$i`、`${message.id}:tools:$i`
  - 禁止多个 assistant item 复用同一个 `message.id`
  - 对 `isStreaming == true` 的消息不再做 segment-based 拆分；流式展示完全交给 transient 状态
- 历史刷新策略：
  - v1 默认在 `chat.final` 后立即 `loadHistory(currentSessionKey)`，用持久化消息替换本地临时态
  - 不在每个 tool result 后都拉 history，先保证交互稳定和刷新成本可控
  - 如果后续要展示 tool result 原文，再追加“tool result 后局部 reload”优化

## Public / Interface Changes
- `SyncedChatWsManager` 新增若干 `StateFlow`：
  - `streamingAssistantText`
  - `toolLeadText`
  - `toolWorkingText`
  - `pendingToolCalls`
- `ChatMessageItem.ToolChainMessageItem` 新增字段：
  - `workingText` 或 `postToolText`
  - 可选 `isStreaming`
- `ChatScreenState` 对外仍可只暴露 `messages`，不必把 transient 字段继续往 UI 层透出；transient 合成放在 ViewModel 完成

## Test Plan
- 无工具场景：
  - `user -> assistant(final only)` 仍然只出现 assistant 气泡
  - streaming 过程中只更新一个 assistant item，不产生 tool item
- 单次工具场景：
  - `assistant -> tool -> assistant(final)` 在 tool 开始后，前面的 assistant streaming 气泡消失并并入 TOOL item
  - tool 执行中后续 delta 只更新 TOOL item 底部文本
  - `chat.final` 后只保留一个 TOOL item 和一个最终 ASSISTANT item
- 多工具场景：
  - `text -> tool1 -> text -> tool2 -> final` 保持一个 TOOL item 内部顺序稳定，不额外生成中间 assistant item
- 终止场景：
  - `aborted/error` 后 transient assistant/tool 状态全部清空
  - 不残留空 assistant item 或过期 tool item
- Diff/刷新场景：
  - 同一条 assistant message 拆出的多个历史 item id 唯一
  - RecyclerView 不再因为重复 id 出现错绑、闪烁、内容错位

## Assumptions
- Gateway/ACP 协议没有显式字段告诉客户端“这条 assistant 文本是最终回答的开始”；可靠边界只有 terminal `chat.final`。
- 因此“最后的 assistant”定义为：`chat.final` 落地后的最终文本，而不是流式过程中任意一次 delta。
- v1 不展示独立的 tool-result 原始输出气泡，只展示工具链摘要和中间工作文本，先对齐目标交互形态。
