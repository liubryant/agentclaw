package ai.cjym.agentclaw.safety

object ContentSafetyGuard {
    const val DEFAULT_BLOCKED_REPLY: String =
        "这个请求涉及现实伤害、暴力或煽动性内容，我不能协助执行、策划或生成这类内容。可以改为讨论新闻背景、历史脉络、冲突降温、公共安全或法律合规角度。"

    enum class Surface {
        CHAT,
        IMAGE_PROMPT,
        VIDEO_PROMPT
    }

    data class Result(
        val allowed: Boolean,
        val reason: String? = null,
        val userMessage: String = DEFAULT_BLOCKED_REPLY
    )

    fun evaluate(text: String, surface: Surface = Surface.CHAT): Result {
        val normalized = normalize(text)
        if (normalized.isBlank()) return Result(allowed = true)

        val prohibitedTerm = PROHIBITED_TERMS.firstOrNull { normalized.contains(it) }
        val political = POLITICAL_CONTEXT.any { normalized.contains(it) }
        val violent = VIOLENCE_TERMS.any { normalized.contains(it) }
        val facilitation = FACILITATION_TERMS.any { normalized.contains(it) }
        val target = TARGETING_TERMS.any { normalized.contains(it) }
        val mediaDepiction = surface != Surface.CHAT && MEDIA_VIOLENCE_TERMS.any { normalized.contains(it) }
        val directViolentRequest = DIRECT_VIOLENT_PATTERNS.any { it.containsMatchIn(normalized) }

        val blocked = prohibitedTerm != null ||
            directViolentRequest ||
            mediaDepiction ||
            (political && violent && (facilitation || target)) ||
            (political && facilitation && target)

        return if (blocked) {
            Result(
                allowed = false,
                reason = when {
                    prohibitedTerm != null -> "prohibited_term"
                    directViolentRequest -> "direct_violent_facilitation"
                    mediaDepiction -> "media_violent_prompt"
                    else -> "political_violence"
                }
            )
        } else {
            Result(allowed = true)
        }
    }

    private fun normalize(value: String): String {
        return value
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val PROHIBITED_TERMS = listOf(
        "肢解", "坦克人", "割手腕", "抽烟打架"
    )

    private val POLITICAL_CONTEXT = listOf(
        "政治", "政府", "政权", "政党", "选举", "总统", "首相", "议员", "官员", "国家领导人",
        "抗议", "示威", "游行", "革命", "政变", "恐怖主义", "极端组织", "分裂主义",
        "politic", "government", "election", "president", "prime minister", "minister",
        "official", "protest", "riot", "revolution", "coup", "terror", "extremist"
    )

    private val VIOLENCE_TERMS = listOf(
        "杀", "暗杀", "刺杀", "袭击", "攻击", "炸", "爆炸", "爆破", "纵火", "枪击", "投毒",
        "伤害", "暴力", "武器", "炸弹", "燃烧瓶", "处决", "消灭", "血洗",
        "kill", "assassinate", "attack", "bomb", "explosive", "shoot", "poison", "weapon",
        "molotov", "execute", "massacre", "violent"
    )

    private val FACILITATION_TERMS = listOf(
        "怎么", "如何", "教程", "步骤", "计划", "策划", "组织", "动员", "招募", "煽动", "号召",
        "制作", "制造", "配方", "路线", "目标", "名单", "宣言", "口号", "文案",
        "how to", "tutorial", "steps", "plan", "organize", "mobilize", "recruit", "incite",
        "make", "build", "recipe", "manifesto", "slogan", "target list"
    )

    private val TARGETING_TERMS = listOf(
        "针对", "瞄准", "报复", "清除", "推翻", "占领", "冲击", "堵截", "威胁", "恐吓",
        "target", "retaliate", "overthrow", "occupy", "storm", "threaten", "intimidate"
    )

    private val MEDIA_VIOLENCE_TERMS = listOf(
        "暗杀", "刺杀", "爆炸", "爆破", "枪击", "血腥", "处决", "斩首", "燃烧瓶", "恐袭",
        "assassination", "explosion", "shooting", "bloody", "execution", "beheading", "terror attack"
    )

    private val DIRECT_VIOLENT_PATTERNS = listOf(
        Regex(".*(怎么|如何|教程|步骤|计划|策划|制作|制造).*(炸弹|爆炸物|燃烧瓶|枪击|暗杀|刺杀|投毒).*"),
        Regex(".*(炸弹|爆炸物|燃烧瓶|枪击|暗杀|刺杀|投毒).*(怎么|如何|教程|步骤|计划|策划|制作|制造).*"),
        Regex(".*(我要|帮我|准备|打算).*(杀|暗杀|刺杀|袭击|枪击|炸).*(总统|首相|官员|议员|政府|政党).*"),
        Regex(".*(总统|首相|官员|议员|政府|政党).*(杀|暗杀|刺杀|袭击|枪击|炸).*(我要|帮我|准备|打算).*"),
        Regex(".*(how to|steps to|plan to|make|build).*(bomb|explosive|molotov|assassinat|shoot|poison).*"),
        Regex(".*(bomb|explosive|molotov|assassinat|shoot|poison).*(how to|steps to|plan to|make|build).*"),
        Regex(".*(i want to|help me|planning to).*(kill|assassinat|attack|shoot|bomb).*(president|minister|official|government|party).*"),
        Regex(".*(president|minister|official|government|party).*(kill|assassinat|attack|shoot|bomb).*(i want to|help me|planning to).*")
    )
}
