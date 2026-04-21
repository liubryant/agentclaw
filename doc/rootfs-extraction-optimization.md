# Rootfs 解压加速方案

## 背景

`BootstrapManager.extractRootfsFromStream()` 负责在首次启动时将 APK 内置的 `rootfs-arm64.tar.gz.bin`（344MB 压缩 / ~1.27GB 解压，5 万+ 文件）解压到应用数据目录。原方案为单线程 Java 解压，耗时超过 5 分钟。

### 原方案瓶颈

| 瓶颈 | 说明 |
|------|------|
| 单线程文件写入 | 5 万+ 文件逐个 open→write→close→chmod，无并行 |
| 小输出缓冲区 | `FileOutputStream` 使用 64KB buffer，无 `BufferedOutputStream` 包装 |
| 逐文件权限设置 | 每个文件单独调用 `setExecutable()` syscall |
| 冗余权限遍历 | 解压后 `configureRootfs()` 调用 `fixBinPermissions()`，再次递归遍历所有 bin/sbin/lib 目录 |

## 优化方案：三级降级策略

压缩包格式不变，通过优化解压代码实现加速。三种策略按优先级自动选择，失败自动降级。

```
extractRootfsFromStream()
  ├── 策略 1: Pipe + native tar     ← 最快
  ├── 策略 2: 临时文件 + native tar  ← pipe 失败时
  └── 策略 3: 多线程 Java 管道       ← 无 native tar 时兜底
```

### 策略 1：Pipe + Native Tar（15-25 秒）

Android 7+ (API 24+) 自带 toybox，包含 `/system/bin/tar`。直接将 asset 流 pipe 到 tar 的 stdin，读取和解压同时进行，无需临时文件。

```
APK Asset ──pipe──> /system/bin/tar xz -C <rootfsDir>
  (后台线程读取)      (tar 进程解压写盘)
```

**实现要点：**
- `ProcessBuilder` 启动 tar 进程
- 后台线程用 1MB 缓冲区将 asset 流写入 `process.outputStream`
- 独立线程读取 stderr 捕获错误
- `CountingInputStream` 跟踪已读压缩字节数，映射为百分比进度
- `process.waitFor(600, TimeUnit.SECONDS)` 设超时
- 解压后验证 `bin/bash` 是否存在
- 跳过 `fixBinPermissions()`（native tar 保留原始 mode bits）

**触发条件：** `/system/bin/tar` 存在且可执行（通过 `isNativeTarAvailable()` 探测并缓存结果）

### 策略 2：临时文件 + Native Tar（30-45 秒）

当 pipe 模式失败时（某些设备的 toybox tar 不支持从 stdin 读取）自动降级。

**流程：**
1. 复制 asset 到 `$filesDir/tmp/rootfs-extract.tar.gz`（报告 0-40% 进度）
2. 执行 `/system/bin/tar xzf <tmpFile> -C <rootfsDir>`
3. 删除临时文件
4. 验证 `bin/bash` 存在

### 策略 3：多线程 Java 管道（60-90 秒）

当设备无 native tar 或 native tar 失败时的兜底方案。采用 Producer-Consumer 架构替代原单线程方案。

```
┌─────────────────────┐     ┌──────────────────────────┐
│   Producer Thread    │     │  Consumer Thread Pool     │
│                      │     │  (N = CPU cores, 2~6)     │
│  GZIPInputStream     │     │                           │
│   → TarArchiveInput  │────>│  LinkedBlockingQueue<256> │
│   → 解析条目         │     │   → 写文件到磁盘           │
│   → 读数据到缓冲区    │     │   → 设置权限              │
└─────────────────────┘     └──────────────────────────┘
```

**实现要点：**

- **Producer 线程**（单线程——gzip 是流式的，不可并行）：
  - `GZIPInputStream(BufferedInputStream(512KB))` → `TarArchiveInputStream`
  - 目录条目：同步创建（保证先于子文件存在）
  - Symlink / 硬链接：延迟到 phase 2 批量创建
  - 普通文件 ≤256KB：读入 `ByteArray`，入队到线程池
  - 普通文件 >256KB：Producer 直接用 `BufferedOutputStream(512KB)` 写磁盘，避免大内存分配

