# Flutter → Android 原生迁移方案：openclaw-termux → InmoClaw

## Context

将 `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app` (Flutter/Dart) 完全改造为纯 Android 原生项目 `D:\WorkFiles\Android\AndroidProjects\company\InmoClaw`，使用 MVVM 架构、Kotlin、XML 布局、ViewBinding，复用 core_common 模块基类，保留 Termux/proot 能力，19 个屏幕全部复刻，中英文双语。

---

## 1. 技术选型

| 层级 | 技术方案 |
|------|---------|
| 架构 | MVVM (Activity + ViewModel + Repository) |
| 语言 | Kotlin |
| UI | XML + ViewBinding |
| 网络 | Retrofit 2.11 + OkHttp 4.12 (HTTP) / OkHttp WebSocket (WS) |
| 数据库 | Room 2.6.1 |
| 序列化 | Gson 2.11 |
| 状态管理 | LiveData / StateFlow |
| 基类 | core_common 的 BaseBindingActivity、BaseViewModel、BaseListAdapter 等 |
| 日志 | ai.inmo.core_common.utils.Logger |
| 协程 | kotlinx.coroutines (已有) |

---

## 2. 项目结构

```
app/src/main/java/ai/inmo/openclaw/
├── constants/              # AppConstants (URL、版本等)
├── data/
│   ├── local/
│   │   ├── db/             # Room Database + Entity + DAO
│   │   └── prefs/          # PreferencesManager (SharedPreferences 封装)
│   ├── remote/
│   │   ├── api/            # Retrofit 接口 (GatewayApi)
│   │   └── websocket/      # NodeWsManager, SyncedChatWsManager
│   ├── model/              # 网络/数据库 DTO
│   └── repository/         # Repository 实现类
├── domain/
│   └── model/              # 领域模型 (GatewayState, NodeState, ChatMessage 等)
├── service/                # Android 前台服务
│   ├── gateway/            # GatewayService (从 Flutter native 迁移)
│   ├── node/               # NodeForegroundService
│   ├── terminal/           # TerminalSessionService
│   ├── ssh/                # SshForegroundService
│   ├── setup/              # SetupService
│   └── capture/            # ScreenCaptureService
├── proot/                  # ProcessManager, BootstrapManager, ArchUtils
├── capability/             # 设备能力处理器 (Camera, Flash, Location 等)
├── ui/
│   ├── splash/             # SplashActivity + SplashViewModel
│   ├── startup/            # StartupActivity + StartupViewModel
│   ├── setup/              # SetupWizardActivity + SetupViewModel
│   ├── dashboard/          # DashboardActivity + DashboardViewModel
│   ├── terminal/           # TerminalActivity + TerminalViewModel
│   ├── onboarding/         # OnboardingActivity + OnboardingViewModel
│   ├── chat/               # ChatActivity + ChatViewModel
│   ├── synced_chat/        # SyncedChatActivity + SyncedChatViewModel
│   ├── configure/          # ConfigureActivity + ConfigureViewModel
│   ├── providers/          # ProvidersActivity, ProviderDetailActivity
│   ├── node/               # NodeActivity + NodeViewModel
│   ├── ssh/                # SshActivity + SshViewModel
│   ├── logs/               # LogsActivity + LogsViewModel
│   ├── packages/           # PackagesActivity, PackageInstallActivity
│   ├── web_dashboard/      # WebDashboardActivity
│   ├── settings/           # SettingsActivity + SettingsViewModel
│   ├── license/            # LicenseActivity
│   ├── widget/             # 自定义 View (StatusCardView, ProgressStepView 等)
│   └── dialog/             # TermsDialog, ConfirmDialog
└── util/                   # 扩展函数、工具类
```

---

## 3. 依赖配置

### `gradle/libs.versions.toml` 新增

