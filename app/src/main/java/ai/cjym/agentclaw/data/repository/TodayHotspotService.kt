package ai.cjym.agentclaw.data.repository

import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.data.local.prefs.PreferencesManager
import ai.cjym.agentclaw.data.remote.api.NetworkModule
import ai.cjym.agentclaw.ui.shell.IdeaCategory
import ai.cjym.agentclaw.ui.shell.ShellSeedData
import ai.cjym.agentclaw.ui.shell.ideas.IdeaTemplate
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class TodayHotspotService(context: Context) {
    private val appContext = context.applicationContext
    private val client = NetworkModule.okHttpClient

    private val baseUrl: String
        get() = PreferencesManager(appContext).agentclawBaseUrl.trimEnd('/').removeSuffix("/v1")

    suspend fun loadTemplates(): Result<List<IdeaTemplate>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/im/bot/navi/hotspots")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("今日热点加载失败（HTTP ${response.code}）")
                val root = JSONObject(response.body?.string().orEmpty())
                val code = root.opt("code")?.toString()
                if (code != "0") throw IllegalStateException(root.optString("msg", "今日热点加载失败"))
                val array = root.optJSONArray("data") ?: throw IllegalStateException("今日热点数据为空")
                val defaults = ShellSeedData.ideaTemplates()
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val title = item.optString("title").trim()
                        val subtitle = item.optString("subtitle").trim()
                        val prompt = item.optString("promptTemplate").trim()
                        if (title.isEmpty() || subtitle.isEmpty() || prompt.isEmpty()) continue
                        val fixed = defaults.getOrNull(index)
                        add(IdeaTemplate(
                            id = fixed?.id ?: "idea_hotspot_${item.optLong("id", index.toLong())}",
                            title = title,
                            subtitle = subtitle,
                            promptTemplate = prompt,
                            tags = fixed?.tags ?: listOf("热点", "推荐"),
                            category = fixed?.category ?: IdeaCategory.WORK,
                            accentColorRes = fixed?.accentColorRes ?: R.color.idea_soft_pink
                        ))
                    }
                }.ifEmpty { throw IllegalStateException("今日热点没有可展示内容") }
            }
        }
    }
}
