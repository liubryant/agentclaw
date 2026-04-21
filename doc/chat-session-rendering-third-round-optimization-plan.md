# Chat Session 切换第三轮渲染优化方案

## 摘要

现有日志已经足够说明主瓶颈位置：

- `submitToCommitMs=15`：`submitList` 和 Diff 已经不是主要问题。
- `commitToFirstDrawMs=468`：主要耗时在 RecyclerView 提交之后的 `measure/layout/draw`。
- `totalToStableMs=1137`：首帧之后仍有二次或多次布局，说明当前切换路径存在额外布局放大器。
- `assistantBind=27ms`、`toolCallBind=35ms`、`markdown=26ms`：绑定逻辑有成本，但远小于总耗时，不是头号瓶颈。

这轮目标固定为：

- 把 `commitToFirstDrawMs` 作为第一优化目标。
- 先消除“切换后额外滚动导致的重复布局”。
- 再压缩 tool row 的视图层级和文本测量成本。
- 最终目标不是 10ms 全链路稳定显示，而是：
  - 缓存命中 session：`commitToFirstDrawMs < 120ms`
  - `totalToStableMs < 250ms`

## 关键改动

### 1. 先消除切换时的重复滚动布局

- 在 `ChatFragment.kt` 调整自动滚动策略。
- 切换 session 时不再沿用“新增消息自动滚到底部”的规则。
- 行为改为：
  - 新 session 首次展示缓存列表时，如果已经是完整首屏切换，不主动执行 `scrollToPosition(lastIndex)`。
  - 只有当前 session 内新增消息、streaming 更新、或用户已在底部附近时，才自动滚动。
- `stackFromEnd = true` 保留，但避免在 `submitList` commit callback 中再额外触发一次滚动。
- 新增明确区分：
  - `reason = SESSION_SWITCH`
  - `reason = NEW_MESSAGE`
  - `reason = STREAMING_UPDATE`
- 只有后两种 reason 允许自动滚动到底部。

### 2. 把布局阶段埋点拆到可定位重复 layout 的粒度

- 继续保留现有 `renderTrace`，但补足以下埋点：
  - 一次 `submitList` 后记录 RecyclerView 在下一次稳定前触发了几次 `onLayoutCompleted`
  - 记录 `scrollToPosition` 是否发生
  - 记录是否发生 `requestLayout` 连锁
- 输出字段固定新增：
  - `layoutPassCount`
  - `didAutoScroll`
  - `scrollReason`
  - `itemCountVisibleOnFirstDraw`
- 目的不是长期保留日志，而是验证第一轮优化是否真的把二次布局去掉。

### 3. 压扁 tool item 视图层级

- 当前 `item_chat_message_tool_call_flat.xml` 和 `item_tool_call.xml` 仍是多层包裹。
- 下一轮改成单一 item 布局，不再使用 `FrameLayout + LinearLayout + include` 组合。
- Tool call item 直接使用一个根布局承载：
  - timeline 线段
  - icon
  - content
  - status
- Tool text item 同样改成单一根布局，不再额外包一层“气泡容器 + include”。
- 视觉样式保持当前效果，不改颜色和间距语义，只减少层级和测量节点数。
- 这一步的目标是降低每个 tool row 的 `measure/layout` 成本，而不是绑定成本。

### 4. 把可见区文本预计算做得更激进

- 当前 markdown 已有预热，但日志显示首帧仍然偏慢，说明仅缓存 `Spanned` 还不够。
- 下一轮默认策略：
  - session 切换时，只优先预热“首屏可见范围”的 assistant markdown 和 tool description 文本。
  - 范围按预计首屏 8 到 12 个 item 计算，而不是全量 29 条。
- 对 assistant：
  - 继续复用 `Spanned` 缓存。
- 对 tool call：
  - 新增按 `toolCallId + description` 的文本缓存，避免同一描述重复排版准备。
- 不引入自定义 TextView 或手工 StaticLayout；本轮仍保持标准 TextView，但把可预热文本先准备好，减少首帧时的文本构建和 span 应用成本。

### 5. 明确把“首屏可见”与“全列表稳定”拆开

- 切换 session 的成功标准改成两阶段：
  - 第一阶段：首屏可见内容快速出现
  - 第二阶段：列表其余项逐步稳定
- 实现上：
  - 首次 `submitList` 仍提交完整列表
  - 但预热优先级只给当前 viewport 可能命中的那部分 item
- 不引入 skeleton/shimmer，不额外改 UI 风格。
- 这样做的目的是让可感知延迟先降下来，而不是追求一次性把所有复杂 item 完整排版结束。

### 6. 保持当前数据层不动，避免扩大变量

- 本轮不修改 `SyncedChatWsManager` 的 session 切换与缓存发布逻辑。
- 不改 Room 结构，不改消息模型协议，不改 markdown 库。
- 所有优化限定在：
  - `ChatFragment`
  - `ChatMessageAdapter`
  - tool item 布局 XML
  - 轻量文本预热缓存
- 这样可以确保日志变化能明确归因到渲染层，而不是被数据层改动掩盖。

## 测试方案

- 基线对照：
  - 用同一个约 29 条消息、11 个 tool call 的 session，记录优化前日志。
- 自动滚动验证：
  - 切 session 时禁用自动滚动后，预期 `didAutoScroll=false`
  - `layoutPassCount` 明显下降
  - `commitToFirstDrawMs` 明显下降
- 新消息回归：
  - 在当前 session 内发送新消息或 streaming 更新
  - 仍应自动滚到底部，不影响原本聊天体验
- tool item 扁平布局验证：
  - 同一 session 切换前后对比 `commitToFirstDrawMs`
  - 预期 tool 多的会话下降最明显
- 文本预热验证：
  - 第二次切回相同 session 时，assistant markdown 与 tool description 不应再次成为首帧热点
- 验收目标：
  - `submitToCommitMs` 保持在当前量级
  - `commitToFirstDrawMs` 从约 `468ms` 压到 `80ms - 150ms`
  - `totalToStableMs` 从约 `1137ms` 压到 `150ms - 300ms`
- 若以上仍未达标，再进入第四轮：
  - 只提交首屏窗口 + 后续增量挂载
  - 或引入更激进的文本布局缓存

## 假设

- 当前日志来自缓存命中 session，而不是冷加载 session。
- 用户优先要的是“点击 session 后马上看到聊天内容”，不是必须一帧内完成全列表稳定。
- 可以接受把“切换 session 自动滚到底部”的策略改得更保守，只在真正新增消息时自动滚动。
- 本轮不追求 10ms 全链路最终显示，10ms 只作为局部路径参考值，不作为验收标准。
