package ai.inmo.openclaw.ui.fancyideas

sealed interface FancyIdeasListItem

data class FancyIdeasHeaderItem(
    val title: String
) : FancyIdeasListItem

data class FancyIdeasItem(
    val id: String,
    val iconResId: Int,
    val title: String,
    val subtitle: String,
    val scenario: String,
    val prompt: String,
    val presetUserPrompt: String,
    val presetAssistantReply: String
) : FancyIdeasListItem
