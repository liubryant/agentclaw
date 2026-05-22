package ai.cjym.agentclaw.ui.shell

data class ShellUiState(
    val currentDestination: ShellDestination = ShellDestination.CHAT,
    val sidebarQuery: String = "",
    val chatDrafts: Map<String, String> = emptyMap(),
    val chatEntryModes: Map<String, ChatEntryMode> = emptyMap(),
    val topBarTitle: String = "",
    val topBarSubtitle: String = "",
    val selectedIdeaId: String? = null,
    val selectedTaskId: String? = null,
    val usageText: String = ""
)

enum class ChatEntryMode {
    DEFAULT,
    IMAGE,
    VIDEO
}

data class PresetConversation(
    val sourceId: String,
    val userPrompt: String,
    val assistantReply: String
)

sealed interface ShellEvent {
    data object ClearChatComposerFocus : ShellEvent

    data class OpenChatDraft(
        val draft: String,
        val sourceId: String
    ) : ShellEvent

    data class OpenChatInNewSession(
        val draft: String,
        val sourceId: String,
        val entryMode: ChatEntryMode = ChatEntryMode.DEFAULT
    ) : ShellEvent

    data class OpenChatInNewSessionWithPresetConversation(
        val conversation: PresetConversation
    ) : ShellEvent
}
