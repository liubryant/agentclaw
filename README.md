# INMOClaw

INMOClaw 是一个运行在 Android 设备上的 AI Gateway 客户端与本地执行容器。它不是单纯的聊天界面，而是把本地 Ubuntu rootfs、Node/OpenClaw 运行时、网关控制、聊天会话、终端、设备能力暴露、技能管理、可选包安装和 SSH 工具整合在同一个 App 中，目标是在移动设备上提供持续可用的 AI 协作与自动化能力。

当前仓库是一个基于 Kotlin + Android XML + ViewBinding/DataBinding 的多模块 Android 工程，主要面向内部开发者维护与迭代。

## 1. 项目定位

这个项目的核心目标可以概括为四件事：

- 在 Android 私有目录内准备并维护一个可运行的 Ubuntu rootfs 环境。
- 在 rootfs 中运行 OpenClaw / Node 相关能力，并通过本地 Gateway 暴露服务。
- 提供 Android 原生界面，承载聊天、Shell、终端、节点、技能、配置、日志等工作流。
- 将 Android 设备本身的能力以 Node/工具调用的方式暴露给网关侧使用。

从当前实现来看，INMOClaw 既包含“本地 AI 网关客户端”，也包含“设备节点与能力桥接器”，还包含“技能与运行环境管理器”。

## 2. 核心能力概览

仓库当前已经具备或明确实现了以下能力：

- 本地环境初始化：解包预置 Ubuntu rootfs、创建运行目录、写入 DNS 配置、安装兼容补丁。
- Gateway 管理：启动、停止、健康检查、日志汇总、自动提取 Dashboard URL。
- 聊天能力：本地聊天历史、同步聊天、流式响应渲染、Markdown 渲染、聊天搜索。
- Shell/工作区界面：包含侧边栏、会话列表、Ideas、Schedule、任务上下文等 UI。
- Node 配对与设备能力：通过 WebSocket 与网关建立配对，暴露相机、文件、定位、系统控制、截图、传感器、震动、串口、闪光灯等能力。
- 终端能力：集成 Termux terminal-emulator / terminal-view。
- 可选包管理：在 rootfs 中安装和移除 Go、Homebrew、OpenSSH。
- SSH 服务：在设备内的 rootfs 环境中启停 SSH 服务。
- 技能管理：内置技能 + 从 GitHub 导入技能，并支持启用、停用、删除。
- 快照与日志：支持快照导入导出、日志查看、部分运行状态检查。

## 3. 技术栈

- 语言：Kotlin
- UI：Android XML、ViewBinding、DataBinding
- 构建：Gradle Groovy DSL
- Android Gradle Plugin：`8.10.1`
- Kotlin：`2.2.10`
- 最低系统版本：`minSdk 24`
- 目标版本：`targetSdk 34`
- 编译版本：`compileSdk 34`
- 本地存储：
  - Room
  - MMKV
- 网络：
  - Retrofit
  - OkHttp
- Markdown 渲染：Markwon
- 终端组件：Termux `terminal-emulator` / `terminal-view`
- 其他关键依赖：
  - Firebase Analytics
  - Firebase Crashlytics NDK
  - UsbSerial
  - Apache Commons Compress
  - XZ

依赖版本统一维护在 [gradle/libs.versions.toml](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/gradle/libs.versions.toml)。

## 4. 模块结构

### `app`

主应用模块，应用 ID 为 `ai.inmo.openclaw`。包含：

- AndroidManifest、页面布局、资源文件
- 聊天、Shell、终端、Node、Providers、Packages、SSH、Settings、Dashboard 等页面
- 前台服务与运行环境初始化逻辑
- Room 数据库与 DAO
- rootfs/技能相关资产
- AIDL 能力接口

### `core/core_common`

共享基础库模块，命名空间为 `ai.inmo.core_common`。主要提供：

- 基础 Activity / Dialog / Popup / Adapter
- 协程工具
- 线程与上下文工具
- 日志与设备信息工具
- 通用 UI 组件与基础样式

