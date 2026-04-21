package ai.inmo.openclaw.ui.chat

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivityChatBinding
import ai.inmo.openclaw.domain.model.GeneratingPhase
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

abstract class BaseChatActivity :
    BaseBindingActivity<ActivityChatBinding>(ActivityChatBinding::inflate) {

    protected abstract val screenTitleRes: Int
    protected abstract val screenSubtitleRes: Int
    protected open val showSettingsAction: Boolean = false
    protected abstract val stateFlow: StateFlow<ChatScreenState>
    protected abstract fun onScreenStart()
    protected abstract fun onNewChat()
    protected abstract fun onSendMessage(text: String)
    protected abstract fun onDeleteSession(sessionId: String)
    protected abstract fun onSelectSession(sessionId: String, abortCurrent: Boolean)
    protected abstract fun onStopGeneration()
    protected abstract fun onDismissError()
    protected open fun onSettingsClick() = Unit
    protected open fun shouldConfirmSessionSwitch(targetSessionId: String): Boolean = false

    private val sessionAdapter = ChatSessionAdapter()
    private val messageAdapter = ChatMessageAdapter()
    private var pendingScroll = false
    private var lastMessageCount = 0
    private var lastTailMessageId: String? = null

    override fun initData() {
        onScreenStart()
    }

    override fun initView() {
        binding.titleView.setText(screenTitleRes)
        binding.subtitleView.setText(screenSubtitleRes)
        binding.settingsAction.visibility = if (showSettingsAction) View.VISIBLE else View.GONE
        binding.sessionsRecycler.layoutManager = LinearLayoutManager(this)
        binding.sessionsRecycler.adapter = sessionAdapter
        binding.messagesRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messagesRecycler.itemAnimator = null
        binding.messagesRecycler.adapter = messageAdapter

        if (resources.configuration.smallestScreenWidthDp >= 600) {
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_OPEN)
            binding.menuButton.visibility = View.GONE
        }

        lifecycleScope.launch {
            stateFlow.collectLatest(::render)
        }
    }

    override fun initEvent() {
        binding.backButton.setOnClickListener { finish() }
        binding.menuButton.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.settingsAction.setOnClickListener { onSettingsClick() }
        binding.newChatButton.setOnClickListener {
            onNewChat()
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
        binding.sendButton.setOnClickListener {
            val text = binding.composerInput.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            onSendMessage(text)
            binding.composerInput.setText("")
        }
        binding.stopButton.setOnClickListener { onStopGeneration() }
        binding.dismissErrorButton.setOnClickListener { onDismissError() }

        sessionAdapter.onSessionClick = { session ->
            if (shouldConfirmSessionSwitch(session.id)) {
                showSwitchConfirm(session.id)
            } else {
                onSelectSession(session.id, false)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
        sessionAdapter.onDeleteClick = { session ->
            AlertDialog.Builder(this)
                .setTitle(R.string.chat_delete_title)
                .setMessage(R.string.chat_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.chat_delete_confirm) { _, _ ->
                    onDeleteSession(session.id)
                }
                .show()
        }
    }

    private fun render(state: ChatScreenState) {
        binding.connectionView.text = state.connectionMessage.orEmpty()
        binding.connectionView.visibility = if (state.connectionMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.errorView.text = state.errorMessage.orEmpty()
        binding.errorCard.visibility = if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.loadingBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.stopButton.visibility = if (state.isGenerating) View.VISIBLE else View.GONE
        binding.sendButton.isEnabled = state.canSend
        binding.composerInput.isEnabled = state.canSend
        binding.emptyView.visibility = if (state.messages.isEmpty() && !state.isLoading) View.VISIBLE else View.GONE

        // Thinking / calling tool indicator
        when (state.generatingPhase) {
            GeneratingPhase.THINKING -> {
                binding.thinkingIndicator.visibility = View.VISIBLE
                binding.thinkingLabel.setText(R.string.chat_thinking)
            }
            GeneratingPhase.CALLING_TOOL -> {
                binding.thinkingIndicator.visibility = View.VISIBLE
                binding.thinkingLabel.setText(R.string.chat_calling_tool)
            }
            GeneratingPhase.NONE -> {
                binding.thinkingIndicator.visibility = View.GONE
            }
        }

        sessionAdapter.submitList(
            state.sessions.map(ChatSessionListItem::Session),
            state.selectedSessionId
        )
        val newTailMessageId = state.messages.lastOrNull()?.id
        val shouldScroll = shouldAutoScroll(state.messages.size, newTailMessageId)
        messageAdapter.submitList(state.messages) {
            if (shouldScroll) {
                scrollMessagesToBottom()
            }
            lastMessageCount = state.messages.size
            lastTailMessageId = newTailMessageId
        }
    }

    private fun scrollMessagesToBottom() {
        if (pendingScroll) return
        pendingScroll = true
        binding.messagesRecycler.post {
            pendingScroll = false
            val lastIndex = messageAdapter.itemCount - 1
            if (lastIndex >= 0) {
                binding.messagesRecycler.scrollToPosition(lastIndex)
            }
        }
    }

    private fun shouldAutoScroll(newMessageCount: Int, newTailMessageId: String?): Boolean {
        return isNearBottom() || (newMessageCount > lastMessageCount && newTailMessageId != lastTailMessageId)
    }

    private fun isNearBottom(): Boolean {
        val layoutManager = binding.messagesRecycler.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val lastIndex = messageAdapter.itemCount - 1
        return lastIndex <= 0 || lastVisible >= lastIndex - 1
    }

    private fun showSwitchConfirm(targetSessionId: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_switch_title)
            .setMessage(R.string.chat_switch_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.chat_switch_confirm) { _, _ ->
                onSelectSession(targetSessionId, true)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
            .show()
    }

    protected fun showTransientError(message: String?) {
        if (!message.isNullOrBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
