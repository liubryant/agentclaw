package ai.inmo.openclaw.ui.shell.chat

import ai.inmo.core_common.utils.Logger
import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.FragmentShellChatBinding
import ai.inmo.openclaw.domain.model.GeneratingPhase
import ai.inmo.openclaw.ui.chat.ChatMessageItem
import ai.inmo.openclaw.ui.chat.ChatMessageAdapter
import ai.inmo.openclaw.ui.chat.ChatScreenState
import ai.inmo.openclaw.ui.common.BaseBindingFragment
import ai.inmo.openclaw.ui.shell.ShellChatViewModel
import ai.inmo.openclaw.ui.shell.ShellChromeController
import ai.inmo.openclaw.ui.shell.ShellEvent
import ai.inmo.openclaw.ui.shell.PresetConversation
import ai.inmo.openclaw.ui.shell.ShellSeedData
import ai.inmo.openclaw.ui.shell.ShellSharedViewModel
import ai.inmo.openclaw.util.hideKeyboard
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.os.SystemClock
import ai.inmo.core_common.ui.dialog.CommonMessageDialog
import ai.inmo.core_common.utils.coroutine.CoroutineUtils
import kotlinx.coroutines.delay

class ChatFragment : BaseBindingFragment<FragmentShellChatBinding>(FragmentShellChatBinding::inflate) {
    private companion object {
        private const val PREFETCH_THRESHOLD = 6
        private const val TRACE_TAG = "ShellChatTrace"
        private const val TAG = "ChatFragment"
    }

    private val shellViewModel: ShellSharedViewModel by activityViewModels()
    private val chatViewModel: ShellChatViewModel by activityViewModels()

    private val messageAdapter = ChatMessageAdapter()
    private val quickPromptAdapter = ChatEmptyIdeaAdapter()
    private var suppressDraftSync = false
    private var pendingScroll = false
    private var lastMessageCount = 0
    private var lastTailMessageId: String? = null
    private var lastRenderedSessionId: String? = null
    private var activeRenderTrace: RenderTrace? = null

