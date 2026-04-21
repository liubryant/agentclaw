package ai.inmo.openclaw.ui.license

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivityLicenseBinding
import androidx.appcompat.app.AlertDialog

class LicenseActivity : BaseBindingActivity<ActivityLicenseBinding>(ActivityLicenseBinding::inflate) {
    override fun initData() = Unit

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }
        binding.licenseText.text = MIT_LICENSE
        binding.openSourceButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(binding.openSourceButton.text)
                .setMessage(getString(R.string.license_notices_unavailable))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun initEvent() = Unit

    companion object {
        private const val MIT_LICENSE = """MIT License

Copyright (c) 2026 Mithun Gowda B

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the \"Software\"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""
    }
}