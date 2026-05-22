package ai.cjym.agentclaw.ui.terminal

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.cjym.agentclaw.data.repository.TerminalSessionManager
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.TerminalSessionSpec
import ai.cjym.agentclaw.domain.model.TerminalSessionState
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
