package ai.inmo.openclaw.ui.widget

import ai.inmo.openclaw.databinding.ViewGatewayControlsBinding
import ai.inmo.openclaw.domain.model.GatewayState
import ai.inmo.openclaw.domain.model.GatewayStatus
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout

class GatewayControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val binding = ViewGatewayControlsBinding.inflate(LayoutInflater.from(context), this, true)

    var onStartClick: (() -> Unit)? = null
    var onStopClick: (() -> Unit)? = null
    var onLogsClick: (() -> Unit)? = null
    var onOpenDashboardClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        binding.startButton.setOnClickListener { onStartClick?.invoke() }
        binding.stopButton.setOnClickListener { onStopClick?.invoke() }
        binding.logsButton.setOnClickListener { onLogsClick?.invoke() }
        binding.openDashboardButton.setOnClickListener { onOpenDashboardClick?.invoke() }
    }

    fun bind(state: GatewayState) {
        binding.statusValue.text = state.statusText
        binding.urlValue.text = state.dashboardUrl ?: "http://127.0.0.1:18789"
        binding.errorValue.text = state.errorMessage.orEmpty()
        binding.errorValue.alpha = if (state.errorMessage.isNullOrBlank()) 0f else 1f
        binding.startButton.isEnabled = state.isStopped
        binding.stopButton.isEnabled = state.isRunning || state.status == GatewayStatus.STARTING
        binding.openDashboardButton.isEnabled = state.isRunning && !state.dashboardUrl.isNullOrBlank()
    }
}
