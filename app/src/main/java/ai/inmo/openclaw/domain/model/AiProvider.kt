package ai.inmo.openclaw.domain.model

import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.remote.api.BotNetworkModule
import androidx.annotation.ColorInt

data class AiProvider(
    val id: String,
    val name: String,
    val description: String,
    val iconResName: String,
    @ColorInt val color: Int,
    val baseUrl: String,
    val defaultModels: List<String>,
    val apiKeyHint: String,
    val apiType: String? = null,
    val authHeader: Boolean = false
) {
    companion object {
        const val ENV_INMOCLAW_API_KEY = "INMOCLAW_LLM_API_KEY"
        const val ENV_INMOCLAW_BASE_URL = "INMOCLAW_LLM_BASE_URL"

        val ANTHROPIC = AiProvider(
            id = "anthropic",
            name = "Anthropic",
            description = "Claude models for advanced reasoning and coding",
            iconResName = "ic_psychology",
            color = 0xFFD97706.toInt(),
            baseUrl = "https://api.anthropic.com/v1",
            defaultModels = listOf(
                "claude-sonnet-4-20250514",
                "claude-opus-4-20250514",
                "claude-haiku-4-20250506",
                "claude-3-5-sonnet-20241022"
            ),
            apiKeyHint = "sk-ant-..."
        )

        val OPENAI = AiProvider(
            id = "openai",
            name = "OpenAI",
            description = "GPT and o-series models",
            iconResName = "ic_auto_awesome",
            color = 0xFF10A37F.toInt(),
            baseUrl = "https://api.openai.com/v1",
            defaultModels = listOf("gpt-4o", "gpt-4o-mini", "o1", "o1-mini", "gpt-4-turbo"),
            apiKeyHint = "sk-..."
        )

        val GOOGLE = AiProvider(
            id = "google",
            name = "Google Gemini",
            description = "Gemini family of multimodal models",
            iconResName = "ic_diamond",
            color = 0xFF4285F4.toInt(),
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModels = listOf(
                "gemini-2.5-pro",
                "gemini-2.5-flash",
                "gemini-2.0-flash",
                "gemini-1.5-pro"
            ),
            apiKeyHint = "AIza..."
        )

        val OPENROUTER = AiProvider(
            id = "openrouter",
            name = "OpenRouter",
            description = "Unified API for hundreds of models",
            iconResName = "ic_route",
            color = 0xFF6366F1.toInt(),
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModels = listOf(
                "anthropic/claude-sonnet-4",
                "openai/gpt-4o",
                "google/gemini-2.5-pro",
                "meta-llama/llama-3.1-405b-instruct"
            ),
            apiKeyHint = "sk-or-..."
        )

        val NVIDIA = AiProvider(
            id = "nvidia",
            name = "NVIDIA NIM",
            description = "GPU-optimized inference endpoints",
            iconResName = "ic_memory",
            color = 0xFF76B900.toInt(),
            baseUrl = "https://integrate.api.nvidia.com/v1",
            defaultModels = listOf(
                "meta/llama-3.1-405b-instruct",
                "meta/llama-3.1-70b-instruct",
                "meta/llama-3.3-70b-instruct",
                "nvidia/nemotron-4-340b-instruct",
                "deepseek-ai/deepseek-r1"
            ),
            apiKeyHint = "nvapi-..."
        )

        val DEEPSEEK = AiProvider(
            id = "deepseek",
            name = "DeepSeek",
            description = "High-performance open models",
            iconResName = "ic_explore",
            color = 0xFF0EA5E9.toInt(),
            baseUrl = "https://api.deepseek.com/v1",
            defaultModels = listOf("deepseek-chat", "deepseek-reasoner"),
            apiKeyHint = "sk-..."
        )

        val XAI = AiProvider(
            id = "xai",
            name = "xAI",
            description = "Grok models from xAI",
            iconResName = "ic_bolt",
            color = 0xFFEF4444.toInt(),
            baseUrl = "https://api.x.ai/v1",
            defaultModels = listOf("grok-3", "grok-3-mini", "grok-2"),
            apiKeyHint = "xai-..."
        )

        val ZHIPU = AiProvider(
            id = "zai",
            name = "Zhipu AI",
            description = "GLM series models by Zhipu AI",
            iconResName = "ic_hub",
            color = 0xFF4F46E5.toInt(),
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModels = listOf(
                "glm-5",
                "glm-4.7",
                "glm-4-plus",
                "glm-4",
                "glm-4-flash",
                "glm-4-long"
            ),
            apiKeyHint = "xxxxxxxx.xxxxxxxxxxxxxxxx"
        )

        val MINIMAX = AiProvider(
            id = "minimax",
            name = "MiniMax",
            description = "MiniMax M2.5 reasoning models",
            iconResName = "ic_blur_on",
            color = 0xFF1A1A2E.toInt(),
            baseUrl = "https://api.minimax.io/anthropic",
            defaultModels = listOf("MiniMax-M2.5", "MiniMax-M2.5-highspeed", "MiniMax-VL-01"),
            apiKeyHint = "eyJ...",
            apiType = "anthropic-messages",
            authHeader = true
        )

        val MINIMAX_CN = AiProvider(
            id = "minimax-cn",
            name = "MiniMax (CN)",
            description = "MiniMax China endpoints for domestic deployment",
            iconResName = "ic_blur_on",
            color = 0xFF1A1A2E.toInt(),
            baseUrl = "https://api.minimaxi.com/anthropic",
            defaultModels = listOf("MiniMax-M2.5", "MiniMax-M2.5-highspeed"),
            apiKeyHint = "eyJ...",
            apiType = "anthropic-messages",
            authHeader = true
        )

        val INMOCLAW = AiProvider(
            id = "inmoclaw",
            name = "INMOClaw",
            description = "clawbootdo OpenAI-compatible chat service",
            iconResName = "ic_hub",
            color = 0xFF4F46E5.toInt(),
            baseUrl = "http://192.168.110.37:8066/v1",
            defaultModels = listOf("glm-5.1", "glm-4.7"),
            apiKeyHint = "gateway token (optional)",
            apiType = "openai-completions"
        )

        val ALL = listOf(INMOCLAW)
    }
}