## 5. 目录说明

仓库内几个关键目录的职责如下：

- `app/`
  - 主应用代码、资源、Manifest、Room schema、测试代码。
- `core/core_common/`
  - 可复用公共能力。
- `gradle/`
  - 版本目录、wrapper 与 Gradle 相关配置。
- `doc/`
  - 项目内各类设计方案、迁移计划、问题分析文档。多数是阶段性实现说明，不是最终用户文档。
- `js/`
  - 当前包含 `hook.js`，与运行时 hook / 调试逻辑相关。
- `keystore/`
  - 签名相关文件。属于敏感目录。
- `log/`
  - 本地日志与调试输出，通常不应进入正式版本控制流程。
- `app/schemas/`
  - Room 自动导出的数据库 schema。
- `app/src/main/assets/skills/`
  - 内置技能定义与相关脚本。
- `app/src/main/assets/rootfs-arm64.tar.gz.bin`
  - 预置的 arm64 rootfs 资产，用于首启初始化。

## 6. 关键架构与运行链路

### 6.1 应用启动链路

从当前实现看，主启动链路大致如下：

1. `SplashActivity` 作为 Launcher Activity 进入应用。
2. 初始化检查后进入启动 / 配置 / 主界面流程。
3. `SetupCoordinator` 判断 rootfs 与运行时是否已准备完成。
4. 若未完成，则通过 `SetupService` + `BootstrapManager` 执行环境准备。
5. 环境准备完成后，`GatewayManager` 负责启动与监控本地 Gateway。
6. Dashboard、Chat、Shell、Node、Terminal 等页面围绕网关与 rootfs 能力工作。

### 6.2 本地环境初始化链路

环境初始化的核心在 [SetupCoordinator.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/repository/SetupCoordinator.kt:1) 与 [BootstrapManager.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/proot/BootstrapManager.kt:1)。

当前实现会做这些事：

- 创建 rootfs、tmp、home、config、lib 等目录。
- 写入 DNS 配置到应用私有目录及 rootfs 内的 `etc/resolv.conf`。
- 检查是否已有完整 bootstrap 环境。
- 若缺失，则从 `assets/rootfs-arm64.tar.gz.bin` 解包 rootfs。
- 如果 apt 缓存目录里已有 `.deb`，则进一步提取安装。
- 写入 bionic/proot 兼容脚本、hook 脚本与 Git 配置。
- 创建 OpenClaw 二进制包装器。

`BootstrapManager` 还负责：

- 处理大体积 asset 的流式复制与解压。
- 为 proot 环境准备 fake `/proc` 与 `/sys` 数据。
- 在 rootfs 中读写文件、列目录、创建目录、删除文件。
- 检查 Node/OpenClaw/bypass 是否齐备。

### 6.3 Gateway 管理链路

网关侧核心由 [GatewayManager.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/repository/GatewayManager.kt:1) 管理，主要职责：

- 启动前准备 Gateway 配置。
- 启动 `GatewayService`。
- 周期性健康检查。
- 从服务日志中提取 `Dashboard URL`。
- 维护网关状态流：`STOPPED`、`STARTING`、`RUNNING`、`ERROR`。

默认网关地址定义在 [AppConstants.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/constants/AppConstants.kt:1)：

- Host：`127.0.0.1`
- Port：`18789`
- URL：`http://127.0.0.1:18789`

### 6.4 Chat 链路

聊天能力包括两类：

- 本地聊天历史与本地持久化
- 同步网关聊天与流式响应

[ChatService.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/repository/ChatService.kt:1) 当前直接向本地 Gateway 的 `/v1/chat/completions` 发起 SSE 风格流式请求，并将增量内容逐块向 UI 层透传。

相关 UI 与状态管理分布在：

- `ui/chat/`
- `ui/synced_chat/`
- `ui/shell/chat/`
- `data/repository/ChatRepository.kt`
- `data/repository/SyncedChatWsManager.kt`

### 6.5 Node 与设备能力链路

