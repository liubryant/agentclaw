package ai.cjym.agentclaw.ui.account

import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.di.AppGraph
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper

class DeleteAccountDialogController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDeleted: () -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var codeCountdownRunnable: Runnable? = null

    fun show() {
        val phone = AppGraph.preferences.userPhone.orEmpty()
        if (!AppGraph.preferences.isLoggedIn || phone.isBlank()) {
            Toast.makeText(context, context.getString(R.string.setting_delete_account_login_required), Toast.LENGTH_SHORT).show()
            return
        }

        codeCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        codeCountdownRunnable = null
        dialog?.dismiss()

        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = rounded("#FFFFFF", 20)
        }

        contentView.addView(TextView(context).apply {
            text = context.getString(R.string.setting_delete_account)
            setTextColor(Color.parseColor("#15151A"))
            textSize = 20f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, matchWrap())

        contentView.addView(TextView(context).apply {
            text = context.getString(R.string.setting_delete_account_subtitle, phone)
            setTextColor(Color.parseColor("#62616B"))
            textSize = 13f
            gravity = Gravity.CENTER
        }, matchWrap(top = 8))

        contentView.addView(TextView(context).apply {
            text = context.getString(R.string.setting_delete_account_warning)
            setTextColor(Color.parseColor("#7A4B16"))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded("#FFF7ED", 12, strokeColor = "#1AF59E0B")
        }, matchWrap(top = 16))

        val codeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contentView.addView(codeRow, matchWrap(top = 16))

        val codeInput = EditText(context).apply {
            hint = context.getString(R.string.login_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 15f
            setSingleLine(true)
            setTextColor(Color.parseColor("#17171D"))
            setHintTextColor(Color.parseColor("#A6A6AF"))
            background = rounded("#F7F7FA", 14, strokeColor = "#12000000")
            setPadding(dp(14), 0, dp(14), 0)
        }
        codeRow.addView(codeInput, LinearLayout.LayoutParams(0, dp(48), 1f))

        val codeButton = TextView(context).apply {
            text = context.getString(R.string.login_get_code)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#15151A"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded("#FFFFFF", 14, strokeColor = "#22000000")
            isClickable = true
            isFocusable = true
        }
        codeRow.addView(codeButton, LinearLayout.LayoutParams(dp(118), dp(48)).apply {
            marginStart = dp(10)
        })

        val errorView = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#E5484D"))
            textSize = 12f
        }
        contentView.addView(errorView, matchWrap(top = 8))

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        contentView.addView(actionRow, matchWrap(top = 18))

        val cancelButton = TextView(context).apply {
            text = context.getString(R.string.setting_cancel)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#4B4B55"))
            background = rounded("#F1F1F4", 16)
            isClickable = true
            isFocusable = true
        }
        actionRow.addView(cancelButton, LinearLayout.LayoutParams(0, dp(48), 1f))

        val deleteButton = TextView(context).apply {
            text = context.getString(R.string.setting_delete_account_confirm_button)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded("#E5484D", 16)
            isClickable = true
            isFocusable = true
        }
        actionRow.addView(deleteButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(10)
        })

        fun setError(message: String?) {
            errorView.text = message.orEmpty()
            errorView.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val newDialog = AlertDialog.Builder(context)
            .setView(contentView)
            .create()

        dialog = newDialog
        newDialog.setOnDismissListener {
            if (dialog === newDialog) dialog = null
            codeCountdownRunnable?.let { runnable -> mainHandler.removeCallbacks(runnable) }
            codeCountdownRunnable = null
        }
        newDialog.setOnShowListener {
            newDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            newDialog.window?.setDimAmount(0.42f)
            cancelButton.setOnClickListener { newDialog.dismiss() }
            codeButton.setOnClickListener {
                requestDeleteAccountCode(phone, codeButton, ::setError)
            }
            deleteButton.setOnClickListener {
                val code = codeInput.text?.toString()?.trim().orEmpty()
                if (!isLoginCodeValid(code)) {
                    setError(context.getString(R.string.login_code_hint))
                    return@setOnClickListener
                }
                confirmDeleteAccount(phone, code, newDialog, deleteButton, ::setError)
            }
        }
        newDialog.show()
    }

    fun release() {
        dialog?.dismiss()
        dialog = null
        codeCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        codeCountdownRunnable = null
    }

    private fun requestDeleteAccountCode(
        phone: String,
        codeButton: TextView,
        setError: (String?) -> Unit
    ) {
        setError(null)
        setCodeButtonEnabled(codeButton, false)
        codeButton.text = context.getString(R.string.login_sending_code)
        scope.launch {
            val result = AppGraph.authService.sendSmsCode(phone)
            result.onSuccess {
                startDeleteCodeCountdown(codeButton)
            }.onFailure { e ->
                setCodeButtonEnabled(codeButton, true)
                codeButton.text = context.getString(R.string.login_get_code)
                setError(e.message ?: context.getString(R.string.login_code_failed))
            }
        }
    }

    private fun startDeleteCodeCountdown(codeButton: TextView) {
        codeCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        var remaining = 120
        val runnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    setCodeButtonEnabled(codeButton, false)
                    codeButton.text = context.getString(R.string.login_code_countdown, remaining)
                    remaining -= 1
                    mainHandler.postDelayed(this, 1_000L)
                } else {
                    setCodeButtonEnabled(codeButton, true)
                    codeButton.text = context.getString(R.string.login_get_code)
                    if (codeCountdownRunnable === this) codeCountdownRunnable = null
                }
            }
        }
        codeCountdownRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun confirmDeleteAccount(
        phone: String,
        code: String,
        parentDialog: AlertDialog,
        deleteButton: TextView,
        setError: (String?) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.setting_delete_account_confirm_title)
            .setMessage(R.string.setting_delete_account_confirm_message)
            .setNegativeButton(R.string.setting_cancel, null)
            .setPositiveButton(R.string.setting_delete_account_confirm_positive) { _, _ ->
                executeDeleteAccount(phone, code, parentDialog, deleteButton, setError)
            }
            .show()
    }

    private fun executeDeleteAccount(
        phone: String,
        code: String,
        currentDialog: AlertDialog,
        deleteButton: TextView,
        setError: (String?) -> Unit
    ) {
        setError(null)
        deleteButton.isEnabled = false
        deleteButton.alpha = 0.72f
        deleteButton.text = context.getString(R.string.setting_delete_account_deleting)
        scope.launch {
            val result = AppGraph.authService.deleteAccount(phone, code)
            result.onSuccess {
                AppGraph.preferences.isLoggedIn = false
                AppGraph.preferences.userPhone = null
                AppGraph.preferences.userAccessToken = null
                Toast.makeText(context, context.getString(R.string.setting_delete_account_success), Toast.LENGTH_SHORT).show()
                currentDialog.dismiss()
                onDeleted()
            }.onFailure { e ->
                deleteButton.isEnabled = true
                deleteButton.alpha = 1f
                deleteButton.text = context.getString(R.string.setting_delete_account_confirm_button)
                setError(e.message ?: context.getString(R.string.setting_delete_account_failed))
            }
        }
    }

    private fun setCodeButtonEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = 1f
        button.background = if (enabled) {
            rounded("#FFFFFF", 14, strokeColor = "#22000000")
        } else {
            rounded("#F4F4F6", 14, strokeColor = "#16000000")
        }
        button.setTextColor(if (enabled) Color.parseColor("#15151A") else Color.parseColor("#7A7A84"))
    }

    private fun isLoginCodeValid(code: String): Boolean {
        return code.length == 6 && code.all { it.isDigit() }
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }
    }

    private fun rounded(
        color: String,
        radius: Int,
        strokeWidth: Int = 1,
        strokeColor: String? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
            strokeColor?.let { setStroke(dp(strokeWidth), Color.parseColor(it)) }
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
