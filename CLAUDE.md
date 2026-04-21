# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

INMOClaw (package: `ai.inmo.openclaw`) is an Android app that runs an OpenClaw gateway inside a PRoot-based Linux container (Ubuntu 24.04), providing terminal access, node connectivity via WebSocket, and AI chat capabilities. Built by INMO.

## Build Commands

```bash
# Build debug/release APK
./gradlew assembleDebug
./gradlew assembleRelease

# Run unit tests (all modules)
./gradlew test

# Run unit tests for a specific module
./gradlew :app:testDebugUnitTest
./gradlew :core:core_common:testDebugUnitTest

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean
```

## Architecture

### Module Structure

- **`:app`** — Main application module with all business logic, UI, services, and data layer. Uses ViewBinding + XML layouts (not Compose). Plugins: `android.application`, `kotlin.android`, `ksp` (for Room).
- **`:core:core_common`** — Shared foundation library with base classes and utilities.

### Dependency Injection

**Manual service locator** via `AppGraph` singleton (no Hilt/Dagger). All dependencies are lazy-initialized and accessed as `AppGraph.prefsManager`, `AppGraph.gatewayManager`, etc. New services must be wired here.

### App Flow

`SplashActivity` (launcher) → `StartupActivity` (multi-step bootstrap: environment → model config → gateway → node) → `DashboardActivity` (main hub). `MainActivity` is a trampoline that redirects to Dashboard.

### Layered Architecture

**Data Layer** (`data/`):
- `data/local/db/` — Room database (`AppDatabase`) with chat sessions and messages (KSP-processed, schemas in `app/schemas/`)
- `data/local/prefs/` — `PreferencesManager` wraps SharedPreferences for config (gateway host/port, tokens, setup state)
- `data/remote/api/` — `NetworkModule` (Retrofit + OkHttp singleton, base URL `http://127.0.0.1:18789/`), `GatewayApi` for health checks
- `data/remote/websocket/` — `NodeWsManager` handles WebSocket connections with exponential backoff reconnection and request-response correlation

**Repository Layer** (`data/repository/`):
- `GatewayManager` — Gateway process lifecycle, health polling, log streaming, dashboard URL extraction
- `NodeManager` — Node device lifecycle, WebSocket auth (challenge-response), capability routing
- `SetupCoordinator` — One-time environment bootstrap orchestration (rootfs extraction, bionic bypass)
- `ChatRepository` / `ChatService` — Chat persistence (Room) + SSE streaming to LLM
- `TerminalSessionManager` — Termux terminal emulation via `TerminalSessionClient` interface
- `SyncedChatWsManager` — WebSocket-based synced chat

**Capability Handlers** (`capability/`):
Pluggable handlers for node capability invocation requests (camera, filesystem, location, system). `NodeManager` routes incoming `node.invoke.request` frames to the appropriate handler.

**PRoot Layer** (`proot/`):
- `BootstrapManager` — Rootfs asset extraction (tar.gz/tar.xz/ar), DNS config, bionic bypass for Node.js
- `ProcessManager` — Builds PRoot commands (install mode vs. login/shell mode) with mount bindings, env vars, fake /proc files

**Services** (`service/`):
All are foreground services with wake locks and persistent notifications:
- `GatewayService` — Spawns gateway process, streams logs, auto-restart (max 5 attempts, exponential backoff), watchdog thread
- `NodeForegroundService` — Node connection status notification
- `SetupService` — Setup progress notification with progress bar
- `TerminalSessionService` — Keeps terminal session alive across activity transitions
- `ScreenCaptureService` — Media projection (foregroundServiceType=mediaProjection)

**UI Layer** (`ui/`):
Each screen follows Activity + ViewModel pattern. ViewModels use `BaseViewModel` with `launchUi{}`/`launchIo{}` coroutine helpers. State observation via StateFlow + `collectLatest`.

### core_common Base Classes

Activities extend: `BaseBindingActivity<VB>` → (optionally) `KeyMonitorActivity<VB>`. All implement `InitListener` enforcing: `initView()` → `initData()` → `initEvent()`.

Key base classes: `BaseViewModel` (lifecycle-aware, coroutine-scoped), `BaseListAdapter<T,VB>` / `BaseListViewTypeAdapter<T>` (DiffUtil-based RecyclerView adapters), `BaseBindingDialog<VB>`, `BaseFloatingView`.

### Global Context

`AppContentProvider` auto-captures application Context at startup. Access via `AppProvider.get()`.

### Reactive State Pattern

Managers expose `StateFlow` for state (e.g., `GatewayState`, `NodeState`, `SetupState`) and `SharedFlow` for event streams (logs, WebSocket frames). UI collects these in coroutine scopes.

## Key Configuration

- **Build files**: Groovy DSL (`build.gradle`, not `.kts`)
- **Version catalog**: `gradle/libs.versions.toml`
- **compileSdk / targetSdk**: 34, **minSdk**: 24
- **Java**: 11, **Kotlin**: 2.2.10, **AGP**: 8.10.1
- **Room schemas**: exported to `app/schemas/` via KSP arg
- **ViewBinding + BuildConfig + AIDL**: enabled in `:app`
- APK naming: `INMOOpenClaw-{buildType}-v{version}-{timestamp}.apk`
- Signing: `keystore/InmoClaw.jks` for both debug and release
- `aaptOptions.noCompress`: `gz`, `xz`, `bin` etc. — required for `openFd()` on large assets
- `jniLibs.useLegacyPackaging = true` — native libs uncompressed in APK
- Maven repos: Google, Maven Central, Aliyun mirror, JitPack, Gitee-hosted INMO common libs
- Gateway base URL: `http://127.0.0.1:18789/`
- Logging: `Logger` wraps INMO `logmodule` library, conditional on `BuildConfig.DEBUG`, writes to `QLog/apps/`
