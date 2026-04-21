package ai.inmo.core_common.utils

import ai.inmo.core_common.utils.context.AppProvider
import android.os.Environment
import android.util.Log
import com.inmo.log.INMOLog
import java.io.File

/**
 * 
 * Date: 2025/07/04 14:20
 * 
 */
object Logger {

    init {
        val packageName = "ai.inmo.openclaw"

        INMOLog.getConfig().apply {
            setBorderSwitch(false)
            setLogSwitch(true)
            setLogHeadSwitch(true)
            setLog2FileSwitch(true)
            setIsDebug(false)
            singleFileMaxSize = 10
            saveSize = 300
            context = AppProvider.get().applicationContext
            dir =
                Environment.getExternalStorageDirectory().path + "/QLog/apps" + File.separator + packageName
            channelName = 1
        }
    }

    private val DEBUG = true

    fun i(content: String) {
        i("", content)
    }

    fun i(tag: String, content: String) {
        if (DEBUG) {
            Log.d(tag, content)
        } else {
            INMOLog.dTag(tag, content)
        }
    }

    fun v(content: String) {
        v("", content)
    }

    fun v(tag: String, content: String) {
        if (DEBUG) {
            Log.v(tag, content)
        } else {
            INMOLog.vTag(tag, content)
        }
    }

    fun d(content: String) {
        d("", content)
    }

    fun d(tag: String, content: String) {
        if (DEBUG) {
            Log.d(tag, content)
        } else {
            INMOLog.dTag(tag, content)
        }
    }

    fun w(content: String) {
        w("", content)
    }

    fun w(tag: String, content: String) {
        if (DEBUG) {
            Log.w(tag, content)
        } else {
            INMOLog.wTag(tag, content)
        }
    }

    fun e(content: String) {
        e("", content)
    }

    fun e(tag: String, content: String) {
        if (DEBUG) {
            Log.e(tag, content)
        } else {
            INMOLog.eTag(tag, content)
        }
    }
}