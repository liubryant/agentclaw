package ai.cjym.agentclaw.data.remote.api

import retrofit2.http.Body
import retrofit2.http.POST

interface BotApi {
    @POST("/im/bot/token-usage")
    suspend fun getTokenUsage(@Body request: TokenUsageRequest): TokenUsageResponse

    @POST("/im/bot/login-code")
    suspend fun requestLoginVerificationCode(@Body request: LoginVerificationCodeRequest): LoginVerificationCodeResponse
}
