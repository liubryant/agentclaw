package ai.inmo.core_common.ui.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.viewbinding.ViewBinding
import com.inmo.core_common.dpToPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 *
 * Date: 2025/10/14 14:17
 * 
 */
abstract class BaseFloatingView<VB : ViewBinding>(
    protected val context: Context,
    private val inflate: (LayoutInflater) -> VB,
    private val widthPercent: Float = 0.5f,
    private val heightPercent: Float = 0.12f,
    private val yOffsetDp: Int = 60,
//    @StyleRes private val animStyle: Int = R.style.BaseDialog_Notification_Anim,
) {
    protected val binding: VB = inflate(LayoutInflater.from(context))
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var countdownJob: Job? = null
    private var isAdded = false

    private val layoutParams = WindowManager.LayoutParams().apply {
        width = (getScreenWidth(context) * widthPercent).toInt()
        height = (getScreenHeight(context) * heightPercent).toInt()
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = context.dpToPx(yOffsetDp)
        format = PixelFormat.TRANSLUCENT
        flags = (
//                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                )
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
//        windowAnimations = animStyle
    }

    /** 子类可重写初始化逻辑 */
    open fun onCreateView(binding: VB) {}

    /** 显示 */
    fun show(autoDismissMillis: Long = 0L) {
        if (isAdded) return
        onCreateView(binding)
        windowManager.addView(binding.root, layoutParams)
        isAdded = true
        if (autoDismissMillis > 0) {
            startAutoDismissTimer(autoDismissMillis)
        }
    }

    /** 自动关闭计时 */
    private fun startAutoDismissTimer(millis: Long) {
        countdownJob?.cancel()
        countdownJob = CoroutineScope(Dispatchers.Main).launch {
            delay(millis)
            dismiss()
        }
    }

    /** 安全关闭 */
    fun dismiss() {
        if (!isAdded) return
        try {
            windowManager.removeView(binding.root)
        } catch (_: Exception) {}
        countdownJob?.cancel()
        countdownJob = null
        isAdded = false
    }

    fun isShowing(): Boolean = isAdded

    private fun getScreenWidth(context: Context) = context.resources.displayMetrics.widthPixels
    private fun getScreenHeight(context: Context) = context.resources.displayMetrics.heightPixels
}