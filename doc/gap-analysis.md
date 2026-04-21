# 查漏补缺清单：Flutter → Android 迁移功能缺失分析

> **最后更新**: 2026-03-26
>
> **总计 30 项** — ✅ 已完成 28 项 · ⚠️ 暂不实现 2 项

## Context
所有 Phase 0-8 的迁移工作已完成。对比 Flutter 源码后整理功能缺失项，标注实际修复状态。

---

## 一、启动流程 (Startup/Setup) — ✅ 已修复

### 1.1 ✅ Startup 环境预加载 — 已修复
- `SetupCoordinator.runSetup()` 现在会检测 rootfs 是否缺失，自动调用 `BootstrapManager.extractRootfsFromAsset()` 解压
- `StartupViewModel` Step 1 改为调用 `SetupCoordinator.runSetup()` 并监听 state 变化

### 1.2 ✅ 解压进度跟踪 — 已修复
- `SetupCoordinator` 每 500ms 轮询 `BootstrapManager.getExtractionProgressDetail()`
- UI 显示 "Extracting rootfs 45% (1234 files)"

### 1.3 ✅ Gateway 等待超时 — 已修复
- 从 `Thread.sleep(1000) * 24` 改为 `withTimeoutOrNull(120_000L)` + `StateFlow.first { it.isRunning }`

### 1.4 ✅ Node 连接等待 — 已修复
- Step 4 使用 `withTimeoutOrNull(60_000L)` 等待 paired 状态，超时非致命

### 1.5 ✅ Model 配置检查 — 已修复
- Step 2 先调用 `readConfig()` 检查是否已有 provider，跳过已配置的情况

### 1.6 ✅ DNS 配置 — 已修复
- `SetupCoordinator.runSetup()` 中调用 `manager.writeResolvConf()`

### 1.7 ✅ 错误重试 — 已有
- `StartupActivity` 已有 retryButton，调用 `viewModel.runStartup()`
- 重试自动跳过已完成的步骤

---

## 二、终端类屏幕 — ✅ 已有（初始分析有误）

### 2.1-2.6 ✅ 全部已实现
- `BaseTerminalActivity` 已有完整工具栏：Ctrl/Alt/Esc/Tab/Enter/方向键/Home/End/PgUp/PgDn/特殊字符
- Copy/Paste/Screenshot/OpenURL/Restart 按钮均已实现
- URL 检测（ANSI + box-drawing 清理）+ 弹窗确认打开
- Onboarding token URL 自动检测和保存 (通过 `TerminalSessionManager`)
- 命令完成检测 (sentinel pattern matching) 已在所有 3 个屏幕实现
- Done 按钮在完成后自动显示

---

## 三、SyncedChat 聊天屏幕 — ✅ 已修复

### 3.1 ✅ Tool Call 可视化 — 已改进
- `ToolCallAdapter` 使用 DiffUtil，Running 显示品牌色，Done 显示绿色
- 中英文本地化字符串

### 3.2 ✅ Thinking 动画 — 已修复
- 添加 `thinkingIndicator` 布局（ProgressBar + Label）
- 根据 `GeneratingPhase.THINKING` / `CALLING_TOOL` 显示不同文本
- 中英文："思考中…" / "调用工具中…"

### 3.3 ✅ 消息差异追踪 — 已修复
- `ChatMessageAdapter.submitList()` 改用 `DiffUtil.calculateDiff()` 替代 `notifyDataSetChanged()`

### 3.4 ✅ Session 切换确认 — 已有
- `BaseChatActivity.showSwitchConfirm()` 已实现确认对话框

### 3.5 ✅ Markdown 渲染 — 已修复
- 添加 Markwon 依赖 (core + strikethrough + tables + linkify)
- `AssistantHolder` 使用 `markwon.setMarkdown()` 渲染

### 3.6 ✅ 代码块复制 — 已修复
- 助手消息 `textIsSelectable=true`，用户可选中代码块并复制
- 长按仍可复制整条消息原始 Markdown

---

## 四、其他屏幕功能 — ✅ 全部完成

### 4.1 ✅ Dashboard — Snapshot 卡片 — 已修复
- 添加 snapshotCard 和 webDashboardCard

### 4.2 ✅ Dashboard — 版本信息页脚 — 已修复
- 添加 versionFooter 显示 `v{VERSION}`

