package ai.cjym.agentclaw.ui.avatar

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Spanned
import android.text.style.LineBackgroundSpan

class RoundedHighlightSpan(
    private val color: Int,
    private val radius: Float,
    private val horizontalPadding: Float
) : LineBackgroundSpan {
    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int
    ) {
        val spanned = text as? Spanned ?: return
        val spanStart = spanned.getSpanStart(this).coerceAtLeast(start)
        val spanEnd = spanned.getSpanEnd(this).coerceAtMost(end)
        if (spanStart >= spanEnd) return
        val startX = left + paint.measureText(text, start, spanStart)
        val endX = left + paint.measureText(text, start, spanEnd)
        val oldColor = paint.color
        paint.color = color
        canvas.drawRoundRect(
            RectF(startX - horizontalPadding, top + 1f, endX + horizontalPadding, bottom - 1f),
            radius,
            radius,
            paint
        )
        paint.color = oldColor
    }
}
