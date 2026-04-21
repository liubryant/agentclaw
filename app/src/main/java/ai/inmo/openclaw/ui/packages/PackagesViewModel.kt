package ai.inmo.openclaw.ui.packages

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.OptionalPackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PackagesViewModel : BaseViewModel() {
    data class PackageItem(
        val pkg: OptionalPackage,
        val installed: Boolean
    )

    data class PackagesUiState(
        val items: List<PackageItem> = emptyList(),
        val loading: Boolean = true
    )

    private val _state = MutableStateFlow(PackagesUiState())
    val state: StateFlow<PackagesUiState> = _state.asStateFlow()

    fun refresh() {
        val statuses = AppGraph.packageService.checkAllStatuses()
        _state.value = PackagesUiState(
            items = OptionalPackage.ALL.map { PackageItem(it, statuses[it.id] == true) },
            loading = false
        )
    }
}
