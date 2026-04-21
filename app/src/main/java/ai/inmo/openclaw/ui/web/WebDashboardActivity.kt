package ai.inmo.openclaw.ui.web

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivityWebDashboardBinding
import ai.inmo.openclaw.di.AppGraph
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

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
        val gatewayState = AppGraph.gatewayManager.state.value
        val explicitUrl = intent.getStringExtra(EXTRA_URL)
        val dashboardUrl = explicitUrl ?: gatewayState.dashboardUrl ?: AppGraph.preferences.dashboardUrl
        if ((explicitUrl == null && !gatewayState.isRunning) || dashboardUrl.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.web_dashboard_not_ready), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUrl = dashboardUrl
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
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
        binding.webView.loadUrl(dashboardUrl)
    }

    override fun initEvent() = Unit

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "dashboard_url"
    }
}
