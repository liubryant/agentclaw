package ai.inmo.openclaw.ui.shell.ideas

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.ui.shell.IdeaCategory
import ai.inmo.openclaw.ui.shell.ShellSeedData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class IdeasUiState(
    val selectedCategory: IdeaCategory = IdeaCategory.ALL,
    val templates: List<IdeaTemplate> = ShellSeedData.ideaTemplates()
)

class IdeasViewModel : BaseViewModel() {
    private val allTemplates = ShellSeedData.ideaTemplates()
    private val _uiState = MutableStateFlow(IdeasUiState())
    val uiState: StateFlow<IdeasUiState> = _uiState.asStateFlow()

    fun selectCategory(category: IdeaCategory) {
        _uiState.update {
            it.copy(
                selectedCategory = category,
                templates = allTemplates.filterBy(category)
            )
        }
    }

    private fun List<IdeaTemplate>.filterBy(category: IdeaCategory): List<IdeaTemplate> {
        if (category == IdeaCategory.ALL) return this
        return filter { it.category == category }
    }
}
