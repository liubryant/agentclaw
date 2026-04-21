package ai.inmo.openclaw.ui.providers

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ActivityProviderDetailBinding
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.AiProvider
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProviderDetailActivity : BaseBindingActivity<ActivityProviderDetailBinding>(ActivityProviderDetailBinding::inflate) {
    private val viewModel = ProviderDetailViewModel()
    private lateinit var provider: AiProvider

    override fun initData() {
        val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
        provider = AiProvider.ALL.firstOrNull { it.id == providerId } ?: AiProvider.ALL.first()
        viewModel.addObserver()
        viewModel.bindExisting(provider)
    }

    override fun initView() {
        binding.backButton.setOnClickListener { finish() }
        binding.titleView.text = provider.name
        binding.descriptionView.text = provider.description
        val snapshot = AppGraph.providerConfigService.readConfig()
        val config = snapshot.providers[provider.id] as? Map<*, *>
        val models = provider.defaultModels + listOf(getString(R.string.providers_custom_model))
        binding.modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        val existingApiKey = config?.get("apiKey")?.toString().orEmpty()
        val modelList = config?.get("models") as? List<*>
        val existingModel = (modelList?.firstOrNull() as? Map<*, *>)?.get("id")?.toString()
        binding.apiKeyInput.setText(existingApiKey)
        if (!existingModel.isNullOrBlank() && provider.defaultModels.contains(existingModel)) {
            binding.modelSpinner.setSelection(provider.defaultModels.indexOf(existingModel))
        } else if (!existingModel.isNullOrBlank()) {
            binding.modelSpinner.setSelection(models.lastIndex)
            binding.customModelInput.setText(existingModel)
            binding.customModelInputLayout.visibility = android.view.View.VISIBLE
        }
        binding.modelSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                binding.customModelInputLayout.visibility = if (position == models.lastIndex) android.view.View.VISIBLE else android.view.View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                binding.saveButton.isEnabled = !state.saving && !state.removing
                binding.removeButton.isEnabled = state.configured && !state.saving && !state.removing
                binding.progressBar.visibility = if (state.saving || state.removing) android.view.View.VISIBLE else android.view.View.GONE
                when (state.resultEvent) {
                    ProviderDetailViewModel.ResultEvent.SAVED -> {
                        Toast.makeText(
                            this@ProviderDetailActivity,
                            getString(R.string.providers_saved_message, state.providerName ?: provider.name),
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.consumeResultEvent()
                        finish()
                    }
                    ProviderDetailViewModel.ResultEvent.REMOVED -> {
                        Toast.makeText(
                            this@ProviderDetailActivity,
                            getString(R.string.providers_removed_message, state.providerName ?: provider.name),
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.consumeResultEvent()
                        finish()
                    }
                    null -> Unit
                }
                state.error?.let {
                    binding.errorView.text = it
                    binding.errorView.visibility = android.view.View.VISIBLE
                } ?: run { binding.errorView.visibility = android.view.View.GONE }
            }
        }
    }

    override fun initEvent() {
        binding.saveButton.setOnClickListener {
            val apiKey = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            val selected = binding.modelSpinner.selectedItem?.toString().orEmpty()
            val model = if (selected == getString(R.string.providers_custom_model)) {
                binding.customModelInput.text?.toString()?.trim().orEmpty()
            } else {
                selected
            }
            if (apiKey.isBlank()) {
                binding.errorView.text = getString(R.string.providers_api_key_required)
                binding.errorView.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            if (model.isBlank()) {
                binding.errorView.text = getString(R.string.providers_model_required)
                binding.errorView.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            viewModel.save(provider, apiKey, model)
        }
        binding.removeButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.providers_remove_title, provider.name))
                .setMessage(getString(R.string.providers_remove_confirm))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.chat_delete_confirm) { _, _ -> viewModel.remove(provider) }
                .show()
        }
    }

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
    }
}
