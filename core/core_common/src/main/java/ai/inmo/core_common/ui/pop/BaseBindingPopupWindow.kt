package ai.inmo.core_common.ui.pop

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job

/**
 * 
 * Date: 2024/09/20 18:25
 * 
 */
abstract class BaseBindingPopupWindow<T : ViewBinding>(
    val ctx: Context,
    val w: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    val h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    val canOutsideTouchables: Boolean = true
) : PopupWindow(ctx) {

    protected lateinit var binding: T

    // 这里保留 offset 定义，但含义转变为相对于锚点 View 的中心或边缘的微调
    abstract val xoff: Int
    abstract val yoff: Int

    protected var mMainScope = MainScope()

    init {
        initBinding()
        init()
    }

    private fun initBinding() {
        val inflater = LayoutInflater.from(ctx)
        binding = createBinding(inflater)
        contentView = binding.root
    }

    protected abstract fun createBinding(inflater: LayoutInflater): T

    private fun init() {
        width = w
        height = h
        setBackgroundDrawable(BitmapDrawable())
        isFocusable = true
        isTouchable = true
        isOutsideTouchable = canOutsideTouchables
        initView()
    }

    protected abstract fun initView()

    protected open fun onShow() {}

    private fun initMainScope() {
        if (mMainScope.coroutineContext[Job]?.isActive != true) {
            mMainScope = MainScope()
        }
    }

    /**
     * 关键修复：手动测量 View 的大小
     * 解决 binding.root.measuredHeight 为 0 的问题
     */
    private fun preMeasure() {
        binding.root.measure(
            makeDropDownMeasureSpec(width),
            makeDropDownMeasureSpec(height)
        )
    }

    @Suppress("SameParameterValue")
    private fun makeDropDownMeasureSpec(measureSpec: Int): Int {
        return if (measureSpec == ViewGroup.LayoutParams.WRAP_CONTENT) {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            View.MeasureSpec.makeMeasureSpec(measureSpec, View.MeasureSpec.EXACTLY)
        }
    }

    // ---------------------------------------------------------
    // 下面是修复后的显示逻辑
    // ---------------------------------------------------------

    fun show(view: View) {
        // show 默认作为下拉，使用定义的偏移
        showDown(view)
    }

    /**
     * 在 View 上方显示
     */
    open fun showUp(view: View) {
        initMainScope()
        onShow()

        // 1. 必须先测量，否则获取不到高度
        preMeasure()

        val popupWidth = binding.root.measuredWidth
        val popupHeight = binding.root.measuredHeight

        // 2. 计算 X 轴偏移：居中显示
        // showAsDropDown 默认 X 是对齐 View 左边的
        // 公式：(Anchor宽 / 2) - (Popup宽 / 2) + 自定义偏移
        val xOffset = (view.width / 2) - (popupWidth / 2) + xoff

        // 3. 计算 Y 轴偏移：上方显示
        // showAsDropDown 默认 Y 是在 View 下方
        // 需要向上移动：Anchor高度 + Popup高度 + 自定义偏移
        // 注意：这里需要取负数
        val yOffset = -(view.height + popupHeight) + yoff

        // 4. 使用 showAsDropDown 替代 showAtLocation
        showAsDropDown(view, xOffset, yOffset)
    }

    /**
     * 在 View 下方显示
     */
    open fun showDown(view: View) {
        initMainScope()
        onShow()

        preMeasure()

        val popupWidth = binding.root.measuredWidth

        // 计算 X 轴偏移：居中显示
        val xOffset = (view.width / 2) - (popupWidth / 2) + xoff

        // Y 轴偏移：showAsDropDown 默认就在下方，直接加上 yoff 即可
        val finalYOff = yoff

        showAsDropDown(view, xOffset, finalYOff)
    }

    fun clearJobs() {
        mMainScope.cancel()
    }

    override fun dismiss() {
        clearJobs()
        super.dismiss()
    }
}