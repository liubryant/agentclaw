package ai.cjym.agentclaw.ui.splash

import ai.inmo.core_common.ui.dialog.CommonMessageDialog
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.ui.chat.ChatMarkdownProvider
import ai.cjym.agentclaw.ui.shell.ShellActivity
import ai.cjym.agentclaw.ui.startup.StartupActivity
import ai.cjym.agentclaw.ui.widget.TermsDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.CSJAdError
import com.bytedance.sdk.openadsdk.CSJSplashAd
import com.bytedance.sdk.openadsdk.TTAdConfig
import com.bytedance.sdk.openadsdk.TTAdNative
import com.bytedance.sdk.openadsdk.TTAdSdk
import com.bytedance.sdk.openadsdk.TTCustomController
import com.bytedance.sdk.openadsdk.mediation.init.MediationPrivacyConfig
import com.umeng.commonsdk.UMConfigure
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "agentad"
    }

    private val viewModel = SplashViewModel()
    private lateinit var splashContainer: FrameLayout

    // 注意事项⑦：onStop 时标记，onResume 时检查是否直接跳主页
    private var forceGoMain = false
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        configureImmersiveFullscreen()
        setContentView(R.layout.activity_splash_ad)
        splashContainer = findViewById<FrameLayout>(R.id.splashAdContainer)!!
        lifecycle.addObserver(viewModel)

        lifecycleScope.launch {
            ChatMarkdownProvider.get(this@SplashActivity)

            if (!AppGraph.preferences.termsAccepted) {
                val accepted = TermsDialog(this@SplashActivity).showAwait()
                if (!accepted) {
                    finish()
                    return@launch
                }
                AppGraph.preferences.termsAccepted = true
                initSdksAndLoadAd()
            } else {
                initSdksAndLoadAd()
            }
        }
    }

    // 注意事项⑦：onResume 判断是否强制跳主页
    override fun onResume() {
        super.onResume()
        configureImmersiveFullscreen()
        Log.d(TAG, "onResume forceGoMain=$forceGoMain hasNavigated=$hasNavigated")
        if (forceGoMain) {
            Log.d(TAG, "onResume: forceGoMain=true, navigating immediately")
            navigateToDestination()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureImmersiveFullscreen()
        }
    }

    // 注意事项⑦：onStop 时打标记
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop forceGoMain set to true")
        forceGoMain = true
    }

    // ─────────────────────── SDK 初始化 ───────────────────────

    private fun initSdksAndLoadAd() {
        initUmengSdk()
        initGroMoreSdk() // 注意事项②：init 成功后才加载广告
    }

    private fun configureImmersiveFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun initUmengSdk() {
        UMConfigure.init(
            this,
            "6a1010366f259537c7ad0122",
            "agentclaw",
            UMConfigure.DEVICE_TYPE_PHONE,
            null
        )
    }

    // 注意事项⑧：TTAdSdk.init 必须在主线程调用
    private fun initGroMoreSdk() {
        Log.d(TAG, "initGroMoreSdk start, thread=${Thread.currentThread().name}")
        TTAdSdk.init(this, buildGroMoreConfig())
        TTAdSdk.start(object : TTAdSdk.Callback {
            override fun success() {
                Log.d(TAG, "GroMore init success, thread=${Thread.currentThread().name}")
                // 注意事项⑤：success 在子线程，UI 操作切主线程
                runOnUiThread { loadSplashAd() }
            }

            override fun fail(code: Int, msg: String?) {
                Log.e(TAG, "GroMore init fail code=$code msg=$msg")
                // 初始化失败，直接跳主页
                runOnUiThread { navigateToDestination() }
            }
        })
    }

    private fun buildGroMoreConfig(): TTAdConfig {
        return TTAdConfig.Builder()
            .appId("5829977")
            .appName("AgentClaw")
            .useMediation(true)
            .debug(false)
            .themeStatus(0)
            .supportMultiProcess(false)
            .customController(buildPrivacyController())
            .build()
    }

    private fun buildPrivacyController(): TTCustomController {
        return object : TTCustomController() {
            override fun isCanUseLocation(): Boolean = true
            override fun isCanUsePhoneState(): Boolean = true
            override fun isCanUseWifiState(): Boolean = true
            override fun isCanUseWriteExternal(): Boolean = true
            override fun isCanUseAndroidId(): Boolean = true

            override fun getMediationPrivacyConfig(): MediationPrivacyConfig {
                return object : MediationPrivacyConfig() {
                    override fun isLimitPersonalAds(): Boolean = false
                    override fun isProgrammaticRecommend(): Boolean = true
                }
            }
        }
    }

    // ─────────────────────── 广告加载 ───────────────────────

    private fun loadSplashAd() {
        val adNativeLoader = TTAdSdk.getAdManager().createAdNative(this)
        val adSlot = AdSlot.Builder()
            .setCodeId("104086778")
            .build()

        Log.d(TAG, "loadSplashAd start codeId=104086778")
        adNativeLoader.loadSplashAd(adSlot, object : TTAdNative.CSJSplashAdListener {
            override fun onSplashLoadSuccess(p0: CSJSplashAd?) {
                Log.d(TAG, "onSplashLoadSuccess, waiting render...")
            }

            override fun onSplashLoadFail(error: CSJAdError?) {
                Log.e(TAG, "onSplashLoadFail code=${error?.code} msg=${error?.msg}")
                runOnUiThread { navigateToDestination() }
            }

            override fun onSplashRenderSuccess(ad: CSJSplashAd?) {
                Log.d(TAG, "onSplashRenderSuccess, showing ad")
                runOnUiThread { showSplashAd(ad) }
            }

            override fun onSplashRenderFail(ad: CSJSplashAd?, error: CSJAdError?) {
                Log.e(TAG, "onSplashRenderFail code=${error?.code} msg=${error?.msg}")
                runOnUiThread { navigateToDestination() }
            }
        }, 3500)
    }

    // ─────────────────────── 广告展示 ───────────────────────

    private fun showSplashAd(ad: CSJSplashAd?) {
        if (ad == null) {
            Log.e(TAG, "showSplashAd: ad is null, navigate directly")
            navigateToDestination()
            return
        }
        Log.d(TAG, "showSplashAd: setting listener and showing")
        ad.setSplashAdListener(object : CSJSplashAd.SplashAdListener {
            override fun onSplashAdShow(ad: CSJSplashAd?) {
                Log.d(TAG, "onSplashAdShow")
            }

            override fun onSplashAdClick(ad: CSJSplashAd?) {
                Log.d(TAG, "onSplashAdClick")
            }

            override fun onSplashAdClose(ad: CSJSplashAd?, closeType: Int) {
                Log.d(TAG, "onSplashAdClose closeType=$closeType")
                navigateToDestination()
            }
        })
        ad.showSplashView(splashContainer)
    }

    // ─────────────────────── 跳转主页 ───────────────────────

    private fun navigateToDestination() {
        Log.d(TAG, "navigateToDestination called hasNavigated=$hasNavigated, caller=${Thread.currentThread().stackTrace[2]}")
        if (hasNavigated || isFinishing || isDestroyed) return
        hasNavigated = true
        Log.d(TAG, "navigateToDestination: actually navigating")
        viewModel.resolveDestination { destination ->
            runOnUiThread {
                val intent = when (destination) {
                    SplashViewModel.Destination.Startup -> Intent(this, StartupActivity::class.java)
                    SplashViewModel.Destination.Shell -> Intent(this, ShellActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