```toml
[versions]
retrofit = "2.11.0"
okhttp = "4.12.0"
room = "2.6.1"
gson = "2.11.0"
fragment = "1.8.5"
recyclerview = "1.3.2"
swiperefreshlayout = "1.2.0"
webkit = "1.12.1"
ksp = "2.2.10-1.0.31"

[libraries]
retrofit-core = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-gson = { group = "com.squareup.retrofit2", name = "converter-gson", version.ref = "retrofit" }
okhttp-core = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }
fragment-ktx = { group = "androidx.fragment", name = "fragment-ktx", version.ref = "fragment" }
recyclerview = { group = "androidx.recyclerview", name = "recyclerview", version.ref = "recyclerview" }
swiperefreshlayout = { group = "androidx.swiperefreshlayout", name = "swiperefreshlayout", version.ref = "swiperefreshlayout" }
webkit = { group = "androidx.webkit", name = "webkit", version.ref = "webkit" }
lifecycle-livedata = { group = "androidx.lifecycle", name = "lifecycle-livedata-ktx", version.ref = "lifecycle" }
lifecycle-runtime = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

### `app/build.gradle` 改动

- 添加 `kotlin("android")` 和 `ksp` 插件
- 添加上述所有 library 依赖
- 添加 Room schema 导出配置

---

## 4. 核心模型

### 领域模型 (`domain/model/`)

| 模型 | 字段 | 对应 Flutter |
|------|------|-------------|
| `GatewayState` | status, logs, errorMessage, dashboardUrl | gateway_state.dart |
| `NodeState` | status, logs, pairingCode, gatewayHost/Port, deviceId | node_state.dart |
| `ChatMessage` | id, sessionId, role, content, createdAt, isStreaming | chat_message.dart |
| `ChatSession` | id, title, createdAt, updatedAt | chat_session.dart |
| `SyncedSession` | sessionKey, title, updatedAt, kind | synced_session.dart |
| `AiProvider` | id, name, description, icon, baseUrl, models, apiKeyHint | ai_provider.dart |
| `SetupState` | step, progress, message, error | setup_state.dart |
| `NodeFrame` | type, id, method, params, result, error | node_frame.dart |
| `OptionalPackage` | name, description, installCommand, size | optional_package.dart |

### Room 实体

- `ChatSessionEntity` (chat_sessions 表)
- `ChatMessageEntity` (chat_messages 表，外键关联 session)

---

## 5. 服务迁移策略

### 可直接复用的 Kotlin 文件（从 Flutter native 层复制）

| 源文件 | 改动 |
|--------|------|
| `ProcessManager.kt` (326行) | 无需改动，纯 Kotlin |
| `BootstrapManager.kt` | 无需改动 |
| `ArchUtils.kt` | 无需改动 |
| `GatewayService.kt` (432行) | 移除 Flutter EventChannel → 改用 SharedFlow |
| `NodeForegroundService.kt` | 修正 import 路径 |
| `SshForegroundService.kt` | 修正 import 路径 |
| `TerminalSessionService.kt` | 修正 import 路径 |
| `SetupService.kt` | 修正 import 路径 |
| `ScreenCaptureService.kt` | 修正 import 路径 |
| `CapabilityServiceClient.kt` | 修正 import 路径 |

### 需新写的 Kotlin 服务（替换 Dart 服务）

| 新服务 | 替换目标 | 功能 |
|--------|---------|------|
| `GatewayManager` | gateway_service.dart | Gateway 生命周期、健康检查、配置写入 |
| `NodeWsManager` | node_ws_service.dart | OkHttp WebSocket，node 角色通信 |
| `SyncedChatWsManager` | synced_chat_service.dart | OkHttp WebSocket，operator 角色聊天 |
| `NodeIdentityService` | node_identity_service.dart | Ed25519 密钥管理 |
| `BootstrapService` | bootstrap_service.dart | 编排 rootfs 下载/解压/Node 安装 |
| `ProviderConfigService` | provider_config_service.dart | 读写 openclaw.json |
| `PreferencesManager` | preferences_service.dart | SharedPreferences 封装 |
| `NetworkModule` | - | OkHttp + Retrofit 单例 |

---

## 6. 状态管理映射

| Flutter Provider | Android ViewModel | 关键 LiveData |
|-----------------|-------------------|--------------|
| GatewayProvider | GatewayViewModel | `state: LiveData<GatewayState>` |
| NodeProvider | NodeViewModel | `state: LiveData<NodeState>` |
| ChatProvider | ChatViewModel | `messages`, `sessions` |
| SyncedChatProvider | SyncedChatViewModel | `messages`, `isStreaming` |
| SetupProvider | SetupViewModel | `setupState` |

跨屏幕共享状态用 `SharedFlow` 单例 (如 `GatewayStateHolder`)。

---

## 7. 屏幕导航

```
SplashActivity
  ├── (首次) → SetupWizardActivity → StartupActivity → DashboardActivity
  └── (非首次) → StartupActivity → DashboardActivity

DashboardActivity (主页)
  ├── TerminalActivity
  ├── WebDashboardActivity
  ├── SyncedChatActivity
  ├── OnboardingActivity
  ├── ConfigureActivity
  ├── ProvidersActivity → ProviderDetailActivity
  ├── PackagesActivity → PackageInstallActivity
  ├── SshActivity
  ├── LogsActivity
  ├── NodeActivity
  └── SettingsActivity → LicenseActivity
