package ai.cjym.agentclaw.pay

import android.app.Activity
import com.alipay.sdk.app.PayTask

object AlipayManager {

    interface PayResultCallback {
        fun onSuccess()
        fun onProcessing()
        fun onCancelled()
        fun onFailed(msg: String)
    }

    fun pay(activity: Activity, orderString: String, callback: PayResultCallback) {
        Thread {
            val alipay = PayTask(activity)
            val result = alipay.payV2(orderString, true)
            val resultStatus = result["resultStatus"] ?: ""
            activity.runOnUiThread {
                when (resultStatus) {
                    "9000" -> callback.onSuccess()
                    "8000" -> callback.onProcessing()
                    "6001" -> callback.onCancelled()
                    else -> callback.onFailed(result["memo"] ?: "支付失败")
                }
            }
        }.start()
    }
}
