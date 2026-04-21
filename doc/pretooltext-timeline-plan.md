# `preToolText` 与 `workingText` 一起并入工具时间线的实现方案

## Summary
目标更新为：`preToolText` 和 `workingText` 都不再作为时间线外部文本显示，而是统一变成 `toolContainer` 内部的时间线节点。

默认采用同一种“文本节点”样式处理两者：

- `preToolText` 作为时间线第一条节点
- `toolCalls` 作为中间的工具节点
- `workingText` 作为时间线最后一条节点
- 两类文本节点都只显示一行，超出尾部省略
- 工具链整体顺序变成：`preToolText -> tool1 -> tool2 -> ... -> workingText`

这样可以保证所有“工具相关过程信息”都落在同一条时间线上，而不是一部分在线内、一部分在线外。

## Key Changes
- 调整 `item_chat_message_tool_chain.xml`
  - 删除或停用当前时间线外的 `preToolTextView`
  - 删除或停用当前时间线外的 `postToolTextView`
  - 保留 `toolContainer` 作为唯一的时间线内容容器
  - `toolContainer` 直接承载三类节点：前置文本节点、工具节点、后置工作文本节点
- 新增一个通用文本时间线 item 布局
  - 建议新增 `item_tool_timeline_text.xml`
  - 复用 `item_tool_call.xml` 的时间线骨架：`topLineView`、`nodeView`、`bottomLineView`
  - 中间内容改为单个 `TextView`
  - 不放 `ImageView` 和 `statusView`
  - `TextView` 配置：
    - `maxLines="1"`
    - `ellipsize="end"`
    - `includeFontPadding="false"`
    - 风格接近工具行，但可比工具名略弱一点
- 修改 `ChatMessageAdapter.bindToolChain()`
  - 不再直接绑定 `binding.preToolTextView`
  - 不再直接绑定 `binding.postToolTextView`
  - 改成先组装统一的时间线渲染列表，渲染列表顺序固定：
    - 如果 `preToolText` 非空，先加一个文本节点
    - 再加全部 `toolCalls`
    - 如果 `workingText` 非空，最后加一个文本节点
  - 遍历渲染列表时：
    - 文本节点 inflate/bind `item_tool_timeline_text.xml`
    - 工具节点 inflate/bind `item_tool_call.xml`
  - 复用策略默认采用“每次先 `container.removeAllViews()` 再按渲染列表重建”
    - 节点数通常很少，这里优先实现稳定性，避免混合 child 类型导致错绑
- 统一时间线首尾连线规则
  - 渲染列表第一个节点：隐藏 `topLineView`
  - 渲染列表最后一个节点：隐藏 `bottomLineView`
  - 中间节点：上下线都显示
  - 因为 `workingText` 也进入时间线，最后一个工具节点不一定是时间线尾部，尾线显示要由“渲染列表位置”决定，不能只看是不是 tool item
- 文本节点的展示规则
  - `preToolText` 和 `workingText` 绑定前都做 `trim()`
  - UI 上只显示单行尾部省略，不在 adapter 中手工截断字符
  - 两者默认使用相同节点样式，避免引入第二套中间态视觉语言
- 结构抽象建议
  - 在 `ChatMessageAdapter` 内新增一个私有 sealed model，例如：
    - `TimelineEntry.Text(kind = PRE/WORKING, text)`
    - `TimelineEntry.Tool(toolCall)`
  - `bindToolChain()` 只依赖这个中间渲染模型，不直接把 UI 逻辑写死在 `preToolText/toolCalls/workingText` 三段判断里
  - 这样后续如果还要把别的中间态并入时间线，只需要扩充渲染模型
- 日志与兼容性
  - 现有 `Logger.d("cjym", ...)` 可暂留用于验证节点顺序，但建议最终删除或降级
  - `ToolChainMessageItem` 字段定义不需要变化，仍保留 `preToolText` 和 `workingText`

## Public / Interface Changes
- 不需要改 `ChatMessageItem.ToolChainMessageItem` 的公开字段
- 不需要改 `ViewModel` 或 `WsManager` 的数据流
- 需要新增一个布局资源，并把 `ChatMessageAdapter` 的 tool-chain 渲染从“外部文本 + 内部工具列表”改成“统一时间线节点列表”

## Test Plan
- `preToolText + tool1 + tool2 + workingText`
  - 四个节点按顺序都出现在同一条时间线上
  - `preToolText` 为首节点，`workingText` 为尾节点
  - 工具节点位于中间，连线连续
- `preToolText + tools` 无 `workingText`
  - `preToolText` 为第一节点
  - 最后一个工具节点隐藏底部线
- `tools + workingText` 无 `preToolText`
  - 第一个工具节点隐藏顶部线
  - `workingText` 成为最后节点
- 只有 `preToolText`
  - 显示单个文本节点，无上下连线
- 只有 `workingText`
  - 显示单个文本节点，无上下连线
- 无文本，只有多个工具
  - 保持当前工具时间线表现不变
- 文本超长
  - `preToolText` 和 `workingText` 都只显示一行，尾部省略
- RecyclerView 复用滚动后
  - 不出现旧工具节点残留
  - 不出现文本节点被误绑定成工具节点
  - 首尾连线不会错位

## Assumptions
- 默认让 `preToolText` 和 `workingText` 使用同一种“文本节点”样式，不做视觉区分。
- `workingText` 现在语义上也归入“工具调用过程”，因此进入时间线末尾而不是保留在卡片底部。
- 当前优先目标是时间线语义统一和渲染稳定性，不追求 child 级别的复杂复用优化。
