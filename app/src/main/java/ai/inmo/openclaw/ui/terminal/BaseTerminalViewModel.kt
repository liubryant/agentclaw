package ai.inmo.openclaw.ui.terminal

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.data.repository.TerminalSessionManager
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.TerminalSessionSpec
import ai.inmo.openclaw.domain.model.TerminalSessionState
import android.content.Context
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow

abstract class BaseTerminalViewModel(context: Context) : BaseViewModel() {
    protected val sessionManager = TerminalSessionManager(context.applicationContext, AppGraph.preferences)
    val state: StateFlow<TerminalSessionState> = sessionManager.state
    val sessionFlow: StateFlow<TerminalSession?> = sessionManager.sessionFlow

    protected abstract fun buildSpec(): TerminalSessionSpec

    fun start() = sessionManager.start(buildSpec())

    fun restart() = sessionManager.restart()

    fun sendInput(text: String) = sessionManager.sendInput(text)

    fun sendBytes(bytes: ByteArray) = sessionManager.writeBytes(bytes)

    fun pasteText(text: String) = sessionManager.pasteText(text)

    fun copyAllText(): String = sessionManager.getTranscriptText()

    fun latestUrl(): String? = sessionManager.getLatestUrl()

    override fun onDestroy() {
        sessionManager.stop()
        super.onDestroy()
    }
}
