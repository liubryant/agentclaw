package ai.cjym.agentclaw.ui.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import ai.cjym.agentclaw.R

class CircularBorderImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val clipPath = Path()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    init { scaleType = ScaleType.CENTER_CROP }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = minOf(w, h) / 2f - borderPaint.strokeWidth / 2f
        clipPath.reset()
        clipPath.addCircle(w / 2f, h / 2f, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.clipPath(clipPath)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
        borderPaint.color = if (isSelected) 0xFF6750F5.toInt() else 0xFFD8DAE2.toInt()
        val radius = minOf(width, height) / 2f - borderPaint.strokeWidth / 2f
        canvas.drawCircle(width / 2f, height / 2f, radius, borderPaint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
