package ai.cjym.agentclaw.ui.widget

import ai.inmo.core_common.ui.dialog.BaseBindingDialog
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.constants.AppConstants
import ai.cjym.agentclaw.databinding.DialogTermsBinding
import ai.cjym.agentclaw.ui.legal.LegalWebActivity
import ai.cjym.agentclaw.util.toSpannableString
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred

class TermsDialog(
    context: Context
) : BaseBindingDialog<DialogTermsBinding>(
    context = context,
    inflate = DialogTermsBinding::inflate,
    canceledOnTouchOutside = false,
    widthPercentage = 0.9f,
    heightPercentage = -1f,
    gravity = Gravity.CENTER
) {

    private var isConsentChecked = false

    override fun onStart() {
        super.onStart()
        window?.attributes = window?.attributes?.apply {
            height = 1200
        }
    }
    private val result = CompletableDeferred<Boolean>()

    init {
        setupContent()
        setOnDismissListener {
            if (!result.isCompleted) {
                result.complete(false)
            }
        }
    }

    suspend fun showAwait(): Boolean {
        show()
        return result.await()
    }

    private fun setupContent() {
        binding.contentText.setText(R.string.terms_content)
        binding.contentText.toSpannableString(colorId = R.color.terms_link, isBold = false) { key ->
            when (key) {
                USER_AGREEMENT_ANNOTATION -> openLegalPage(
                    context.getString(R.string.terms_user_agreement),
                    AppConstants.USER_AGREEMENT_URL
                )
                PRIVACY_POLICY_ANNOTATION -> openLegalPage(
                    context.getString(R.string.terms_privacy_policy),
                    AppConstants.PRIVACY_POLICY_URL
                )
            }
        }
        binding.contentText.highlightColor = Color.TRANSPARENT
        updateConsentState()

        binding.consentRow.setOnClickListener {
            isConsentChecked = !isConsentChecked
            updateConsentState()
        }

        binding.rejectButton.setOnClickListener {
            completeAndDismiss(false)
        }
        binding.acceptButton.setOnClickListener {
            completeAndDismiss(true)
        }
    }

    private fun openLegalPage(title: String, url: String) {
        context.startActivity(LegalWebActivity.createIntent(context, title, url))
    }

    private fun updateConsentState() {
        binding.consentCheckIcon.isSelected = isConsentChecked
        binding.acceptButton.isEnabled = isConsentChecked
        binding.acceptButton.alpha = if (isConsentChecked) 1f else 0.4f
    }

    private fun completeAndDismiss(value: Boolean) {
        if (!result.isCompleted) {
            result.complete(value)
        }
        dismiss()
    }

    companion object {
        private const val USER_AGREEMENT_ANNOTATION = "user_agreement"
        private const val PRIVACY_POLICY_ANNOTATION = "privacy_policy"
    }
}
