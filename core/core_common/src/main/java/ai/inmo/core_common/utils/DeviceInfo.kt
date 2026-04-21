package ai.inmo.core_common.utils

import ai.inmo.core_common.utils.context.AppProvider
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 
 * Date: 2025/07/07 15:13
 * 
 */
object DeviceInfo {
    /**
     * 设备 sn
     */
//    val sn = Build.getSerial()
//    val sn = "YM00FCE5600128"

    val sn: String
        get() = Settings.Global.getString(
            AppProvider.get().contentResolver,
            "normal_permission_app_sn"
        ).orEmpty()

}