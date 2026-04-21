package ai.inmo.core_common.ui.dialog

import ai.inmo.core_common.databinding.DialogCommonMessageBinding
import android.content.Context
import android.view.Gravity
import androidx.core.view.isVisible
import android.widget.LinearLayout

class CommonMessageDialog(
    context: Context,
    private val config: DialogConfig
) : BaseBindingDialog<DialogCommonMessageBinding>(
    context = context,
    inflate = DialogCommonMessageBinding::inflate,
    canceledOnTouchOutside = config.canceledOnTouchOutside,
    widthPercentage = WIDTH_PERCENTAGE,
    heightPercentage = HEIGHT_PERCENTAGE,
    gravity = Gravity.CENTER
) {

    init {
        bindContent()
        setOnDismissListener { config.onDismiss?.invoke() }
    }

    private fun bindContent() {
        binding.titleText.text = config.title
        binding.messageText.text = config.message ?: ""
        binding.messageText.isVisible = shouldShowMessage()

        val showNegativeButton = shouldShowNegativeButton()
        binding.negativeButton.isVisible = showNegativeButton
        binding.negativeButton.text = config.negativeText ?: ""
        binding.negativeButton.setOnClickListener {
            config.onNegative?.invoke()
            dismiss()
        }

        binding.positiveButton.text = config.positiveText
        binding.positiveButton.setOnClickListener {
            config.onPositive?.invoke()
            dismiss()
        }

        (binding.positiveButton.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (showNegativeButton) 0 else LinearLayout.LayoutParams.MATCH_PARENT
            weight = if (showNegativeButton) 1f else 0f
        }.also { binding.positiveButton.layoutParams = it }
    }

    private fun shouldShowMessage(): Boolean {
        return config.type == DialogType.MESSAGE_CONFIRM && !config.message.isNullOrBlank()
    }

    private fun shouldShowNegativeButton(): Boolean {
        return when (config.type) {
            DialogType.SINGLE_ACTION -> false
            DialogType.TITLE_CONFIRM,
            DialogType.MESSAGE_CONFIRM -> !config.negativeText.isNullOrBlank()
        }
    }

    data class DialogConfig(
        val type: DialogType,
        val title: CharSequence,
        val positiveText: CharSequence,
        val negativeText: CharSequence? = null,
        val message: CharSequence? = null,
        val canceledOnTouchOutside: Boolean = false,
        val onPositive: (() -> Unit)? = null,
        val onNegative: (() -> Unit)? = null,
        val onDismiss: (() -> Unit)? = null
    )

    enum class DialogType {
        SINGLE_ACTION,
        TITLE_CONFIRM,
        MESSAGE_CONFIRM
    }

    companion object {
        private const val WIDTH_PERCENTAGE = 0.4f
        private const val HEIGHT_PERCENTAGE = 0.3f

        fun createSingleAction(
            context: Context,
            title: CharSequence,
            positiveText: CharSequence,
            canceledOnTouchOutside: Boolean = false,
            onPositive: (() -> Unit)? = null,
            onDismiss: (() -> Unit)? = null
        ): CommonMessageDialog {
            return CommonMessageDialog(
                context = context,
                config = DialogConfig(
                    type = DialogType.SINGLE_ACTION,
                    title = title,
                    positiveText = positiveText,
                    canceledOnTouchOutside = canceledOnTouchOutside,
                    onPositive = onPositive,
                    onDismiss = onDismiss
                )
            )
        }

        fun createConfirm(
            context: Context,
            title: CharSequence,
            positiveText: CharSequence,
            negativeText: CharSequence,
            canceledOnTouchOutside: Boolean = false,
            onPositive: (() -> Unit)? = null,
            onNegative: (() -> Unit)? = null,
            onDismiss: (() -> Unit)? = null
        ): CommonMessageDialog {
            return CommonMessageDialog(
                context = context,
                config = DialogConfig(
                    type = DialogType.TITLE_CONFIRM,
                    title = title,
                    positiveText = positiveText,
                    negativeText = negativeText,
                    canceledOnTouchOutside = canceledOnTouchOutside,
                    onPositive = onPositive,
                    onNegative = onNegative,
                    onDismiss = onDismiss
                )
            )
        }

        fun createMessageConfirm(
            context: Context,
            title: CharSequence,
            message: CharSequence,
            positiveText: CharSequence,
            negativeText: CharSequence,
            canceledOnTouchOutside: Boolean = false,
            onPositive: (() -> Unit)? = null,
            onNegative: (() -> Unit)? = null,
            onDismiss: (() -> Unit)? = null
        ): CommonMessageDialog {
            return CommonMessageDialog(
                context = context,
                config = DialogConfig(
                    type = DialogType.MESSAGE_CONFIRM,
                    title = title,
                    message = message,
                    positiveText = positiveText,
                    negativeText = negativeText,
                    canceledOnTouchOutside = canceledOnTouchOutside,
                    onPositive = onPositive,
                    onNegative = onNegative,
                    onDismiss = onDismiss
                )
            )
        }
    }
}
