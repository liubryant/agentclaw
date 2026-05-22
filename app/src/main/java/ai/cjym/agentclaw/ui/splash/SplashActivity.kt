package ai.cjym.agentclaw.ui.splash

import ai.inmo.core_common.ui.dialog.CommonMessageDialog
import ai.inmo.core_common.utils.DeviceInfo
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.ui.chat.ChatMarkdownProvider
import ai.cjym.agentclaw.ui.shell.ShellActivity
import ai.cjym.agentclaw.ui.startup.StartupActivity
import ai.cjym.agentclaw.ui.widget.TermsDialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.umeng.commonsdk.UMConfigure
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {
    private val viewModel = SplashViewModel()
    private var snEmptyDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(viewModel)

        lifecycleScope.launch {
//            if (DeviceInfo.sn.isBlank()) {
//                showSnEmptyDialog()
//                return@launch
//            }

            ChatMarkdownProvider.get(this@SplashActivity)

            if (!AppGraph.preferences.termsAccepted) {
                val accepted = TermsDialog(this@SplashActivity).showAwait()
                if (!accepted) {
                    finish()
                    return@launch
                }
                AppGraph.preferences.termsAccepted = true
                // 用户首次同意隐私政策，正式初始化友盟 SDK 开始采数
                initUmengSdk()
            } else {
                // 老用户已同意过，直接初始化
                initUmengSdk()
            }

            resolveDestination()
        }
    }

    private fun showSnEmptyDialog() {
        if (snEmptyDialogShown || isFinishing || isDestroyed) return
        snEmptyDialogShown = true
        CommonMessageDialog.createSingleAction(
            context = this,
            title = "请升级到最新固件再进行使用",
            positiveText = "确定",
            canceledOnTouchOutside = false,
            onPositive = {
                finish()
            }
        ).show()
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

    private fun resolveDestination() {
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
