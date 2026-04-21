package ai.inmo.openclaw.ui.chat

import ai.inmo.openclaw.R
import kotlinx.coroutines.flow.StateFlow

class ChatActivity : BaseChatActivity() {
    private val viewModel = ChatViewModel()

    override val screenTitleRes: Int = R.string.chat_title
    override val screenSubtitleRes: Int = R.string.chat_subtitle_local
    override val stateFlow: StateFlow<ChatScreenState> = viewModel.state

    override fun onScreenStart() = viewModel.start()
    override fun onNewChat() = viewModel.createSession()
    override fun onSendMessage(text: String) = viewModel.sendMessage(text)
    override fun onDeleteSession(sessionId: String) = viewModel.deleteSession(sessionId)
    override fun onSelectSession(sessionId: String, abortCurrent: Boolean) = viewModel.switchSession(sessionId, abortCurrent)
    override fun onStopGeneration() = viewModel.stopGeneration()
    override fun onDismissError() = viewModel.dismissError()
}
