package ai.cjym.agentclaw.util

import android.view.View
import androidx.annotation.IdRes

/** Returns a required child view or fails when the layout contract is broken. */
fun <T : View> View.requireView(@IdRes id: Int): T =
    findViewById<T>(id) ?: error("Required view with id $id was not found")
