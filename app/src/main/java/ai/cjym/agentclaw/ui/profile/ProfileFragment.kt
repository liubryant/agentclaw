package ai.cjym.agentclaw.ui.profile

import ai.cjym.agentclaw.BuildConfig
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.constants.AppConstants
import ai.cjym.agentclaw.databinding.FragmentProfileBinding
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.ui.common.BaseBindingFragment
import ai.cjym.agentclaw.ui.legal.LegalWebActivity
import ai.cjym.agentclaw.ui.shell.ShellProfileActions
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View

class ProfileFragment : BaseBindingFragment<FragmentProfileBinding>(FragmentProfileBinding::inflate) {

    override fun initView(savedInstanceState: Bundle?) {
        refreshUserState()
        binding.versionText.text = getString(
            R.string.profile_version,
            getString(R.string.app_name),
            BuildConfig.VERSION_NAME
        )
        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(240L).start()
    }

    override fun initEvent() {
        val actions = activity as? ShellProfileActions
        binding.loginCard.setOnClickListener { actions?.openProfileLogin() }
        binding.loginAction.setOnClickListener { actions?.openProfileLogin() }
        binding.vipCard.setOnClickListener { actions?.openProfileMembership() }
        binding.root.findViewById<View>(R.id.documentsRow).setOnClickListener { actions?.openProfileDocuments() }
        binding.root.findViewById<View>(R.id.settingsRow).setOnClickListener { actions?.openProfileSettings() }
        binding.root.findViewById<View>(R.id.userAgreementRow).setOnClickListener {
            startActivity(LegalWebActivity.createIntent(
                requireContext(),
                getString(R.string.setting_user_agreement),
                AppConstants.USER_AGREEMENT_URL
            ))
        }
        binding.root.findViewById<View>(R.id.privacyPolicyRow).setOnClickListener {
            startActivity(LegalWebActivity.createIntent(
                requireContext(),
                getString(R.string.setting_privacy_policy),
                AppConstants.PRIVACY_POLICY_URL
            ))
        }
        binding.logoutCard.setOnClickListener { confirmLogout() }
        binding.root.findViewById<View>(R.id.feedbackRow).setOnClickListener { openFeedback() }
        binding.root.findViewById<View>(R.id.ratingRow).setOnClickListener { openAppStore() }
    }

    private fun openFeedback() {
        startActivity(
            LegalWebActivity.createIntent(
                requireContext(),
                "客服反馈",
                "https://www.cjym123.cn/feedback_agentclaw.html"
            )
        )
    }

    private fun openAppStore() {
        AlertDialog.Builder(requireContext())
            .setTitle("给我们评分")
            .setMessage("感谢您的支持！进入应用商店后，请找到「评分」或「写评价」按钮为我们打分，您的评价对我们非常重要。")
            .setPositiveButton("前往评分") { _, _ -> launchStoreRating() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchStoreRating() {
        val pkg = requireContext().packageName
        val manufacturer = Build.MANUFACTURER.lowercase()
        val storeUri = when {
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                Uri.parse("appmarket://details?id=$pkg&referrer=commentList")
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                Uri.parse("mimarket://details?id=$pkg")
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") ->
                Uri.parse("oppomarket://details?packageName=$pkg")
            manufacturer.contains("vivo") ->
                Uri.parse("vivomarket://details?id=$pkg")
            manufacturer.contains("samsung") ->
                Uri.parse("samsungapps://ProductDetail/$pkg")
            else ->
                Uri.parse("market://details?id=$pkg")
        }
        val intent = Intent(Intent.ACTION_VIEW, storeUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            }
        }
    }

    private fun confirmLogout() {
        val prefs = AppGraph.preferences
        if (!prefs.isLoggedIn) return
        AlertDialog.Builder(requireContext())
            .setTitle("退出登录")
            .setMessage("确认退出当前登录账号？")
            .setPositiveButton("退出") { _, _ -> doLogout() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doLogout() {
        val prefs = AppGraph.preferences
        prefs.isLoggedIn = false
        prefs.userPhone = null
        prefs.userAccessToken = null
        refreshUserState()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) refreshUserState()
    }

    fun refreshUserState() {
        if (view == null) return
        val preferences = AppGraph.preferences
        val loggedIn = preferences.isLoggedIn
        val phone = preferences.userPhone.orEmpty()
        binding.profileName.text = if (loggedIn && phone.isNotBlank()) {
            maskPhone(phone)
        } else {
            getString(R.string.profile_login_title)
        }
        binding.profileSubtitle.setText(
            if (loggedIn) R.string.profile_logged_in_hint else R.string.profile_login_hint
        )
        binding.loginAction.setText(
            if (loggedIn) R.string.profile_account_manage else R.string.login_title
        )
        binding.vipSubtitle.setText(
            if (loggedIn) R.string.profile_vip_logged_in_hint else R.string.profile_vip_hint
        )
    }

    private fun maskPhone(phone: String): String {
        return if (phone.length >= 7) phone.take(3) + "****" + phone.takeLast(4) else phone
    }
}
