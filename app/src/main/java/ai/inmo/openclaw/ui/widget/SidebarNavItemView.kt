package ai.inmo.openclaw.ui.widget

import ai.inmo.openclaw.R
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.use

class SidebarNavItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val textView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 50.dp
        isClickable = true
        isFocusable = true
        background = AppCompatResources.getDrawable(context, R.drawable.bg_sidebar_nav_item)
        setPaddingRelative(12.dp, 0, 12.dp, 0)

        LayoutInflater.from(context).inflate(R.layout.view_sidebar_nav_item, this, true)
        iconView = requireNotNull(findViewById<ImageView>(R.id.navIconView))
        textView = requireNotNull(findViewById<TextView>(R.id.navTextView))
        textView.typeface = Typeface.DEFAULT_BOLD

        context.obtainStyledAttributes(
            attrs,
            R.styleable.SidebarNavItemView,
            defStyleAttr,
            0
        ).use { typedArray ->
            if (typedArray.hasValue(R.styleable.SidebarNavItemView_navIcon)) {
                val iconResId = typedArray.getResourceId(R.styleable.SidebarNavItemView_navIcon, 0)
                if (iconResId != 0) {
                    setIcon(iconResId)
                }
            }
            typedArray.getText(R.styleable.SidebarNavItemView_navText)?.let { text ->
                setText(text)
            }
            if (typedArray.hasValue(R.styleable.SidebarNavItemView_navIconTint)) {
                iconView.imageTintList =
                    typedArray.getColorStateList(R.styleable.SidebarNavItemView_navIconTint)
            }
        }
    }

    fun setText(text: CharSequence) {
        textView.text = text
    }

    fun setText(textResId: Int) {
        textView.setText(textResId)
    }

    fun setIcon(drawable: Drawable?) {
        iconView.setImageDrawable(drawable)
    }

    fun setIcon(drawableResId: Int) {
        iconView.setImageResource(drawableResId)
    }

    fun setIconTint(colorStateList: ColorStateList?) {
        iconView.imageTintList = colorStateList
    }

    private val Int.dp: Int
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            toFloat(),
            resources.displayMetrics
        ).toInt()
}
