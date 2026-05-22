package ai.cjym.agentclaw

import ai.inmo.core_common.utils.DeviceInfo
import ai.inmo.core_common.utils.Logger
import android.app.Application
import com.umeng.commonsdk.UMConfigure

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // 仅预初始化，不采集任何数据（用户同意隐私政策后再调 init）
        UMConfigure.preInit(this, "6a1010366f259537c7ad0122", "agentclaw")

        try {
            val sn = DeviceInfo.sn
            Logger.d("sn:${sn}")
        }catch (e: Exception){
            e.printStackTrace()
        }
    }
}