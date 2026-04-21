package ai.inmo.openclaw.util

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun View.hideKeyboard(clearFocus: Boolean = false) {
    val windowToken = windowToken
    ViewCompat.getWindowInsetsController(this)?.hide(WindowInsetsCompat.Type.ime())
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(windowToken, 0)
    if (clearFocus) {
        clearFocus()
    }
}
