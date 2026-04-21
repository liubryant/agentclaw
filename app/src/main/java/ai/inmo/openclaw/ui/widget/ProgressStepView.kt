package ai.inmo.openclaw.ui.widget

import ai.inmo.openclaw.databinding.ViewProgressStepBinding
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout

class ProgressStepView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val binding = ViewProgressStepBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
    }

    fun setLabel(label: String) {
        binding.labelView.text = label
    }

    fun setState(active: Boolean, complete: Boolean) {
        binding.indicatorView.isSelected = active || complete
        binding.statusView.text = when {
            complete -> "Done"
            active -> "Running"
            else -> "Pending"
        }
    }
}
