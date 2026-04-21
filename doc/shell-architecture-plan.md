# 基于 Figma「初始画面-输入模式」的 Single Activity XML 架构方案

## Summary
基于已确认的方向，方案采用 `Single Activity + Multiple Fragments + Activity-scoped Shared ViewModel`，以 Figma 当前页面轮廓为母版，做成一个统一 `ShellActivity`：

- 左侧为全局侧栏：搜索、历史会话、一级导航入口
- 右侧为主内容区：承载 `ChatFragment`、`IdeasFragment`、`ScheduleFragment`
- `ChatFragment` 以现有同步聊天能力为主
- `IdeasFragment` 提供灵感卡片/模板，一键带入聊天
- `ScheduleFragment` 提供定时任务列表、创建、启停、状态，并支持“一键带入聊天”
- 统一由 `ShellSharedViewModel` 管理跨 Fragment 状态，子 Fragment 各自有 feature ViewModel 处理本页业务

## Key Changes

### 1. 壳层与导航
新增一个统一壳层：

- `ShellActivity`
  负责承载整个 XML 架构、WindowInsets、Fragment 路由、返回栈协调
- `activity_shell.xml`
  按 Figma 拆成两栏：
  - 左栏 `sidebar_container`
  - 右栏 `content_container`
- 一级导航采用 3 个并列主页面：
  - `ChatFragment`
  - `IdeasFragment`
  - `ScheduleFragment`

建议导航实现：

- 不引入 Jetpack Navigation 作为首版硬依赖
- 使用 `FragmentContainerView + FragmentTransaction show/hide`
- 三个主 Fragment 常驻，切换时保留状态，避免聊天滚动位置、输入内容、任务筛选状态丢失
- `ShellDestination` 作为唯一导航枚举，避免字符串路由散落

### 2. ViewModel 分层
采用“壳层共享 + 页面自治”两层结构：

- `ShellSharedViewModel`，`activityViewModels()` 获取
- `ChatViewModel`，仅负责聊天页内消息/会话/发送流
- `IdeasViewModel`，仅负责灵感卡片、分类、模板点击
- `ScheduleViewModel`，仅负责任务列表、启停、筛选、创建入口

`ShellSharedViewModel` 职责只放跨页状态，不吞并所有业务：

- 当前主页面 `currentDestination`
- 全局搜索词 `sidebarQuery`
- 当前选中的会话/模板/任务摘要
- 跨页跳转事件：
  - `launchIdeaIntoChat(promptTemplate)`
  - `launchTaskIntoChat(taskContext)`
  - `openSession(sessionId)`
- 壳层公共 UI 状态：
  - 左栏展开/折叠
  - 顶部标题/副标题
  - 全局 loading / error banner
  - 首屏欢迎态是否展示

避免的设计：

- 不把聊天消息列表、灵感卡片列表、任务列表全部塞进一个超大 SharedViewModel
- 不让 Fragment 彼此直接调用，统一通过 SharedViewModel 分发意图

### 3. ChatFragment 设计
`ChatFragment` 继承当前同步聊天能力，而不是重新发明一套：

- 复用 `SyncedChatWsManager`
- 复用现有聊天 UI model：`ChatScreenState`、`ChatMessageItem`、`ChatSessionItem`
- 将原 `BaseChatActivity` 的页面职责拆成：
  - `ChatFragment`：渲染与交互
  - `ChatViewModel`：聊天业务
  - `ShellSharedViewModel`：跨页联动

页面结构对齐 Figma：

- 顶部轻操作区：菜单、新对话、配额/状态入口
- 中部主内容区：
  - 欢迎态
  - 消息流
  - Thinking / Tool Calling 指示器
- 底部输入区：
  - 多行输入框
  - 发送按钮
- 推荐卡片区：
  - 首屏时展示
  - 可由 `IdeasFragment` 数据源提供一部分内容
- 左侧历史区：
  - 会话搜索
  - 会话列表
  - 删除会话
  - 新建会话

关键行为：

- 从 `IdeasFragment` 点击模板后，切换到聊天页并把模板填入输入框或直接生成草稿态
- 从 `ScheduleFragment` 点击“一键带入聊天”后，切换聊天页并附带任务上下文摘要
- 聊天输入中的未发送草稿由 `ShellSharedViewModel` 或 `SavedStateHandle` 保留

### 4. IdeasFragment 设计
`IdeasFragment` 对应 Figma 的推荐卡片区升级版，定位为“灵感模板中心”：

页面组成：

- 顶部标题与筛选
- 卡片流列表
- 分类 chips：如办公、自动化、生活、设备控制
- 卡片支持：
  - 标题
  - 简介
  - 标签
  - 推荐图或插画占位
  - 主 CTA “带入聊天”

v1 数据策略：

