package ai.cjym.agentclaw.data.remote.api

data class TokenUsageRequest(val sn: String)

data class TokenUsageResponse(
    val msg: String,
    val code: String,
    val data: TokenUsageData?
)

data class TokenUsageData(
    val usedTokens: Long,
    val totalQuota: Long
)

data class LoginVerificationCodeRequest(val phone: String)

data class LoginVerificationCodeResponse(
    val msg: String?,
    val code: String?,
    val data: Any?
)
