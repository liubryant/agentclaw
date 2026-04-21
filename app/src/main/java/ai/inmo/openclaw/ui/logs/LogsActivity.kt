package ai.inmo.openclaw.ui.logs

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivityLogsBinding
import ai.inmo.openclaw.util.ScreenshotUtils
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LogsActivity : BaseBindingActivity<ActivityLogsBinding>(ActivityLogsBinding::inflate) {
    private val viewModel = LogsViewModel()
    private var latestLogs: List<String> = emptyList()
    private var filter: String = ""

    override fun initData() {
        viewModel.addObserver()
    }

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }
        binding.copyButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("gateway-logs", latestLogs.joinToString("\n")))
            Toast.makeText(this, getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
        }
        binding.autoScrollButton.setOnClickListener { viewModel.toggleAutoScroll() }
        binding.screenshotButton.setOnClickListener {
            val saved = ScreenshotUtils.captureView(this, binding.logsScroll, "gateway_logs")
            Toast.makeText(this, if (saved) getString(R.string.terminal_screenshot_saved) else getString(R.string.logs_screenshot_failed), Toast.LENGTH_SHORT).show()
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString().orEmpty()
                renderLogs(binding.autoScrollButton.tag as? Boolean ?: true)
            }
        })
        lifecycleScope.launch {
            viewModel.autoScroll.collectLatest { autoScroll ->
                binding.autoScrollButton.tag = autoScroll
                binding.autoScrollButton.text = if (autoScroll) getString(R.string.logs_auto_scroll_on) else getString(R.string.logs_auto_scroll_off)
                renderLogs(autoScroll)
            }
        }
        lifecycleScope.launch {
            viewModel.gatewayState.collectLatest { state ->
                latestLogs = state.logs
                renderLogs(binding.autoScrollButton.tag as? Boolean ?: true)
            }
        }
    }

    override fun initEvent() = Unit

    private fun renderLogs(autoScroll: Boolean) {
        val filtered = if (filter.isBlank()) latestLogs else latestLogs.filter { it.contains(filter, true) }
        binding.emptyView.visibility = if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.logsView.text = buildColoredLogs(filtered)
        if (autoScroll) {
            binding.logsScroll.post { binding.logsScroll.fullScroll(NestedScrollView.FOCUS_DOWN) }
        }
    }

    private fun buildColoredLogs(lines: List<String>): CharSequence {
        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val start = builder.length
            builder.append(line)
            val end = builder.length
            val color = when {
                line.contains("[ERR]") || line.contains("ERROR", true) -> Color.parseColor("#D32F2F")
                line.contains("[WARN]") || line.contains("WARN", true) -> Color.parseColor("#FF9800")
                line.contains("[INFO]") -> Color.parseColor("#607D8B")
                else -> Color.parseColor("#EDEDED")
            }
            builder.setSpan(ForegroundColorSpan(color), start, end, 0)
            if (index < lines.lastIndex) builder.append('\n')
        }
        return builder
    }
}
