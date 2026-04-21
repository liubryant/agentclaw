package ai.inmo.openclaw.ui.settings

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivitySettingsBinding
import ai.inmo.openclaw.ui.license.LicenseActivity
import ai.inmo.openclaw.ui.startup.StartupActivity
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
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                syncingSwitches = true
                binding.autoStartSwitch.isChecked = state.autoStartGateway
                binding.nodeSwitch.isChecked = state.nodeEnabled
                syncingSwitches = false
                binding.dashboardUrlValue.text = state.dashboardUrl ?: getString(R.string.settings_not_available)
                binding.archValue.text = state.arch
                binding.prootValue.text = state.prootPath
                binding.rootfsValue.text = installedText(state.rootfsInstalled)
                binding.nodeValue.text = installedText(state.nodeInstalled)
                binding.openclawValue.text = installedText(state.openClawInstalled)
                binding.goValue.text = installedText(state.goInstalled)
                binding.brewValue.text = installedText(state.brewInstalled)
                binding.sshValue.text = installedText(state.sshInstalled)
                binding.snapshotStatus.text = state.statusMessage ?: ""
                binding.batteryValue.text = if (isBatteryOptimized()) getString(R.string.settings_battery_optimized) else getString(R.string.settings_battery_unrestricted)
                binding.storageValue.text = if (hasStorageAccess()) getString(R.string.settings_storage_granted) else getString(R.string.settings_storage_not_granted)
                when (state.snapshotAction) {
                    SettingsViewModel.SnapshotAction.EXPORTED -> {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_snapshot_exported), Toast.LENGTH_SHORT).show()
                        viewModel.consumeSnapshotAction()
                    }
                    SettingsViewModel.SnapshotAction.IMPORTED -> {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_snapshot_imported), Toast.LENGTH_SHORT).show()
                        viewModel.consumeSnapshotAction()
                    }
                    SettingsViewModel.SnapshotAction.EXPORT_FAILED -> {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_snapshot_export_failed), Toast.LENGTH_SHORT).show()
                        viewModel.consumeSnapshotAction()
                    }
                    SettingsViewModel.SnapshotAction.IMPORT_FAILED -> {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_snapshot_import_failed), Toast.LENGTH_SHORT).show()
                        viewModel.consumeSnapshotAction()
                    }
                    null -> Unit
                }
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
        binding.exportSnapshotButton.setOnClickListener { viewModel.exportSnapshot() }
        binding.importSnapshotButton.setOnClickListener { viewModel.importSnapshot() }
        binding.rerunSetupButton.setOnClickListener { startActivity(Intent(this, StartupActivity::class.java)) }
        binding.licenseRow.setOnClickListener { startActivity(Intent(this, LicenseActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun installedText(installed: Boolean): String {
        return if (installed) getString(R.string.settings_installed) else getString(R.string.settings_not_installed)
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
