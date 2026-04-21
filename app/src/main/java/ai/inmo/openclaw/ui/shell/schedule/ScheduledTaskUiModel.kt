package ai.inmo.openclaw.ui.shell.schedule

data class ScheduledTaskUiModel(
    val id: String,
    val title: String,
    val summary: String,
    val scheduleText: String,
    val status: ScheduleStatus,
    val lastRunAt: String,
    val nextRunAt: String,
    val enabled: Boolean
)

enum class ScheduleStatus {
    RUNNING,
    PAUSED,
    COMPLETED
}

enum class ScheduleFilter {
    ALL,
    RUNNING,
    PAUSED,
    COMPLETED
}
