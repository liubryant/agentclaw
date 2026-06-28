package ai.cjym.agentclaw.ui.profile

import ai.cjym.agentclaw.BuildConfig
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.constants.AppConstants
import ai.cjym.agentclaw.databinding.FragmentProfileBinding
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.ui.common.BaseBindingFragment
import ai.cjym.agentclaw.ui.legal.LegalWebActivity
import ai.cjym.agentclaw.ui.shell.ShellProfileActions
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
        binding.root.findViewById<View>(R.id.openSourceRow).setOnClickListener {
            startActivity(LegalWebActivity.createIntent(
                requireContext(),
                getString(R.string.setting_open_source_license),
                AppConstants.GITHUB_URL
            ))
        }
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
