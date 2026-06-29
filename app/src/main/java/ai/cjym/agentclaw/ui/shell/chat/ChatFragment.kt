package ai.cjym.agentclaw.ui.shell.chat

import ai.inmo.core_common.utils.Logger
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.os.Environment
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
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.data.aigc.AigcMetadataWriter
import ai.cjym.agentclaw.data.repository.TodayHotspotService
import ai.cjym.agentclaw.databinding.FragmentShellChatBinding
import ai.cjym.agentclaw.domain.model.GeneratingPhase
import ai.cjym.agentclaw.ui.chat.ChatMessageItem
import ai.cjym.agentclaw.ui.chat.ChatMessageAdapter
import ai.cjym.agentclaw.ui.chat.ChatScreenState
import ai.cjym.agentclaw.ui.common.BaseBindingFragment
import ai.cjym.agentclaw.ui.shell.ChatEntryMode
import ai.cjym.agentclaw.ui.shell.ShellChatViewModel
import ai.cjym.agentclaw.ui.shell.ShellChromeController
import ai.cjym.agentclaw.ui.shell.ShellEvent
import ai.cjym.agentclaw.ui.shell.PresetConversation
import ai.cjym.agentclaw.ui.shell.ShellSeedData
import ai.cjym.agentclaw.ui.shell.ShellSharedViewModel
import ai.cjym.agentclaw.util.hideKeyboard
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.content.res.ColorStateList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.os.SystemClock
import ai.inmo.core_common.ui.dialog.CommonMessageDialog
import ai.inmo.core_common.utils.coroutine.CoroutineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.ByteArrayOutputStream

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
    private val imageDownloadClient by lazy { OkHttpClient() }
    private val todayHotspotService by lazy { TodayHotspotService(requireContext()) }

    private var cameraImageUri: Uri? = null
    private var pendingImageUri: Uri? = null
    private var currentEntryMode: ChatEntryMode = ChatEntryMode.DEFAULT

    private data class DownloadedGeneratedFile(
        val uriOrPath: String,
        val mimeType: String,
        val downloadedNow: Boolean
    )

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cameraImageUri?.let { setComposerImage(it) }
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { setComposerImage(it) }
    }

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(requireContext(), "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }

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
        binding.quickPromptRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.quickPromptRecycler.adapter = quickPromptAdapter
        quickPromptAdapter.submitList(ShellSeedData.ideaTemplates())
        loadTodayHotspots()

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
                                    visibleItems.count { it is ai.cjym.agentclaw.ui.chat.ChatMessageItem.AssistantMessageItem }
                                val visibleToolCount =
                                    visibleItems.count {
                                        it is ai.cjym.agentclaw.ui.chat.ChatMessageItem.ToolCallMessageItem ||
                                            it is ai.cjym.agentclaw.ui.chat.ChatMessageItem.ToolTextMessageItem
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
            chatViewModel.state
                .map { it.selectedSessionId }
                .distinctUntilChanged()
                .collect { sessionId ->
                    if (!sessionId.isNullOrBlank()) {
                        shellViewModel.restoreSessionEntryModeIfNeeded(sessionId)
                    }
                }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                chatViewModel.state.map { it.selectedSessionId }.distinctUntilChanged(),
                shellViewModel.uiState.map { state ->
                    state.chatDrafts to state.chatEntryModes
                }.distinctUntilChanged()
            ) { selectedSessionId, shellState ->
                Pair(
                    shellState.first[selectedSessionId].orEmpty(),
                    shellState.second[selectedSessionId] ?: ChatEntryMode.DEFAULT
                )
            }.collectLatest { (draft, entryMode) ->
                updateComposerHint(entryMode)
                updateEntryModeButtons(entryMode)
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

                    is ShellEvent.OpenChatInNewSession -> {
                        openDraftInNewSession(draft = event.draft, entryMode = event.entryMode)
                    }
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
            showExportedFilesDialog()
        }
        binding.sendButton.setOnClickListener {
            val text = binding.composerInput.text?.toString().orEmpty().trim()
            val hasImage = pendingImageUri != null
            if (text.isBlank() && !hasImage) return@setOnClickListener
            // Quota check for image / video generation modes
            val ctx = requireContext()
            when (currentEntryMode) {
                ChatEntryMode.IMAGE -> {
                    if (!ai.cjym.agentclaw.quota.QuotaManager.canGenerateImage(ctx)) {
                        ai.cjym.agentclaw.ui.vip.VipUpgradeBottomSheet.forImage()
                            .show(parentFragmentManager, "vip_upgrade_image")
                        return@setOnClickListener
                    }
                    ai.cjym.agentclaw.quota.QuotaManager.consumeImage(ctx)
                }
                ChatEntryMode.VIDEO -> {
                    if (!ai.cjym.agentclaw.quota.QuotaManager.canGenerateVideo(ctx)) {
                        ai.cjym.agentclaw.ui.vip.VipUpgradeBottomSheet.forVideo()
                            .show(parentFragmentManager, "vip_upgrade_video")
                        return@setOnClickListener
                    }
                    ai.cjym.agentclaw.quota.QuotaManager.consumeVideo(ctx)
                }
                else -> { /* text mode: no quota */ }
            }
            val imageUri = pendingImageUri
            clearComposerImage()
            viewLifecycleOwner.lifecycleScope.launch {
                val imageBase64 = imageUri?.let { encodeImageToBase64(it) }
                chatViewModel.sendMessage(text, imageBase64)
            }
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
        messageAdapter.onAssistantImageClick = { imageUrl ->
            viewLifecycleOwner.lifecycleScope.launch {
                val downloaded = downloadGeneratedImageToDownloads(imageUrl)
                if (downloaded == null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.chat_image_download_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (downloaded.downloadedNow) {
                    Toast.makeText(
                        requireContext(),
                        R.string.chat_image_downloaded,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (downloaded != null) {
                    binding.shellChatRoot.postDelayed({
                        openDownloadedGeneratedFile(downloaded)
                    }, 300L)
                }
            }
        }
        messageAdapter.onAssistantVideoClick = { videoUrl ->
            viewLifecycleOwner.lifecycleScope.launch {
                val downloaded = downloadGeneratedVideoToDownloads(videoUrl)
                if (downloaded == null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.chat_video_download_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (downloaded.downloadedNow) {
                    Toast.makeText(
                        requireContext(),
                        R.string.chat_video_downloaded,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (downloaded != null) {
                    binding.shellChatRoot.postDelayed({
                        openDownloadedGeneratedFile(downloaded)
                    }, 300L)
                }
            }
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
        binding.imageChatEntryButton.setOnClickListener {
            if (currentEntryMode == ChatEntryMode.IMAGE) {
                shellViewModel.launchDefaultChat()
            } else {
                shellViewModel.launchImageChat()
            }
        }
        binding.videoChatEntryButton.setOnClickListener {
            if (currentEntryMode == ChatEntryMode.VIDEO) {
                shellViewModel.launchDefaultChat()
            } else {
                shellViewModel.launchVideoChat()
            }
        }

        binding.attachButton.setOnClickListener { showImagePickerSheet() }
        binding.removeThumbnailButton.setOnClickListener { clearComposerImage() }
    }

    private fun loadTodayHotspots() {
        viewLifecycleOwner.lifecycleScope.launch {
            todayHotspotService.loadTemplates()
                .onSuccess { templates ->
                    if (templates.isNotEmpty()) quickPromptAdapter.submitList(templates)
                }
                .onFailure { error ->
                    Logger.w(TAG, "loadTodayHotspots fallback to local seed: ${error.message}")
                }
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

    private fun openDraftInNewSession(
        draft: String,
        entryMode: ChatEntryMode = ChatEntryMode.DEFAULT
    ) {
        val proceed: (Boolean) -> Unit = { abortCurrent ->
            viewLifecycleOwner.lifecycleScope.launch {
                val sessionId = chatViewModel.createPersistentSession(abortCurrent = abortCurrent)
                shellViewModel.bindSessionEntryMode(sessionId, entryMode)
                chatViewModel.bindSessionEntryMode(sessionId, entryMode)
                if (draft.isNotBlank()) {
                    chatViewModel.sendMessage(draft)
                }
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

    private fun openAgentClawDirectory() {
        val folderDocUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FAgentClaw")
        val openFolderIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val fallbackIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        val opened = tryStartActivity(openFolderIntent) || tryStartActivity(fallbackIntent)
        if (!opened) {
            Toast.makeText(requireContext(), "No file manager available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportedFilesDialog() {
        if (childFragmentManager.findFragmentByTag(ExportedFilesDialogFragment.TAG) != null) return
        ExportedFilesDialogFragment.newInstance()
            .show(childFragmentManager, ExportedFilesDialogFragment.TAG)
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        if (intent.resolveActivity(requireContext().packageManager) == null) return false
        return runCatching { startActivity(intent) }.isSuccess
    }

    private suspend fun downloadGeneratedImageToDownloads(imageUrl: String): DownloadedGeneratedFile? {
        findExistingGeneratedFile(
            fileUrl = imageUrl,
            defaultPrefix = "generated-image",
            fallbackMimeType = "image/png"
        )?.let { return it }
        return downloadGeneratedFileToDownloads(
            fileUrl = imageUrl,
            defaultPrefix = "generated-image",
            fallbackMimeType = "image/png"
        )
    }

    private suspend fun downloadGeneratedVideoToDownloads(videoUrl: String): DownloadedGeneratedFile? {
        findExistingGeneratedFile(
            fileUrl = videoUrl,
            defaultPrefix = "generated-video",
            fallbackMimeType = "video/mp4"
        )?.let { return it }
        return downloadGeneratedFileToDownloads(
            fileUrl = videoUrl,
            defaultPrefix = "generated-video",
            fallbackMimeType = "video/mp4"
        )
    }

    private suspend fun downloadGeneratedFileToDownloads(
        fileUrl: String,
        defaultPrefix: String,
        fallbackMimeType: String
    ): DownloadedGeneratedFile? {
        val appContext = requireContext().applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(fileUrl).build()
                val response = imageDownloadClient.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) return@withContext null
                    val rawBytes = it.body?.bytes() ?: return@withContext null
                    val contentType = it.body?.contentType()?.toString().orEmpty()
                    val fileName = buildGeneratedFileName(
                        fileUrl = fileUrl,
                        contentType = fallbackMimeType,
                        defaultPrefix = defaultPrefix
                    )
                    val mimeType = contentType.substringBefore(';').trim()
                        .ifBlank { guessMimeType(fileName) }
                        .ifBlank { fallbackMimeType }
                    val bytes = applyAigcIdentification(fileName, rawBytes)
                    val uriOrPath = writeFileToDownloads(appContext, fileName, bytes, mimeType)
                    DownloadedGeneratedFile(uriOrPath = uriOrPath, mimeType = mimeType, downloadedNow = true)
                }
            }.getOrElse { error ->
                Logger.e(TAG, "downloadGeneratedFileToDownloads failed url=$fileUrl\n${error.stackTraceToString()}")
                null
            }
        }
    }

    private suspend fun findExistingGeneratedFile(
        fileUrl: String,
        defaultPrefix: String,
        fallbackMimeType: String
    ): DownloadedGeneratedFile? {
        val appContext = requireContext().applicationContext
        val fileName = buildGeneratedFileName(
            fileUrl = fileUrl,
            contentType = fallbackMimeType,
            defaultPrefix = defaultPrefix
        )
        val mimeType = guessMimeType(fileName).ifBlank { fallbackMimeType }
        return withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = Environment.DIRECTORY_DOWNLOADS + "/AgentClaw/"
                val projection = arrayOf(MediaStore.Downloads._ID)
                val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
                val selectionArgs = arrayOf(fileName, relativePath)
                appContext.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        return@withContext DownloadedGeneratedFile(
                            uriOrPath = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()).toString(),
                            mimeType = mimeType,
                            downloadedNow = false
                        )
                    }
                }
                null
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(File(downloadDir, "AgentClaw"), fileName)
                if (targetFile.exists() && targetFile.length() > 0L) {
                    DownloadedGeneratedFile(targetFile.absolutePath, mimeType, downloadedNow = false)
                } else {
                    null
                }
            }
        }
    }

    private fun openDownloadedGeneratedFile(file: DownloadedGeneratedFile) {
        val uri = if (file.uriOrPath.startsWith("content://")) {
            Uri.parse(file.uriOrPath)
        } else {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                File(file.uriOrPath)
            )
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!tryStartActivity(intent)) {
            showExportedFilesDialog()
        }
    }

    /**
     * 人工智能生成合成内容文件元数据隐式标识（GB 45438-2025）。
     *
     * 这里下载下来的图片/视频很多情况下已经是上游模型（比如智谱 GLM）服务端打好水印的，
     * 这一步只是兜底——上游没打、或者以后换了别的不打水印的来源时，本地也补一层。
     * 按真实文件头判断格式（GLM 返回的有些是 jpg 但文件名是 .png，不能只看后缀/contentType），
     * 既不是 PNG 也不是 MP4 就原样返回，不影响下载。
     */
    private fun applyAigcIdentification(fileName: String, bytes: ByteArray): ByteArray {
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val isPng = bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(pngSignature)
        val isMp4 = !isPng && bytes.size > 8 &&
            String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
        if (!isPng && !isMp4) return bytes
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes).joinToString("") { b -> "%02x".format(b) }.take(10)
            val produceId = "agentclaw_artifact_$hash"
            val propagateId = "agentclaw_msg_$hash"
            if (isPng) {
                AigcMetadataWriter.embedPng(bytes, produceId = produceId, propagateId = propagateId)
            } else {
                AigcMetadataWriter.embedMp4(bytes, produceId = produceId, propagateId = propagateId)
            }
        }.getOrElse { error ->
            Logger.e(TAG, "applyAigcIdentification failed fileName=$fileName, err=${error.message}")
            bytes
        }
    }

    private fun writeFileToDownloads(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        mimeType: String
    ): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/AgentClaw/"
            val existingUri = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(fileName, relativePath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString())
                } else null
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
            val uri = existingUri ?: context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Unable to create generated file in Downloads")
            if (existingUri != null) {
                context.contentResolver.update(uri, values, null, null)
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(bytes)
            } ?: error("Unable to write generated file")
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadDir, "AgentClaw").apply { mkdirs() }
            val targetFile = File(targetDir, fileName)
            targetFile.writeBytes(bytes)
            targetFile.absolutePath
        }
    }

    private fun buildGeneratedFileName(
        fileUrl: String,
        contentType: String,
        defaultPrefix: String
    ): String {
        val pathName = runCatching {
            URLDecoder.decode(Uri.parse(fileUrl).lastPathSegment.orEmpty(), "UTF-8")
        }.getOrDefault("")
            .substringBefore('?')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()

        if (pathName.isNotBlank() && pathName.contains('.')) {
            return pathName
        }
        val urlHash = Integer.toHexString(fileUrl.hashCode()).takeLast(8)
        val base = pathName.takeIf { it.isNotBlank() } ?: defaultPrefix
        return "$base-$urlHash.${extensionFor(contentType)}"
    }

    private fun extensionFor(contentType: String): String {
        val normalized = contentType.substringBefore(';').trim().lowercase(Locale.US)
        return when (normalized) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/x-m4v" -> "m4v"
            "video/webm" -> "webm"
            "application/vnd.apple.mpegurl", "application/x-mpegurl" -> "m3u8"
            else -> "png"
        }
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/png"
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

    private fun visibleItems(messages: List<ai.cjym.agentclaw.ui.chat.ChatMessageItem>): List<ai.cjym.agentclaw.ui.chat.ChatMessageItem> {
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

    private fun updateComposerHint(entryMode: ChatEntryMode) {
        binding.composerInput.hint = when (entryMode) {
            ChatEntryMode.IMAGE -> getString(R.string.chat_shell_input_hint_image)
            ChatEntryMode.VIDEO -> getString(R.string.chat_shell_input_hint_video)
            ChatEntryMode.DEFAULT -> getString(R.string.chat_shell_input_hint)
        }
    }

    private fun updateEntryModeButtons(entryMode: ChatEntryMode) {
        currentEntryMode = entryMode
        renderEntryModeButton(
            button = binding.imageChatEntryButton,
            selected = entryMode == ChatEntryMode.IMAGE,
            startDrawableRes = R.drawable.ic_chat_entry_image
        )
        renderEntryModeButton(
            button = binding.videoChatEntryButton,
            selected = entryMode == ChatEntryMode.VIDEO,
            startDrawableRes = R.drawable.ic_chat_entry_video
        )
        // 纯文本模式下隐藏图片附件按钮
        binding.attachButton.visibility = if (entryMode == ChatEntryMode.DEFAULT)
            android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun renderEntryModeButton(
        button: android.widget.TextView,
        selected: Boolean,
        startDrawableRes: Int
    ) {
        val context = button.context
        val tintColor = if (selected) 0xFF3978F3.toInt() else 0xFF111111.toInt()
        button.isSelected = selected
        button.background = AppCompatResources.getDrawable(
            context,
            if (selected) R.drawable.bg_chat_entry_button_selected else R.drawable.bg_chat_entry_button
        )
        button.setTextColor(tintColor)
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(
            startDrawableRes,
            0,
            0,
            0
        )
        TextViewCompat.setCompoundDrawableTintList(
            button,
            ColorStateList.valueOf(tintColor)
        )
    }

    private fun refreshComposerActionButton(state: ChatScreenState = chatViewModel.state.value) {
        val isInputBlank = binding.composerInput.text?.toString()?.trim().isNullOrEmpty()
        val hasContent = !isInputBlank || pendingImageUri != null
        binding.stopButton.visibility = if (state.isGenerating) android.view.View.VISIBLE else android.view.View.GONE
        binding.sendButton.visibility = if (state.isGenerating) android.view.View.GONE else android.view.View.VISIBLE
        binding.sendButton.isEnabled = state.canSend
        binding.sendButton.setImageResource(
            if (!hasContent) R.drawable.ic_chat_unsend else R.drawable.ic_chat_send
        )
        binding.sendButton.imageTintList = ColorStateList.valueOf(
            Color.parseColor(if (!hasContent) "#666666" else "#111111")
        )
    }

    private fun showImagePickerSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = LayoutInflater.from(requireContext())
            .inflate(R.layout.bottom_sheet_image_picker, null)
        sheetView.findViewById<View>(R.id.optionCamera).setOnClickListener {
            dialog.dismiss()
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPermission.launch(android.Manifest.permission.CAMERA)
            }
        }
        sheetView.findViewById<View>(R.id.optionGallery).setOnClickListener {
            dialog.dismiss()
            galleryLauncher.launch("image/*")
        }
        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun launchCamera() {
        val cacheDir = File(requireContext().cacheDir, "camera").apply { mkdirs() }
        val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            photoFile
        )
        cameraImageUri = uri
        cameraLauncher.launch(uri)
    }

    private fun setComposerImage(uri: Uri) {
        pendingImageUri = uri
        binding.imageThumbnail.setImageURI(uri)
        binding.imageThumbnailRow.visibility = View.VISIBLE
        refreshComposerActionButton()
    }

    private fun clearComposerImage() {
        pendingImageUri = null
        cameraImageUri = null
        binding.imageThumbnail.setImageDrawable(null)
        binding.imageThumbnailRow.visibility = View.GONE
        refreshComposerActionButton()
    }

    private suspend fun encodeImageToBase64(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                    ?: return@runCatching null
                val original = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (original == null) return@runCatching null
                val maxDim = 1024
                val scaled = if (original.width > maxDim || original.height > maxDim) {
                    val ratio = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height)
                    Bitmap.createScaledBitmap(
                        original,
                        (original.width * ratio).toInt(),
                        (original.height * ratio).toInt(),
                        true
                    )
                } else original
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()
        }
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
