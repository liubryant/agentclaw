package ai.inmo.openclaw.ui.node

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.data.repository.NodeManager
import ai.inmo.openclaw.databinding.ActivityNodeBinding
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NodeActivity : BaseBindingActivity<ActivityNodeBinding>(ActivityNodeBinding::inflate) {
    private val viewModel = NodeViewModel()
    private var renderedCapabilities: List<NodeManager.CapabilityDescriptor> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pendingRemote = binding.remoteModeSwitch.isChecked
        if (pendingRemote) {
            connectRemote()
        } else {
            viewModel.enableLocal()
        }
    }

    override fun initData() {
        viewModel.addObserver()
        viewModel.refresh()
    }

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }
        binding.localModeSwitch.isChecked = true
        binding.remoteContainer.alpha = 0.5f
        binding.hostInput.setText("127.0.0.1")
        binding.portInput.setText("18789")

        lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                binding.statusValue.updateTextIfChanged(state.statusText)
                binding.deviceIdValue.updateTextIfChanged(
                    state.deviceIdText ?: getString(R.string.node_value_unknown)
                )
                binding.gatewayValue.updateTextIfChanged(state.gatewayText)
                binding.errorValue.updateTextIfChanged(
                    state.errorText ?: getString(R.string.node_value_none)
                )
                binding.pairingValue.updateTextIfChanged(
                    state.pairingText ?: getString(R.string.node_pairing_waiting)
                )
                binding.logsValue.updateTextIfChanged(
                    state.logsText.ifEmpty { getString(R.string.node_logs_empty) }
                )
                binding.enableButton.isEnabled = state.enableButtonEnabled
                binding.disableButton.isEnabled = state.disableButtonEnabled
                binding.reconnectButton.isEnabled = state.reconnectButtonEnabled
            }
        }

        lifecycleScope.launch {
            viewModel.capabilitiesState.collectLatest { capabilities ->
                if (capabilities != renderedCapabilities) {
                    renderedCapabilities = capabilities
                    renderCapabilities(capabilities)
                }
            }
        }
    }

    override fun initEvent() {
        binding.localModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                binding.remoteModeSwitch.isChecked = false
                binding.remoteContainer.alpha = 0.5f
            }
        }
        binding.remoteModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                binding.localModeSwitch.isChecked = false
                binding.remoteContainer.alpha = 1f
            } else if (!binding.localModeSwitch.isChecked) {
                binding.localModeSwitch.isChecked = true
                binding.remoteContainer.alpha = 0.5f
            }
        }
        binding.enableButton.setOnClickListener {
            requestNodePermissions()
        }
        binding.disableButton.setOnClickListener {
            viewModel.disable()
        }
        binding.reconnectButton.setOnClickListener {
            if (binding.remoteModeSwitch.isChecked) connectRemote() else viewModel.reconnect()
        }
        binding.settingsPermissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
    }

    private fun requestNodePermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        )
    }

    private fun capabilityIcon(name: String): String = when (name.lowercase()) {
        "camera" -> "\uD83D\uDCF7"
        "flash" -> "\uD83D\uDD26"
        "location" -> "\uD83D\uDCCD"
        "screen" -> "\uD83D\uDDA5"
        "sensor" -> "\uD83D\uDCE1"
        "fs", "filesystem" -> "\uD83D\uDCC1"
        "system" -> "\u2699\uFE0F"
        "vibration" -> "\uD83D\uDCF3"
        "serial" -> "\uD83D\uDD0C"
        "canvas" -> "\uD83C\uDFA8"
        else -> "\u2B50"
    }

    private fun connectRemote() {
        val host = binding.hostInput.text?.toString()?.trim().orEmpty()
        val port = binding.portInput.text?.toString()?.trim()?.toIntOrNull() ?: 18789
        val token = binding.tokenInput.text?.toString()?.trim().orEmpty().ifBlank { null }
        if (host.isNotBlank()) {
            viewModel.connectRemote(host, port, token)
        }
    }

    private fun renderCapabilities(capabilities: List<NodeManager.CapabilityDescriptor>) {
        binding.capabilitiesContainer.removeAllViews()
        capabilities.forEach { item ->
            val capabilityView = layoutInflater.inflate(
                R.layout.item_node_capability,
                binding.capabilitiesContainer,
                false
            )
            capabilityView.findViewById<TextView>(R.id.capabilityIcon)!!.text = capabilityIcon(item.name)
            capabilityView.findViewById<TextView>(R.id.capabilityName)!!.text = item.name
            capabilityView.findViewById<TextView>(R.id.capabilitySummary)!!.text = item.summary
            val statusView = capabilityView.findViewById<TextView>(R.id.capabilityStatus)!!
            if (item.enabled) {
                statusView.text = getString(R.string.node_capability_ready)
                statusView.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                statusView.text = getString(R.string.node_capability_planned)
                statusView.setTextColor(resources.getColor(R.color.text_secondary, theme))
            }
            binding.capabilitiesContainer.addView(capabilityView)
        }
    }

    private fun TextView.updateTextIfChanged(value: CharSequence) {
        if (text.toString() != value.toString()) {
            text = value
        }
    }
}