    override fun initView(savedInstanceState: Bundle?) {
        chatViewModel.start()
        (activity as? ShellChromeController)?.setTopBarVisible(false)
        bindWindowInsets()

        binding.messagesRecycler.layoutManager = object : LinearLayoutManager(requireContext()) {
            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                activeRenderTrace?.layoutPassCount = activeRenderTrace?.layoutPassCount?.plus(1) ?: 0
            }
        }.apply {
            stackFromEnd = true
        }
        binding.messagesRecycler.itemAnimator = null
        binding.messagesRecycler.setItemViewCacheSize(10)
        binding.messagesRecycler.recycledViewPool.setMaxRecycledViews(2, 8)
        binding.messagesRecycler.recycledViewPool.setMaxRecycledViews(3, 12)
        binding.messagesRecycler.recycledViewPool.setMaxRecycledViews(4, 12)
        binding.messagesRecycler.adapter = messageAdapter
        binding.messagesRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.findFirstVisibleItemPosition() <= PREFETCH_THRESHOLD &&
                    !chatViewModel.state.value.isGenerating
                ) {
                    chatViewModel.loadMore()
                }
            }
        })
        binding.quickPromptRecycler.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.quickPromptRecycler.adapter = quickPromptAdapter
        quickPromptAdapter.submitList(ShellSeedData.ideaTemplates())

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.state.collectLatest(::renderUiState)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.state
                .map { it.messages }
                .distinctUntilChanged()
                .conflate()
                .collect { messages ->
                    val renderStartMs = SystemClock.elapsedRealtime()
                    val currentSessionId = chatViewModel.state.value.selectedSessionId.orEmpty()
                    val newTailMessageId = messages.lastOrNull()?.id
                    val scrollReason = resolveScrollReason(
                        sessionId = currentSessionId,
                        newMessageCount = messages.size,
                        newTailMessageId = newTailMessageId
                    )
                    val shouldScroll = shouldAutoScroll(scrollReason)
                    Logger.d(
                        TRACE_TAG,
                        "uiMessages session=$currentSessionId, " +
                            "messages=${messages.describeItems()}, " +
                            "count=${messages.size}, " +
                            "lastId=${newTailMessageId.orEmpty()}, " +
                            "shouldScroll=$shouldScroll, " +
                            "scrollReason=${scrollReason.name}"
                    )
                    messageAdapter.setContentWidthPx(resolveRecyclerContentWidth())
                    if (scrollReason == ScrollReason.SESSION_SWITCH) {
                        messageAdapter.beginFirstFrameRender()
                    } else {
                        messageAdapter.finishFirstFrameRender()
                    }
                    messageAdapter.resetRenderStats()
                    val submitStartMs = SystemClock.elapsedRealtime()
                    activeRenderTrace = RenderTrace(
                        sessionId = currentSessionId,
                        messageCount = messages.size,
                        submitStartMs = submitStartMs,
                        renderStartMs = renderStartMs,
                        scrollReason = scrollReason
                    )
                    messageAdapter.submitList(messages) {
                        val commitMs = SystemClock.elapsedRealtime()
                        if (shouldScroll) {
                            activeRenderTrace?.didAutoScroll = true
                            scrollMessagesToBottom()
                        }
                        binding.messagesRecycler.doOnPreDraw {
                            val firstDrawMs = SystemClock.elapsedRealtime()
                            val visibleCount = visibleItemCount()
                            binding.messagesRecycler.post {
                                val stableMs = SystemClock.elapsedRealtime()
                                val stats = messageAdapter.snapshotRenderStats()
                                val trace = activeRenderTrace
                                val visibleItems = visibleItems(messages)
                                val visibleAssistantCount =
                                    visibleItems.count { it is ai.inmo.openclaw.ui.chat.ChatMessageItem.AssistantMessageItem }
                                val visibleToolCount =
                                    visibleItems.count {
                                        it is ai.inmo.openclaw.ui.chat.ChatMessageItem.ToolCallMessageItem ||
                                            it is ai.inmo.openclaw.ui.chat.ChatMessageItem.ToolTextMessageItem
                                    }
                                Logger.d(
                                    "renderTrace session=$currentSessionId, " +
                                        "count=${messages.size}, " +
                                        "submitToCommitMs=${commitMs - submitStartMs}, " +
                                        "commitToFirstDrawMs=${firstDrawMs - commitMs}, " +
                                        "totalToStableMs=${stableMs - renderStartMs}, " +
                                        "layoutPassCount=${trace?.layoutPassCount ?: 0}, " +
                                        "didAutoScroll=${trace?.didAutoScroll ?: false}, " +
                                        "scrollReason=${scrollReason.name}, " +
                                        "itemCountVisibleOnFirstDraw=$visibleCount, " +
                                        "visibleAssistantCount=$visibleAssistantCount, " +
                                        "visibleToolCount=$visibleToolCount, " +
                                        "bubbleWidthPx=${messageAdapter.currentBubbleWidthPx()}, " +
                                        "assistantBind=${stats.assistantBindCount}/${stats.assistantBindTotalMs}ms, " +
                                        "toolTextBind=${stats.toolTextBindCount}/${stats.toolTextBindTotalMs}ms, " +
                                        "toolCallBind=${stats.toolCallBindCount}/${stats.toolCallBindTotalMs}ms, " +
                                        "markdown=${stats.markdownCount}/${stats.markdownTotalMs}ms, " +
                                        "maxBindMs=${stats.bindMaxMs}"
                                )
                                if (scrollReason == ScrollReason.SESSION_SWITCH && visibleItems.isNotEmpty()) {
                                    val upgradeStartMs = SystemClock.elapsedRealtime()
                                    val upgradeResult = messageAdapter.upgradeVisibleRange(visibleItems)
                                    binding.messagesRecycler.post {
                                        Logger.d(
                                            "postFrameUpgrade session=$currentSessionId, " +
                                                "postFrameUpgradeMs=${SystemClock.elapsedRealtime() - upgradeStartMs}, " +
                                                "upgradedAssistantCount=${upgradeResult.upgradedAssistantCount}, " +
                                                "upgradedToolCount=${upgradeResult.upgradedToolCount}"
                                        )
                                    }
                                }
                                if (activeRenderTrace === trace) {
                                    activeRenderTrace = null
                                }
                            }
                        }
                    }
                    lastMessageCount = messages.size
                    lastTailMessageId = newTailMessageId
                    lastRenderedSessionId = currentSessionId
                }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                chatViewModel.state.map { it.selectedSessionId }.distinctUntilChanged(),
                shellViewModel.uiState.map { it.chatDrafts }.distinctUntilChanged()
            ) { selectedSessionId, chatDrafts ->
                chatDrafts[selectedSessionId].orEmpty()
            }.collectLatest { draft ->
                if (!suppressDraftSync && binding.composerInput.text?.toString() != draft) {
                    binding.composerInput.setText(draft)
                    binding.composerInput.setSelection(binding.composerInput.text?.length ?: 0)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            shellViewModel.events.collectLatest { event ->
                when (event) {
                    ShellEvent.ClearChatComposerFocus -> {
                        clearComposerInputFocus()
                    }

                    is ShellEvent.OpenChatDraft -> {
                        val currentSessionId = chatViewModel.state.value.selectedSessionId.orEmpty()
                        if (currentSessionId.isNotBlank()) {
                            shellViewModel.updateChatDraft(currentSessionId, event.draft)
                        }
                        binding.composerInput.requestFocus()
                    }

                    is ShellEvent.OpenChatInNewSession -> openDraftInNewSession(event.draft)
                    is ShellEvent.OpenChatInNewSessionWithPresetConversation -> {
                        openPresetConversationInNewSession(event.conversation)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
//            chatViewModel.exportMessages.collectLatest { message ->
//                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
//            }
        }
    }

    override fun initEvent() {
        binding.exportSessionFilesButton.setOnClickListener {
            chatViewModel.rememberExportButtonVisibleForCurrentSession()
            chatViewModel.exportCurrentSessionArtifacts()
            binding.shellChatRoot.postDelayed({ openInmoClawDirectory() }, 300L)
        }
        binding.sendButton.setOnClickListener {
            val text = binding.composerInput.text?.toString().orEmpty().trim()
            if (text.isBlank()) return@setOnClickListener
            chatViewModel.sendMessage(text)
            binding.composerInput.hideKeyboard(clearFocus = true)
            val currentSessionId = chatViewModel.state.value.selectedSessionId.orEmpty()
            suppressDraftSync = true
            binding.composerInput.setText("")
            suppressDraftSync = false
            if (currentSessionId.isNotBlank()) {
                shellViewModel.clearDraft(currentSessionId)
            }
            CoroutineUtils.ui {
                delay(50L)
                scrollMessagesToBottom()
            }
        }
        binding.stopButton.setOnClickListener { chatViewModel.stopGeneration() }
        binding.dismissErrorButton.setOnClickListener { chatViewModel.dismissError() }
        binding.composerInput.doAfterTextChanged { editable ->
            if (!suppressDraftSync) {
                val currentSessionId = chatViewModel.state.value.selectedSessionId.orEmpty()
                if (currentSessionId.isNotBlank()) {
                    shellViewModel.updateChatDraft(currentSessionId, editable?.toString().orEmpty())
                }
            }
            refreshComposerActionButton()
        }
        messageAdapter.onAssistantExportClick = { messageId ->
            chatViewModel.exportArtifactsByMessage(messageId)
        }
        messageAdapter.onUserNoticeClick = { messageId ->
            chatViewModel.retryMessage(messageId)
        }
        quickPromptAdapter.onItemClick = { idea ->
            suppressDraftSync = true
            binding.composerInput.setText(idea.promptTemplate)
            binding.composerInput.setSelection(binding.composerInput.text?.length ?: 0)
            suppressDraftSync = false
            val currentSessionId = chatViewModel.state.value.selectedSessionId.orEmpty()
            if (currentSessionId.isNotBlank()) {
                shellViewModel.updateChatDraft(currentSessionId, idea.promptTemplate)
            }
            refreshComposerActionButton()
        }
    }

    override fun onPause() {
        super.onPause()
        clearComposerInputFocus()
    }

    private fun clearComposerInputFocus(){
        Logger.d(TAG,"clearComposerInputFocus")
        binding.composerInput.clearFocus()
    }

    private fun bindWindowInsets() {
        val root = binding.shellChatRoot
        val baseBottomPadding = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBarsBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val extraBottomInset = maxOf(systemBarsBottom, imeBottom)
            view.updatePadding(bottom = baseBottomPadding + extraBottomInset)

            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                scrollMessagesToBottom()
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun openDraftInNewSession(draft: String) {
        val proceed: (Boolean) -> Unit = { abortCurrent ->
            viewLifecycleOwner.lifecycleScope.launch {
                chatViewModel.createPersistentSession(abortCurrent = abortCurrent)
                chatViewModel.sendMessage(draft)
            }
        }

        if (!chatViewModel.state.value.isGenerating) {
            proceed(false)
            return
        }

        CommonMessageDialog.createMessageConfirm(
            context = requireContext(),
            title = getString(R.string.chat_switch_title),
            message = getString(R.string.chat_switch_message),
            positiveText = getString(R.string.chat_switch_confirm),
            negativeText = getString(R.string.chat_switch_cancel),
            onPositive = { proceed(true) }
        ).show()
    }

    private fun openPresetConversationInNewSession(conversation: PresetConversation) {
        val proceed: (Boolean) -> Unit = { abortCurrent ->
            viewLifecycleOwner.lifecycleScope.launch {
                chatViewModel.createPersistentSessionWithPresetConversation(
                    userPrompt = conversation.userPrompt,
                    assistantReply = conversation.assistantReply,
                    abortCurrent = abortCurrent
                )
            }
        }

        if (!chatViewModel.state.value.isGenerating) {
            proceed(false)
            return
        }

        CommonMessageDialog.createMessageConfirm(
            context = requireContext(),
            title = getString(R.string.chat_switch_title),
            message = getString(R.string.chat_switch_message),
            positiveText = getString(R.string.chat_switch_confirm),
            negativeText = getString(R.string.chat_switch_cancel),
            onPositive = { proceed(true) }
        ).show()
    }

    private fun openInmoClawDirectory() {
        val folderDocUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FInmoClaw")

        val openFolderIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val fallbackIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)

        val opened = tryStartActivity(openFolderIntent) ||
            tryStartActivity(fallbackIntent)

        if (!opened) {
            Toast.makeText(requireContext(), "未找到可用的文件管理器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        if (intent.resolveActivity(requireContext().packageManager) == null) return false
        return runCatching { startActivity(intent) }.isSuccess
    }

    private fun scrollMessagesToBottom() {
        Logger.d(TAG,"scrollMessagesToBottom pendingScroll:$pendingScroll")
        if (pendingScroll) return
        pendingScroll = true
        binding.messagesRecycler.post {
            pendingScroll = false
            val lastIndex = messageAdapter.itemCount - 1
            Logger.d(TAG,"scrollMessagesToBottom lastIndex:$lastIndex")
            if (lastIndex >= 0) {
                binding.messagesRecycler.scrollToPosition(lastIndex)
            }
        }
    }

    private fun shouldAutoScroll(reason: ScrollReason): Boolean {
        return when (reason) {
            ScrollReason.SESSION_SWITCH -> false
            ScrollReason.NEW_MESSAGE,
            ScrollReason.STREAMING_UPDATE -> isNearBottom()
        }
    }

    private fun isNearBottom(): Boolean {
        val layoutManager = binding.messagesRecycler.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val lastIndex = messageAdapter.itemCount - 1
        return lastIndex <= 0 || lastVisible >= lastIndex - 1
    }

    private fun visibleItemCount(): Int {
        val layoutManager = binding.messagesRecycler.layoutManager as? LinearLayoutManager ?: return 0
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return 0
        return (lastVisible - firstVisible + 1).coerceAtLeast(0)
    }

    private fun visibleItems(messages: List<ai.inmo.openclaw.ui.chat.ChatMessageItem>): List<ai.inmo.openclaw.ui.chat.ChatMessageItem> {
        val layoutManager = binding.messagesRecycler.layoutManager as? LinearLayoutManager ?: return emptyList()
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return emptyList()
        val safeFirst = firstVisible.coerceAtLeast(0)
        val safeLastExclusive = (lastVisible + 1).coerceAtMost(messages.size)
        if (safeFirst >= safeLastExclusive) return emptyList()
        return messages.subList(safeFirst, safeLastExclusive)
    }

    private fun resolveRecyclerContentWidth(): Int {
        val recycler = binding.messagesRecycler
        val width = recycler.width.takeIf { it > 0 }
            ?: recycler.measuredWidth.takeIf { it > 0 }
            ?: 0
        return (width - recycler.paddingLeft - recycler.paddingRight).coerceAtLeast(0)
    }

    private fun resolveScrollReason(
        sessionId: String,
        newMessageCount: Int,
        newTailMessageId: String?
    ): ScrollReason {
        if (sessionId != lastRenderedSessionId) {
            return ScrollReason.SESSION_SWITCH
        }
        if (newMessageCount > lastMessageCount && newTailMessageId != lastTailMessageId) {
            return ScrollReason.NEW_MESSAGE
        }
        return ScrollReason.STREAMING_UPDATE
    }

    private fun renderUiState(state: ChatScreenState) {
        binding.connectionView.text = state.connectionMessage.orEmpty()
        binding.connectionView.visibility = if (state.connectionMessage.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.errorView.text = state.errorMessage.orEmpty()
        binding.errorCard.visibility = if (state.errorMessage.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.loadingBar.visibility = if (state.isLoading) android.view.View.VISIBLE else android.view.View.GONE
        refreshComposerActionButton(state)
        binding.composerInput.isEnabled = state.canSend
        val showEmptyState = state.messages.isEmpty() && !state.isLoading
        binding.emptyStateScroll.visibility = if (showEmptyState) android.view.View.VISIBLE else android.view.View.GONE
        binding.messagesRecycler.visibility = if (showEmptyState) android.view.View.GONE else android.view.View.VISIBLE
//        binding.disclaimerView.visibility = if (showEmptyState) android.view.View.VISIBLE else android.view.View.GONE

        when (state.generatingPhase) {
            GeneratingPhase.THINKING -> {
                binding.thinkingIndicator.visibility = android.view.View.VISIBLE
                binding.thinkingLabel.setText(R.string.chat_thinking)
            }

            GeneratingPhase.CALLING_TOOL -> {
                binding.thinkingIndicator.visibility = android.view.View.VISIBLE
                binding.thinkingLabel.setText(R.string.chat_calling_tool)
            }

            GeneratingPhase.NONE -> {
                binding.thinkingIndicator.visibility = android.view.View.GONE
            }
        }

        binding.exportSessionFilesButton.visibility = if (state.showExportSessionFilesButton) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun refreshComposerActionButton(state: ChatScreenState = chatViewModel.state.value) {
        val isInputBlank = binding.composerInput.text?.toString()?.trim().isNullOrEmpty()
        binding.stopButton.visibility = if (state.isGenerating) android.view.View.VISIBLE else android.view.View.GONE
        binding.sendButton.visibility = if (state.isGenerating) android.view.View.GONE else android.view.View.VISIBLE
        binding.sendButton.isEnabled = state.canSend
        binding.sendButton.setImageResource(
            if (isInputBlank) R.drawable.ic_chat_unsend else R.drawable.ic_chat_send
        )
    }

    override fun onDestroyView() {
        (activity as? ShellChromeController)?.setTopBarVisible(false)
        super.onDestroyView()
    }

    private data class RenderTrace(
        val sessionId: String,
        val messageCount: Int,
        val submitStartMs: Long,
        val renderStartMs: Long,
        val scrollReason: ScrollReason,
        var layoutPassCount: Int = 0,
        var didAutoScroll: Boolean = false
    )

    private enum class ScrollReason {
        SESSION_SWITCH,
        NEW_MESSAGE,
        STREAMING_UPDATE
    }

    private fun List<ChatMessageItem>.describeItems(): String {
        return joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
            when (item) {
                is ChatMessageItem.UserMessageItem -> {
                    "user(id=${item.id},hash=${item.content.stableHash()},len=${item.content.length},stream=${item.isStreaming},ts=${item.createdAt})"
                }
                is ChatMessageItem.AssistantMessageItem -> {
                    "assistant(id=${item.id},hash=${item.content.stableHash()},len=${item.content.length},stream=${item.isStreaming},ts=${item.createdAt})"
                }
                is ChatMessageItem.ToolTextMessageItem -> {
                    "toolText(id=${item.id},parent=${item.parentMessageId},hash=${item.content.stableHash()},len=${item.content.length},stream=${item.isStreaming},ts=${item.createdAt})"
                }
                is ChatMessageItem.ToolCallMessageItem -> {
                    "toolCall(id=${item.id},parent=${item.parentMessageId},call=${item.tool.toolCallId},name=${item.tool.name},done=${item.tool.completed},stream=${item.isStreaming},ts=${item.createdAt})"
                }
            }
        }
    }

    private fun String.stableHash(): String = hashCode().toUInt().toString(16)
}
