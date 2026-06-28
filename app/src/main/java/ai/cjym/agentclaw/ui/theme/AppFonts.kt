package ai.cjym.agentclaw.ui.theme

import ai.cjym.agentclaw.R
import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

object AppFonts {
    fun miSans(context: Context): Typeface {
        return ResourcesCompat.getFont(context, R.font.misans_normal) ?: Typeface.DEFAULT
    }
}
