package ai.cjym.agentclaw.pay

import android.content.Context
import android.content.Intent
import com.tencent.mm.opensdk.modelpay.PayReq
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory

object WeChatPayManager {

    @Volatile
    private var api: IWXAPI? = null
    @Volatile
    private var registeredAppId: String = ""

    fun init(context: Context) {
        // Lazily create API; actual appId will be registered on first pay() call
        if (api == null) {
            synchronized(this) {
                if (api == null) {
                    api = WXAPIFactory.createWXAPI(context.applicationContext, null, true)
                }
            }
        }
    }

    fun isWeChatInstalled(): Boolean = api?.isWXAppInstalled == true

    fun handleIntent(intent: Intent, handler: IWXAPIEventHandler) {
        api?.handleIntent(intent, handler)
    }

    fun pay(context: Context, params: WeChatPayParams): Boolean {
        val wxApi = api ?: WXAPIFactory.createWXAPI(context.applicationContext, params.appId, true)
            .also { api = it }
        // Re-register if the appId from the server differs from what's currently registered
        if (registeredAppId != params.appId) {
            wxApi.registerApp(params.appId)
            registeredAppId = params.appId
        }
        val req = PayReq().apply {
            appId = params.appId
            partnerId = params.partnerId
            prepayId = params.prepayId
            packageValue = params.packageValue
            nonceStr = params.nonceStr
            timeStamp = params.timeStamp
            sign = params.sign
            signType = params.signType
        }
        return wxApi.sendReq(req)
    }
}
