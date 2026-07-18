package ai.cjym.agentclaw.ui.avatar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class VoiceWaveView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 4f * resources.displayMetrics.density }
    private var phase = 0f
    private var active = false
    private var listening = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_600L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedFraction; invalidate() }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) animator.start() else { animator.cancel(); phase = 0f; invalidate() }
    }

    fun setListening(value: Boolean) { listening = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colors = if (listening) intArrayOf(Color.rgb(52, 199, 89)) else intArrayOf(Color.rgb(52, 120, 246))
        val count = 7
        val gap = width / (count + 1f)
        repeat(count) { index ->
            val pulse = if (active) (0.25f + 0.75f * kotlin.math.abs(sin((phase * 6.28f) + index * 0.62f))) else 0.18f
            val h = height * (0.18f + pulse * 0.62f)
            paint.color = colors[index % colors.size]
            val x = gap * (index + 1)
            canvas.drawLine(x, height / 2f - h / 2f, x, height / 2f + h / 2f, paint)
        }
    }

    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
}
