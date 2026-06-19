package ai.cjym.agentclaw.ui.shell

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.core_common.ui.dialog.CommonMessageDialog
import ai.inmo.core_common.utils.Logger
import ai.inmo.core_common.utils.WifiNetworkMonitor
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.SettingPanelController
import ai.cjym.agentclaw.data.remote.api.LoginVerificationCodeRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.databinding.ActivityShellBinding
import ai.cjym.agentclaw.databinding.DialogLoginBinding
import ai.cjym.agentclaw.ui.chat.ChatScreenState
import ai.cjym.agentclaw.ui.chat.ChatSessionAdapter
import ai.cjym.agentclaw.ui.chat.ChatSessionItem
import ai.cjym.agentclaw.ui.chat.ChatSessionListItem
import ai.cjym.agentclaw.ui.fancyideas.FancyIdeasFragment
import ai.cjym.agentclaw.ui.search.ChatSearchActivity
import ai.cjym.agentclaw.ui.shell.chat.ChatFragment
import ai.cjym.agentclaw.ui.shell.schedule.ScheduleFragment
import android.app.Dialog
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.provider.DocumentsContract
import android.view.MotionEvent
import android.os.Build
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import ai.cjym.agentclaw.constants.AppConstants
import ai.cjym.agentclaw.ui.legal.LegalWebActivity
import ai.cjym.agentclaw.util.toSpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class ShellActivity : BaseBindingActivity<ActivityShellBinding>(ActivityShellBinding::inflate),
    ShellChromeController,
    WifiNetworkMonitor.Listener {
    companion object {
        private const val TAG = "ShellActivity"
        const val EXTRA_INITIAL_CHAT_DRAFT = "extra_initial_chat_draft"
        const val EXTRA_TARGET_SESSION_KEY = "extra_target_session_key"
        const val EXTRA_TARGET_MESSAGE_INDEX = "extra_target_message_index"
    }

    private val shellViewModel: ShellSharedViewModel by viewModels()
    private val chatViewModel: ShellChatViewModel by viewModels()
    private val sessionAdapter = ChatSessionAdapter().apply {
        sessionActionMode = ChatSessionAdapter.SessionActionMode.DELETE_ALWAYS
    }
    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val sessionKey = result.data
            ?.getStringExtra(ChatSearchActivity.EXTRA_RESULT_SESSION_KEY)
            ?.trim()
            .orEmpty()
        if (result.resultCode == RESULT_OK && sessionKey.isNotBlank()) {
            handleSessionClick(sessionKey)
        }
    }

    private var latestChatState = ChatScreenState()
    private var latestShellState = ShellUiState()
    private var sessionActionPopup: ShellSessionActionPopupWindow? = null
    private var settingPopupWindow: PopupWindow? = null
    private var settingPanelController: SettingPanelController? = null
    private var previousSoftInputMode: Int? = null
    private var hoverTooltipPopup: ShellHoverTooltipPopupWindow? = null
    private var suppressedHoverTooltipView: View? = null
    private var activeLoginDialog: Dialog? = null

    override fun initData() {
        chatViewModel.start()
        shellViewModel.loadTokenUsage()
    }

    override fun initView() {
        setSidebarVisible(AppGraph.preferences.sidebarVisible)
        updateShellTopNoticeVisibility(isVisible = false)
        binding.sidebarSessionsRecycler.layoutManager = LinearLayoutManager(this)
        binding.sidebarSessionsRecycler.adapter = sessionAdapter
        binding.sidebarSessionsRecycler.itemAnimator = null

        if (supportFragmentManager.findFragmentByTag(ShellDestination.CHAT.name) == null) {
            val chatFragment = ChatFragment()
            val ideasFragment = FancyIdeasFragment()
            val scheduleFragment = ScheduleFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.shellFragmentContainer, chatFragment, ShellDestination.CHAT.name)
                .add(R.id.shellFragmentContainer, ideasFragment, ShellDestination.IDEAS.name)
                .hide(ideasFragment)
                .add(R.id.shellFragmentContainer, scheduleFragment, ShellDestination.SCHEDULE.name)
                .hide(scheduleFragment)
                .commitNow()
        }

        lifecycleScope.launch {
            shellViewModel.uiState.collectLatest { state ->
                latestShellState = state
                renderShell(state)
                renderSidebar(latestChatState, state)
                showDestination(state.currentDestination)
            }
        }
        lifecycleScope.launch {
            chatViewModel.state.collectLatest { state ->
                latestChatState = state
                renderSidebar(state, latestShellState)
            }
        }

        if (shellViewModel.uiState.value.topBarTitle.isBlank()) {
            navigateTo(ShellDestination.CHAT)
        }
        handleInitialDraftFromIntent()
        handleSearchNavigationIntent(intent)
    }

    override fun initEvent() {
        binding.sidebarButton.setOnClickListener {
            dismissHoverTooltip()
            suppressHoverTooltip(binding.sidebarButton)
            toggleSidebar()
        }
        binding.newConversationButton.setOnClickListener {
            handleCreateSessionClick()
        }
        binding.headerSettingButton.setOnClickListener {
            shellViewModel.requestClearChatComposerFocus()
            showSettingDialogPopup()
        }

        lifecycleScope.launch {
            chatViewModel.exportMessages.collectLatest { message ->
                Toast.makeText(this@ShellActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        binding.navIdeas.setOnClickListener { navigateTo(ShellDestination.IDEAS) }
//        binding.navSchedule.setOnClickListener { navigateTo(ShellDestination.SCHEDULE) }
        binding.navAgentclawDocs.setOnClickListener {
            shellViewModel.requestClearChatComposerFocus()
            showExportedFilesDialog()
        }
        binding.navLogin.setOnClickListener {
            shellViewModel.requestClearChatComposerFocus()
            showLoginDialog()
        }
        binding.navSetting.setOnClickListener {
            shellViewModel.requestClearChatComposerFocus()
            showSettingDialogPopup()
        }
        binding.newChatButton.setOnClickListener {
            handleCreateSessionClick()
        }
        binding.sidebarSearchInput.setOnClickListener {
            searchLauncher.launch(Intent(this, ChatSearchActivity::class.java))
        }
        sessionAdapter.onSessionClick = { session -> handleSessionClick(session.id) }
        sessionAdapter.onDeleteClick = { session ->
            showDeleteSessionConfirm(session)
        }
        sessionAdapter.onMoreClick = { session, anchor ->
            showSessionActionPopup(anchor, session)
        }
        bindHeaderHoverTooltip(
            view = binding.sidebarButton,
            textProvider = ::resolveSidebarTooltipText
        )
        bindHeaderHoverTooltip(
            view = binding.newConversationButton,
            textProvider = { getString(R.string.chat_new) }
        )
        bindHeaderHoverTooltip(
            view = binding.headerSettingButton,
            textProvider = { getString(R.string.settings_title) }
        )
    }

    private fun handleSessionClick(targetSessionId: String) {
        val isCurrentSession = targetSessionId == latestChatState.selectedSessionId
        val isChatDestination = latestShellState.currentDestination == ShellDestination.CHAT

        if (!isChatDestination) {
            navigateTo(ShellDestination.CHAT)
        }
        if (isCurrentSession) return

        executeSessionActionWithConfirm(
            shouldConfirm = chatViewModel.shouldConfirmSwitch(targetSessionId),
            onConfirmed = { abortCurrent -> switchToSession(targetSessionId, abortCurrent) }
        )
    }

    private fun handleCreateSessionClick() {
        executeSessionActionWithConfirm(
            shouldConfirm = latestChatState.isGenerating,
            onConfirmed = { abortCurrent -> createSession(abortCurrent) }
        )
    }

    private fun executeSessionActionWithConfirm(
        shouldConfirm: Boolean,
        onConfirmed: (abortCurrent: Boolean) -> Unit
    ) {
        if (!shouldConfirm) {
            onConfirmed(false)
            return
        }

        CommonMessageDialog.createMessageConfirm(
            context = this,
            title = getString(R.string.chat_switch_title),
            message = getString(R.string.chat_switch_message),
            positiveText = getString(R.string.chat_switch_confirm),
            negativeText = getString(R.string.chat_switch_cancel),
            onPositive = { onConfirmed(true) }
        ).show()
    }

    private fun switchToSession(sessionId: String, abortCurrent: Boolean) {
        navigateTo(ShellDestination.CHAT)
        chatViewModel.switchSession(sessionId, abortCurrent = abortCurrent)
    }

    private fun createSession(abortCurrent: Boolean) {
        if (shellViewModel.uiState.value.currentDestination != ShellDestination.CHAT) {
            navigateTo(ShellDestination.CHAT)
        }
        chatViewModel.createSession(abortCurrent = abortCurrent)
    }

    private fun showSessionActionPopup(anchor: View, session: ChatSessionItem) {
        sessionActionPopup?.dismiss()
        val popupWindow = ShellSessionActionPopupWindow(this).apply {
            onDeleteClick = {
                showDeleteSessionConfirm(session)
            }
        }
        popupWindow.setOnDismissListener {
            if (sessionActionPopup === popupWindow) {
                sessionActionPopup = null
            }
        }
        popupWindow.show(anchor)
        sessionActionPopup = popupWindow
    }

    private fun showDeleteSessionConfirm(session: ChatSessionItem) {
        CommonMessageDialog.createMessageConfirm(
            context = this,
            title = getString(R.string.shell_delete_confirm_title),
            message = getString(R.string.shell_delete_confirm_message),
            positiveText = getString(R.string.shell_delete_confirm_positive),
            negativeText = getString(R.string.shell_delete_confirm_negative),
            onPositive = {
                shellViewModel.removeSessionDraft(session.id)
                shellViewModel.removeSessionEntryMode(session.id)
                chatViewModel.removeSessionEntryMode(session.id)
                chatViewModel.deleteSession(session.id)
            }
        ).show()
    }
    private fun navigateTo(destination: ShellDestination) {
        val titleRes = when (destination) {
            ShellDestination.CHAT -> R.string.chat_title
            ShellDestination.IDEAS -> R.string.ideas_title
            ShellDestination.SCHEDULE -> R.string.schedule_title
        }
        val subtitleRes = when (destination) {
            ShellDestination.CHAT -> R.string.chat_shell_subtitle
            ShellDestination.IDEAS -> R.string.ideas_subtitle
            ShellDestination.SCHEDULE -> R.string.schedule_subtitle
        }
        shellViewModel.setDestination(destination, getString(titleRes), getString(subtitleRes))
    }

    private fun renderShell(state: ShellUiState) {
        //binding.topBarTitle.text = state.topBarTitle
        //binding.topBarSubtitle.text = state.topBarSubtitle
        binding.navIdeas.isSelected = state.currentDestination == ShellDestination.IDEAS
//        binding.navSchedule.isSelected = state.currentDestination == ShellDestination.SCHEDULE
        val isChatDestination = state.currentDestination == ShellDestination.CHAT
        if (!isChatDestination) {
            setSidebarVisible(true)
        }
        setTopBarVisible(!isChatDestination)
        if (state.usageText.isNotBlank()) {
            binding.usageChip.visibility = View.VISIBLE
            binding.usageText.text = state.usageText
        }
    }

    private fun renderSidebar(chatState: ChatScreenState, shellState: ShellUiState) {
        val sessions = chatState.sessions
        sessionAdapter.submitList(buildSessionListItems(sessions), chatState.selectedSessionId)
        binding.sidebarEmptyView.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildSessionListItems(sessions: List<ChatSessionItem>): List<ChatSessionListItem> {
        if (sessions.isEmpty()) return emptyList()

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monthStart = today.withDayOfMonth(1)
        val grouped = linkedMapOf(
            R.string.chat_sessions_today to mutableListOf<ChatSessionItem>(),
            R.string.chat_sessions_this_week to mutableListOf<ChatSessionItem>(),
            R.string.chat_sessions_this_month to mutableListOf<ChatSessionItem>(),
            R.string.chat_sessions_earlier to mutableListOf<ChatSessionItem>()
        )

        sessions.forEach { session ->
            val updatedDate = Instant.ofEpochMilli(session.updatedAt)
                .atZone(zoneId)
                .toLocalDate()
            val section = when {
                updatedDate == today -> R.string.chat_sessions_today
                !updatedDate.isBefore(weekStart) -> R.string.chat_sessions_this_week
                !updatedDate.isBefore(monthStart) -> R.string.chat_sessions_this_month
                else -> R.string.chat_sessions_earlier
            }
            grouped.getValue(section).add(session)
        }

        return buildList {
            grouped.forEach { (titleRes, items) ->
                if (items.isEmpty()) return@forEach
                add(ChatSessionListItem.Header(getString(titleRes)))
                items.forEach { add(ChatSessionListItem.Session(it)) }
            }
        }
    }

    private fun showDestination(destination: ShellDestination) {
        supportFragmentManager.beginTransaction().apply {
            ShellDestination.entries.forEach { key ->
                val fragment = requireFragment(key)
                if (key == destination) show(fragment) else hide(fragment)
            }
        }.commitAllowingStateLoss()
    }

    private fun requireFragment(destination: ShellDestination): Fragment {
        return requireNotNull(supportFragmentManager.findFragmentByTag(destination.name))
    }

    override fun toggleSidebar() {
        setSidebarVisible(binding.sidebarContainer.visibility != View.VISIBLE)
    }

    override fun setSidebarVisible(visible: Boolean) {
        binding.sidebarContainer.visibility = if (visible) View.VISIBLE else View.GONE
        AppGraph.preferences.sidebarVisible = visible
    }

    override fun setTopBarVisible(visible: Boolean) {
        //binding.topBarContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSearchNavigationIntent(intent)
        handleInitialDraftFromIntent()
    }

    override fun onDestroy() {
        WifiNetworkMonitor.unregister()
        hoverTooltipPopup?.dismiss()
        hoverTooltipPopup = null
        settingPanelController?.release()
        settingPanelController = null
        settingPopupWindow?.dismiss()
        settingPopupWindow = null
        sessionActionPopup?.dismiss()
        sessionActionPopup = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        WifiNetworkMonitor.register(this, this)
    }

    override fun onResume() {
        super.onResume()
        enforceLoginGate()
    }

    private fun enforceLoginGate() {
        if (!AppGraph.preferences.isLoggedIn) {
            showLoginDialog(forceLogin = true)
        }
    }

    override fun onStop() {
        WifiNetworkMonitor.unregister()
        super.onStop()
    }

    override fun onWifiConnectionChanged(isConnected: Boolean) {
        Logger.d(TAG, "onWifiConnectionChanged isConnected=$isConnected")
        runOnUiThread {
            updateShellTopNoticeVisibility(isVisible = !isConnected)
        }
    }

    private fun updateShellTopNoticeVisibility(isVisible: Boolean) {
        binding.shellTopNotice.visibility = if (isVisible) View.VISIBLE else View.GONE
    }

    private fun showSettingDialogPopup() {
        settingPopupWindow?.dismiss()
        settingPanelController?.release()

        if (previousSoftInputMode == null) {
            previousSoftInputMode = window.attributes.softInputMode
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        val popupContentView = LayoutInflater.from(this)
            .inflate(R.layout.activity_setting, null, false)
        popupContentView.setBackgroundColor(Color.parseColor("#99000000"))

        val dialogContainer = popupContentView.findViewById<View>(R.id.settingDialogContainer)
        popupContentView.setOnClickListener {
            settingPopupWindow?.dismiss()
        }
        dialogContainer?.setOnClickListener { /* consume */ }

        var stableDialogYOnScreen: Int? = null
        var hasLockedDialogTop = false
        val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = Rect()
            popupContentView.getWindowVisibleDisplayFrame(visible)
            val rootHeight = popupContentView.rootView.height
            val keyboardGuess = (rootHeight - visible.height()).coerceAtLeast(0)
            val pos = IntArray(2)
            dialogContainer?.getLocationOnScreen(pos)
            if (stableDialogYOnScreen == null && pos[1] > 0) {
                stableDialogYOnScreen = pos[1]
            }
            stableDialogYOnScreen?.let { stableY ->
                val container = dialogContainer ?: return@let
                val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return@let
                if (!hasLockedDialogTop || lp.topMargin != stableY || lp.gravity != (android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)) {
                    hasLockedDialogTop = true
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    lp.topMargin = stableY
                    container.layoutParams = lp
                }
                val delta = stableY - pos[1]
                val targetTranslation = ((container.translationY + delta).coerceIn(-300f, 300f))
                if (abs(container.translationY - targetTranslation) >= 1f) {
                    container.translationY = targetTranslation
                }
            }
        }
        popupContentView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        val popupWindow = PopupWindow(
            popupContentView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false
            elevation = 12f
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setIsLaidOutInScreen(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setAttachedInDecor(false)
            }
        }

        popupWindow.setOnDismissListener {
            settingPanelController?.release()
            settingPanelController = null
            settingPopupWindow = null
            if (popupContentView.viewTreeObserver.isAlive) {
                popupContentView.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
            }
            previousSoftInputMode?.let {
                window.setSoftInputMode(it)
                previousSoftInputMode = null
            }
        }

        popupWindow.showAtLocation(binding.root, android.view.Gravity.CENTER, 0, 0)
        settingPopupWindow = popupWindow

        popupContentView.post {
            if (settingPopupWindow !== popupWindow) return@post
            val pos = IntArray(2)
            dialogContainer?.getLocationOnScreen(pos)
            if (stableDialogYOnScreen == null && pos[1] > 0) {
                stableDialogYOnScreen = pos[1]
            }
        }

        popupContentView.post {
            if (settingPopupWindow !== popupWindow) return@post
            settingPanelController = SettingPanelController(
                context = this,
                rootView = popupContentView,
                popupHostView = binding.root
            ).also { it.bind() }
        }
    }

    private fun bindHeaderHoverTooltip(
        view: View,
        textProvider: () -> String
    ) {
        view.setOnHoverListener { hoveredView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    if (!isHoverTooltipSuppressed(hoveredView)) {
                        showHoverTooltip(hoveredView, textProvider())
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT,
                MotionEvent.ACTION_CANCEL -> {
                    dismissHoverTooltip()
                    clearHoverTooltipSuppression(hoveredView)
                }
            }
            false
        }
    }

    private fun showHoverTooltip(anchor: View, text: String) {
        if (hoverTooltipPopup == null) {
            hoverTooltipPopup = ShellHoverTooltipPopupWindow(this)
        }
        hoverTooltipPopup?.show(anchor, text)
    }

    private fun dismissHoverTooltip() {
        hoverTooltipPopup?.dismiss()
    }

    private fun suppressHoverTooltip(view: View) {
        suppressedHoverTooltipView = view
    }

    private fun isHoverTooltipSuppressed(view: View): Boolean {
        return suppressedHoverTooltipView === view
    }

    private fun clearHoverTooltipSuppression(view: View) {
        if (suppressedHoverTooltipView === view) {
            suppressedHoverTooltipView = null
        }
    }

    private fun resolveSidebarTooltipText(): String {
        return if (binding.sidebarContainer.visibility == View.VISIBLE) {
            getString(R.string.shell_collapse_sidebar)
        } else {
            getString(R.string.shell_open_sidebar)
        }
    }

    private fun handleInitialDraftFromIntent() {
        val initialDraft = intent?.getStringExtra(EXTRA_INITIAL_CHAT_DRAFT)?.trim().orEmpty()
        if (initialDraft.isBlank()) return

        navigateTo(ShellDestination.CHAT)
        val currentSessionId = latestChatState.selectedSessionId.orEmpty()
        if (currentSessionId.isNotBlank()) {
            shellViewModel.updateChatDraft(currentSessionId, initialDraft)
        }
        intent?.removeExtra(EXTRA_INITIAL_CHAT_DRAFT)
    }

    private fun handleSearchNavigationIntent(intent: Intent?) {
        val targetSessionKey = intent?.getStringExtra(EXTRA_TARGET_SESSION_KEY)?.trim().orEmpty()
        if (targetSessionKey.isBlank()) return

        navigateTo(ShellDestination.CHAT)
        handleSessionClick(targetSessionKey)
        intent?.removeExtra(EXTRA_TARGET_SESSION_KEY)
        intent?.removeExtra(EXTRA_TARGET_MESSAGE_INDEX)
    }

    private fun showExportedFilesDialog() {
        val tag = ai.cjym.agentclaw.ui.shell.chat.ExportedFilesDialogFragment.TAG
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        ai.cjym.agentclaw.ui.shell.chat.ExportedFilesDialogFragment.newInstance()
            .show(supportFragmentManager, tag)
    }

    private fun showLoginDialog(forceLogin: Boolean = false) {
        if (activeLoginDialog?.isShowing == true) return

        val dialogBinding = DialogLoginBinding.inflate(layoutInflater)
        val dialog = Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(dialogBinding.root)
            setCancelable(!forceLogin)
            setCanceledOnTouchOutside(!forceLogin)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (forceLogin) {
                setOnKeyListener { _, keyCode, _ ->
                    keyCode == android.view.KeyEvent.KEYCODE_BACK
                }
            }
        }
        activeLoginDialog = dialog

        dialogBinding.loginCloseButton.visibility = if (forceLogin) View.GONE else View.VISIBLE

        var isCodeMode = true
        var countdownJob: Job? = null
        var setPasswordCountdownJob: Job? = null
        var isAgreementChecked = false

        fun refreshAgreementState() {
            dialogBinding.loginAgreementCheckIcon.isSelected = isAgreementChecked
            dialogBinding.loginSubmitButton.alpha = if (isAgreementChecked) 1f else 0.4f
        }

        fun setError(msg: String?) {
            dialogBinding.loginFormError.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
            dialogBinding.loginFormError.text = msg.orEmpty()
        }

        fun setLoading(loading: Boolean) {
            dialogBinding.loginSubmitButton.isEnabled = !loading
            dialogBinding.loginSubmitButton.text =
                getString(if (loading) R.string.login_logging_in else R.string.login_submit)
        }

        fun setSetPasswordError(msg: String?) {
            dialogBinding.setPasswordError.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
            dialogBinding.setPasswordError.text = msg.orEmpty()
        }

        fun setSetPasswordLoading(loading: Boolean) {
            dialogBinding.setPasswordSubmitButton.isEnabled = !loading
            dialogBinding.setPasswordSubmitButton.text =
                getString(if (loading) R.string.set_password_submitting else R.string.set_password_submit)
        }

        fun refreshMode() {
            if (isCodeMode) {
                dialogBinding.loginSmsRow.visibility = View.VISIBLE
                dialogBinding.loginPasswordInput.visibility = View.GONE
                dialogBinding.loginSwitchModeButton.text = getString(R.string.login_use_password)
            } else {
                dialogBinding.loginSmsRow.visibility = View.GONE
                dialogBinding.loginPasswordInput.visibility = View.VISIBLE
                dialogBinding.loginSwitchModeButton.text = getString(R.string.login_use_code)
            }
            setError(null)
        }

        fun showLoggedInSection() {
            dialogBinding.loggedInSection.visibility = View.VISIBLE
            dialogBinding.loginFormSection.visibility = View.GONE
            dialogBinding.setPasswordSection.visibility = View.GONE
            dialogBinding.loginSubtitle.text = getString(R.string.login_dialog_subtitle)
        }

        fun showSetPasswordSection(phone: String) {
            dialogBinding.loggedInSection.visibility = View.GONE
            dialogBinding.loginFormSection.visibility = View.GONE
            dialogBinding.setPasswordSection.visibility = View.VISIBLE
            dialogBinding.loginSubtitle.text = getString(R.string.set_password_title)
            dialogBinding.setPasswordPhoneText.text = getString(R.string.login_logged_in_as, phone)
            dialogBinding.setPasswordCodeInput.text?.clear()
            dialogBinding.setPasswordNewInput.text?.clear()
            setSetPasswordError(null)
        }

        // 已登录状态
        if (AppGraph.preferences.isLoggedIn) {
            val phone = AppGraph.preferences.userPhone.orEmpty()
            dialogBinding.loggedInPhoneText.text = getString(R.string.login_logged_in_as, phone)
            showLoggedInSection()
            dialogBinding.setPasswordButton.setOnClickListener {
                showSetPasswordSection(phone)
            }
            dialogBinding.logoutButton.setOnClickListener {
                AppGraph.preferences.isLoggedIn = false
                AppGraph.preferences.userPhone = null
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.login_logout), Toast.LENGTH_SHORT).show()
                showLoginDialog(forceLogin = true)
            }
        } else {
            dialogBinding.loggedInSection.visibility = View.GONE
            dialogBinding.loginFormSection.visibility = View.VISIBLE
            dialogBinding.setPasswordSection.visibility = View.GONE
            refreshMode()
            refreshAgreementState()

            dialogBinding.loginAgreementText.toSpannableString(colorId = R.color.terms_link, isBold = false) { key ->
                when (key) {
                    "user_agreement" -> startActivity(
                        LegalWebActivity.createIntent(this, getString(R.string.terms_user_agreement), AppConstants.USER_AGREEMENT_URL)
                    )
                    "privacy_policy" -> startActivity(
                        LegalWebActivity.createIntent(this, getString(R.string.terms_privacy_policy), AppConstants.PRIVACY_POLICY_URL)
                    )
                }
            }
            dialogBinding.loginAgreementText.highlightColor = android.graphics.Color.TRANSPARENT

            dialogBinding.loginAgreementRow.setOnClickListener {
                isAgreementChecked = !isAgreementChecked
                refreshAgreementState()
            }
        }

        // 设置密码 — 返回
        dialogBinding.setPasswordBackButton.setOnClickListener {
            setPasswordCountdownJob?.cancel()
            setPasswordCountdownJob = null
            showLoggedInSection()
        }

        // 设置密码 — 获取验证码
        dialogBinding.setPasswordCodeButton.setOnClickListener {
            val phone = AppGraph.preferences.userPhone.orEmpty()
            if (phone.isEmpty()) return@setOnClickListener
            dialogBinding.setPasswordCodeButton.isEnabled = false
            dialogBinding.setPasswordCodeButton.text = getString(R.string.login_sending_code)

            lifecycleScope.launch {
                AppGraph.authService.sendSmsCode(phone)
                    .onSuccess {
                        setPasswordCountdownJob?.cancel()
                        setPasswordCountdownJob = launch {
                            var remaining = 120
                            while (remaining > 0) {
                                dialogBinding.setPasswordCodeButton.text =
                                    getString(R.string.login_code_countdown, remaining)
                                delay(1000)
                                remaining--
                            }
                            dialogBinding.setPasswordCodeButton.isEnabled = true
                            dialogBinding.setPasswordCodeButton.text = getString(R.string.login_get_code)
                        }
                    }
                    .onFailure { e ->
                        dialogBinding.setPasswordCodeButton.isEnabled = true
                        dialogBinding.setPasswordCodeButton.text = getString(R.string.login_get_code)
                        setSetPasswordError(e.message ?: getString(R.string.login_code_failed))
                    }
            }
        }

        // 设置密码 — 提交
        dialogBinding.setPasswordSubmitButton.setOnClickListener {
            val phone = AppGraph.preferences.userPhone.orEmpty()
            val code = dialogBinding.setPasswordCodeInput.text?.toString()?.trim().orEmpty()
            val password = dialogBinding.setPasswordNewInput.text?.toString().orEmpty()
            if (code.length != 6) {
                setSetPasswordError(getString(R.string.login_code_hint))
                return@setOnClickListener
            }
            if (password.length < 6) {
                setSetPasswordError(getString(R.string.set_password_new_hint))
                return@setOnClickListener
            }
            lifecycleScope.launch {
                setSetPasswordLoading(true)
                setSetPasswordError(null)
                AppGraph.authService.setPassword(phone, code, password)
                    .onSuccess {
                        setSetPasswordLoading(false)
                        setPasswordCountdownJob?.cancel()
                        setPasswordCountdownJob = null
                        showLoggedInSection()
                        Toast.makeText(
                            this@ShellActivity,
                            getString(R.string.set_password_success),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .onFailure { e ->
                        setSetPasswordLoading(false)
                        setSetPasswordError(e.message ?: getString(R.string.login_code_failed))
                    }
            }
        }

        dialogBinding.root.setOnClickListener { if (!forceLogin) dialog.dismiss() }
        dialogBinding.loginDialogContainer.setOnClickListener { /* consume */ }
        dialogBinding.loginCloseButton.setOnClickListener { dialog.dismiss() }

        dialogBinding.loginSwitchModeButton.setOnClickListener {
            isCodeMode = !isCodeMode
            refreshMode()
        }

        dialogBinding.loginCodeButton.setOnClickListener {
            val phone = normalizeMainlandPhone(dialogBinding.loginPhoneInput.text?.toString().orEmpty())
            if (!isReliableMainlandPhone(phone)) {
                dialogBinding.loginPhoneError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            dialogBinding.loginPhoneError.visibility = View.GONE
            dialogBinding.loginCodeButton.isEnabled = false
            dialogBinding.loginCodeButton.text = getString(R.string.login_sending_code)

            lifecycleScope.launch {
                AppGraph.authService.sendSmsCode(phone)
                    .onSuccess {
                        // 120s 倒计时
                        countdownJob?.cancel()
                        countdownJob = launch {
                            var remaining = 120
                            while (remaining > 0) {
                                dialogBinding.loginCodeButton.text =
                                    getString(R.string.login_code_countdown, remaining)
                                delay(1000)
                                remaining--
                            }
                            dialogBinding.loginCodeButton.isEnabled = true
                            dialogBinding.loginCodeButton.text = getString(R.string.login_get_code)
                        }
                    }
                    .onFailure { e ->
                        dialogBinding.loginCodeButton.isEnabled = true
                        dialogBinding.loginCodeButton.text = getString(R.string.login_get_code)
                        setError(e.message ?: getString(R.string.login_code_failed))
                    }
            }
        }

        dialogBinding.loginSubmitButton.setOnClickListener {
            if (!isAgreementChecked) {
                Toast.makeText(this, getString(R.string.login_agreement_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val phone = normalizeMainlandPhone(dialogBinding.loginPhoneInput.text?.toString().orEmpty())
            if (!isReliableMainlandPhone(phone)) {
                dialogBinding.loginPhoneError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            dialogBinding.loginPhoneError.visibility = View.GONE

            lifecycleScope.launch {
                setLoading(true)
                setError(null)
                val result = if (isCodeMode) {
                    val code = dialogBinding.loginCodeInput.text?.toString()?.trim().orEmpty()
                    if (code.length != 6) {
                        setError(getString(R.string.login_code_hint))
                        setLoading(false)
                        return@launch
                    }
                    AppGraph.authService.loginByCode(phone, code)
                } else {
                    val password = dialogBinding.loginPasswordInput.text?.toString().orEmpty()
                    if (password.length < 6) {
                        setError(getString(R.string.login_password_empty))
                        setLoading(false)
                        return@launch
                    }
                    AppGraph.authService.loginByPassword(phone, password)
                }
                result.onSuccess { loginResult ->
                    AppGraph.preferences.isLoggedIn = true
                    AppGraph.preferences.userPhone = loginResult.phone
                    setLoading(false)
                    dialog.dismiss()
                    Toast.makeText(this@ShellActivity, getString(R.string.login_success), Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    setLoading(false)
                    setError(e.message ?: getString(R.string.login_code_failed))
                }
            }
        }

        dialog.setOnDismissListener {
            countdownJob?.cancel()
            setPasswordCountdownJob?.cancel()
            if (activeLoginDialog === dialog) {
                activeLoginDialog = null
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun normalizeMainlandPhone(rawPhone: String): String {
        return rawPhone
            .filterNot { it.isWhitespace() || it == '-' }
            .removePrefix("+86")
            .removePrefix("86")
    }

    private fun isReliableMainlandPhone(phone: String): Boolean {
        return Regex("^1[3-9]\\d{9}$").matches(phone)
    }

    private fun isLoginCodeSuccess(code: String?): Boolean {
        return code?.trim()?.lowercase() in setOf("0", "200", "success", "ok")
    }

    private fun openAgentclawDocsDirectory() {
        // Target directory: /sdcard/Download/agentclaw
        // Use SAF Document URI to open the folder in a file manager.
        val folderDocUri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fagentclaw"
        )

        val openFolderIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val fallbackIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)

        val opened = tryStartActivity(openFolderIntent) ||
            tryStartActivity(fallbackIntent)

        if (!opened) {
            Toast.makeText(this, "未找到可用的文件管理器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        if (intent.resolveActivity(packageManager) == null) return false
        return runCatching { startActivity(intent) }.isSuccess
    }
}
