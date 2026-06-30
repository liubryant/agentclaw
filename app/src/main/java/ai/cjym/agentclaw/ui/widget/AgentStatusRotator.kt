package ai.cjym.agentclaw.ui.widget

import android.view.View
import android.widget.TextView
import androidx.annotation.ArrayRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Rotates short agent capability hints while waiting for the first assistant text. */
class AgentStatusRotator(
    private val container: View,
    private val label: TextView,
    private val scope: CoroutineScope
) {
    private var rotationJob: Job? = null
    private var activeStatusesRes: Int? = null
    private var animationGeneration = 0

    fun show(@ArrayRes statusesRes: Int) {
        if (activeStatusesRes == statusesRes && rotationJob?.isActive == true) return

        activeStatusesRes = statusesRes
        rotationJob?.cancel()
        val generation = ++animationGeneration
        val statuses = container.resources.getTextArray(statusesRes)
        if (statuses.isEmpty()) return

        label.animate().cancel()
        label.text = statuses.first()
        label.alpha = 1f
        label.translationY = 0f
        if (container.visibility != View.VISIBLE) {
            container.alpha = 0f
            container.translationY = dp(6f)
            container.visibility = View.VISIBLE
            container.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(CONTAINER_ENTER_DURATION_MS)
                .start()
        }

        rotationJob = scope.launch {
            var index = 0
            while (isActive) {
                delay(STATUS_INTERVAL_MS)
                index = (index + 1) % statuses.size
                animateLabel(statuses[index], generation)
            }
        }
    }

    fun hide() {
        if (activeStatusesRes == null && container.visibility == View.GONE) return
        activeStatusesRes = null
        rotationJob?.cancel()
        rotationJob = null
        animationGeneration++
        label.animate().cancel()
        container.animate().cancel()
        container.visibility = View.GONE
        container.alpha = 1f
        container.translationY = 0f
    }

    private fun animateLabel(nextStatus: CharSequence, generation: Int) {
        if (generation != animationGeneration || container.visibility != View.VISIBLE) return
        label.animate().cancel()
        label.animate()
            .alpha(0f)
            .translationY(-dp(4f))
            .setDuration(LABEL_EXIT_DURATION_MS)
            .withEndAction {
                if (generation != animationGeneration || container.visibility != View.VISIBLE) {
                    return@withEndAction
                }
                label.text = nextStatus
                label.translationY = dp(4f)
                label.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(LABEL_ENTER_DURATION_MS)
                    .withEndAction(null)
                    .start()
            }
            .start()
    }

    private fun dp(value: Float): Float = value * container.resources.displayMetrics.density

    private companion object {
        private const val STATUS_INTERVAL_MS = 4_000L
        private const val CONTAINER_ENTER_DURATION_MS = 220L
        private const val LABEL_EXIT_DURATION_MS = 140L
        private const val LABEL_ENTER_DURATION_MS = 200L
    }
}
