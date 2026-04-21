package ai.inmo.openclaw.ui.terminal

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivityTerminalSessionBinding
import ai.inmo.openclaw.util.ScreenshotUtils
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

abstract class BaseTerminalActivity(
    inflate: (android.view.LayoutInflater) -> ActivityTerminalSessionBinding
) : BaseBindingActivity<ActivityTerminalSessionBinding>(inflate), TerminalViewClient {

    protected abstract val terminalViewModel: BaseTerminalViewModel

    private var ctrlPressed = false
    private var altPressed = false
    private var shiftPressed = false
    private var fnPressed = false
    private var fontSize = 15

    override fun initData() {
        terminalViewModel.addObserver()
        terminalViewModel.start()
    }

    override fun initView() {
        binding.terminalView.setTerminalViewClient(this)
        binding.terminalView.setTextSize(fontSize)
        binding.terminalView.requestFocus()

        lifecycleScope.launch {
            terminalViewModel.sessionFlow.collectLatest { session ->
                if (session != null) {
                    binding.terminalView.attachSession(session)
                    binding.terminalView.requestFocus()
                    showIme()
                }
            }
        }
        lifecycleScope.launch {
            terminalViewModel.state.collectLatest { state ->
                binding.titleView.text = state.title
                binding.subtitleView.text = state.subtitle.orEmpty()
                binding.subtitleView.visibility = if (state.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.loadingBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                binding.errorView.text = state.errorMessage.orEmpty()
                binding.errorView.visibility = if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.doneButton.visibility = if (state.isFinished) View.VISIBLE else View.GONE
                binding.openUrlButton.isEnabled = !state.latestUrl.isNullOrBlank()
            }
        }
        syncModifierButtons()
    }

    override fun initEvent() {
        binding.backButton.setOnClickListener { finish() }
        binding.copyButton.setOnClickListener { copyText(terminalViewModel.copyAllText()) }
        binding.pasteButton.setOnClickListener { terminalViewModel.pasteText(readClipboard()) }
        binding.screenshotButton.setOnClickListener {
            val saved = ScreenshotUtils.captureView(this, binding.root, "terminal")
            Toast.makeText(this, if (saved) getString(R.string.terminal_screenshot_saved) else getString(R.string.terminal_screenshot_failed), Toast.LENGTH_SHORT).show()
        }
        binding.openUrlButton.setOnClickListener {
            val url = terminalViewModel.latestUrl() ?: return@setOnClickListener
            promptOpenUrl(url)
        }
        binding.restartButton.setOnClickListener { terminalViewModel.restart() }
        binding.doneButton.setOnClickListener { finish() }

        binding.ctrlButton.setOnClickListener {
            ctrlPressed = !ctrlPressed
            if (ctrlPressed) altPressed = false
            syncModifierButtons()
        }
        binding.altButton.setOnClickListener {
            altPressed = !altPressed
            if (altPressed) ctrlPressed = false
            syncModifierButtons()
        }
        binding.escButton.setOnClickListener { sendToolbarData("\u001b") }
        binding.tabButton.setOnClickListener { sendToolbarData("\t") }
        binding.enterButton.setOnClickListener { sendToolbarData("\r") }
        binding.upButton.setOnClickListener { sendToolbarData("\u001b[A") }
        binding.downButton.setOnClickListener { sendToolbarData("\u001b[B") }
        binding.leftButton.setOnClickListener { sendToolbarData("\u001b[D") }
        binding.rightButton.setOnClickListener { sendToolbarData("\u001b[C") }
        binding.homeButton.setOnClickListener { sendToolbarData("\u001b[H") }
        binding.endButton.setOnClickListener { sendToolbarData("\u001b[F") }
        binding.pgUpButton.setOnClickListener { sendToolbarData("\u001b[5~") }
        binding.pgDnButton.setOnClickListener { sendToolbarData("\u001b[6~") }
        binding.minusButton.setOnClickListener { sendToolbarData("-") }
        binding.slashButton.setOnClickListener { sendToolbarData("/") }
        binding.pipeButton.setOnClickListener { sendToolbarData("|") }
        binding.tildeButton.setOnClickListener { sendToolbarData("~") }
        binding.underscoreButton.setOnClickListener { sendToolbarData("_") }
    }

    protected fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        Toast.makeText(this, getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
    }

    protected fun readClipboard(): String {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        return item?.coerceToText(this)?.toString().orEmpty()
    }

    private fun showIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun syncModifierButtons() {
        binding.ctrlButton.isSelected = ctrlPressed
        binding.altButton.isSelected = altPressed
        val activeAlpha = 1f
        val inactiveAlpha = 0.6f
        binding.ctrlButton.alpha = if (ctrlPressed) activeAlpha else inactiveAlpha
        binding.altButton.alpha = if (altPressed) activeAlpha else inactiveAlpha
    }

    private fun sendToolbarData(data: String) {
        var payload = data
        if (ctrlPressed) {
            ctrlPressed = false
            payload = when {
                data.length == 1 && data[0].isLetter() -> ((data.lowercase()[0].code - 96).toChar()).toString()
                data == "\u001b[A" -> "\u001b[1;5A"
                data == "\u001b[B" -> "\u001b[1;5B"
                data == "\u001b[D" -> "\u001b[1;5D"
                data == "\u001b[C" -> "\u001b[1;5C"
                data == "\u001b[H" -> "\u001b[1;5H"
                data == "\u001b[F" -> "\u001b[1;5F"
                data == "\u001b[5~" -> "\u001b[5;5~"
                data == "\u001b[6~" -> "\u001b[6;5~"
                else -> data
            }
        } else if (altPressed) {
            altPressed = false
            payload = "\u001b$data"
        }
        syncModifierButtons()
        terminalViewModel.sendBytes(payload.toByteArray(StandardCharsets.UTF_8))
    }

    override fun onScale(scale: Float): Float {
        fontSize = (fontSize * scale).toInt().coerceIn(8, 24)
        binding.terminalView.setTextSize(fontSize)
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        binding.terminalView.requestFocus()
        showIme()
        detectUrlNearTap(e)?.let(::promptOpenUrl)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, currentSession: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean {
        binding.terminalView.startTextSelectionMode(event)
        return true
    }

    override fun readControlKey(): Boolean = ctrlPressed
    override fun readAltKey(): Boolean = altPressed
    override fun readShiftKey(): Boolean = shiftPressed
    override fun readFnKey(): Boolean = fnPressed
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, currentSession: TerminalSession): Boolean = false
    override fun onEmulatorSet() { binding.terminalView.requestFocus() }
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit

    private fun detectUrlNearTap(event: MotionEvent): String? {
        val session = binding.terminalView.currentSession ?: return null
        val emulator = session.emulator ?: return null
        val row = binding.terminalView.getCursorY(event.y)
        val startRow = (row - 2).coerceAtLeast(0)
        val endRow = (row + 2).coerceAtMost(emulator.mRows - 1)
        val selected = emulator.getSelectedText(0, startRow, emulator.mColumns, endRow)
        return extractUrl(selected)
    }

    private fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val clean = text.replace(Regex("[\\u2500-\\u257F]+"), " ").replace(Regex("\\s+"), " ")
        return Regex("""https?://[^\s<>\[\]"')]+""").findAll(clean).map { it.value.trim() }.maxByOrNull { it.length }
    }

    private fun promptOpenUrl(url: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.terminal_open_url))
            .setMessage(url)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.terminal_copy_all) { _, _ -> copyText(url) }
            .setPositiveButton(R.string.terminal_open_url) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { Toast.makeText(this, url, Toast.LENGTH_SHORT).show() }
            }
            .show()
    }
}