- **Consumer 线程池**（`Executors.newFixedThreadPool(N)`，N = CPU 核心数 2~6）：
  - 从队列取 `ExtractTask.WriteFile`，写文件 + 设权限
  - `BufferedOutputStream(512KB)` 包装 `FileOutputStream`

- **内存控制**：
  - `Semaphore` + `AtomicLong pendingBytes` 限制待写入数据上限为 64MB
  - 大文件由 Producer 直接写磁盘，不占队列内存

- **线程安全**：
  - `ConcurrentHashMap.newKeySet()` 替代 `HashSet` 作为目录缓存
  - `ConcurrentLinkedQueue` 存储延迟创建的 symlink
  - `AtomicReference<Exception>` 捕获 consumer 异常，通知 producer 停止

## 通用优化（所有策略受益）

| 优化项 | 原方案 | 新方案 |
|--------|--------|--------|
| 输入缓冲区 | 256KB | 512KB |
| 输出缓冲区 | 裸 FileOutputStream + 64KB | BufferedOutputStream(512KB) |
| Asset 读取缓冲 | 256KB | 1MB |
| 权限设置 | 逐文件 + 解压后全量遍历 | Native tar 保留原始权限 / Java 路径批量设置 |
| `configureRootfs` | 始终执行 `fixBinPermissions()` | `skipPermissionFix=true` 跳过冗余遍历 |
| 目录缓存 | `HashSet`（非线程安全） | `ConcurrentHashMap.newKeySet()` |

## 预期性能

| 策略 | 预计时间 | 适用条件 |
|------|---------|---------|
| Pipe + native tar | **15-25 秒** | 大多数 Android 7+ ARM64 设备 |
| 临时文件 + native tar | **30-45 秒** | toybox tar 不支持 stdin pipe |
| 多线程 Java 管道 | **60-90 秒** | 无 native tar（极少数设备） |
| ~~原方案（单线程 Java）~~ | ~~5+ 分钟~~ | ~~已废弃~~ |

## 涉及文件

- `app/src/main/java/ai/inmo/openclaw/proot/BootstrapManager.kt` — 所有解压逻辑

### 新增方法

| 方法 | 说明 |
|------|------|
| `isNativeTarAvailable()` | 探测 `/system/bin/tar` 是否可用，缓存结果 |
| `tryPipedNativeTarExtraction()` | Pipe 模式 native tar 解压 |
| `tryFiledNativeTarExtraction()` | 临时文件模式 native tar 解压 |
| `extractWithParallelPipeline()` | 多线程 Java 解压管道 |
| `ExtractTask` sealed class | Producer-Consumer 任务类型定义 |

### 修改方法

| 方法 | 变更 |
|------|------|
| `extractRootfsFromStream()` | 重构为三级降级调度器 |
| `extractRootfsFromAsset()` | 传递 `assetName` 参数供 native tar 使用 |
| `configureRootfs()` | 新增 `skipPermissionFix` 参数 |

## 验证方式

1. `adb logcat \| grep BootstrapManager` 查看解压耗时日志（方法末尾的 `Log.i` 会打印总秒数和使用的策略）
2. 在 ARM64 真机测试 pipe 模式：日志应显示 `Native tar pipe extraction complete in X.Xs`
3. 在 API 24 模拟器测试 Java 兜底路径：日志应显示 `Parallel Java extraction complete`
4. 解压后验证 proot 正常启动（证明权限和 symlink 正确）
5. `./gradlew :app:testDebugUnitTest` 确认无回归

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| toybox tar 不支持 gzip | pipe 失败自动降级到临时文件模式，再失败降级到 Java |
| toybox tar 破坏 symlink | 解压后验证 `bin/bash` 存在，失败则清除 rootfs 走 Java 兜底 |
| 多线程写入导致 OOM | Semaphore 反压限制待写入内存上限 64MB |
| Consumer 线程写文件异常 | `AtomicReference` 捕获异常，通知 Producer 停止，统一清理 |
| 父目录未创建导致文件写入失败 | Producer 同步创建目录，Consumer 只负责写文件 |
