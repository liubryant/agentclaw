package ai.inmo.openclaw

import ai.inmo.core_common.utils.DeviceInfo
import ai.inmo.core_common.utils.Logger
import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            val sn = DeviceInfo.sn
            Logger.d("sn:${sn}")
        }catch (e: Exception){
            e.printStackTrace()
        }
    }
}