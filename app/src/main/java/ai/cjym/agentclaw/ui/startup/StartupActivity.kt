package ai.cjym.agentclaw.ui.startup

import ai.cjym.agentclaw.R
import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.cjym.agentclaw.databinding.ActivityStartupBinding
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.ui.shell.ShellActivity
import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class StartupActivity :
    BaseBindingActivity<ActivityStartupBinding>(ActivityStartupBinding::inflate) {
    private val viewModel = StartupViewModel()

    override fun initData() {
        viewModel.addObserver()
    }

    override fun initView() {
        // 走过一次流程后，就不需要显示了
        binding.subtitleView.isVisible = !AppGraph.preferences.setupComplete
        startTipsCarousel()

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

    private fun startTipsCarousel() {
        val tips = resources.getStringArray(R.array.startup_tips)
        if (tips.isEmpty()) {
            binding.startupTipsView.visibility = View.GONE
            return
        }
        binding.startupTipsView.text = formatTip(tips.random())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(TIP_INTERVAL_MS)
                    binding.startupTipsView.text = formatTip(tips.random(Random.Default))
                }
            }
        }
    }

    private fun formatTip(tip: String): String = "tips：$tip"

    override fun initEvent() {
        binding.retryButton.setOnClickListener {
            viewModel.runStartup {
                runOnUiThread {
                    openShell()
                }
            }
        }
    }

    private fun openShell() {
        startActivity(
            Intent(this, ShellActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    companion object {
        private const val TIP_INTERVAL_MS = 3_000L
    }
}
