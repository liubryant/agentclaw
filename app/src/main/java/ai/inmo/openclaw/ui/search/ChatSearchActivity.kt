package ai.inmo.openclaw.ui.search

import ai.inmo.core_common.ui.activity.BaseBindingActivity
import ai.inmo.openclaw.databinding.ActivityChatSearchBinding
import android.content.Intent
import android.view.View
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatSearchActivity : BaseBindingActivity<ActivityChatSearchBinding>(
    ActivityChatSearchBinding::inflate
) {
    companion object {
        const val EXTRA_RESULT_SESSION_KEY = "extra_result_session_key"
    }

    private val viewModel: ChatSearchViewModel by viewModels()
    private val adapter = ChatSearchAdapter()

    override fun initView() {
        binding.searchResultsRecycler.layoutManager = LinearLayoutManager(this)
        binding.searchResultsRecycler.adapter = adapter
        binding.searchPanel.setOnClickListener { }
        binding.searchInput.requestFocus()
        updateClearButton("")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest(::renderState)
            }
        }
    }

    override fun initData() = Unit

    override fun initEvent() {
        binding.scrimView.setOnClickListener { finish() }
        binding.closeButton.setOnClickListener { finish() }
        binding.clearButton.setOnClickListener { binding.searchInput.setText("") }
        binding.searchInput.doAfterTextChanged { editable ->
            val query = editable?.toString().orEmpty()
            updateClearButton(query)
            viewModel.updateQuery(query)
        }
        adapter.onResultClick = { sessionKey ->
            openShell(sessionKey)
        }
    }

    private fun renderState(state: ChatSearchUiState) {
        when (state) {
            ChatSearchUiState.Empty -> {
                binding.loadingView.visibility = View.GONE
                binding.searchResultsRecycler.visibility = View.GONE
                binding.emptyStateView.visibility = View.VISIBLE
                binding.emptyStateView.setText(ai.inmo.openclaw.R.string.chat_search_empty)
            }

            ChatSearchUiState.Loading -> {
                binding.loadingView.visibility = View.VISIBLE
                binding.searchResultsRecycler.visibility = View.GONE
                binding.emptyStateView.visibility = View.GONE
            }

            is ChatSearchUiState.NoResults -> {
                binding.loadingView.visibility = View.GONE
                binding.searchResultsRecycler.visibility = View.GONE
                binding.emptyStateView.visibility = View.VISIBLE
                binding.emptyStateView.setText(ai.inmo.openclaw.R.string.chat_search_no_results)
            }

            is ChatSearchUiState.Results -> {
                binding.loadingView.visibility = View.GONE
                binding.searchResultsRecycler.visibility = View.VISIBLE
                binding.emptyStateView.visibility = View.GONE
                adapter.submitResults(state.data, state.query)
            }

            is ChatSearchUiState.Error -> {
                binding.loadingView.visibility = View.GONE
                binding.searchResultsRecycler.visibility = View.GONE
                binding.emptyStateView.visibility = View.VISIBLE
                binding.emptyStateView.text = state.message.ifBlank {
                    getString(ai.inmo.openclaw.R.string.chat_search_error)
                }
            }
        }
    }

    private fun updateClearButton(query: String) {
        binding.clearButton.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
    }

    private fun openShell(sessionKey: String) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_RESULT_SESSION_KEY, sessionKey)
        )
        finish()
    }
}
