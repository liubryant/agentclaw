package ai.cjym.agentclaw.di

import ai.inmo.core_common.utils.context.AppProvider
import ai.cjym.agentclaw.CapabilityServiceClient
import ai.cjym.agentclaw.capability.SystemCommandExecutor
import ai.cjym.agentclaw.data.local.prefs.PreferencesManager
import ai.cjym.agentclaw.data.repository.ArtifactExportRepository
import ai.cjym.agentclaw.data.repository.ChatRepository
import ai.cjym.agentclaw.data.repository.ChatService
import ai.cjym.agentclaw.data.repository.GithubSkillImportService
import ai.cjym.agentclaw.data.repository.NodeManager
import ai.cjym.agentclaw.data.repository.PackageService
import ai.cjym.agentclaw.data.repository.ProviderConfigService
import ai.cjym.agentclaw.data.repository.SshRepository
import ai.cjym.agentclaw.data.remote.api.BotNetworkModule
import ai.cjym.agentclaw.data.repository.SyncedChatWsManager

object AppGraph {
    private val appContext by lazy { AppProvider.get().applicationContext }

    val preferences by lazy { PreferencesManager(appContext) }
    val githubSkillImportService by lazy { GithubSkillImportService(appContext) }
    val capabilityServiceClient by lazy { CapabilityServiceClient(appContext).also { it.bind() } }
    val systemCommandExecutor by lazy { SystemCommandExecutor(appContext, capabilityServiceClient) }
    val nodeManager by lazy { NodeManager(appContext, preferences) }
    val packageService by lazy { PackageService(appContext) }
    val providerConfigService by lazy { ProviderConfigService(appContext) }
    val sshRepository by lazy { SshRepository(appContext) }
    val chatRepository by lazy { ChatRepository(appContext) }
    val chatService by lazy { ChatService(appContext) }
    val syncedChatWsManager by lazy { SyncedChatWsManager(appContext, preferences) }
    val artifactExportRepository by lazy { ArtifactExportRepository(appContext) }
    val botApi by lazy { BotNetworkModule.botApi }
}
