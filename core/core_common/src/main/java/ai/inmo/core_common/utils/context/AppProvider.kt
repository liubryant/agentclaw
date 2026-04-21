package ai.inmo.core_common.utils.context

import android.content.Context

/**
 * 
 * Date: 2024/09/20 19:01
 * 
 */
object AppProvider {

    private val instance: Context by lazy {
        AppContentProvider.autoContext!!
    }

    @JvmStatic
    fun get() = instance
}