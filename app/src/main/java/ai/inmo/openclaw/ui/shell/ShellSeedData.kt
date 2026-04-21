package ai.inmo.openclaw.ui.shell

import ai.inmo.core_common.utils.context.AppProvider
import ai.inmo.openclaw.R
import ai.inmo.openclaw.ui.shell.ideas.IdeaTemplate
import ai.inmo.openclaw.ui.shell.schedule.ScheduleStatus
import ai.inmo.openclaw.ui.shell.schedule.ScheduledTaskUiModel

object ShellSeedData {
    fun ideaTemplates(): List<IdeaTemplate> {
        return listOf(
            IdeaTemplate(
                id = "idea_skill_onboarding",
                title = "安装你的第一个Skill",
                subtitle = "一键解锁自动化超能力",
                promptTemplate = AppProvider.get().getString(R.string.setting_first_skill_prompt),
                tags = listOf("自动化", "新手"),
                category = IdeaCategory.AUTOMATION,
                accentColorRes = R.color.idea_soft_blue
            ),
            IdeaTemplate(
                id = "idea_life_assistant",
                title = "自动化生活助手",
                subtitle = "自动下载应用、淘宝购物",
                promptTemplate = "打开应用宝帮我下载 应用名称：",
                tags = listOf("生活", "执行"),
                category = IdeaCategory.LIFE,
                accentColorRes = R.color.idea_soft_orange
            ),
            IdeaTemplate(
                id = "idea_docs",
                title = "办公文档生成",
                subtitle = "一句话写竞品分析、会议纪要",
                promptTemplate = "帮我生成一份办公文档助手模板，支持竞品分析、会议纪要和待办事项输出。",
                tags = listOf("办公", "文档"),
                category = IdeaCategory.WORK,
                accentColorRes = R.color.idea_soft_pink
            )
        )
    }

    fun scheduledTasks(): List<ScheduledTaskUiModel> {
        return listOf(
            ScheduledTaskUiModel(
                id = "task_morning_briefing",
                title = "晨间简报",
                summary = "每天 08:30 汇总天气、日历和待办事项",
                scheduleText = "每天 08:30",
                status = ScheduleStatus.RUNNING,
                lastRunAt = "今天 08:30",
                nextRunAt = "明天 08:30",
                enabled = true
            ),
            ScheduledTaskUiModel(
                id = "task_doc_digest",
                title = "文档摘要同步",
                summary = "每个工作日 18:00 汇总当天文档更新",
                scheduleText = "工作日 18:00",
                status = ScheduleStatus.PAUSED,
                lastRunAt = "昨天 18:00",
                nextRunAt = "已暂停",
                enabled = false
            ),
            ScheduledTaskUiModel(
                id = "task_weekly_review",
                title = "周报草稿",
                summary = "每周五自动整理工作记录，生成周报草稿",
                scheduleText = "每周五 17:00",
                status = ScheduleStatus.COMPLETED,
                lastRunAt = "上周五 17:00",
                nextRunAt = "本周五 17:00",
                enabled = true
            )
        )
    }
}

enum class IdeaCategory {
    ALL,
    WORK,
    AUTOMATION,
    LIFE
}
