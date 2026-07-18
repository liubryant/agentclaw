package ai.cjym.agentclaw.ui.splash

import ai.inmo.core_common.ui.dialog.CommonMessageDialog
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.ui.chat.ChatMarkdownProvider
import ai.cjym.agentclaw.ui.shell.ChatEntryMode
import ai.cjym.agentclaw.ui.shell.ShellActivity
import ai.cjym.agentclaw.ui.shell.ShellDestination
import ai.cjym.agentclaw.ui.startup.StartupActivity
import ai.cjym.agentclaw.ui.widget.TermsDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        private const val ACTION_INSTANT_CHAT =
            "ai.cjym.agentclaw.action.INSTANT_CHAT"
        private const val ACTION_AI_IMAGE_GENERATION =
            "ai.cjym.agentclaw.action.AI_IMAGE_GENERATION"
    }

    private val viewModel = SplashViewModel()
    private lateinit var splashContainer: FrameLayout

    // 注意事项⑦：onStop 时标记，onResume 时检查是否直接跳主页
    private var forceGoMain = false
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 内容延伸到系统栏背后（edge-to-edge）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideStatusBar()
        // 刘海屏/打孔屏：允许内容延伸到凹口区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
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
        hideStatusBar()
        Log.d(TAG, "onResume forceGoMain=$forceGoMain hasNavigated=$hasNavigated")
        if (forceGoMain) {
            Log.d(TAG, "onResume: forceGoMain=true, navigating immediately")
            navigateToDestination()
        }
    }

    private fun hideStatusBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
        val (screenW, screenH) = realScreenSize()
        val adSlot = AdSlot.Builder()
            .setCodeId("104086778")
            // 告知 SDK 预期展示尺寸（全屏物理像素），确保选到匹配素材并正确缩放
            .setImageAcceptedSize(screenW, screenH)
            .build()

        Log.d(TAG, "loadSplashAd start codeId=104086778 size=${screenW}x${screenH}")
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
        // 用真实物理屏幕像素强制覆盖容器尺寸，绕过任何 insets 导致的缩减
        val (w, h) = realScreenSize()
        splashContainer.layoutParams = FrameLayout.LayoutParams(w, h)
        Log.d(TAG, "showSplashAd: container forced to ${w}x${h}, setting listener and showing")
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

    // ─────────────────────── 工具 ───────────────────────

    /** 返回真实物理屏幕尺寸（含状态栏、导航栏、刘海区域），不受 window insets 影响 */
    private fun realScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    // ─────────────────────── 跳转主页 ───────────────────────

    private fun navigateToDestination() {
        Log.d(TAG, "navigateToDestination called hasNavigated=$hasNavigated, caller=${Thread.currentThread().stackTrace[2]}")
        if (hasNavigated || isFinishing || isDestroyed) return
        hasNavigated = true
        Log.d(TAG, "navigateToDestination: actually navigating")
        val shortcutIntent = if (AppGraph.preferences.isFirstRun) null else shortcutDestinationIntent()
        if (shortcutIntent != null) {
            startActivity(shortcutIntent)
            finish()
            return
        }
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

    private fun shortcutDestinationIntent(): Intent? {
        return when (intent?.action) {
            ACTION_INSTANT_CHAT -> Intent(this, ShellActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(
                    ShellActivity.EXTRA_INITIAL_DESTINATION,
                    ShellDestination.AVATAR.name
                )
            }
            ACTION_AI_IMAGE_GENERATION -> Intent(this, ShellActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(
                    ShellActivity.EXTRA_INITIAL_CHAT_ENTRY_MODE,
                    ChatEntryMode.IMAGE.name
                )
            }
            else -> null
        }
    }
}