[NodeManager.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/repository/NodeManager.kt:1) 负责设备作为 Node 接入 Gateway，并通过 WebSocket 完成：

- 身份初始化
- challenge / connect 握手
- pairing 请求
- 本地配对自动批准
- 指令下发与能力调用结果回传

当前已注册的能力处理器包括：

- `camera`
- `fs`
- `location`
- `system`
- `screen`
- `sensor`
- `flash`
- `haptic` / vibration
- `serial`

能力实现位于 `app/src/main/java/ai/inmo/openclaw/capability/`。

### 6.6 技能管理链路

技能分两类：

- 内置技能：来自 `app/src/main/assets/skills/`
- 外部导入技能：通过 GitHub 导入到 rootfs 内 `.openclaw/skills`

[GithubSkillImportService.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/repository/GithubSkillImportService.kt:1) 当前支持从以下格式导入：

`https://github.com/<owner>/<repo>/tree/<ref>/<path>`

导入行为包括：

- 调 GitHub Contents API 递归下载目录
- 校验 `SKILL.md` 是否存在
- 将技能放入启用目录或停用目录
- 写入导入元数据

### 6.7 可选包与 SSH

可选包定义在 [OptionalPackage.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/domain/model/OptionalPackage.kt:1)，当前包括：

- Go
- Homebrew
- OpenSSH

[PackageService.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/repository/PackageService.kt:1) 通过 rootfs 内特定文件路径判断安装状态。

SSH 页面与服务用于在设备内部 rootfs 环境中启动 SSH server，便于远程进入设备内的 Ubuntu 环境。

## 7. 主要页面与功能入口

从 Manifest 和 `ui/` 目录来看，当前主要页面包括：

- `SplashActivity`
- `StartupActivity`
- `DashboardActivity`
- `ShellActivity`
- `ChatActivity`
- `SyncedChatActivity`
- `TerminalActivity`
- `ProvidersActivity`
- `ProviderDetailActivity`
- `PackagesActivity`
- `PackageInstallActivity`
- `NodeActivity`
- `SshActivity`
- `LogsActivity`
- `SettingsActivity`
- `ConfigureActivity`
- `OnboardingActivity`
- `WebDashboardActivity`
- `LicenseActivity`

`ShellActivity` 这一支是当前较重要的工作流容器，集成了聊天、Ideas、Schedule、会话侧栏与搜索等界面元素。

## 8. 构建与运行要求

### 8.1 基础环境

建议开发环境：

- Android Studio 新版稳定版
- JDK 11
- Android SDK 34
- 可用的 Android 真机或模拟器

由于项目包含：

- 前台服务
- 媒体投屏
- 文件权限
- 蓝牙 / 定位 / 传感器
- rootfs 与本地执行环境

因此很多能力在真机上更容易验证，模拟器通常只能覆盖一部分流程。

### 8.2 本地构建前检查

在开始前至少确认这些项：

- 本机已安装 Android SDK 与对应 Build Tools。
- `local.properties` 指向有效的 Android SDK 路径。
- 若需完整 Firebase 相关功能，`app/google-services.json` 已准备就绪。
- 若需正式签名构建，本地签名配置已按团队规范准备。

## 9. 常用构建命令

在仓库根目录执行。

### Windows

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat connectedAndroidTest
.\gradlew.bat clean
```

### macOS / Linux

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew test
./gradlew :app:testDebugUnitTest
./gradlew connectedAndroidTest
./gradlew clean
```

## 10. 安装与首次启动建议

首次安装到设备后，建议按以下顺序验证：

1. 启动 App，观察是否进入 `Splash` / `Setup` / `Startup` 流程。
2. 允许必要权限。
3. 执行环境初始化，确认 rootfs 解包成功。
4. 打开 Dashboard，确认 Gateway 可以启动。
5. 打开 Chat，确认能与本地 Gateway 建立连接。
6. 打开 Node，确认可完成配对或看到连接日志。
7. 如需远程进入 rootfs，再到 Packages 安装 OpenSSH，并进入 SSH 页面启动服务。

如果首启初始化失败，优先排查：

