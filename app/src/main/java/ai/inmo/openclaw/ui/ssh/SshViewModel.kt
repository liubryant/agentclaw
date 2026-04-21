package ai.inmo.openclaw.ui.ssh

import ai.inmo.core_common.ui.viewModel.BaseViewModel
import ai.inmo.openclaw.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SshViewModel : BaseViewModel() {
    enum class MessageEvent {
        PASSWORD_UPDATED
    }

    data class SshUiState(
        val installed: Boolean = false,
        val running: Boolean = false,
        val port: Int = 8022,
        val ips: List<String> = emptyList(),
        val loading: Boolean = true,
        val busy: Boolean = false,
        val messageEvent: MessageEvent? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(SshUiState())
    val state: StateFlow<SshUiState> = _state.asStateFlow()

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        launchIo {
            try {
                _state.value = SshUiState(
                    installed = AppGraph.sshRepository.isInstalled(),
                    running = AppGraph.sshRepository.isRunning(),
                    port = AppGraph.sshRepository.getPort(),
                    ips = AppGraph.sshRepository.getIpAddresses(),
                    loading = false
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, error = t.message)
            }
        }
    }

    fun toggle(port: Int) {
        _state.value = _state.value.copy(busy = true, messageEvent = null, error = null)
        launchIo {
            try {
                if (_state.value.running) AppGraph.sshRepository.stop() else AppGraph.sshRepository.start(port)
                Thread.sleep(if (_state.value.running) 500 else 2000)
                refresh()
                _state.value = _state.value.copy(busy = false)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(busy = false, error = t.message)
            }
        }
    }

    fun setPassword(password: String) {
        _state.value = _state.value.copy(busy = true, messageEvent = null, error = null)
        launchIo {
            try {
                AppGraph.sshRepository.setRootPassword(password)
                _state.value = _state.value.copy(busy = false, messageEvent = MessageEvent.PASSWORD_UPDATED)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(busy = false, error = t.message)
            }
        }
    }

    fun consumeMessageEvent() {
        _state.value = _state.value.copy(messageEvent = null)
    }
}
