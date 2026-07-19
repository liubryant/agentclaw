package ai.cjym.agentclaw.ui.avatar

import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.VipActivity
import ai.cjym.agentclaw.data.repository.ChatService
import ai.cjym.agentclaw.data.repository.ChatRepository
import ai.cjym.agentclaw.databinding.FragmentAvatarBinding
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.domain.model.ChatMessage
import ai.cjym.agentclaw.domain.model.ChatRole
import ai.cjym.agentclaw.ui.widget.AgentStatusRotator
import ai.cjym.agentclaw.ui.vip.VipUpgradeBottomSheet
import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.loader.ModelInfo
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Spannable
import android.text.SpannableString
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

class AvatarFragment : Fragment() {
    private var _binding: FragmentAvatarBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var duix: DUIX? = null
    private var renderer: DUIXRenderer? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var speechController: LilySpeechController? = null
    private var requestJob: Job? = null
    private var statusRotator: AgentStatusRotator? = null
    private var activeAssistantView: TextView? = null
    private var activeAssistantText = ""
    private var greetingMotionName: String? = null
    private var greetingIndex = 0
    private var isAvatarSpeaking = false
    private val history = mutableListOf<ChatMessage>()
    private val chatService by lazy { ChatService(requireContext().applicationContext) }
    private val chatRepository by lazy { ChatRepository(requireContext().applicationContext) }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else toast("需要麦克风权限才能语音对话")
    }
    private val systemAsrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        stopListeningUi()
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (text.isNotBlank()) submit(text)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentAvatarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        applyGradientTitle()
        applyStatusBarInset()
        bindComposerKeyboardInsets()
        statusRotator = AgentStatusRotator(binding.thinkingContainer, binding.thinkingText, viewLifecycleOwner.lifecycleScope)
        binding.messageInput.isEnabled = false
        binding.micButton.isEnabled = false
        binding.sendButton.isEnabled = false
        restoreConversation()
        binding.messageInput.addTextChangedListener(SimpleTextWatcher { binding.sendButton.isEnabled = it.trim().isNotEmpty() })
        binding.sendButton.setOnClickListener { sendInput(hideKeyboard = true) }
        binding.stopCurrentSession.setOnClickListener {
            requestJob?.cancel()
            speechController?.stop()
            highlightSentence(null)
            setSpeaking(false)
        }
        binding.messageInput.setOnEditorActionListener { _, action, event ->
            val confirmed = action == EditorInfo.IME_ACTION_SEND ||
                action == EditorInfo.IME_ACTION_DONE ||
                action == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP)
            if (confirmed) { sendInput(hideKeyboard = true); true } else false
        }
        binding.micButton.setOnClickListener {
            if (isListening) {
                stopAsr()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.avatarStage.setOnLongClickListener {
            playAvatarGreeting()
            true
        }
        binding.avatarVipCard.setOnClickListener {
            startActivity(VipActivity.createIntent(requireContext()))
        }
        speechController = LilySpeechController(requireContext().applicationContext, { duix }, ::highlightSentence, ::setSpeaking)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.modelLoadingText.text = "AI 数字人需要 Android 10 或更高版本"
        } else installAndStartDuix()
    }

    private fun restoreConversation() {
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.ensureSession(AVATAR_SESSION_ID, AVATAR_SESSION_TITLE)
            val savedMessages = chatRepository.loadMessages(AVATAR_SESSION_ID)
                .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
            history.clear()
            history.addAll(savedMessages)
            if (_binding == null) return@launch
            if (savedMessages.isEmpty()) {
                addMessage(WELCOME_MESSAGE, false)
            } else {
                savedMessages.forEach { addMessage(it.content, it.role == ChatRole.USER, scroll = false) }
                binding.messageScroll.post { binding.messageScroll.scrollTo(0, binding.messageList.height) }
            }
            binding.messageInput.isEnabled = true
            binding.micButton.isEnabled = true
            binding.sendButton.isEnabled = binding.messageInput.text?.isNotBlank() == true
        }
    }

    private fun installAndStartDuix() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                DuixAssetInstaller.install(requireContext()) { progress ->
                    _binding?.root?.post { if (_binding != null) binding.modelLoadingText.text = "首次准备 Lily… $progress%" }
                }
            }.onSuccess { initializeDuix() }
                .onFailure { showModelError("Lily 资源准备失败：${it.message}") }
        }
    }

    private fun initializeDuix() {
        renderer = DUIXRenderer(requireContext(), binding.avatarTexture)
        binding.avatarTexture.setEGLContextClientVersion(2)
        binding.avatarTexture.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.avatarTexture.isOpaque = false
        binding.avatarTexture.preserveEGLContextOnPause = true
        binding.avatarTexture.setRenderer(renderer)
        binding.avatarTexture.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        binding.avatarTexture.visibility = View.VISIBLE
        duix = DUIX(requireContext().applicationContext, "Lily", renderer) { event, message, info ->
            _binding?.root?.post {
                if (_binding == null) return@post
                when (event) {
                    Constant.CALLBACK_EVENT_INIT_READY -> {
                        greetingMotionName = findGreetingMotion(info as? ModelInfo)
                        binding.modelLoading.visibility = View.GONE
                        binding.avatarPoster.visibility = View.GONE
                    }
                    Constant.CALLBACK_EVENT_INIT_ERROR -> showModelError("Lily 初始化失败：$message")
                }
            }
        }
        duix?.init()
    }

    private fun findGreetingMotion(modelInfo: ModelInfo?): String? {
        val motionNames = modelInfo?.motionRegions
            ?.mapNotNull { it.name?.trim() }
            .orEmpty()
        val greetingKeywords = listOf(
            "greeting", "wave", "waving", "hello", "welcome",
            "招手", "挥手", "打招呼", "问候", "欢迎"
        )
        val smileKeywords = listOf("微笑", "笑", "开心", "喜悦", "smile", "happy", "joy")
        return motionNames.firstOrNull { name ->
            greetingKeywords.any { name.contains(it, ignoreCase = true) }
        } ?: motionNames.firstOrNull { name ->
            smileKeywords.any { name.contains(it, ignoreCase = true) }
        }
    }

    private fun playAvatarGreeting() {
        if (requestJob?.isActive == true || isListening || isAvatarSpeaking) {
            toast("对话进行中，请稍候")
            return
        }
        if (duix == null) {
            toast("Lily 正在准备，请稍候")
            return
        }
        speechController?.stop()
        greetingMotionName?.let { duix?.startMotion(it, true) }
            ?: duix?.startRandomMotion(true)
        activeAssistantView = null
        activeAssistantText = ""
        val greeting = AVATAR_GREETINGS[greetingIndex % AVATAR_GREETINGS.size]
        greetingIndex = (greetingIndex + 1) % AVATAR_GREETINGS.size
        viewLifecycleOwner.lifecycleScope.launch {
            speechController?.speak(greeting, lipLeadMs = SPEECH_LIP_LEAD_MS)
        }
    }

    private fun sendInput(hideKeyboard: Boolean = false) {
        val text = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || requestJob?.isActive == true) return
        if (hideKeyboard) {
            (requireContext().getSystemService(InputMethodManager::class.java))
                ?.hideSoftInputFromWindow(binding.messageInput.windowToken, 0)
            binding.messageInput.clearFocus()
        }
        binding.messageInput.setText("")
        submit(text)
    }

    private fun submit(text: String) {
        val preferences = AppGraph.preferences
        if (!preferences.isVipActive && preferences.freeAgentQuestionCount >= FREE_AGENT_QUESTION_LIMIT) {
            if (parentFragmentManager.findFragmentByTag(AGENT_VIP_DIALOG_TAG) == null) {
                VipUpgradeBottomSheet.forAgentChat()
                    .show(parentFragmentManager, AGENT_VIP_DIALOG_TAG)
            }
            return
        }
        if (!preferences.isVipActive) {
            preferences.freeAgentQuestionCount += 1
        }
        speechController?.stop()
        addMessage(text, true)
        val userMessage = message(ChatRole.USER, text)
        history += userMessage
        setThinking(true)
        requestJob = viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.insertMessage(userMessage)
            chatRepository.touchSession(AVATAR_SESSION_ID)
            val context = listOf(message(ChatRole.SYSTEM, SYSTEM_PROMPT)) + history.takeLast(12)
            val answer = StringBuilder()
            chatService.sendMessageStream(context)
                .catch { error ->
                    setThinking(false)
                    addMessage("暂时无法回答：${error.message ?: "网络异常"}", false)
                }
                .collect { chunk -> answer.append(chunk) }
            if (answer.isNotBlank()) {
                setThinking(false)
                val reply = answer.toString().trim()
                val assistantMessage = message(ChatRole.ASSISTANT, reply)
                history += assistantMessage
                chatRepository.insertMessage(assistantMessage)
                chatRepository.touchSession(AVATAR_SESSION_ID)
                activeAssistantText = reply
                activeAssistantView = addMessage(reply, false)
                speechController?.speak(reply, lipLeadMs = SPEECH_LIP_LEAD_MS)
            }
        }
    }

    private fun setThinking(active: Boolean) {
        if (active) {
            binding.messageList.removeView(binding.thinkingContainer)
            binding.messageList.addView(binding.thinkingContainer)
            statusRotator?.show(R.array.chat_agent_thinking_statuses)
        } else {
            statusRotator?.hide()
        }
        binding.voiceWave.setActive(active)
        binding.waveContainer.visibility = if (active) View.VISIBLE else View.GONE
        updateGreetingAvailability()
    }

    private fun setSpeaking(speaking: Boolean) {
        isAvatarSpeaking = speaking
        _binding?.root?.post {
            if (_binding == null) return@post
            binding.voiceWave.setActive(speaking)
            binding.voiceWave.setListening(false)
            binding.waveContainer.visibility = if (speaking) View.VISIBLE else View.GONE
            binding.stopCurrentSession.visibility = if (speaking) View.VISIBLE else View.GONE
            updateGreetingAvailability()
        }
    }

    private fun highlightSentence(range: IntRange?) {
        _binding?.root?.post {
            val textView = activeAssistantView ?: return@post
            if (range == null) {
                textView.text = activeAssistantText
            } else {
                val start = range.first.coerceIn(0, activeAssistantText.length)
                val end = (range.last + 1).coerceIn(start, activeAssistantText.length)
                val spannable = SpannableString(activeAssistantText)
                if (end > start) {
                    spannable.setSpan(
                        RoundedHighlightSpan(Color.rgb(226, 235, 255), dp(6f), dp(3f)),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                textView.text = spannable
                binding.messageScroll.post {
                    val line = textView.layout?.getLineForOffset(start) ?: 0
                    val sentenceY = textView.top + (textView.layout?.getLineTop(line) ?: 0)
                    val target = (sentenceY - binding.messageScroll.height / 2).coerceAtLeast(0)
                    binding.messageScroll.smoothScrollTo(0, target)
                }
            }
        }
    }

    private fun startListening() {
        val recognitionService = findRecognitionService()
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(requireContext()) }.getOrDefault(false) ||
            recognitionService != null
        if (!available) { launchSystemRecognizer(); return }
        speechController?.stop()
        stopAsr(updateUi = false)
        val recognizer = runCatching {
            if (recognitionService != null) {
                SpeechRecognizer.createSpeechRecognizer(requireContext().applicationContext, recognitionService)
            } else {
                SpeechRecognizer.createSpeechRecognizer(requireContext().applicationContext)
            }
        }
            .getOrElse { launchSystemRecognizer(); return }
        speechRecognizer = recognizer
        recognizer.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { showListening("请开始说话") }
                override fun onBeginningOfSpeech() { showListening("正在聆听…") }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { showListening("正在识别…") }
                override fun onError(error: Int) {
                    stopAsr()
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> toast("没有听清，请再试一次")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> toast("请允许麦克风权限后重试")
                        else -> toast("语音识别暂时不可用，请重试")
                    }
                }
                override fun onResults(results: Bundle?) {
                    stopAsr()
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) submit(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (_binding == null) return
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { binding.messageInput.setText(it) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        val intent = recognitionIntent()
        runCatching { recognizer.startListening(intent) }
            .onSuccess { showListening("正在准备聆听…") }
            .onFailure { stopAsr(); launchSystemRecognizer() }
    }

    private fun findRecognitionService(): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        return requireContext().packageManager.queryIntentServices(intent, 0)
            .firstOrNull()
            ?.serviceInfo
            ?.let { ComponentName(it.packageName, it.name) }
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
    }

    private fun launchSystemRecognizer() {
        val intent = recognitionIntent()
        if (intent.resolveActivity(requireContext().packageManager) == null) {
            toast("请安装或启用系统语音识别服务")
            stopListeningUi()
            return
        }
        runCatching { systemAsrLauncher.launch(intent) }
            .onFailure { toast("无法启动系统语音识别"); stopListeningUi() }
    }

    private fun stopAsr(updateUi: Boolean = true) {
        isListening = false
        val recognizer = speechRecognizer
        speechRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        if (updateUi && _binding != null) stopListeningUi()
    }

    private fun showListening(text: String) {
        isListening = true
        binding.voiceWave.setListening(true)
        binding.voiceWave.setActive(true)
        binding.waveContainer.visibility = View.VISIBLE
        updateGreetingAvailability()
    }

    private fun stopListeningUi() {
        isListening = false
        binding.voiceWave.setActive(false)
        binding.voiceWave.setListening(false)
        binding.waveContainer.visibility = View.GONE
        updateGreetingAvailability()
    }

    private fun updateGreetingAvailability() {
        if (_binding == null) return
        val available = requestJob?.isActive != true && !isListening && !isAvatarSpeaking
        binding.avatarGreetingHint.alpha = if (available) 1f else 0.45f
    }

    private fun addMessage(text: String, user: Boolean, scroll: Boolean = true): TextView {
        val view = TextView(requireContext()).apply {
            this.text = text
            setTextIsSelectable(true)
            setTextColor(Color.rgb(35, 38, 50))
            textSize = 16f
            setLineSpacing(dp(3f), 1f)
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            setBackgroundResource(if (user) R.drawable.bg_avatar_message_user else R.drawable.bg_avatar_message_assistant)
            setOnLongClickListener {
                showMessageActions(this, text)
                true
            }
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (user) Gravity.END else Gravity.START
            topMargin = dp(8f).toInt()
            marginStart = if (user) dp(48f).toInt() else 0
            marginEnd = if (user) 0 else dp(28f).toInt()
        }
        val indicatorIndex = binding.messageList.indexOfChild(binding.thinkingContainer).coerceAtLeast(0)
        binding.messageList.addView(view, indicatorIndex, params)
        if (scroll) binding.messageScroll.post { binding.messageScroll.smoothScrollTo(0, binding.messageList.height) }
        return view
    }

    private fun showMessageActions(anchor: TextView, text: String) {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f)
                setColor(Color.WHITE)
                setStroke(dp(1f).toInt(), Color.rgb(232, 234, 241))
            }
        }
        val popup = PopupWindow(content, dp(164f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8f)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        fun action(textValue: String, iconRes: Int, onClick: () -> Unit) = TextView(context).apply {
            this.text = textValue
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(42, 44, 54))
            setPadding(dp(14f).toInt(), dp(11f).toInt(), dp(12f).toInt(), dp(11f).toInt())
            compoundDrawablePadding = dp(10f).toInt()
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Color.rgb(91, 101, 128))
            setOnClickListener { popup.dismiss(); onClick() }
        }
        content.addView(action("复制", R.drawable.ic_avatar_action_copy) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("智能体对话", text))
            toast("已复制全文")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(action("朗读全文", R.drawable.ic_avatar_action_speak) {
            requestJob?.cancel()
            speechController?.stop()
            activeAssistantText = text
            activeAssistantView = anchor
            viewLifecycleOwner.lifecycleScope.launch {
                speechController?.speak(text, lipLeadMs = SPEECH_LIP_LEAD_MS)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        popup.showAsDropDown(anchor, 0, -anchor.height)
    }

    private fun applyGradientTitle() {
        binding.avatarTitle.post {
            binding.avatarTitle.paint.shader = LinearGradient(
                0f, 0f, binding.avatarTitle.width.toFloat(), 0f,
                intArrayOf(Color.rgb(140, 224, 255), Color.rgb(194, 158, 255), Color.rgb(255, 173, 224)), null, Shader.TileMode.CLAMP
            )
            binding.avatarTitle.invalidate()
        }
    }

    private fun applyStatusBarInset() {
        val initialTop = dp(48f).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (binding.avatarHeader.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = top + initialTop
                binding.avatarHeader.layoutParams = this
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun bindComposerKeyboardInsets() {
        val root = binding.root
        val baseBottomPadding = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBarsBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // This fragment already ends above ShellActivity's 68dp bottom navigation.
            // Apply only the part of the keyboard that overlaps the fragment.
            val keyboardOverlap = (imeBottom - dp(SHELL_BOTTOM_NAV_HEIGHT_DP).toInt()).coerceAtLeast(0)
            val bottomInset = if (imeVisible) maxOf(systemBarsBottom, keyboardOverlap) else systemBarsBottom
            view.updatePadding(bottom = baseBottomPadding + bottomInset)
            if (imeVisible) {
                binding.messageScroll.scrollTo(0, binding.messageList.height)
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showModelError(message: String) { binding.modelLoading.visibility = View.VISIBLE; binding.modelLoadingText.text = message }
    private fun message(role: ChatRole, content: String) = ChatMessage(UUID.randomUUID().toString(), AVATAR_SESSION_ID, role, content, System.currentTimeMillis())
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun toast(value: String) = Toast.makeText(requireContext(), value, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        requestJob?.cancel()
        statusRotator?.hide()
        statusRotator = null
        stopAsr(updateUi = false)
        speechController?.release()
        speechController = null
        duix?.release()
        duix = null
        renderer = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val SHELL_BOTTOM_NAV_HEIGHT_DP = 68f
        const val AVATAR_SESSION_ID = "avatar-sofia"
        const val AVATAR_SESSION_TITLE = "AI数字人 Lily"
        const val WELCOME_MESSAGE = "你好，我是你的 AI 数字人 Lily。你可以直接说话，也可以输入文字。"
        val AVATAR_GREETINGS = listOf(
            "你好，我是 Lily，很高兴认识你。",
            "嗨，见到你真开心，今天想聊点什么？",
            "你好呀，我一直在这里等你。",
            "欢迎回来，我是你的数字人伙伴 Lily。",
            "很高兴和你见面，有什么可以帮你的吗？",
            "嗨，我是 Lily，愿你今天有个好心情。",
            "你好，轻松一点，我们慢慢聊。",
            "见到你真好，我已经准备好听你说啦。",
            "你好呀，今天也让我陪在你身边吧。",
            "嗨，朋友，又到了我们打招呼的时间。"
        )
        const val SYSTEM_PROMPT = "你是 AgentClaw 中的 AI 数字人 Lily。请用自然、友好、简洁的中文直接回答用户，优先帮助用户完成任务。不要使用 Markdown 表格，朗读内容应口语化。"
        const val SPEECH_LIP_LEAD_MS = 400
        const val FREE_AGENT_QUESTION_LIMIT = 3
        const val AGENT_VIP_DIALOG_TAG = "agent_vip_upgrade"
    }
}

private class SimpleTextWatcher(private val changed: (CharSequence) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed(s ?: "")
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
