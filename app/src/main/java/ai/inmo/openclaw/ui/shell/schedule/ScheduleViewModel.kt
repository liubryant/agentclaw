package ai.inmo.openclaw.ui.shell.schedule

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.ui.shell.ShellSeedData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScheduleSummaryUiState(
    val totalCount: Int = 0,
    val runningCount: Int = 0,
    val pausedCount: Int = 0
)

data class ScheduleUiState(
    val filter: ScheduleFilter = ScheduleFilter.ALL,
    val summary: ScheduleSummaryUiState = ScheduleSummaryUiState(),
    val tasks: List<ScheduledTaskUiModel> = ShellSeedData.scheduledTasks()
)

class ScheduleViewModel : BaseViewModel() {
    private val _allTasks = MutableStateFlow(ShellSeedData.scheduledTasks())
    private val _uiState = MutableStateFlow(buildUiState(_allTasks.value, ScheduleFilter.ALL))
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    fun selectFilter(filter: ScheduleFilter) {
        _uiState.value = buildUiState(_allTasks.value, filter)
    }

    fun toggleTask(taskId: String, enabled: Boolean) {
        _allTasks.update { tasks ->
            tasks.map { task ->
                if (task.id != taskId) task else task.copy(
                    enabled = enabled,
                    status = when {
                        enabled && task.status == ScheduleStatus.PAUSED -> ScheduleStatus.RUNNING
                        !enabled -> ScheduleStatus.PAUSED
                        else -> task.status
                    },
                    nextRunAt = if (enabled) task.nextRunAt.takeUnless { it == "已暂停" } ?: "今天 21:00" else "已暂停"
                )
            }
        }
        _uiState.value = buildUiState(_allTasks.value, _uiState.value.filter)
    }

    fun addTask() {
        val nextIndex = _allTasks.value.size + 1
        _allTasks.update { tasks ->
            listOf(
                ScheduledTaskUiModel(
                    id = "task_custom_$nextIndex",
                    title = "新建任务 $nextIndex",
                    summary = "待补充执行条件与触发动作",
                    scheduleText = "每天 21:${10 + nextIndex}",
                    status = ScheduleStatus.PAUSED,
                    lastRunAt = "尚未运行",
                    nextRunAt = "已暂停",
                    enabled = false
                )
            ) + tasks
        }
        _uiState.value = buildUiState(_allTasks.value, _uiState.value.filter)
    }

    private fun buildUiState(
        allTasks: List<ScheduledTaskUiModel>,
        filter: ScheduleFilter
    ): ScheduleUiState {
        val filtered = when (filter) {
            ScheduleFilter.ALL -> allTasks
            ScheduleFilter.RUNNING -> allTasks.filter { it.status == ScheduleStatus.RUNNING }
            ScheduleFilter.PAUSED -> allTasks.filter { it.status == ScheduleStatus.PAUSED }
            ScheduleFilter.COMPLETED -> allTasks.filter { it.status == ScheduleStatus.COMPLETED }
        }
        return ScheduleUiState(
            filter = filter,
            summary = ScheduleSummaryUiState(
                totalCount = allTasks.size,
                runningCount = allTasks.count { it.status == ScheduleStatus.RUNNING },
                pausedCount = allTasks.count { it.status == ScheduleStatus.PAUSED }
            ),
            tasks = filtered
        )
    }
}
