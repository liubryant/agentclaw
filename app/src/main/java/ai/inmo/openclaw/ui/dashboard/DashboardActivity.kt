package ai.inmo.openclaw.ui.dashboard

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.SettingPanelController
import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.databinding.ActivityDashboardBinding
import ai.inmo.openclaw.ui.configure.ConfigureActivity
import ai.inmo.openclaw.ui.logs.LogsActivity
import ai.inmo.openclaw.ui.node.NodeActivity
import ai.inmo.openclaw.ui.onboarding.OnboardingActivity
import ai.inmo.openclaw.ui.packages.PackagesActivity
import ai.inmo.openclaw.ui.providers.ProvidersActivity
import ai.inmo.openclaw.ui.ssh.SshActivity
import ai.inmo.openclaw.ui.synced_chat.SyncedChatActivity
import ai.inmo.openclaw.ui.terminal.TerminalActivity
import ai.inmo.openclaw.ui.web.WebDashboardActivity
import android.content.Intent
import android.os.Build
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class DashboardActivity :
    BaseBindingActivity<ActivityDashboardBinding>(ActivityDashboardBinding::inflate) {
    companion object {
    }

    private val viewModel = DashboardViewModel()
    private val gatewayViewModel = GatewayViewModel()
    private var settingPopupWindow: PopupWindow? = null
    private var settingPanelController: SettingPanelController? = null
    private var previousSoftInputMode: Int? = null

    override fun initData() {
        viewModel.addObserver()
        gatewayViewModel.addObserver()
        viewModel.refreshNode()
        gatewayViewModel.refresh()
    }

    override fun initView() {
        lifecycleScope.launch {
            gatewayViewModel.state.collectLatest { state ->
                binding.gatewayControls.bind(state)
                binding.chatCard.isEnabled = state.isRunning
                binding.chatSubtitleView.text = if (state.isRunning) getString(R.string.dashboard_chat_subtitle_ready) else getString(R.string.dashboard_chat_subtitle_pending)
                binding.chatSubtitleView.alpha = if (state.isRunning) 1f else 0.6f
            }
        }
        lifecycleScope.launch {
            viewModel.overviewState.collectLatest { state ->
                binding.gatewayStatusCard.bind(state.gatewayCard)
                binding.nodeStatusCard.bind(state.nodeCard)
            }
        }
    }

    override fun initEvent() {
        binding.gatewayControls.onStartClick = { gatewayViewModel.start() }
        binding.gatewayControls.onStopClick = { gatewayViewModel.stop() }
        binding.gatewayControls.onLogsClick = { startActivity(Intent(this, LogsActivity::class.java)) }
        binding.gatewayControls.onOpenDashboardClick = { startActivity(Intent(this, WebDashboardActivity::class.java)) }
        binding.terminalCard.setOnClickListener { startActivity(Intent(this, TerminalActivity::class.java)) }
        binding.chatCard.setOnClickListener { if (binding.chatCard.isEnabled) startActivity(Intent(this, SyncedChatActivity::class.java)) }
        binding.onboardingCard.setOnClickListener { startActivity(Intent(this, OnboardingActivity::class.java)) }
        binding.configureCard.setOnClickListener { startActivity(Intent(this, ConfigureActivity::class.java)) }
        binding.providersCard.setOnClickListener { startActivity(Intent(this, ProvidersActivity::class.java)) }
        binding.packagesCard.setOnClickListener { startActivity(Intent(this, PackagesActivity::class.java)) }
        binding.sshCard.setOnClickListener { startActivity(Intent(this, SshActivity::class.java)) }
        binding.logsCard.setOnClickListener { startActivity(Intent(this, LogsActivity::class.java)) }
        binding.snapshotCard.setOnClickListener {
            val file = ai.inmo.openclaw.di.AppGraph.snapshotManager.exportLatestSnapshot()
            if (file != null) {
                Toast.makeText(this, getString(R.string.settings_snapshot_exported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.settings_snapshot_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
        binding.webDashboardCard.setOnClickListener { startActivity(Intent(this, WebDashboardActivity::class.java)) }
        binding.settingsCard.setOnClickListener { showSettingDialogPopup() }
        binding.nodeStatusCard.setOnClickListener { startActivity(Intent(this, NodeActivity::class.java)) }
        binding.versionFooter.text = getString(R.string.dashboard_version, AppConstants.VERSION)
    }

    override fun onDestroy() {
        settingPanelController?.release()
        settingPanelController = null
        settingPopupWindow?.dismiss()
        settingPopupWindow = null
        super.onDestroy()
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
}
