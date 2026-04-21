package ai.inmo.openclaw.ui.startup

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivityStartupBinding
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.ui.shell.ShellActivity
import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StartupActivity :
    BaseBindingActivity<ActivityStartupBinding>(ActivityStartupBinding::inflate) {
    private val viewModel = StartupViewModel()

    override fun initData() {
        viewModel.addObserver()
    }

    override fun initView() {
        binding.startupVideoView.setVideoResource(R.raw.claw)
        // 走过一次流程后，就不需要显示了
        binding.subtitleView.isVisible = !AppGraph.preferences.setupComplete

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.progressBar.progress = (state.progress * 100).toInt()
                binding.errorContainer.visibility = if (state.errorMessage != null) View.VISIBLE else View.GONE
                binding.errorView.text = state.errorMessage.orEmpty()
                binding.retryButton.isEnabled = !state.isRunning
            }
        }

        lifecycleScope.launch {
            delay(350)
            viewModel.runStartup {
                runOnUiThread {
                    openShell()
                }
            }
        }
    }

    override fun initEvent() {
        binding.retryButton.setOnClickListener {
            viewModel.runStartup {
                runOnUiThread {
                    openShell()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        binding.startupVideoView.play()
    }

    override fun onStop() {
        binding.startupVideoView.release()
        super.onStop()
    }

    private fun openShell() {
        startActivity(
            Intent(this, ShellActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}
