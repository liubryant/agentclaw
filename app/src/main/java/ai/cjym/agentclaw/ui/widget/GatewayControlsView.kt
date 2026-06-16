package ai.cjym.agentclaw.ui.widget

import ai.cjym.agentclaw.databinding.ViewGatewayControlsBinding
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
}