```

使用 `startActivity(Intent)` + `ActivityResultContracts`。

---

## 8. 国际化

- `res/values/strings.xml` — 英文（默认）
- `res/values-zh/strings.xml` — 中文
- 从 `app_localizations_en.dart` 和 `app_localizations_zh.dart` 提取所有字符串
- 命名规则：`screen_element_description`，如 `dashboard_title`、`setup_step_environment`
- 参数化字符串用 `%s`/`%d` 格式

---

## 9. 实施阶段

### Phase 0: 基础架构 (1-2天)
1. 更新 `libs.versions.toml` 添加所有新依赖
2. 更新 `app/build.gradle`（添加 kotlin.android、ksp 插件和依赖）
3. 创建包结构骨架
4. 创建 `AppConstants.kt`
5. 创建 `PreferencesManager.kt`
6. 创建 Room 数据库 + 实体 + DAO
7. 创建 `NetworkModule` (OkHttp + Retrofit)
8. 创建所有领域模型数据类
9. 更新 `AndroidManifest.xml`（权限 + 服务声明）

### Phase 1: 核心服务层 (2-3天)
1. 复制并适配 `ProcessManager.kt`、`BootstrapManager.kt`、`ArchUtils.kt`
2. 适配 `GatewayService.kt`（EventChannel → SharedFlow）
3. 复制适配其他前台服务
4. 新写 `GatewayManager.kt`
5. 新写 `NodeWsManager.kt` (OkHttp WebSocket)
6. 新写 `NodeIdentityService.kt`

### Phase 2: 启动流程 (2-3天)
1. `SplashActivity` + 布局 + TermsDialog
2. `SetupWizardActivity` + `SetupViewModel` + 布局
3. `StartupActivity` + `StartupViewModel` + 布局
4. 自定义 View: `ProgressStepView`
5. 导航串联: Splash → Setup → Startup → Dashboard
6. 中英文 strings

### Phase 3: Dashboard + Gateway 控制 (1-2天)
1. `StatusCardView` 自定义 View
2. `GatewayControlsView`
3. `DashboardActivity` + `DashboardViewModel` + 布局
4. `GatewayViewModel` (start/stop/status)

### Phase 4: 终端相关屏幕 (2-3天)
1. `TerminalActivity` + `TerminalViewModel` (proot shell 交互)
2. `OnboardingActivity` (API key 设置终端)
3. `ConfigureActivity` (openclaw configure)
4. `PackageInstallActivity` (终端安装器)

### Phase 5: 聊天屏幕 (2-3天)
1. `SyncedChatWsManager.kt`
2. `SyncedChatActivity` + `SyncedChatViewModel` + 消息适配器
3. 聊天消息气泡布局
4. 流式消息展示
5. `ChatActivity` + `ChatViewModel` (本地聊天 + Room)

### Phase 6: Node + 设备能力 (2-3天)
1. `NodeManager.kt` (WebSocket 连接 + 挑战/配对)
2. 能力处理器 (Camera, Flash, Location, Screen, Sensor, FS, System, Vibration, Serial)
3. `NodeActivity` + `NodeViewModel`

### Phase 7: 剩余屏幕 (2-3天)
1. `ProvidersActivity` + `ProviderDetailActivity`
2. `SshActivity`
3. `LogsActivity` (实时日志 + 搜索)
4. `PackagesActivity`
5. `WebDashboardActivity` (WebView)
6. `SettingsActivity`
7. `LicenseActivity`

### Phase 8: 完善 (1-2天)
1. 补全所有中文翻译
2. UI 一致性检查
3. 错误处理和边界情况
4. 端到端测试

---

## 10. 关键文件路径

### 需修改的文件
- `D:\WorkFiles\Android\AndroidProjects\company\InmoClaw\gradle\libs.versions.toml`
- `D:\WorkFiles\Android\AndroidProjects\company\InmoClaw\app\build.gradle`
- `D:\WorkFiles\Android\AndroidProjects\company\InmoClaw\app\src\main\AndroidManifest.xml`

### 需复制的源文件（从 Flutter native 层）
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\android\app\src\main\kotlin\ai\inmo\openclaw\ProcessManager.kt`
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\android\app\src\main\kotlin\ai\inmo\openclaw\BootstrapManager.kt`
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\android\app\src\main\kotlin\ai\inmo\openclaw\GatewayService.kt`
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\android\app\src\main\kotlin\ai\inmo\openclaw\ArchUtils.kt`
- 其他 5 个前台服务文件

### 需参考的 Flutter 源文件（UI + 业务逻辑）
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\lib\screens\` — 19 个屏幕
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\lib\services\` — 所有服务
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\lib\models\` — 数据模型
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\lib\providers\` — 状态管理
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\lib\widgets\` — 复用组件
- `E:\BaiduNetdiskDownload\openclaw-termux\flutter_app\lib\l10n\` — 国际化字符串

### 复用的 core_common 基类
- `BaseBindingActivity<VB>` — 所有 Activity 基类
- `BaseViewModel` — 所有 ViewModel 基类 (launchUi/launchIo)
- `BaseListAdapter<T,VB>` — RecyclerView 列表
- `BaseBindingDialog<VB>` — 对话框
- `Logger` — 日志 (d/i/v/w/e)
- `AppProvider.get()` — 全局 Context
- `CoroutineUtils` — 协程工具

---

## 11. 验证方案

| 阶段 | 验证方式 |
|------|---------|
| Phase 0 | `./gradlew assembleDebug` 编译通过 |
| Phase 1 | GatewayService 启动并通过 SharedFlow 发射日志 |
| Phase 2 | 真机：启动 → Splash → Setup(rootfs解压) → Startup(4步) → Dashboard |
| Phase 3 | Dashboard 显示，Gateway 可启停 |
| Phase 4 | 终端打开，proot shell 可执行命令 |
| Phase 5 | 聊天连接 gateway，发送/接收消息，流式显示 |
| Phase 6 | Node WebSocket 连接、配对、能力响应 |
| Phase 7 | 所有屏幕可访问，功能正常 |
| Phase 8 | 中文切换正常，无 UI 异常 |
