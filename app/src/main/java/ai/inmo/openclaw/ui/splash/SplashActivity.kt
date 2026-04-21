package ai.inmo.openclaw.ui.splash

import ai.inmo.core_common.ui.dialog.CommonMessageDialog
import ai.inmo.core_common.utils.DeviceInfo
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.ui.chat.ChatMarkdownProvider
import ai.inmo.openclaw.ui.shell.ShellActivity
import ai.inmo.openclaw.ui.startup.StartupActivity
import ai.inmo.openclaw.ui.widget.TermsDialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
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
