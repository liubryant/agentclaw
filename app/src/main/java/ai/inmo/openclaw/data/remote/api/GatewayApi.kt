package ai.inmo.openclaw.data.remote.api

import retrofit2.Response
import retrofit2.http.HEAD

interface GatewayApi {
    @HEAD("/")
    suspend fun healthCheck(): Response<Void>
}
