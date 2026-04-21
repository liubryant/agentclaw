package ai.inmo.openclaw.ui.shell.ideas

import android.os.Bundle
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.FragmentIdeasBinding
import ai.inmo.openclaw.ui.common.BaseBindingFragment
import ai.inmo.openclaw.ui.shell.IdeaCategory
import ai.inmo.openclaw.ui.shell.ShellDestination
import ai.inmo.openclaw.ui.shell.ShellSharedViewModel
import kotlinx.coroutines.launch

class IdeasFragment : BaseBindingFragment<FragmentIdeasBinding>(FragmentIdeasBinding::inflate) {
    private val shellViewModel: ShellSharedViewModel by activityViewModels()
    private val viewModel: IdeasViewModel by viewModels()
    private val adapter = IdeasAdapter()

    override fun initView(savedInstanceState: Bundle?) {
        binding.ideaRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.ideaRecycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                adapter.submitList(state.templates)
                binding.emptyView.isVisible = state.templates.isEmpty()
                binding.filterAll.isChecked = state.selectedCategory == IdeaCategory.ALL
                binding.filterWork.isChecked = state.selectedCategory == IdeaCategory.WORK
                binding.filterAutomation.isChecked = state.selectedCategory == IdeaCategory.AUTOMATION
                binding.filterLife.isChecked = state.selectedCategory == IdeaCategory.LIFE
            }
        }
    }

    override fun initEvent() {
        adapter.onUseClick = { idea ->
            shellViewModel.launchIdeaIntoChat(idea.promptTemplate, idea.id)
        }
        binding.filterAll.setOnClickListener { viewModel.selectCategory(IdeaCategory.ALL) }
        binding.filterWork.setOnClickListener { viewModel.selectCategory(IdeaCategory.WORK) }
        binding.filterAutomation.setOnClickListener { viewModel.selectCategory(IdeaCategory.AUTOMATION) }
        binding.filterLife.setOnClickListener { viewModel.selectCategory(IdeaCategory.LIFE) }
    }
}