- rootfs asset 是否存在
- 存储空间是否足够
- 权限是否被拒绝
- 网关是否成功启动
- 日志中是否出现 DNS / 解压 / 进程启动错误

## 11. 关键配置文件说明

### 11.1 根目录 `openclaw.json`

仓库根目录的 [openclaw.json](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/openclaw.json:1) 当前更像一个示例或开发态配置来源，里面可以看到：

- Provider 定义
- 默认模型
- Gateway 节点命令白名单
- HTTP 接口开关
- 鉴权模式
- 工具 profile

这个文件可用于理解项目期望的 OpenClaw 配置结构，但不要默认把仓库里的现有值视为可直接投入生产的正式配置。

### 11.2 运行期 rootfs 配置

运行时真正被应用读写的 Provider 配置文件路径是：

- `filesDir/rootfs/ubuntu/root/.openclaw/openclaw.json`

[ProviderConfigService.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/repository/ProviderConfigService.kt:1) 会读写这里的内容，并维护：

- `models.providers`
- `agents.defaults.model.primary`

因此，应用内 Providers 页面修改后的结果并不是直接改仓库根目录 `openclaw.json`，而是改 rootfs 内运行期配置。

### 11.3 Gradle 与版本配置

- 根构建脚本：[build.gradle](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/build.gradle:1)
- Settings：[settings.gradle](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/settings.gradle:1)
- 版本目录：[gradle/libs.versions.toml](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/gradle/libs.versions.toml:1)
- Gradle 属性：[gradle.properties](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/gradle.properties:1)

### 11.4 应用构建配置

[app/build.gradle](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/build.gradle:1) 中定义了：

- `applicationId`
- `minSdk/targetSdk/compileSdk`
- `versionCode = 6`
- `versionName = 1.0.5`
- Room schema 导出
- DataBinding / ViewBinding / AIDL
- APK 输出命名规则

当前 APK 命名格式类似：

`INMOClaw-<buildType>-v<versionName>-<timestamp>.apk`

说明：代码中还存在与签名文件相关的历史/开发态配置。README 不复写这些敏感值，实际维护时应优先迁移到本地私有配置或未入库的安全配置来源。

## 12. 权限说明

Manifest 申请了较多权限，因为项目不仅是普通 UI App，还涉及前台服务、媒体投屏、节点能力和本地环境管理。

主要按用途可分为：

### 网络与服务

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `WAKE_LOCK`

用途：网关访问、长连接、状态检测、前台服务保活。

### 媒体投屏与通知

- `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- `POST_NOTIFICATIONS`

用途：截图/录屏能力、前台通知展示。

### 存储与文件

- `READ_EXTERNAL_STORAGE`
- `WRITE_EXTERNAL_STORAGE`
- `MANAGE_EXTERNAL_STORAGE`

用途：文件导出、快照、技能/产物导出等。不同 Android 版本的权限语义不同，调试时要按系统版本判断。

### 设备能力

- `CAMERA`
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `BLUETOOTH`
- `BLUETOOTH_ADMIN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_SCAN`
- `VIBRATE`
- `BODY_SENSORS`
- `HIGH_SAMPLING_RATE_SENSORS`

用途：Node 侧对设备能力的暴露与调用。

### 电池优化

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

用途：减少前台服务、网关或节点被系统频繁回收的概率。

## 13. 数据与运行产物

应用运行后，重要数据大多位于应用私有目录中，典型包括：

- `filesDir/rootfs/ubuntu/`
  - Ubuntu rootfs 主体
- `filesDir/rootfs/ubuntu/root/.openclaw/`
  - OpenClaw 运行时目录
- `filesDir/rootfs/ubuntu/root/.openclaw/openclaw.json`
  - 运行期配置
- `filesDir/rootfs/ubuntu/root/.openclaw/skills/`
  - 启用中的技能
- `filesDir/rootfs/ubuntu/root/.openclaw/skills_disabled/`
  - 已停用技能
- `filesDir/rootfs/ubuntu/root/.openclaw/tmp/skills/`
  - 技能导入过程中的临时目录
- `filesDir/config/`
  - DNS、fake proc/sys 等相关配置
- Room 数据库
  - 本地聊天、同步聊天、搜索等数据

如果要排查“配置改了但不生效”，优先确认你改的是仓库文件，还是设备上的运行期 rootfs 文件。

## 14. 数据存储与数据库

项目当前使用 Room 保存本地数据，数据模型涉及：

- 聊天会话
- 聊天消息
- 同步聊天会话
- 同步消息
- 同步分片
- 同步工具调用
- 搜索结果

相关类位于 `app/src/main/java/ai/inmo/openclaw/data/local/db/`。

数据库 schema 已导出到：

- [app/schemas](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/schemas)

如果修改 Entity / DAO / Database：

- 需要更新版本与迁移
- 需要刷新 schema 输出
- 需要同步更新相关测试

## 15. 网络与 API 层说明

[NetworkModule.kt](/D:/WorkFiles/Android/AndroidProjects/company/InmoClaw/app/src/main/java/ai/inmo/openclaw/data/remote/api/NetworkModule.kt:1) 当前使用固定 base URL：

- `http://127.0.0.1:18789/`

