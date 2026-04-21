package ai.inmo.openclaw.di

import ai.inmo.core_common.utils.context.AppProvider
import ai.inmo.openclaw.CapabilityServiceClient
import ai.inmo.openclaw.capability.SystemCommandExecutor
import ai.inmo.openclaw.data.local.prefs.PreferencesManager
import ai.inmo.openclaw.data.repository.ChatRepository
import ai.inmo.openclaw.data.repository.ChatService
import ai.inmo.openclaw.data.repository.ArtifactExportRepository
import ai.inmo.openclaw.data.repository.GatewayConfigService
import ai.inmo.openclaw.data.repository.GatewayManager
import ai.inmo.openclaw.data.repository.GithubSkillImportService
import ai.inmo.openclaw.data.repository.NodeManager
import ai.inmo.openclaw.data.repository.PackageService
import ai.inmo.openclaw.data.repository.ProviderConfigService
import ai.inmo.openclaw.data.repository.SetupCoordinator
import ai.inmo.openclaw.data.repository.SnapshotManager
import ai.inmo.openclaw.data.repository.SshRepository
import ai.inmo.openclaw.data.remote.api.BotNetworkModule
import ai.inmo.openclaw.data.repository.SyncedChatWsManager

object AppGraph {
    private val appContext by lazy { AppProvider.get().applicationContext }

    val preferences by lazy { PreferencesManager(appContext) }
    val gatewayConfigService by lazy { GatewayConfigService(appContext, preferences) }
    val githubSkillImportService by lazy { GithubSkillImportService(appContext) }
    val capabilityServiceClient by lazy { CapabilityServiceClient(appContext).also { it.bind() } }
    val systemCommandExecutor by lazy { SystemCommandExecutor(appContext, capabilityServiceClient) }
    val gatewayManager by lazy { GatewayManager(appContext, preferences, gatewayConfigService) }
    val nodeManager by lazy { NodeManager(appContext, preferences) }
    val packageService by lazy { PackageService(appContext) }
    val providerConfigService by lazy { ProviderConfigService(appContext) }
    val setupCoordinator by lazy { SetupCoordinator(appContext, preferences) }
    val snapshotManager by lazy { SnapshotManager(appContext, preferences) }
    val sshRepository by lazy { SshRepository(appContext) }
    val chatRepository by lazy { ChatRepository(appContext) }
    val chatService by lazy { ChatService(appContext) }
    val syncedChatWsManager by lazy { SyncedChatWsManager(appContext, preferences) }
    val artifactExportRepository by lazy { ArtifactExportRepository(appContext) }
    val botApi by lazy { BotNetworkModule.botApi }
}
