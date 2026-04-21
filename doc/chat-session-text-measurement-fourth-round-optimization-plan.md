# Chat Session 切换第四轮文本测量优化方案

## 摘要

最新日志已经把瓶颈进一步收敛出来：

- `submitToCommitMs=47`：列表提交不是主要问题。
- `commitToFirstDrawMs=523`：单次首屏 `measure/layout/draw` 仍然过重。
- `layoutPassCount=1`、`didAutoScroll=false`：重复滚动布局已经排除。
- `assistantBind=4ms`、`toolCallBind=18ms`、`markdown=4ms`：adapter 绑定和 markdown 转换都不是主瓶颈。

这说明当前瓶颈基本落在“首屏消息气泡的文本测量和绘制”上，尤其是长文本与富文本 `TextView`。本轮目标固定为：

- 优先压缩 `commitToFirstDrawMs`
- 把长文本气泡从“自由测量”改成“受约束测量”
- 把 `TextView` 的高成本特性降下来
- 目标区间：
  - `commitToFirstDrawMs < 180ms`
  - `totalToStableMs < 300ms`

## 关键改动

### 1. 给消息气泡加明确宽度约束

- 在 assistant、user、tool text、tool call 相关 item 布局中，取消依赖 `wrap_content` 的自由宽度测量策略。
- 统一调整为：
  - 外层 item 仍是 `match_parent`
  - 气泡容器宽度改成受约束的固定上限，不再纯 `wrap_content`
  - 文本 `TextView` 使用受约束宽度布局
- 默认宽度策略：
  - 最大宽度为聊天区域可用宽度的 `0.78f`
  - user 和 assistant 统一采用相同上限策略
  - tool call / tool text 使用更紧凑上限，默认 `0.72f`
- 实现方式固定为：
  - 在代码中计算 `maxBubbleWidthPx`
  - adapter 绑定时给气泡根布局和文本 view 设置 `maxWidth`
- 不在 XML 中写死 dp 宽度，避免不同屏幕下失真。

### 2. 关闭 assistant 文本默认 selectable

- 当前 `item_chat_message_assistant.xml` 的 `messageView` 默认启用了 `textIsSelectable=true`。
- 这会显著增加富文本 `TextView` 的内部开销，尤其在长 markdown 文本上。
- 本轮改为：
  - 默认 `textIsSelectable=false`
  - 继续保留长按复制整条消息
- 不做“点击进入选择模式”的二次交互，本轮只保留现有长按复制行为。
- streaming assistant 本来就是不可选，保持不变。

### 3. 对 assistant 文本启用 `PrecomputedTextCompat`

- 继续保留现有 markdown `Spanned` 缓存，但增加文本布局预计算。
- 针对 assistant 消息：
  - 使用 `PrecomputedTextCompat` 在后台线程预计算文本布局参数
  - 预计算时复用最终 `TextView` 的字号、行距、字重、break strategy
- 缓存键固定为：
  - `messageId + contentHash + widthBucket`
- widthBucket 使用有限离散值，避免因像素级差异导致缓存命中率过低。
- UI 绑定时：
  - 命中预计算缓存则直接赋值
  - 未命中才退回普通 `setText`
- 本轮只对 assistant 消息做预计算，不先扩展到 user/tool。

### 4. tool 文本同步采用受约束文本策略

- `item_tool_call.xml` 和 `item_tool_timeline_text.xml` 继续沿用当前单层结构，但调整文本控件参数：
  - `contentView` 使用明确 `maxWidth`
  - `tool call` 文本保持最多两行
  - `tool text` 保持单行或受控多行，避免超长说明撑爆测量
- 如果某些 tool description 本身过长，优先用省略展示，不在首屏完整铺开。
- 信息完整性通过详情页或长按复制解决，不在本轮展开新交互。

### 5. 聊天气泡布局统一收敛到“受控排版”

- assistant/user/tool 的核心原则统一：
  - 不再让 `TextView` 在 `wrap_content` 条件下自由探索理想宽度
  - 所有首屏文本都在受控宽度内完成排版
- 同时统一文本参数，避免高成本默认值：
  - 明确 `includeFontPadding=false`
  - 明确 line spacing
  - 对 assistant 文本设置合理 break strategy / hyphenation 兼容配置
- 本轮不改视觉主题，不改字号，不改气泡样式，只改布局约束和文本排版参数。

### 6. 继续保留并扩展 profiling

- 保留现有 `renderTrace`，不删除。
- 新增辅助字段：
  - `visibleAssistantCount`
  - `visibleToolCount`
  - `bubbleWidthPx`
- 目的：
  - 验证宽度约束后 `commitToFirstDrawMs` 是否显著下降
  - 确认首屏是否仍被少量富文本消息拉高
- 若第四轮后 `commitToFirstDrawMs` 仍然高于目标，再进入第五轮：
  - 首屏只渲染纯文本摘要
  - markdown 富文本延后一帧替换

## 测试方案

- 同一 session 基线对照：
  - 仍用当前这组约 29 条消息的 session，对比第四轮前后日志
- 宽度约束验证：
  - assistant/user/tool 首屏消息应明显收敛到稳定宽度
  - `commitToFirstDrawMs` 应明显下降
- selectable 回归：
  - assistant 消息不再支持原生文本选择
  - 长按复制整条消息仍正常
- markdown 预计算验证：
  - 第二次切回相同 session 时，assistant 首屏消息应进一步降低首次绘制延迟
- tool 文本验证：
  - tool call 文本不应在首屏引发明显额外测量卡顿
  - 超长描述在两行内稳定截断
- 验收目标：
  - `submitToCommitMs` 保持可接受范围
  - `commitToFirstDrawMs` 从约 `523ms` 压到 `120ms - 180ms`
  - `totalToStableMs` 从约 `639ms` 压到 `180ms - 300ms`
- 如果未达标：
  - 第五轮改为首屏纯文本占位 + 富文本异步替换

## 假设

- 当前问题主要发生在缓存命中 session 的首屏展示，而不是网络冷加载。
- 用户更看重切换后尽快看到稳定内容，而不是保留原生文本选择能力。
- 可以接受 tool 描述在首屏阶段更保守地截断显示。
- 本轮不引入 Compose、不改消息协议、不修改数据层缓存结构。