并配置了：

- `connectTimeout = 10s`
- `readTimeout = 30s`
- `writeTimeout = 30s`
- `pingInterval = 30s`

Debug 构建会启用基础 HTTP 日志级别，Release 关闭。

项目还包含：

- `GatewayApi`
- `BotApi`
- `BotNetworkModule`
- WebSocket 管理器

其中一部分用于本地 Gateway，一部分用于远端 Bot/服务侧交互。

## 16. Providers 与模型配置

Providers 页面允许在运行期写入 Provider 配置。当前代码支持：

- 保存 provider 的 `apiKey`
- 保存 `baseUrl`
- 保存模型列表
- 指定当前 active model
- 移除 provider 配置时回退到下一个可用 provider/model

README 中需要特别强调：

- 仓库里的默认 provider 配置只能作为结构参考。
- 真正跑在设备上的配置应按环境单独设置。
- 不要把真实 API key、token、内部地址直接提交回仓库。

## 17. 测试

当前仓库已经存在一些 JVM 单元测试与基础仪器测试，例如：

- `GatewayConfigDefaultsTest`
- `GatewayManagerTest`
- `NodeIdentityServiceTest`
- `ChatMarkdownRenderStrategyTest`
- `WifiNetworkMonitorTest`

常用测试命令：

```powershell
.\gradlew.bat test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :core:core_common:testDebugUnitTest
```

如果需要设备侧验证：

```powershell
.\gradlew.bat connectedAndroidTest
```

建议新增功能时至少覆盖：

- 纯逻辑层：JVM 单测
- 涉及 Room：DAO / migration / schema 相关验证
- 涉及状态机：Gateway / Node / Setup 状态流验证
- 涉及 UI 行为：必要时补充 instrumented test 或手工验证记录

## 18. 调试建议

### 18.1 Gateway 启不来

优先检查：

- `Setup` 是否已经 complete
- `rootfs` 是否齐全
- `GatewayService` 日志
- Dashboard 页面状态
- 本地 `127.0.0.1:18789` 是否可访问

可重点查看：

- `LogsActivity`
- `GatewayManager`
- `GatewayService`
- rootfs 内 `openclaw` 与 `node` 是否存在

### 18.2 首次初始化失败

优先检查：

- `assets/rootfs-arm64.tar.gz.bin` 是否被正确打包
- `aaptOptions.noCompress` 是否仍保留 `gz/xz/bin/tar.gz/tar.xz`
- 设备剩余空间是否足够
- 是否因为权限、I/O 或解压异常中断

### 18.3 Chat 无响应或流式中断

优先检查：

- Gateway 是否处于 `RUNNING`
- token 是否正确注入
- `/v1/chat/completions` 是否返回异常
- SSE 数据格式是否变化

重点代码：

- `ChatService`
- `ChatRepository`
- `SyncedChatWsManager`

