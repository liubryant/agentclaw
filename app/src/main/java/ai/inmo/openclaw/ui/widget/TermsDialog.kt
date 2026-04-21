package ai.inmo.openclaw.ui.widget

import ai.inmo.core_common.ui.dialog.BaseBindingDialog
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.DialogTermsBinding
import ai.inmo.openclaw.util.toSpannableString
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import kotlinx.coroutines.CompletableDeferred

class TermsDialog(
    context: Context
) : BaseBindingDialog<DialogTermsBinding>(
    context = context,
    inflate = DialogTermsBinding::inflate,
    canceledOnTouchOutside = false,
    widthPercentage = 0.7f,
    heightPercentage = 0.9f,
    gravity = Gravity.CENTER
) {
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
                USER_AGREEMENT_ANNOTATION -> openLink(USER_AGREEMENT_URL)
                PRIVACY_POLICY_ANNOTATION -> openLink(PRIVACY_POLICY_URL)
            }
        }
        binding.contentText.highlightColor = Color.TRANSPARENT

        binding.rejectButton.setOnClickListener {
            completeAndDismiss(false)
        }
        binding.acceptButton.setOnClickListener {
            completeAndDismiss(true)
        }
    }

    private fun openLink(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
        private const val USER_AGREEMENT_URL =
            "https://cjym.feishu.cn/docx/SyBsdljyQoQmJJxLYTzchVI6nZB"
        private const val PRIVACY_POLICY_URL =
            "https://cjym.feishu.cn/docx/KSUXdWlgcoKYbYxxtWLcsyXHnmh"
    }
}
