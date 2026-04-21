package ai.inmo.openclaw.data.repository

import ai.inmo.core_common.utils.Logger
import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.local.prefs.PreferencesManager
import ai.inmo.openclaw.domain.model.TerminalExecutionMode
import ai.inmo.openclaw.domain.model.TerminalSessionSpec
import ai.inmo.openclaw.domain.model.TerminalSessionState
import ai.inmo.openclaw.proot.ProcessManager
import ai.inmo.openclaw.service.terminal.TerminalSessionService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class TerminalSessionManager(
    context: Context,
    private val preferencesManager: PreferencesManager
) : TerminalSessionClient {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processManager = ProcessManager(appContext.filesDir.absolutePath, appContext.applicationInfo.nativeLibraryDir)
    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val _state = MutableStateFlow(TerminalSessionState())
    val state: StateFlow<TerminalSessionState> = _state.asStateFlow()

    private val _sessionFlow = MutableStateFlow<TerminalSession?>(null)
    val sessionFlow: StateFlow<TerminalSession?> = _sessionFlow.asStateFlow()

    private var activeSpec: TerminalSessionSpec? = null
    private val finishedFromMarker = AtomicBoolean(false)

    fun start(spec: TerminalSessionSpec) {
        stop()
        activeSpec = spec
        finishedFromMarker.set(false)
        _state.value = TerminalSessionState(
            title = spec.title,
            subtitle = spec.subtitle,
            transcript = spec.preamble.orEmpty(),
            isLoading = true,
            canSendInput = true
        )
        scope.launch {
            runCatching {
                TerminalSessionService.start(appContext)
                val config = when (spec.mode) {
                    TerminalExecutionMode.SHELL -> processManager.buildShellPtyConfig(spec.command)
                    TerminalExecutionMode.INSTALL -> processManager.buildInstallPtyConfig(spec.command)
                }
                val session = TerminalSession(
                    config.executable,
                    config.cwd,
                    config.args,
                    config.env,
                    TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                    this@TerminalSessionManager
                )
                _sessionFlow.value = session
                _state.value = _state.value.copy(
                    isLoading = false,
                    isRunning = true,
                    canSendInput = true,
                    errorMessage = null
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    isRunning = false,
                    isFinished = false,
                    canSendInput = false,
                    errorMessage = error.message ?: error.toString()
                )
                TerminalSessionService.stop(appContext)
            }
        }
    }

    fun restart() {
        activeSpec?.let(::start)
    }

    fun sendInput(text: String) {
        writeBytes((text + if (text.endsWith("\n")) "" else "\r").toByteArray(StandardCharsets.UTF_8))
    }

    fun writeBytes(bytes: ByteArray) {
        _sessionFlow.value?.write(bytes, 0, bytes.size)
    }

    fun pasteText(text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        _sessionFlow.value?.write(bytes, 0, bytes.size)
    }

    fun getTranscriptText(): String {
        return _state.value.transcript
    }

    fun getLatestUrl(): String? = _state.value.latestUrl

    fun stop() {
        _sessionFlow.value?.finishIfRunning()
        _sessionFlow.value = null
        TerminalSessionService.stop(appContext)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        if (_sessionFlow.value !== changedSession) return
        val transcript = changedSession.emulator?.screen?.transcriptTextWithFullLinesJoined
            ?: changedSession.emulator?.screen?.transcriptText
            ?: ""
        val previousPreamble = activeSpec?.preamble.orEmpty()
        val nextTranscript = (previousPreamble + transcript).takeLast(100_000)
        val latestUrl = detectLatestUrl(nextTranscript)
        if (activeSpec?.saveDashboardTokenFromOutput == true && latestUrl?.contains("#token=") == true) {
            preferencesManager.dashboardUrl = latestUrl
        }
        if (!finishedFromMarker.get() && activeSpec?.completionMarkers?.any { nextTranscript.contains(it, ignoreCase = true) } == true) {
            finishedFromMarker.set(true)
        }
        _state.value = _state.value.copy(
            transcript = nextTranscript,
            latestUrl = latestUrl
        )
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        if (_sessionFlow.value !== changedSession) return
        val title = changedSession.title.takeUnless { it.isNullOrBlank() } ?: activeSpec?.title.orEmpty()
        _state.value = _state.value.copy(title = title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (_sessionFlow.value !== finishedSession) return
        val exitCode = finishedSession.exitStatus
        val finalTranscript = (getTranscriptText() + "\n[Process exited with code $exitCode]\n").takeLast(100_000)
        _state.value = _state.value.copy(
            transcript = finalTranscript,
            isRunning = false,
            isFinished = finishedFromMarker.get() || activeSpec?.finishOnExit == true || exitCode == 0,
            exitCode = exitCode,
            canSendInput = false
        )
        TerminalSessionService.stop(appContext)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val text = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
        pasteText(text)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Logger.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Logger.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Logger.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Logger.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Logger.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Logger.e(tag, "$message\n${e.stackTraceToString()}")
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Logger.e(tag, e.stackTraceToString())
    }

    private fun detectLatestUrl(text: String): String? {
        val clean = text
            .replace(AppConstants.ANSI_ESCAPE, "")
            .replace(Regex("[\\u2500-\\u257F]+"), "")
        return Regex("""https?://[^\s<>\[\]"')]+""")
            .findAll(clean)
            .map { it.value }
            .maxByOrNull { it.length }
    }
}
