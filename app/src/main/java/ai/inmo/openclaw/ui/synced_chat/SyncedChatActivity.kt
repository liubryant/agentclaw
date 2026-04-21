package ai.inmo.openclaw.ui.synced_chat

import ai.inmo.openclaw.R
import ai.inmo.openclaw.SettingActivity
import ai.inmo.openclaw.ui.chat.BaseChatActivity
import ai.inmo.openclaw.ui.chat.ChatScreenState
import ai.inmo.openclaw.ui.settings.SettingsActivity
import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

class SyncedChatActivity : BaseChatActivity() {
    private val viewModel = SyncedChatViewModel()

    override val screenTitleRes: Int = R.string.chat_title
    override val screenSubtitleRes: Int = R.string.chat_subtitle_synced
    override val showSettingsAction: Boolean = true
    override val stateFlow: StateFlow<ChatScreenState> = viewModel.state

    override fun onScreenStart() = viewModel.start()
    override fun onNewChat() = viewModel.createSession()
    override fun onSendMessage(text: String) = viewModel.sendMessage(text)
    override fun onDeleteSession(sessionId: String) = viewModel.deleteSession(sessionId)
    override fun onSelectSession(sessionId: String, abortCurrent: Boolean) = viewModel.switchSession(sessionId, abortCurrent)
    override fun onStopGeneration() = viewModel.stopGeneration()
    override fun onDismissError() = viewModel.dismissError()
    override fun onSettingsClick() = startActivity(Intent(this, SettingActivity::class.java))
    override fun shouldConfirmSessionSwitch(targetSessionId: String): Boolean = viewModel.shouldConfirmSwitch(targetSessionId)
}
