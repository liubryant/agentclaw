package ai.inmo.openclaw.ui.providers

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivityProvidersBinding
import android.content.Intent
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProvidersActivity : BaseBindingActivity<ActivityProvidersBinding>(ActivityProvidersBinding::inflate) {
    private val viewModel = ProvidersViewModel()

    override fun initData() {
        viewModel.addObserver()
        viewModel.refresh()
    }

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                binding.activeModelValue.text = state.activeModel ?: getString(R.string.providers_no_active_model)
                binding.providersContainer.removeAllViews()
                state.items.forEach { item ->
                    val view = layoutInflater.inflate(R.layout.item_provider, binding.providersContainer, false)
                    view.findViewById<TextView>(R.id.providerName)!!.text = item.provider.name
                    view.findViewById<TextView>(R.id.providerDescription)!!.text = item.provider.description
                    view.findViewById<TextView>(R.id.providerStatus)!!.text = when {
                        item.active -> getString(R.string.providers_status_active)
                        item.configured -> getString(R.string.providers_status_configured)
                        else -> getString(R.string.providers_status_not_configured)
                    }
                    view.setOnClickListener {
                        startActivity(Intent(this@ProvidersActivity, ProviderDetailActivity::class.java).putExtra(ProviderDetailActivity.EXTRA_PROVIDER_ID, item.provider.id))
                    }
                    binding.providersContainer.addView(view)
                }
            }
        }
    }

    override fun initEvent() = Unit

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

