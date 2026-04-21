package ai.inmo.openclaw.data.repository

import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.local.prefs.PreferencesManager
import ai.inmo.openclaw.data.remote.api.NetworkModule
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.domain.model.GatewayState
import ai.inmo.openclaw.domain.model.GatewayStatus
import ai.inmo.openclaw.service.gateway.GatewayService
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GatewayManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val gatewayConfigService: GatewayConfigService
) {
    companion object {
        private const val INITIAL_HEALTH_DELAY_MS = 30_000L
        private const val STARTUP_GRACE_MS = 120_000L
        private val TOKEN_URL_REGEX = Regex(
            """https?://(?:127\.0\.0\.1|localhost):${AppConstants.GATEWAY_PORT}/#token=[0-9a-fA-F]+"""
        )

        internal fun extractTokenizedDashboardUrl(line: String): String? {
            return TOKEN_URL_REGEX.find(line)?.value
        }

        internal fun mergeDashboardUrl(currentUrl: String?, logLine: String): String? {
            val tokenized = extractTokenizedDashboardUrl(logLine)
            return tokenized ?: currentUrl
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val _state = MutableStateFlow(
        GatewayState(
            status = GatewayStatus.STOPPED,
            dashboardUrl = preferencesManager.dashboardUrl ?: AppConstants.GATEWAY_URL
        )
    )
    val state: StateFlow<GatewayState> = _state.asStateFlow()

    @Volatile
    private var startupStartedAt: Long? = null

    init {
        scope.launch {
            GatewayService.logFlow.collect { line ->
                appendLog(line)
                syncDashboardUrlFromLog(line)
            }
        }
        scope.launch {
            gatewayConfigService.syncDashboardUrlFromConfig()
            updateStateFromPreferences()
        }
        scope.launch { monitorHealth() }
    }

    fun start() {
        scope.launch {
            startMutex.withLock {
                startupStartedAt = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    status = GatewayStatus.STARTING,
                    errorMessage = null,
                    dashboardUrl = preferencesManager.dashboardUrl ?: AppConstants.GATEWAY_URL
                )

                runCatching { gatewayConfigService.prepareForLaunch() }
                    .onSuccess { prepared ->
                        _state.value = _state.value.copy(
                            dashboardUrl = prepared.dashboardUrl ?: preferencesManager.dashboardUrl ?: AppConstants.GATEWAY_URL,
                            errorMessage = null
                        )
                        GatewayService.clearLastError()
                        GatewayService.start(context)
                    }
                    .onFailure { error ->
                        val message = error.message ?: "Failed to prepare gateway config"
                        GatewayService.recordLaunchFailure(message)
                        _state.value = _state.value.copy(
                            status = GatewayStatus.ERROR,
                            errorMessage = message
                        )
                    }
            }
        }
    }

    fun refresh() {
        scope.launch { updateStateOnce() }
    }

    fun stop() {
        GatewayService.stop(context)
        startupStartedAt = null
        GatewayService.clearLastError()
        _state.value = _state.value.copy(status = GatewayStatus.STOPPED, errorMessage = null)
    }

    private suspend fun monitorHealth() {
        while (true) {
            updateStateOnce()
            delay(AppConstants.HEALTH_CHECK_INTERVAL_MS)
        }
    }

    private suspend fun updateStateOnce() {
        val processAlive = GatewayService.isRunning || GatewayService.isProcessAlive()
        val now = System.currentTimeMillis()
        val startedAt = startupStartedAt
        val withinInitialDelay = startedAt != null && now - startedAt < INITIAL_HEALTH_DELAY_MS
        val withinStartupGrace = startedAt != null && now - startedAt < STARTUP_GRACE_MS
        val fallbackUrl = preferencesManager.dashboardUrl ?: AppConstants.GATEWAY_URL
        val lastError = GatewayService.lastErrorMessage

        if (!processAlive) {
            _state.value = when {
                !lastError.isNullOrBlank() -> _state.value.copy(
                    status = GatewayStatus.ERROR,
                    errorMessage = lastError,
                    dashboardUrl = fallbackUrl
                )
                _state.value.status == GatewayStatus.STARTING && withinStartupGrace -> _state.value.copy(
                    status = GatewayStatus.STARTING,
                    dashboardUrl = fallbackUrl
                )
                else -> _state.value.copy(
                    status = GatewayStatus.STOPPED,
                    errorMessage = null,
                    dashboardUrl = fallbackUrl
                )
            }
            return
        }

        if (withinInitialDelay) {
            _state.value = _state.value.copy(
                status = GatewayStatus.STARTING,
                errorMessage = null,
                dashboardUrl = fallbackUrl
            )
            return
        }

        val healthy = runCatching {
            NetworkModule.gatewayApi.healthCheck().isSuccessful
        }.getOrDefault(false)

        _state.value = if (healthy) {
            AppGraph.syncedChatWsManager.connect()
            _state.value.copy(
                status = GatewayStatus.RUNNING,
                errorMessage = null,
                startedAt = _state.value.startedAt ?: now,
                dashboardUrl = fallbackUrl
            )
        } else {
            _state.value.copy(
                status = GatewayStatus.STARTING,
                errorMessage = if (withinStartupGrace) null else lastError,
                dashboardUrl = fallbackUrl
            )
        }
    }

    private fun appendLog(line: String) {
        val nextLogs = (_state.value.logs + line).takeLast(200)
        _state.value = _state.value.copy(logs = nextLogs)
    }

    private fun syncDashboardUrlFromLog(line: String) {
        val url = mergeDashboardUrl(preferencesManager.dashboardUrl, line) ?: return
        if (url == preferencesManager.dashboardUrl && _state.value.dashboardUrl == url) return
        preferencesManager.dashboardUrl = url
        _state.value = _state.value.copy(dashboardUrl = url)
    }

    private fun updateStateFromPreferences() {
        _state.value = _state.value.copy(
            dashboardUrl = preferencesManager.dashboardUrl ?: AppConstants.GATEWAY_URL
        )
    }
}
