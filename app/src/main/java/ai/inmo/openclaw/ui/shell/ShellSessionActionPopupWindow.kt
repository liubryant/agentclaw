package ai.inmo.openclaw.ui.shell

import ai.inmo.core_common.ui.pop.BaseBindingPopupWindow
import ai.inmo.openclaw.databinding.PopupShellSessionActionBinding
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

class ShellSessionActionPopupWindow(
    context: Context
) : BaseBindingPopupWindow<PopupShellSessionActionBinding>(
    ctx = context,
    w = ViewGroup.LayoutParams.WRAP_CONTENT,
    h = ViewGroup.LayoutParams.WRAP_CONTENT,
    canOutsideTouchables = true
) {

    override val xoff: Int = 0
    override val yoff: Int = 0

    var onDeleteClick: (() -> Unit)? = null

    override fun createBinding(inflater: LayoutInflater): PopupShellSessionActionBinding {
        return PopupShellSessionActionBinding.inflate(inflater)
    }

    override fun initView() {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = ctx.resources.displayMetrics.density * 10f
        binding.deleteActionView.setOnClickListener {
            dismiss()
            onDeleteClick?.invoke()
        }
    }

    override fun showDown(view: View) {
        onShow()
        binding.root.measure(
            makeContentMeasureSpec(width),
            makeContentMeasureSpec(height)
        )

        val popupWidth = binding.root.measuredWidth
        val xOffset = view.width - popupWidth
        val yOffset = (ctx.resources.displayMetrics.density * 6f).toInt()

        showAsDropDown(view, xOffset, yOffset, Gravity.START)
    }

    private fun makeContentMeasureSpec(size: Int): Int {
        return if (size == ViewGroup.LayoutParams.WRAP_CONTENT) {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        }
    }
}
