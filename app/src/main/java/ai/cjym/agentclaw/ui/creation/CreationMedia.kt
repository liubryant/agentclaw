package ai.cjym.agentclaw.ui.creation

data class CreationMedia(
    val assetPath: String,
    val type: Type
) {
    enum class Type { IMAGE, VIDEO }
}
