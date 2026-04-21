package ai.inmo.openclaw.ui.shell.schedule

import android.os.Bundle
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.FragmentScheduleBinding
import ai.inmo.openclaw.ui.common.BaseBindingFragment
import ai.inmo.openclaw.ui.shell.ShellDestination
import ai.inmo.openclaw.ui.shell.ShellSharedViewModel
import kotlinx.coroutines.launch

class ScheduleFragment : BaseBindingFragment<FragmentScheduleBinding>(FragmentScheduleBinding::inflate) {
    private val shellViewModel: ShellSharedViewModel by activityViewModels()
    private val viewModel: ScheduleViewModel by viewModels()
    private val adapter = ScheduleAdapter()

    override fun initView(savedInstanceState: Bundle?) {
        binding.taskRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.taskRecycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                binding.summaryPrimary.text = getString(R.string.schedule_summary_total, state.summary.totalCount)
                binding.summarySecondary.text = getString(R.string.schedule_summary_running, state.summary.runningCount)
                binding.summaryTertiary.text = getString(R.string.schedule_summary_paused, state.summary.pausedCount)
                binding.emptyView.isVisible = state.tasks.isEmpty()
                binding.filterAll.isChecked = state.filter == ScheduleFilter.ALL
                binding.filterRunning.isChecked = state.filter == ScheduleFilter.RUNNING
                binding.filterPaused.isChecked = state.filter == ScheduleFilter.PAUSED
                binding.filterCompleted.isChecked = state.filter == ScheduleFilter.COMPLETED
                adapter.submitList(state.tasks)
            }
        }
    }

    override fun initEvent() {
        binding.addTaskButton.setOnClickListener { viewModel.addTask() }
        binding.filterAll.setOnClickListener { viewModel.selectFilter(ScheduleFilter.ALL) }
        binding.filterRunning.setOnClickListener { viewModel.selectFilter(ScheduleFilter.RUNNING) }
        binding.filterPaused.setOnClickListener { viewModel.selectFilter(ScheduleFilter.PAUSED) }
        binding.filterCompleted.setOnClickListener { viewModel.selectFilter(ScheduleFilter.COMPLETED) }
        adapter.onToggleChanged = { task, enabled ->
            viewModel.toggleTask(task.id, enabled)
        }
        adapter.onCarryToChat = { task ->
            shellViewModel.launchTaskIntoChat(
                taskContext = "请基于以下定时任务继续协助我：任务名=${task.title}；描述=${task.summary}；执行计划=${task.scheduleText}；下次执行=${task.nextRunAt}。",
                sourceId = task.id
            )
        }
    }
}