### 18.4 Node 配对失败

优先检查：

- 网关 host/port 是否正确
- token 是否过期或为空
- 设备身份是否初始化成功
- 本地自动 approve 是否失败
- 权限是否满足对应能力需求

重点代码：

- `NodeManager`
- `NodeIdentityService`
- `NodeWsManager`
- `capability/*`

### 18.5 技能导入失败

优先检查：

- GitHub URL 是否符合 `tree/<ref>/<path>` 格式
- 目标目录下是否存在 `SKILL.md`
- GitHub API 是否被限流
- rootfs 内技能目录是否可写

## 19. 安全与敏感信息

这个仓库包含多类敏感或潜在敏感资源，维护时必须特别注意：

- `keystore/` 中的签名文件
- `app/google-services.json`
- 各类 API key / token / provider 凭据
- rootfs 内运行期配置
- 任何内部服务地址或鉴权字段

维护原则：

- 不要在 README、Issue、PR 描述里直接贴真实密钥。
- 不要把设备运行期生成的真实配置直接回写进仓库。
- 尽量把签名信息、私密地址、生产密钥迁移到本地私有配置。
- 对外共享仓库前，先进行一次完整的敏感信息审查。

当前仓库中已经能看到一些开发态配置痕迹，这些不应被视为推荐实践。

## 20. 已知现状与维护提示

从仓库当前状态看，有几点需要注意：

- 工程正在持续迭代，`doc/` 中存在大量计划文档，说明部分功能仍处于演进期。
- Git 工作区可能出现未跟踪文档、日志或实验文件，提交前要主动筛选。
- rootfs、技能、节点能力和 Shell 工作流的复杂度较高，新增功能时要优先考虑运行期副作用。
- 某些流程更像“设备运行时集成”，单靠 JVM 单测不能完全覆盖，真机验证通常不可省略。

## 21. 现有文档索引

`doc/` 目录中已有较多阶段性说明文档，例如：

- rootfs 提取优化
- Shell 架构
- Chat 搜索
- Session 渲染优化
- Synced chat 本地优先 / 增量同步 / 可靠性修复
- Workspace 迁移

这些文档适合在深入某一子系统时继续阅读；README 负责给出全局入口，不替代这些专题设计文档。

## 22. 开发约定

结合仓库现有规范，开发时建议遵循：

- 语言保持 Kotlin + Android XML，不引入 Compose。
- 复用 `core_common` 中已有基础类与工具，避免重复造轮子。
- 通用能力优先沉淀到 `core_common`，业务特定逻辑放在 `app`。
- 类名使用 `PascalCase`，方法/变量使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。
- 新增数据库变更时同步维护 Room schema。
- 提交信息保持短而聚焦，推荐 `<scope>: <brief action>` 风格。

## 23. 适合新接手开发者的阅读顺序

如果你是第一次接手这个项目，建议按下面顺序阅读代码：

1. `README.md`
2. `settings.gradle`、`app/build.gradle`、`core/core_common/build.gradle`
3. `AppConstants.kt`
4. `SetupCoordinator.kt`、`BootstrapManager.kt`
5. `GatewayManager.kt`
6. `NodeManager.kt`
7. `ChatService.kt` 与 `ui/shell/`、`ui/chat/`
8. `doc/` 中与你当前任务最相关的计划文档

## 24. 快速检查清单

每次准备开始开发或提测前，建议快速过一遍：

- 工程是否能成功同步 Gradle
- `assembleDebug` 是否可通过
- 首启 setup 是否可完成
- Gateway 是否可正常进入 `RUNNING`
- Chat 是否能收到流式回复
- Node 是否能连接或至少输出明确错误日志
- 若改了数据库，schema 是否已更新
- 若改了技能/导入逻辑，GitHub 导入流程是否验证过

## 25. License

代码中的常量与页面文案显示项目采用 `MIT`。正式对外发布前，仍应以仓库最终附带的许可证文件和第三方依赖声明为准，并确保第三方 notices 完整。
