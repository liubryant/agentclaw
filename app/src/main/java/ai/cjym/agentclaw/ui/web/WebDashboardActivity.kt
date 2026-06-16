package ai.cjym.agentclaw.ui.web

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.databinding.ActivityWebDashboardBinding
import ai.cjym.agentclaw.di.AppGraph
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class WebDashboardActivity : BaseBindingActivity<ActivityWebDashboardBinding>(ActivityWebDashboardBinding::inflate) {
    private var currentUrl: String? = null

    override fun initData() = Unit

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }
        binding.refreshButton.setOnClickListener {
            binding.errorGroup.visibility = android.view.View.GONE
            binding.progressBar.visibility = android.view.View.VISIBLE
            binding.webView.reload()
        }
        binding.retryButton.setOnClickListener {
            binding.errorGroup.visibility = android.view.View.GONE
            binding.progressBar.visibility = android.view.View.VISIBLE
            binding.webView.reload()
        }

        val url = intent.getStringExtra(EXTRA_URL)
            ?: AppGraph.preferences.dashboardUrl
            ?: AppGraph.preferences.agentclawBaseUrl
        currentUrl = url

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.allowContentAccess = true
        binding.webView.settings.allowFileAccess = true
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.progressBar.visibility = android.view.View.VISIBLE
                binding.errorGroup.visibility = android.view.View.GONE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = android.view.View.GONE
                binding.errorGroup.visibility = android.view.View.GONE
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                binding.progressBar.visibility = android.view.View.GONE
                binding.errorMessage.text = getString(R.string.web_dashboard_load_error, error?.description ?: getString(R.string.web_dashboard_unknown_error))
                binding.errorGroup.visibility = android.view.View.VISIBLE
            }
        }
        val htmlContent = intent.getStringExtra(EXTRA_HTML_CONTENT)
        if (!htmlContent.isNullOrBlank()) {
            binding.webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        } else {
            binding.webView.loadUrl(url)
        }
    }

    override fun initEvent() = Unit

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "dashboard_url"
        const val EXTRA_HTML_CONTENT = "html_content"
    }
}
