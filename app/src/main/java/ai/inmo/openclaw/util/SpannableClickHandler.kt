package ai.inmo.openclaw.util

import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View

/**
 * 
 * Date: 2024/10/14 10:13
 * 
 */


/**
 * Creates a new ClickableSpan.
 * @property addUnderline Boolean Whether to underline the link or not.
 * @property callback Function<Unit>
 * @constructor
 */
class SpannableClickHandler(val addUnderline: Boolean = true, private val callback: () -> Unit) :
    ClickableSpan() {
    override fun onClick(widget: View) {
        callback()
    }

    override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        ds.isUnderlineText = addUnderline
    }
}