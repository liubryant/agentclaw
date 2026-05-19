package ai.inmo.openclaw.ui.legal

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.databinding.ActivityLegalWebBinding
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.WebViewClient

class LegalWebActivity : BaseBindingActivity<ActivityLegalWebBinding>(ActivityLegalWebBinding::inflate) {
    override fun initData() = Unit

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }
        binding.legalWebView.apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            loadUrl(intent.getStringExtra(EXTRA_URL).orEmpty())
        }
    }

    override fun initEvent() = Unit

    override fun onDestroy() {
        binding.legalWebView.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "extra_url"

        fun createIntent(context: Context, title: String, url: String): Intent {
            return Intent(context, LegalWebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
    }
}
