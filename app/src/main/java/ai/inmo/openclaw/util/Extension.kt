package ai.inmo.openclaw.util

import ai.inmo.openclaw.R
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.text.getSpans

/** * Create a text click event * @param callback (String) -> Unit * @return Unit */
inline fun TextView.toSpannableString(
    @ColorRes colorId: Int = R.color.brand_primary,
    isBold: Boolean = true,
    crossinline callback: (String) -> Unit
): Unit =
    SpannableString(text).run {
        val linkedHashMap = LinkedHashMap<String, Pair<Int, Int>>()
        getSpans<android.text.Annotation>(0, length).forEach { an ->
            linkedHashMap[an.key] = Pair(getSpanStart(an), getSpanEnd(an))
        }

        linkedHashMap.entries.forEach { kv ->
            setSpan(SpannableClickHandler(false) {
                callback.invoke(kv.key)
            }, kv.value.first, kv.value.second, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, colorId)),
                kv.value.first,
                kv.value.second,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (isBold) {
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    kv.value.first,
                    kv.value.second,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        movementMethod = LinkMovementMethod.getInstance()
        text = this
    }