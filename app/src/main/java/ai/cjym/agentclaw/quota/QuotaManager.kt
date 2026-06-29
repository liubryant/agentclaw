package ai.cjym.agentclaw.quota

import ai.cjym.agentclaw.data.local.prefs.PreferencesManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object QuotaManager {

    // Hardcoded fallbacks used when server config hasn't been fetched yet
    const val FREE_VIDEO_DAILY = 1
    const val FREE_IMAGE_DAILY = 10
    const val VIP_VIDEO_DAILY = 5
    const val VIP_IMAGE_DAILY = 50

    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    private fun today() = dateFormat.format(Date())

    fun freeVideoLimit(context: Context): Int {
        val v = PreferencesManager(context).serverFreeVideoDaily
        return if (v >= 0) v else FREE_VIDEO_DAILY
    }

    fun freeImageLimit(context: Context): Int {
        val v = PreferencesManager(context).serverFreeImageDaily
        return if (v >= 0) v else FREE_IMAGE_DAILY
    }

    fun vipVideoLimit(context: Context): Int {
        val v = PreferencesManager(context).serverVipVideoDaily
        return if (v >= 0) v else VIP_VIDEO_DAILY
    }

    fun vipImageLimit(context: Context): Int {
        val v = PreferencesManager(context).serverVipImageDaily
        return if (v >= 0) v else VIP_IMAGE_DAILY
    }

    fun videoLimit(context: Context): Int =
        if (PreferencesManager(context).isVipActive) vipVideoLimit(context) else freeVideoLimit(context)

    fun imageLimit(context: Context): Int =
        if (PreferencesManager(context).isVipActive) vipImageLimit(context) else freeImageLimit(context)

    fun todayVideoCount(context: Context): Int {
        val prefs = PreferencesManager(context)
        return if (prefs.quotaVideoDate == today()) prefs.quotaVideoCount else 0
    }

    fun todayImageCount(context: Context): Int {
        val prefs = PreferencesManager(context)
        return if (prefs.quotaImageDate == today()) prefs.quotaImageCount else 0
    }

    fun canGenerateVideo(context: Context): Boolean =
        todayVideoCount(context) < videoLimit(context)

    fun canGenerateImage(context: Context): Boolean =
        todayImageCount(context) < imageLimit(context)

    fun consumeVideo(context: Context) {
        val prefs = PreferencesManager(context)
        val today = today()
        val count = if (prefs.quotaVideoDate == today) prefs.quotaVideoCount else 0
        prefs.quotaVideoDate = today
        prefs.quotaVideoCount = count + 1
    }

    fun consumeImage(context: Context) {
        val prefs = PreferencesManager(context)
        val today = today()
        val count = if (prefs.quotaImageDate == today) prefs.quotaImageCount else 0
        prefs.quotaImageDate = today
        prefs.quotaImageCount = count + 1
    }
}
