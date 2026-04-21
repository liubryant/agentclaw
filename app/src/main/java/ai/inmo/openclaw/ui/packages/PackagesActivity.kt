package ai.inmo.openclaw.ui.packages

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivityPackagesBinding
import ai.inmo.openclaw.domain.model.OptionalPackage
import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PackagesActivity : BaseBindingActivity<ActivityPackagesBinding>(ActivityPackagesBinding::inflate) {
    private val viewModel = PackagesViewModel()

    override fun initData() {
        viewModel.addObserver()
        viewModel.refresh()
    }

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                binding.packagesContainer.removeAllViews()
                state.items.forEach { item ->
                    val view = layoutInflater.inflate(R.layout.item_package, binding.packagesContainer, false)
                    view.findViewById<TextView>(R.id.packageName)!!.text = item.pkg.name
                    view.findViewById<TextView>(R.id.packageDescription)!!.text = item.pkg.description
                    view.findViewById<TextView>(R.id.packageStatus)!!.text = if (item.installed) getString(R.string.packages_installed, item.pkg.estimatedSize) else getString(R.string.packages_not_installed, item.pkg.estimatedSize)
                    val button = view.findViewById<android.widget.Button>(R.id.packageActionButton)!!
                    button.text = if (item.installed) getString(R.string.packages_uninstall) else getString(R.string.packages_install)
                    button.setOnClickListener {
                        if (item.installed) {
                            AlertDialog.Builder(this@PackagesActivity)
                                .setTitle(getString(R.string.packages_uninstall_title, item.pkg.name))
                                .setMessage(getString(R.string.packages_uninstall_confirm, item.pkg.name))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.packages_uninstall) { _, _ -> openInstaller(item.pkg, true) }
                                .show()
                        } else {
                            openInstaller(item.pkg, false)
                        }
                    }
                    binding.packagesContainer.addView(view)
                }
            }
        }
    }

    override fun initEvent() = Unit

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun openInstaller(pkg: OptionalPackage, uninstall: Boolean) {
        startActivity(Intent(this, PackageInstallActivity::class.java)
            .putExtra(PackageInstallActivity.EXTRA_PACKAGE_ID, pkg.id)
            .putExtra(PackageInstallActivity.EXTRA_UNINSTALL, uninstall))
    }
}

