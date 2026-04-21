package ai.inmo.openclaw.ui.widget

import ai.inmo.openclaw.databinding.ViewStatusCardBinding
import ai.inmo.openclaw.ui.dashboard.StatusCardUiState
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout

class StatusCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val binding = ViewStatusCardBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        orientation = VERTICAL
    }

    fun bind(state: StatusCardUiState) {
        binding.titleValue.text = state.title
        binding.statusValue.text = state.status
        binding.subtitleValue.text = state.subtitle.orEmpty()
        binding.supportingValue.text = state.supporting.orEmpty()
        binding.errorValue.text = state.error.orEmpty()
        binding.subtitleValue.visibility = if (state.subtitle.isNullOrBlank()) GONE else VISIBLE
        binding.supportingValue.visibility = if (state.supporting.isNullOrBlank()) GONE else VISIBLE
        binding.errorValue.visibility = if (state.error.isNullOrBlank()) GONE else VISIBLE
    }
}
