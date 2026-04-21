package ai.inmo.openclaw.ui.placeholder

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.databinding.ActivityFeaturePlaceholderBinding

class FeaturePlaceholderActivity :
    BaseBindingActivity<ActivityFeaturePlaceholderBinding>(ActivityFeaturePlaceholderBinding::inflate) {

    override fun initData() = Unit

    override fun initView() {
        binding.titleView.text = intent.getStringExtra(EXTRA_TITLE) ?: "Feature"
        binding.messageView.text =
            intent.getStringExtra(EXTRA_MESSAGE) ?: "This screen is planned but not implemented yet."
        binding.backButton.setOnClickListener { finish() }
    }

    override fun initEvent() = Unit

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
    }
}
