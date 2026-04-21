package ai.inmo.openclaw.ui.shell

import ai.inmo.core_common.ui.pop.BaseBindingPopupWindow
import ai.inmo.openclaw.databinding.PopupShellHoverTooltipBinding
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

class ShellHoverTooltipPopupWindow(
    context: Context
) : BaseBindingPopupWindow<PopupShellHoverTooltipBinding>(
    ctx = context,
    w = ViewGroup.LayoutParams.WRAP_CONTENT,
    h = ViewGroup.LayoutParams.WRAP_CONTENT,
    canOutsideTouchables = false
) {

    override val xoff: Int = 0
    override val yoff: Int = 0

    private val screenMargin = (context.resources.displayMetrics.density * 12f).toInt()
    private val anchorGap = (context.resources.displayMetrics.density * 2f).toInt()

    override fun createBinding(inflater: LayoutInflater): PopupShellHoverTooltipBinding {
        return PopupShellHoverTooltipBinding.inflate(inflater)
    }

    override fun initView() {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isFocusable = false
        isTouchable = false
        isOutsideTouchable = false
        elevation = ctx.resources.displayMetrics.density * 12f
    }

    fun show(
        anchor: View,
        text: CharSequence,
        gravity: Int = Gravity.CENTER,
        preferredTextWidth: Int? = null
    ) {
        binding.tooltipTextView.text = text
        binding.tooltipTextView.gravity = gravity

        val windowFrame = Rect()
        anchor.getWindowVisibleDisplayFrame(windowFrame)
        val availableWidth = max(windowFrame.width() - screenMargin * 2, 0)
        val resolvedTextWidth = preferredTextWidth?.coerceAtMost(availableWidth)
        binding.tooltipTextView.maxWidth = resolvedTextWidth ?: availableWidth
        binding.tooltipTextView.layoutParams = binding.tooltipTextView.layoutParams.apply {
            width = resolvedTextWidth ?: ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val textWidthSpec = if (resolvedTextWidth != null) {
            View.MeasureSpec.makeMeasureSpec(resolvedTextWidth, View.MeasureSpec.EXACTLY)
        } else {
            makeContentMeasureSpec(ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val contentHeightSpec = makeContentMeasureSpec(ViewGroup.LayoutParams.WRAP_CONTENT)
        binding.tooltipTextView.measure(textWidthSpec, contentHeightSpec)

        val rootWidthSpec = if (resolvedTextWidth != null) {
            View.MeasureSpec.makeMeasureSpec(binding.tooltipTextView.measuredWidth, View.MeasureSpec.EXACTLY)
        } else {
            makeContentMeasureSpec(width)
        }
        binding.root.measure(rootWidthSpec, contentHeightSpec)
        binding.root.layout(0, 0, binding.root.measuredWidth, binding.root.measuredHeight)

        val popupWidth = binding.root.measuredWidth
        val popupHeight = binding.root.measuredHeight
        width = popupWidth
        height = popupHeight
        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        val anchorCenterX = anchorLocation[0] + anchor.width / 2
        val desiredX = anchorCenterX - popupWidth / 2
        val minX = windowFrame.left + screenMargin
        val maxX = max(windowFrame.right - popupWidth - screenMargin, minX)
        val x = desiredX.coerceIn(minX, maxX)
        val desiredY = anchorLocation[1] + anchor.height + anchorGap
        val maxY = max(windowFrame.bottom - popupHeight - screenMargin, windowFrame.top + screenMargin)
        val y = desiredY.coerceIn(windowFrame.top + screenMargin, maxY) - screenMargin

        if (isShowing) {
            update(x, y, popupWidth, popupHeight)
        } else {
            showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, x, y)
        }
    }

    private fun makeContentMeasureSpec(size: Int): Int {
        return if (size == ViewGroup.LayoutParams.WRAP_CONTENT) {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        }
    }
}
