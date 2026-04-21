package ai.inmo.openclaw.ui.fancyideas

import ai.inmo.openclaw.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel

class FancyIdeasViewModel(application: Application) : AndroidViewModel(application) {
    val tokenUsageText: String = "已用0万，剩余100%"

    private val titles: Array<String> =
        application.resources.getStringArray(R.array.fancy_ideas_titles)
    private val subtitles: Array<String> =
        application.resources.getStringArray(R.array.fancy_ideas_subtitles)
    private val useCases: Array<String> =
        application.resources.getStringArray(R.array.fancy_ideas_usecases)
    private val prompts: Array<String> =
        application.resources.getStringArray(R.array.fancy_ideas_prompts)
    private val replies: Array<String> =
        application.resources.getStringArray(R.array.fancy_ideas_replies)
    private val categoryTitles = listOf(
        application.getString(R.string.fancy_idea_type_gxbg),
        application.getString(R.string.fancy_idea_type_glsh),
        application.getString(R.string.fancy_idea_type_qxzz),
        application.getString(R.string.fancy_idea_type_xqbl),
        application.getString(R.string.fancy_idea_type_czdc)
    )
    private val whatCanDoSuffix = application.getString(R.string.fancy_idea_detail_whatcando)

    val items: List<FancyIdeasItem> =
        titles.indices.map { index ->
            FancyIdeasItem(
                id = "fancy_idea_${index + 1}",
                iconResId = when (index) {
                    0 -> R.drawable.ic_ideas_01
                    1 -> R.drawable.ic_ideas_02
                    2 -> R.drawable.ic_ideas_03
                    3 -> R.drawable.ic_ideas_04
                    4 -> R.drawable.ic_ideas_05
                    5 -> R.drawable.ic_ideas_06
                    6 -> R.drawable.ic_ideas_07
                    7 -> R.drawable.ic_ideas_08
                    8 -> R.drawable.ic_ideas_09
                    9 -> R.drawable.ic_ideas_10
                    10 -> R.drawable.ic_ideas_11
                    11 -> R.drawable.ic_ideas_12
                    12 -> R.drawable.ic_ideas_13
                    13 -> R.drawable.ic_ideas_14
                    14 -> R.drawable.ic_ideas_15
                    15 -> R.drawable.ic_ideas_16
                    16 -> R.drawable.ic_ideas_17
                    17 -> R.drawable.ic_ideas_18
                    18 -> R.drawable.ic_ideas_19
                    else -> R.drawable.ic_ideas_20
                },
                title = titles[index],
                subtitle = subtitles.getOrElse(index) { "" },
                scenario = useCases.getOrElse(index) { "" },
                prompt = prompts.getOrElse(index) { "" },
                presetUserPrompt = titles[index] + whatCanDoSuffix,
                presetAssistantReply = replies.getOrElse(index) { "" }
            )
        }

    val listItems: List<FancyIdeasListItem> = buildList {
        addGroup(categoryTitles[0], items.subList(0, 3))
        addGroup(categoryTitles[1], items.subList(3, 9))
        addGroup(categoryTitles[2], items.subList(9, 13))
        addGroup(categoryTitles[3], items.subList(13, 18))
        addGroup(categoryTitles[4], items.subList(18, 20))
    }

    private fun MutableList<FancyIdeasListItem>.addGroup(
        title: String,
        groupItems: List<FancyIdeasItem>
    ) {
        add(FancyIdeasHeaderItem(title))
        addAll(groupItems)
    }
}
