package ai.cjym.agentclaw.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ViewFlipper
import kotlin.math.abs

class HorizontalSwipeViewFlipper @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewFlipper(context, attrs) {

    var onSwipeToPage: ((Int) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var intercepting = false
    private val touchSlop = resources.displayMetrics.density * 18f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                intercepting = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.15f) {
                    intercepting = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> return intercepting
            MotionEvent.ACTION_UP -> {
                if (!intercepting) return super.onTouchEvent(event)
                val dx = event.x - downX
                val target = when {
                    dx < -touchSlop && displayedChild < childCount - 1 -> displayedChild + 1
                    dx > touchSlop && displayedChild > 0 -> displayedChild - 1
                    else -> displayedChild
                }
                onSwipeToPage?.invoke(target)
                intercepting = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                intercepting = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
