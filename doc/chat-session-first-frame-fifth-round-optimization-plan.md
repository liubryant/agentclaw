# Chat Session 切换第五轮首帧优化方案

## 摘要

这条新日志说明第四轮已经有效，但瓶颈还没清掉：

- `commitToFirstDrawMs` 从 `523ms` 降到 `372ms`，说明宽度约束和 precomputed text 起作用了。
- `layoutPassCount=1`、`didAutoScroll=false` 继续说明问题不是重复 layout，也不是滚动。
- 首屏只有 `5` 个可见 item，其中 `1` 个 assistant、`4` 个 tool。
- `assistantBind=21ms`、`markdown=21ms`、`toolCallBind=23ms`，加起来不到 `50ms`，剩下约 `320ms` 仍然在首帧测量和绘制。

这说明下一轮不能再继续只抠 `submitList` 或 Diff，而要直接把“首帧富文本/复杂文本渲染”拆成两阶段。目标改为：

- 缓存命中 session：`commitToFirstDrawMs < 180ms`
- `totalToStableMs < 260ms`
- 允许首帧先显示纯文本版本，再在下一帧升级成 markdown 富文本

## 关键改动

### 1. assistant 首帧改成纯文本，占位后再升级为 markdown

- 对首屏可见 assistant item，不在第一次 `submitList` 时直接绑定 markdown `Spanned/PrecomputedText`。
- 首帧策略固定为：
  - 第一次绑定先显示纯文本 `content`
  - 首次 `doOnPreDraw` 后，通过主线程下一帧或短延迟任务，把当前可见 assistant item 升级成 markdown
- 这样可以把最重的富文本绘制从 `commitToFirstDraw` 移到首帧之后。
- 纯文本和 markdown 使用同一条消息 id，不触发整列表 `submitList`，只更新当前可见 ViewHolder 内容。

### 2. tool call 首帧改成单行紧凑模式

- 对首屏可见 tool item，第一次绘制时只显示单行文本，不做两行展开。
- 首帧参数固定为：
  - `maxLines = 1`
  - `ellipsize = end`
  - 保持 icon 和 timeline 结构不变
- 在首帧完成后，再把当前可见 tool item 恢复为正常两行模式。
- 目的不是省 `bind` 时间，而是压首屏文本 layout 成本。

### 3. 把“首帧模式”显式加到 UI 模型里

- 在 `ChatMessageItem` 或 adapter 内部新增轻量渲染模式概念：
  - `FIRST_FRAME`
  - `FULL_RENDER`
- session 切换命中缓存时：
  - 首次提交列表使用 `FIRST_FRAME`
  - 首帧日志打完后，对当前可见区触发“升级渲染”
- streaming、新消息、分页不走这个路径，仍直接用正常渲染模式。
- 这样实现清晰，也避免 adapter 内靠临时状态猜测当前是不是首帧。

### 4. 宽度来源改成 RecyclerView 实际可用宽度

- 当前日志里 `bubbleWidthPx=1296`，说明宽度计算仍偏大，来源很可能是 `root/rootView`，不够贴近实际消息列表区域。
- 下一轮统一改为：
  - 宽度从 `messagesRecycler` 的实际 measured width 推导
  - 扣除 `fragment_shell_chat.xml` 里的外层 padding、内部容器 padding、RecyclerView padding
- 不再从 item root 或 rootView 推测。
- 这样可以让：
  - `maxWidth` 更贴近真实内容区
  - precomputed text 的 width bucket 更准确
  - 首帧测量结果更稳定

### 5. 可见区升级渲染只处理 viewport，不碰整列表

- 首帧完成后的 markdown/两行 tool 升级，只针对当前可见区 item。
- 具体行为固定为：
  - 读取 `LinearLayoutManager.findFirstVisibleItemPosition()/findLastVisibleItemPosition()`
  - 只对这一区间内的 assistant/tool item 发 payload 更新
- 不重新 `submitList`
- 不更新不可见 item，避免又引发整轮 layout
- 不可见 item 等滚进视口时，直接按 `FULL_RENDER` 正常绑定即可

### 6. profiling 扩展到“首帧后升级成本”

- 保留现有 `renderTrace`
- 新增一条二阶段日志：
  - `postFrameUpgradeMs`
  - `upgradedAssistantCount`
  - `upgradedToolCount`
- 这样可以把“首帧快显示”和“完整稳定显示”拆开看。
- 目标是：
  - `commitToFirstDrawMs` 显著下降
  - `postFrameUpgradeMs` 可控，不出现明显闪烁

## 测试方案

- 同一 session 回归：
  - 继续用这组 29 条消息的 session 做对照
- 首帧指标：
  - `commitToFirstDrawMs` 目标从约 `372ms` 压到 `120ms - 180ms`
- 升级阶段指标：
  - `postFrameUpgradeMs` 应控制在 `80ms - 150ms`
  - 不应导致明显闪屏或跳动
- 可见区验证：
  - 首屏 assistant 首先出现纯文本，随后升级成 markdown
  - tool call 首先单行，随后恢复正常两行
- 非首屏验证：
  - 向下滚动后的 item 直接使用完整渲染，不走首帧降级
- 回归：
  - streaming assistant、tool timeline、分页加载、长按复制行为保持正常

## 假设

- 用户更在意“点击 session 后尽快看到内容”，可以接受首帧先显示纯文本/紧凑文本，下一帧再变成完整富文本。
- 不引入 shimmer、骨架屏或新的 loading 动画。
- 本轮继续不改数据层、Room 和消息协议，只在 UI 渲染层做两阶段显示优化。
