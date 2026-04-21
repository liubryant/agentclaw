# openclaw.json 环境变量替换

## Context

当前 `ProviderConfigService.saveProviderConfig()` 将 apiKey 和 baseUrl 明文写入 `openclaw.json`。openclaw gateway 已原生支持 `${VAR_NAME}` 环境变量替换语法。本次改动：对 INMOCLAW 提供者写入占位符 `${INMOCLAW_LLM_API_KEY}` / `${INMOCLAW_LLM_BASE_URL}`，实际值通过 PRoot 进程环境变量注入，gateway 启动时自动解析。

## 修改文件（5 个）

### 1. AiProvider.kt — 添加环境变量名常量
**路径:** `app/src/main/java/ai/inmo/openclaw/domain/model/AiProvider.kt`

在 companion object 中添加：
```kotlin
const val ENV_INMOCLAW_API_KEY = "INMOCLAW_LLM_API_KEY"
const val ENV_INMOCLAW_BASE_URL = "INMOCLAW_LLM_BASE_URL"
```

### 2. ProviderConfigService.kt — INMOCLAW 写占位符
**路径:** `app/src/main/java/ai/inmo/openclaw/data/repository/ProviderConfigService.kt`

修改 `saveProviderConfig()` 第 50-52 行，INMOCLAW 时写占位符：
```kotlin
val isInmoClaw = (provider.id == AiProvider.INMOCLAW.id)
val effectiveApiKey = if (isInmoClaw) "\${${AiProvider.ENV_INMOCLAW_API_KEY}}" else apiKey
val effectiveBaseUrl = if (isInmoClaw) "\${${AiProvider.ENV_INMOCLAW_BASE_URL}}" else provider.baseUrl

providers[provider.id] = mutableMapOf(
    "apiKey" to effectiveApiKey,
    "baseUrl" to effectiveBaseUrl,
    ...
)
```

### 3. ProcessManager.kt — 支持额外环境变量
**路径:** `app/src/main/java/ai/inmo/openclaw/proot/ProcessManager.kt`

`buildGatewayCommand` 和 `startProotProcess` 增加 `extraEnv` 参数（默认空 map，不影响现有调用者）：

```kotlin
fun buildGatewayCommand(command: String, extraEnv: Map<String, String> = emptyMap()): List<String> {
    // ... 现有代码 ...
    // 在 "/bin/bash" 之前插入 extraEnv
    val envEntries = extraEnv.map { "${it.key}=${it.value}" }
    flags.addAll(listOf(
        "/usr/bin/env", "-i",
        "HOME=/root", ... "UV_USE_IO_URING=0",
        *envEntries.toTypedArray(),     // <-- 新增
        "/bin/bash", "-c", command,
    ))
}

fun startProotProcess(command: String, extraEnv: Map<String, String> = emptyMap()): Process {
    val cmd = buildGatewayCommand(command, extraEnv)
    // ... 其余不变
}
```

### 4. GatewayService.kt — 传入实际值
**路径:** `app/src/main/java/ai/inmo/openclaw/service/gateway/GatewayService.kt`

第 218 行改为：
```kotlin
val gatewayEnv = mapOf(
    AiProvider.ENV_INMOCLAW_API_KEY to DeviceInfo.sn,
    AiProvider.ENV_INMOCLAW_BASE_URL to AiProvider.INMOCLAW.baseUrl
)
gatewayProcess = pm.startProotProcess("openclaw gateway --verbose", gatewayEnv)
```

### 5. StartupViewModel.kt — 去掉硬编码 SN
**路径:** `app/src/main/java/ai/inmo/openclaw/ui/startup/StartupViewModel.kt`

第 168 行：`apiKey = /*DeviceInfo.sn*/ "YM00FCE5600128"` → `apiKey = DeviceInfo.sn`

## 生成的 openclaw.json 效果

```json
{
  "models": {
    "providers": {
      "inmoclaw": {
        "apiKey": "${INMOCLAW_LLM_API_KEY}",
        "baseUrl": "${INMOCLAW_LLM_BASE_URL}",
        "api": "openai-completions",
        "models": [{ "id": "glm-4.7", "name": "glm-4.7" }]
      }
    }
  }
}
```

Gateway 启动时，PRoot 进程环境变量包含 `INMOCLAW_LLM_API_KEY=<设备SN>` 和 `INMOCLAW_LLM_BASE_URL=https://testai.bot.......，gateway 的 env-substitution 模块自动解析。

## 验证方式

1. 触发 Startup 流程，检查 `{filesDir}/rootfs/ubuntu/root/.openclaw/openclaw.json` 包含 `${INMOCLAW_LLM_API_KEY}` 占位符
2. 启动 gateway，确认 AI 聊天功能正常（说明环境变量被正确替换）
3. 添加非 INMOCLAW 提供者（如 OpenAI），确认仍写入明文值
