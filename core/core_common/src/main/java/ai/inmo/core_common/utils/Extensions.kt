package com.inmo.core_common

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.ColorInt
import androidx.constraintlayout.widget.ConstraintLayout
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 
 * Date: 2025/10/10 16:08
 * 
 */

fun <T> Context.isServiceRunning(serviceClass: Class<T>): Boolean {
    val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
    val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
    return runningServices.any {
        it.service.className == serviceClass.name
    }
}

// Context 扩展函数
fun Context.dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density + 0.5f).toInt()
}

// 可选：Float 版本
fun Context.dpToPx(dp: Float): Float {
    return dp * resources.displayMetrics.density
}

fun View.show() {
    visibility = View.VISIBLE
}

fun View.occupancy() {
    visibility = View.INVISIBLE
}

fun View.hide() {
    visibility = View.GONE
}

@ColorInt
fun Context.getThemeColor(attrResId: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attrResId, typedValue, true)
    return typedValue.data
}

val Float.dp
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this,
        Resources.getSystem().displayMetrics
    )

val Int.dp
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        Resources.getSystem().displayMetrics
    )

fun Int.dpToPx(context: Context): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        context.resources.displayMetrics  // 用 activity context 而非系统全局
    ).roundToInt()
}

val Float.sp
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        this,
        Resources.getSystem().displayMetrics
    )

val Int.sp
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        this.toFloat(),
        Resources.getSystem().displayMetrics
    )

/**
 * 经过校验的显示
 *
 * @param callback
 */
fun Dialog.safetyShow(callback: (() -> Unit)? = null) {
    if (isShowing) {
        return
    }

    show()

    callback?.invoke()
}

/**
 * 经过校验的隐藏
 *
 * @param callback
 */
fun Dialog.safetyDismiss(callback: (() -> Unit)? = null) {
    if (!isShowing) {
        return
    }

    dismiss()

    callback?.invoke()
}


/**
 * 判断状态栏是否可见
 * 兼容 API 21 - API 35+
 */
fun Activity.isStatusBarVisible(): Boolean {
    val window = this.window ?: return false
    val decorView = window.decorView

    // 方案 A: Android 11 (API 30+) 使用 WindowInsets
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val insets = decorView.rootWindowInsets
        // 注意：如果 View 还没 attach 到 Window，insets 可能为 null
        if (insets != null) {
            return insets.isVisible(WindowInsets.Type.statusBars())
        }
    }

    // 方案 B: 旧版本使用 SystemUiVisibility 或 Window Flags
    // 1. 检查 Window Flag (全屏 Flag)
    val windowFlags = window.attributes.flags
    if ((windowFlags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0) {
        return false
    }

    // 2. 检查 SystemUiVisibility (废弃 API 但旧设备必须用)
    // SYSTEM_UI_FLAG_FULLSCREEN 被设置意味着状态栏请求隐藏
    val systemUiVisibility = decorView.systemUiVisibility
    return (systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
}

fun View.updateConstraintLayoutMargin(baseDimenRes: Int, statusBarHeight: Int = 0) {
    layoutParams = (layoutParams as ConstraintLayout.LayoutParams).apply {
        topMargin = resources.getDimensionPixelSize(baseDimenRes) + statusBarHeight
    }
}
/**
 * 将毫秒转换为自适应的时间字符串
 *
 * 规则：
 * < 1 小时 -> "mm:ss" (例如 05:30)
 * >= 1 小时 -> "HH:mm:ss" (例如 01:30:05)
 */
fun Long.toAdaptiveDuration(): String {
    if (this < 0) return "00:00"

    val totalSeconds = this / 1000

    // 计算时、分、秒
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        // 超过1小时：显示 HH:mm:ss
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        // 不足1小时：显示 mm:ss
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