### 4.3 ✅ SSH — 可复制命令行 — 已修复
- 改为每个 IP 独立生成 `ssh root@IP -p PORT` 命令行
- 每行带复制按钮

### 4.4 ✅ Logs — 日志颜色编码 — 已有
- `buildColoredLogs()` 已实现 ERROR 红/WARN 橙/INFO 灰

### 4.5 ✅ ProviderDetail — 自定义模型 — 已有
- `customModelInput` + spinner 最后一项 "Custom model" 切换显示

### 4.6 ✅ ProviderDetail — Gateway 重启 — 已有
- save/remove 后均执行 stop → sleep → start

### 4.7 ✅ Settings — 组件状态 — 已有
- Go/Brew/SSH 安装状态均显示

### 4.8 ✅ Settings — Snapshot 状态 — 已有
- `snapshotStatus` 显示导出/导入路径

### 4.9 ✅ Node — 配对码显示 — 已修复
- 配对码改为 28sp 居中 monospace 粗体，品牌色
- Device ID 添加独立标签，字体 monospace，可选中复制

### 4.10 ✅ Node — 能力图标 — 已修复
- 每个 capability 带 emoji 图标（📷📍📁⚙️📡🔌📳等）
- 状态文字颜色：Ready 绿色，Planned 灰色

---

## 五、服务层 — ✅ 核心完成，2 项暂缓

### 5.1 ⚠️ Canvas Capability — 暂不实现
- Flutter 有 WebView 导航、JS 执行、截图能力
- 当前无使用场景，后续按需补充

### 5.2 ⚠️ Update Service — 暂不实现
- Flutter 有 app 更新检查
- 可通过应用商店更新，非核心功能

### 5.3 ✅ SyncedChat 会话标题自动提取 — 已有
- `SyncedChatWsManager.upsertSessionTitle()` 从首条用户消息提取标题

---

## 六、低优先级

### 6.1 Online Setup Fallback — 暂不实现
### 6.2 响应式布局 — 暂不实现
### 6.3 ✅ Battery/Storage 权限 — 已有

---

## 修改文件清单

本次查漏补缺涉及的文件：

| 文件 | 改动说明 |
|------|---------|
| `data/repository/SetupCoordinator.kt` | 重写 runSetup()：rootfs 解压 + 进度轮询 + deb 安装 + bypass |
| `ui/startup/StartupViewModel.kt` | 重写启动流程：环境预加载 + 120s gateway 等待 + 60s node 等待 |
| `ui/chat/ChatMessageAdapter.kt` | Markwon Markdown 渲染 + DiffUtil 增量更新 |
| `ui/chat/BaseChatActivity.kt` | Thinking/CallingTool 指示器联动 |
| `ui/chat/ToolCallAdapter.kt` | DiffUtil + 颜色状态区分 |
| `ui/ssh/SshActivity.kt` | 每个 IP 独立 SSH 命令行 + 复制按钮 |
| `ui/dashboard/DashboardActivity.kt` | Snapshot 卡片 + WebDashboard 卡片 + 版本页脚 |
| `ui/node/NodeActivity.kt` | Capability emoji 图标 + 状态颜色 |
| `res/layout/activity_chat.xml` | thinkingIndicator 布局 |
| `res/layout/activity_dashboard.xml` | snapshotCard + webDashboardCard + versionFooter |
| `res/layout/activity_ssh.xml` | commandsContainer 替代单条命令 |
| `res/layout/activity_node.xml` | 配对码大字体 + deviceId 标签 |
| `res/layout/item_node_capability.xml` | capabilityIcon 图标位 |
| `res/layout/item_chat_message_assistant.xml` | textIsSelectable + linksClickable |
| `res/values/strings.xml` | 新增 6 条英文字符串 |
| `res/values-zh/strings.xml` | 新增 6 条中文字符串 |
| `gradle/libs.versions.toml` | 添加 markwon 4.6.2 |
| `app/build.gradle` | 添加 markwon 依赖 |

## 暂缓项（非核心功能）

| 编号 | 项目 | 原因 |
|------|------|------|
| 5.1 | Canvas Capability | 当前无使用场景 |
| 5.2 | Update Service | 可通过应用商店更新 |
