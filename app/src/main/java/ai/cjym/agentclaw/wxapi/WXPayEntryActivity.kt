package ai.cjym.agentclaw.wxapi

import ai.cjym.agentclaw.VipActivity
import ai.cjym.agentclaw.pay.WeChatPayManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.tencent.mm.opensdk.constants.ConstantsAPI
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelpay.PayResp
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler

class WXPayEntryActivity : Activity(), IWXAPIEventHandler {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WeChatPayManager.handleIntent(intent, this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        WeChatPayManager.handleIntent(intent, this)
    }

    override fun onReq(req: BaseReq) {}

    override fun onResp(resp: BaseResp) {
        if (resp.type == ConstantsAPI.COMMAND_PAY_BY_WX) {
            val intent = Intent(VipActivity.ACTION_WX_PAY_RESULT).apply {
                putExtra(VipActivity.EXTRA_WX_ERROR_CODE, resp.errCode)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }
        finish()
    }
}