- 先本地静态配置驱动，避免首版依赖后端
- 抽象 `IdeaTemplate` 数据模型：
  - `id`
  - `title`
  - `subtitle`
  - `promptTemplate`
  - `tags`
  - `entrySource`
- 后续可扩展为远端下发而不改 Fragment 对外接口

与聊天联动：

- 点击卡片 -> `ShellSharedViewModel.launchIdeaIntoChat(...)`
- 聊天页收到事件后：
  - 切到 `ChatFragment`
  - 填充输入框
  - 用户确认后发送，默认不直接自动发送

### 5. ScheduleFragment 设计
`ScheduleFragment` 是轻量任务中心，不做重工作流后台系统：

页面组成：

- 顶部概览：任务总数、运行中、失败数
- 筛选区：全部 / 运行中 / 已暂停 / 已完成
- 任务列表
- 底部或右下主操作：新建任务

任务卡片信息建议：

- 任务名
- cron/时间描述
- 最近运行状态
- 下次执行时间
- 启停开关
- “带入聊天”按钮

v1 任务域模型建议：

- `ScheduledTaskUiModel`
  - `id`
  - `title`
  - `summary`
  - `scheduleText`
  - `status`
  - `lastRunAt`
  - `nextRunAt`
  - `enabled`

v1 功能边界：

- 列表展示
- 创建入口
- 启用/停用
- 查看基础状态
- 一键带入聊天

先不纳入 v1 的内容：

- 复杂 DAG 编排
- 完整任务日志中心
- 深度编辑器
- 多级详情页

### 6. XML 组件拆分
建议抽出可复用 XML 组件，避免未来再回到大 layout 拼接：

- `view_shell_sidebar.xml`
- `view_shell_topbar.xml`
- `view_chat_composer.xml`
- `view_welcome_hero.xml`
- `view_quick_prompt_card.xml`
- `view_schedule_task_card.xml`
- `view_empty_state.xml`

RecyclerView item 建议：

- `item_sidebar_session.xml`
- `item_idea_card.xml`
- `item_schedule_task.xml`

统一视觉 token：

- 颜色、圆角、阴影、间距抽到 `colors.xml` / `dimens.xml` / `styles.xml`
- 沿用当前 XML + ViewBinding，不引入 Compose
- 字体、圆角、毛玻璃/浅灰层次尽量贴近 Figma 当前轻盈白底方案

### 7. 启动链路调整
启动链路改成：

- `SplashActivity -> StartupActivity -> ShellActivity`

替换当前：

- `SplashActivity -> StartupActivity -> SyncedChatActivity`

处理方式：

- `StartupActivity` 启动成功后统一进入 `ShellActivity`
- `ShellActivity` 默认落到 `ChatFragment`
- 保留旧 `ChatActivity` / `SyncedChatActivity` 作为迁移过渡期兼容入口，但不再作为主入口

## Public APIs / Interfaces
需要新增或明确的接口：

- `enum class ShellDestination { CHAT, IDEAS, SCHEDULE }`
- `data class ShellUiState(...)`
- `sealed interface ShellIntent`
- `sealed interface ShellEvent`
- `data class IdeaTemplate(...)`
- `data class ScheduledTaskUiModel(...)`

需要调整的边界：

- 原 `BaseChatActivity` 的部分 UI 逻辑下沉到 `ChatFragment` / adapter / 自定义 view
- `SyncedChatViewModel` 的可复用聊天状态整合为 Fragment 可消费形式
- 会话搜索、推荐卡片、任务带聊等跨页动作统一走 `ShellSharedViewModel`

## Test Plan
需要覆盖这些场景：

- 启动后进入 `ShellActivity`，默认展示聊天页
- 三个主 Fragment 切换时状态保留，不重复创建
- 左侧历史会话切换后，聊天页正确加载对应 session
- 奇思妙想卡片点击后，能跳转聊天页并带入 prompt 草稿
- 定时任务“带入聊天”后，聊天页能展示任务上下文草稿
- 聊天生成中切换侧栏会话，确认弹窗逻辑仍正确
- 屏幕旋转或进程重建后，当前主页面和关键草稿可恢复
- 网关未连接时，聊天页正确显示连接态，不影响 Ideas/Schedule 正常浏览
- Schedule 启停任务后，列表状态局部刷新，不造成整页闪烁

## Assumptions
默认按以下前提设计：

- 首版不引入 Navigation Component，使用手写 Fragment 切换控制复杂度
- 聊天能力以现有同步聊天为主，本地聊天不是主路径
- 奇思妙想是模板/灵感中心，不是内容社区或技能市场
- 定时任务首版是轻量任务中心，不做复杂执行编排
- “带入聊天”默认是填充草稿，不自动立即发送
- Figma 当前页面主要作为布局轮廓和视觉语义来源，不要求逐像素复刻
