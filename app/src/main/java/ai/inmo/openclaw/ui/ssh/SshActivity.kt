package ai.inmo.openclaw.ui.ssh

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivitySshBinding
import ai.inmo.openclaw.ui.packages.PackagesActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SshActivity : BaseBindingActivity<ActivitySshBinding>(ActivitySshBinding::inflate) {
    private val viewModel = SshViewModel()

    override fun initData() {
        viewModel.addObserver()
        viewModel.refresh()
    }

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                binding.contentInstalled.visibility = if (state.installed) android.view.View.VISIBLE else android.view.View.GONE
                binding.contentMissing.visibility = if (state.installed) android.view.View.GONE else android.view.View.VISIBLE
                val currentPort = binding.portInput.text?.toString()
                val nextPort = state.port.toString()
                if (currentPort != nextPort) {
                    binding.portInput.setText(nextPort)
                }
                binding.statusValue.text = if (state.running) getString(R.string.ssh_running) else getString(R.string.ssh_stopped)
                binding.ipsValue.text = if (state.ips.isEmpty()) getString(R.string.ssh_no_ips) else state.ips.joinToString(", ")
                buildCommandRows(state.ips, state.port)
                binding.toggleButton.text = if (state.running) getString(R.string.ssh_stop_server) else getString(R.string.ssh_start_server)
                binding.progressBar.visibility = if (state.loading || state.busy) android.view.View.VISIBLE else android.view.View.GONE
                binding.errorView.text = state.error.orEmpty()
                binding.errorView.visibility = if (state.error.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                when (state.messageEvent) {
                    SshViewModel.MessageEvent.PASSWORD_UPDATED -> {
                        Toast.makeText(this@SshActivity, getString(R.string.ssh_password_updated), Toast.LENGTH_SHORT).show()
                        viewModel.consumeMessageEvent()
                    }
                    null -> Unit
                }
            }
        }
    }

    override fun initEvent() {
        binding.openPackagesButton.setOnClickListener { startActivity(Intent(this, PackagesActivity::class.java)) }
        binding.toggleButton.setOnClickListener {
            val port = binding.portInput.text?.toString()?.trim()?.toIntOrNull()
            if (port == null || port !in 1..65535) {
                Toast.makeText(this, getString(R.string.ssh_invalid_port), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.toggle(port)
        }
        binding.setPasswordButton.setOnClickListener {
            val password = binding.passwordInput.text?.toString().orEmpty()
            if (password.isBlank()) {
                Toast.makeText(this, getString(R.string.ssh_password_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.setPassword(password)
            binding.passwordInput.setText("")
        }
    }

    private fun buildCommandRows(ips: List<String>, port: Int) {
        binding.commandsContainer.removeAllViews()
        if (ips.isEmpty()) {
            val placeholder = TextView(this).apply {
                text = getString(R.string.ssh_command_placeholder, port)
                typeface = Typeface.MONOSPACE
                setTextColor(resources.getColor(R.color.text_primary, theme))
            }
            binding.commandsContainer.addView(placeholder)
            return
        }
        for (ip in ips) {
            val cmd = "ssh root@$ip -p $port"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            val textView = TextView(this).apply {
                text = cmd
                typeface = Typeface.MONOSPACE
                setTextColor(resources.getColor(R.color.text_primary, theme))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val copyBtn = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_save)
                setBackgroundResource(android.R.attr.selectableItemBackgroundBorderless)
                contentDescription = getString(R.string.ssh_copy_command)
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ssh", cmd))
                    Toast.makeText(this@SshActivity, getString(R.string.ssh_copied), Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(textView)
            row.addView(copyBtn)
            binding.commandsContainer.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
