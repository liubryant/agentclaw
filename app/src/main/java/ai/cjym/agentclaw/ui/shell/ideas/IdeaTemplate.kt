package ai.cjym.agentclaw.ui.shell.ideas

import ai.cjym.agentclaw.ui.shell.IdeaCategory
import androidx.annotation.ColorRes

data class IdeaTemplate(
    val id: String,
    val title: String,
    val subtitle: String,
    val promptTemplate: String,
    val tags: List<String>,
    val category: IdeaCategory,
    @ColorRes val accentColorRes: Int
)
