package ai.cjym.agentclaw.ui.settings

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.databinding.ActivitySettingsBinding
import ai.cjym.agentclaw.ui.license.LicenseActivity
import ai.cjym.agentclaw.ui.startup.StartupActivity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsActivity : BaseBindingActivity<ActivitySettingsBinding>(ActivitySettingsBinding::inflate) {
    private val viewModel = SettingsViewModel()
    private var syncingSwitches = false

    override fun initData() {
        viewModel.addObserver()
        viewModel.refresh()
    }

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }

        // proot/Linux VM removed — show static "not available" for system info rows
        val na = getString(R.string.settings_not_available)
        binding.archValue.text = na
        binding.prootValue.text = na
        binding.rootfsValue.text = na
        binding.nodeValue.text = na
        binding.openclawValue.text = na
        binding.goValue.text = na
        binding.brewValue.text = na
        binding.sshValue.text = na
        binding.snapshotStatus.text = ""

        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                syncingSwitches = true
                binding.autoStartSwitch.isChecked = state.autoStartGateway
                binding.nodeSwitch.isChecked = state.nodeEnabled
                syncingSwitches = false
                binding.dashboardUrlValue.text = state.dashboardUrl ?: getString(R.string.settings_not_available)
                binding.batteryValue.text = if (isBatteryOptimized()) getString(R.string.settings_battery_optimized) else getString(R.string.settings_battery_unrestricted)
                binding.storageValue.text = if (hasStorageAccess()) getString(R.string.settings_storage_granted) else getString(R.string.settings_storage_not_granted)
            }
        }
    }

    override fun initEvent() {
        binding.autoStartSwitch.text = getString(R.string.settings_auto_start)
        binding.nodeSwitch.text = getString(R.string.settings_enable_node)
        binding.autoStartSwitch.setOnCheckedChangeListener { _, checked ->
            if (!syncingSwitches) viewModel.toggleAutoStart(checked)
        }
        binding.nodeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!syncingSwitches) viewModel.toggleNode(checked)
        }
        binding.batteryRow.setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
        binding.storageRow.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
        binding.exportSnapshotButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.settings_not_available), Toast.LENGTH_SHORT).show()
        }
        binding.importSnapshotButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.settings_not_available), Toast.LENGTH_SHORT).show()
        }
        binding.rerunSetupButton.setOnClickListener { startActivity(Intent(this, StartupActivity::class.java)) }
        binding.licenseRow.setOnClickListener { startActivity(Intent(this, LicenseActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }
}
