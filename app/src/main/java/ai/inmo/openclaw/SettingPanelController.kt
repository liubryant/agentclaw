package ai.inmo.openclaw

import ai.inmo.openclaw.constants.AppConstants
import ai.inmo.openclaw.data.repository.GithubSkillImportService
import ai.inmo.openclaw.di.AppGraph
import ai.inmo.openclaw.ui.shell.ShellActivity
import ai.inmo.openclaw.util.hideKeyboard
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

class SettingPanelController(
    private val context: Context,
    private val rootView: View,
    private val popupHostView: View = rootView
) {
    private companion object {
        private val NON_REMOVABLE_BUILTIN_SKILLS = setOf("dev_ctrl", "multi-search-engine", "pdf", "pptx", "html-default")
        private const val SKILL_CACHE_TTL_MS = 30_000L
        private const val SKILL_TRACE_TAG = "SettingSkillTrace"

        @Volatile
        private var cachedBuiltinSkillNames: List<String>? = null

        @Volatile
        private var cachedSkillsSnapshot: List<SkillItem>? = null

        @Volatile
        private var cachedSkillsTimestamp: Long = 0L
    }

    private var addSkillPopupWindow: PopupWindow? = null
    private var removeSkillPopupWindow: PopupWindow? = null
    private var closeSkillPopupWindow: PopupWindow? = null
    private var skillDetailPopupWindow: PopupWindow? = null
    private var githubImportPopupWindow: PopupWindow? = null
    private var legalWebPopupWindow: PopupWindow? = null
    private var skillContainer: LinearLayout? = null
    private var skillSearchInput: EditText? = null
    private val githubSkillImportService: GithubSkillImportService by lazy { AppGraph.githubSkillImportService }
    private val runtimeSkillsDir: File by lazy {
        File(context.filesDir, "rootfs/ubuntu/root/.openclaw/skills")
    }
    private val runtimeDisabledSkillsDir: File by lazy {
        File(context.filesDir, "rootfs/ubuntu/root/.openclaw/skills_disabled")
    }
    private val workspaceSkillsDir: File by lazy {
        File(context.filesDir, "rootfs/ubuntu/root/.openclaw/workspace/skills")
    }
    private val workspaceDisabledSkillsDir: File by lazy {
        File(context.filesDir, "rootfs/ubuntu/root/.openclaw/workspace/skills_disabled")
    }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val skills = mutableListOf<SkillItem>()
    private var currentSkillQuery: String = ""

    private data class SkillItem(
        val id: String,
        val title: String,
        val content: String,
        var enabled: Boolean,
        val tag: String,
        val source: SkillSource
    )

    private enum class SkillSource {
        BUILTIN,
        GITHUB,
        USER_ADDED
    }

    private val userAddedSkillTag: String
        get() = context.getString(R.string.setting_user_added_skill)

    fun bind() {
        bindAboutInfo()
        bindMenu()
        bindActions()
        bindAboutActions()
        showPage(Page.SKILL)
    }

    fun release() {
        addSkillPopupWindow?.dismiss()
        addSkillPopupWindow = null
        removeSkillPopupWindow?.dismiss()
        removeSkillPopupWindow = null
        closeSkillPopupWindow?.dismiss()
        closeSkillPopupWindow = null
        skillDetailPopupWindow?.dismiss()
        skillDetailPopupWindow = null
        githubImportPopupWindow?.dismiss()
        githubImportPopupWindow = null
        legalWebPopupWindow?.dismiss()
        legalWebPopupWindow = null
        ioExecutor.shutdownNow()
    }

    private fun bindAboutInfo() {
        rootView.findViewById<TextView>(R.id.tvVersion)?.text = "V${getAppVersionName()}"
    }

    private fun getAppVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    private fun bindMenu() {
        val menuUsage = rootView.findViewById<LinearLayout>(R.id.menuUsage)
        val menuSkill = rootView.findViewById<LinearLayout>(R.id.menuSkill)
        val menuAbout = rootView.findViewById<LinearLayout>(R.id.menuAbout)

        menuUsage?.setOnClickListener { showPage(Page.USAGE) }
        menuSkill?.setOnClickListener { showPage(Page.SKILL) }
        menuAbout?.setOnClickListener { showPage(Page.ABOUT) }
    }

    private fun bindActions() {
        skillContainer = rootView.findViewById(R.id.skillContainer)
        skillSearchInput = rootView.findViewById(R.id.etSkillSearch)
        initSkills()

        skillSearchInput?.setOnEditorActionListener { _, actionId, event ->
            val isConfirmAction =
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)

            if (isConfirmAction) {
                applySkillFilter(skillSearchInput?.text?.toString().orEmpty())
                skillSearchInput?.hideKeyboard(clearFocus = true)
                true
            } else {
                false
            }
        }

        rootView.findViewById<View>(R.id.btnAddSkill)?.setOnClickListener {
            showAddSkillPopup(it)
        }
    }

    private fun bindAboutActions() {
        rootView.findViewById<View>(R.id.rowUserAgreement)?.setOnClickListener {
            showLegalWebPopup(AppConstants.USER_AGREEMENT_URL)
        }
        rootView.findViewById<View>(R.id.rowPrivacyPolicy)?.setOnClickListener {
            showLegalWebPopup(AppConstants.PRIVACY_POLICY_URL)
        }
        rootView.findViewById<View>(R.id.rowOpenSourceLicense)?.setOnClickListener {
            showLegalWebPopup(AppConstants.PRIVACY_POLICY_URL)
        }
    }

    private fun initSkills() {
        ioExecutor.execute {
            val loadedSkills = mutableListOf<SkillItem>()
            val seenSkillIds = mutableSetOf<String>()

            val bundled = context.assets.list("skills").orEmpty()
            bundled.forEach { name ->
                val detailDescription = resolveBuiltinSkillDescription(name).orEmpty()
                val id = "builtin:$name"
                if (seenSkillIds.add(id)) {
                    loadedSkills.add(
                        SkillItem(
                            id = id,
                            title = name,
                            content = detailDescription.ifBlank {
                                context.getString(R.string.setting_builtin_skill_content, name)
                            },
                            enabled = true,
                            tag = context.getString(R.string.setting_builtin_skill),
                            source = SkillSource.BUILTIN
                        )
                    )
                }
            }

            val runtimeSkills = resolveRuntimeSkillItems()
            runtimeSkills.forEach { skill ->
                if (seenSkillIds.add(skill.id)) {
                    loadedSkills.add(skill)
                }
            }

            val workspaceSkills = resolveWorkspaceSkillItems()
            workspaceSkills.forEach { skill ->
                if (seenSkillIds.add(skill.id)) {
                    loadedSkills.add(skill)
                }
            }

            val imported = githubSkillImportService.listImportedSkills()
            imported.forEach { skill ->
                val detailDescription = githubSkillImportService
                    .resolveImportedSkillDescription(skill.skillName)
                    .orEmpty()
                val id = "github:${skill.skillName}"
                if (seenSkillIds.add(id)) {
                    loadedSkills.add(
                        SkillItem(
                            id = id,
                            title = skill.skillName,
                            content = detailDescription.ifBlank { skill.sourceUrl },
                            enabled = skill.enabled,
                            tag = context.getString(R.string.setting_github_import_tag),
                            source = SkillSource.GITHUB
                        )
                    )
                }
            }

            val deduplicatedSkills = loadedSkills
                .groupBy { it.title.trim().lowercase() }
                .values
                .map { bucket ->
                    bucket.maxByOrNull(::skillPriority)
                        ?: bucket.first()
                }
                .sortedWith(
                    compareBy<SkillItem> { pinnedBuiltinOrder(it) }
                        .thenBy { it.title.lowercase() }
                )

            rootView.post {
                if (!canShowPopup()) return@post
                skills.clear()
                skills.addAll(deduplicatedSkills)
                applySkillFilter(currentSkillQuery)
            }
        }
    }

    private fun applySkillFilter(rawQuery: String) {
        currentSkillQuery = rawQuery.trim()
        if (currentSkillQuery.isBlank()) {
            renderSkills(skills)
            return
        }

        val filtered = skills.filter {
            it.title.contains(currentSkillQuery, ignoreCase = true)
        }
        renderSkills(filtered)
    }

    private fun renderSkills(skillList: List<SkillItem>) {
        val container = skillContainer ?: return
        container.removeAllViews()

        skillList.chunked(2).forEachIndexed { rowIndex, rowSkills ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            rowSkills.forEachIndexed { colIndex, skill ->
                val index = rowIndex * 2 + colIndex
                val card = LayoutInflater.from(context).inflate(R.layout.item_skill_card, row, false)
                val cardParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val margin = dp(6f)
                    leftMargin = if (colIndex == 0) 0 else margin
                    rightMargin = if (colIndex == rowSkills.lastIndex) 0 else margin
                    topMargin = margin
                    bottomMargin = margin
                }
                bindSkillCard(card, skill, index)
                row.addView(card, cardParams)
            }

            if (rowSkills.size == 1) {
                val spacer = View(context)
                val spacerParams = LinearLayout.LayoutParams(0, 0, 1f).apply {
                    val margin = dp(6f)
                    leftMargin = margin
                    rightMargin = 0
                }
                row.addView(spacer, spacerParams)
            }

            container.addView(row)
        }
    }

    private fun bindSkillCard(card: View, skill: SkillItem, index: Int) {
        val titleView = card.findViewById<TextView>(R.id.tvSkillTitle) ?: return
        val contentView = card.findViewById<TextView>(R.id.tvSkillContent) ?: return
        val tagView = card.findViewById<TextView>(R.id.tvSkillTag) ?: return
        val switchView = card.findViewById<ImageView>(R.id.skillSwitch) ?: return
        val removeView = card.findViewById<ImageView>(R.id.ivRemove) ?: return

        titleView.text = skill.title
        contentView.text = skill.content
        tagView.text = skill.tag
        switchView.setImageResource(if (skill.enabled) R.mipmap.switch_on else R.mipmap.switch_off)

        val canToggleSkill = !(isNonRemovableBuiltinSkill(skill) && skill.enabled)
        switchView.isEnabled = canToggleSkill
        switchView.isClickable = canToggleSkill
        switchView.isFocusable = canToggleSkill
        switchView.alpha = if (canToggleSkill) 1f else 0.45f

        card.setOnClickListener {
            showSkillDetailPopup(skill)
        }

        if (canToggleSkill) {
            switchView.setOnClickListener {
                if (skill.enabled) {
                    showCloseSkillPopup(skill)
                } else {
                    if (skill.source == SkillSource.GITHUB) {
                        toggleGithubSkill(skill, enable = true)
                    } else if (isRuntimeSkill(skill)) {
                        toggleRuntimeSkill(skill, enable = true)
                    } else if (isWorkspaceSkill(skill)) {
                        toggleWorkspaceSkill(skill, enable = true)
                    } else {
                        skill.enabled = true
                        switchView.setImageResource(R.mipmap.switch_on)
                        Toast.makeText(
                            context,
                            context.getString(R.string.setting_skill_enabled_message, skill.title),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else {
            switchView.setOnClickListener(null)
        }

        removeView.setOnClickListener {
            showRemoveSkillPopup(it, skill)
        }
    }

    private fun showAddSkillPopup(anchor: View) {
        addSkillPopupWindow?.dismiss()

        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.popup_add_skill, null, false)

        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = contentView.measuredWidth
        val popupHeight = contentView.measuredHeight

        val popupWindow = PopupWindow(
            contentView,
            popupWidth,
            popupHeight,
            true
        )

        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = dp(10f).toFloat()
        popupWindow.isClippingEnabled = true

        contentView.findViewById<View>(R.id.optionCreateByChat)?.setOnClickListener {
            context.startActivity(
                Intent(context, ShellActivity::class.java).apply {
                    putExtra(
                        ShellActivity.EXTRA_INITIAL_CHAT_DRAFT,
                        context.getString(R.string.setting_first_skill_prompt)
                    )
                }
            )
            (context as? Activity)?.finish()
            popupWindow.dismiss()
        }

        contentView.findViewById<View>(R.id.optionImportFromGithub)?.setOnClickListener {
            popupWindow.dismiss()
            rootView.post {
                showGithubImportPopup()
            }
        }

        val yOff = dp(8f)
        val xOff = anchor.width - popupWidth
        popupWindow.showAsDropDown(anchor, xOff, yOff)

        addSkillPopupWindow = popupWindow
    }

    private fun showGithubImportPopup() {
        if (!canShowPopup()) return
        githubImportPopupWindow?.dismiss()

        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.popup_import_from_github, null, false)

        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popupWindow = PopupWindow(
            contentView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            true
        )

        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = dp(10f).toFloat()
        popupWindow.isClippingEnabled = false
        popupWindow.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popupWindow.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popupWindow.setIsLaidOutInScreen(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            popupWindow.setAttachedInDecor(false)
        }

        val repoInput = contentView.findViewById<EditText>(R.id.etGithubUrl)
        val overlay = contentView.findViewById<View>(R.id.githubImportOverlay)
        val dialog = contentView.findViewById<View>(R.id.githubImportDialog)

        var stableDialogYOnScreen: Int? = null
        var hasLockedDialogTop = false
        val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val pos = IntArray(2)
            dialog?.getLocationOnScreen(pos)
            if (stableDialogYOnScreen == null && pos[1] > 0) {
                stableDialogYOnScreen = pos[1]
            }
            stableDialogYOnScreen?.let { stableY ->
                val container = dialog ?: return@let
                val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return@let
                if (!hasLockedDialogTop || lp.topMargin != stableY || lp.gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL)) {
                    hasLockedDialogTop = true
                    lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    lp.topMargin = stableY
                    container.layoutParams = lp
                }
                val delta = stableY - pos[1]
                val targetTranslation = (container.translationY + delta).coerceIn(-300f, 300f)
                if (abs(container.translationY - targetTranslation) >= 1f) {
                    container.translationY = targetTranslation
                }
            }
        }
        contentView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        overlay?.setOnClickListener { popupWindow.dismiss() }
        dialog?.setOnClickListener { /* consume */ }

        contentView.findViewById<View>(R.id.btnCancelImport)?.setOnClickListener {
            popupWindow.dismiss()
        }

        contentView.findViewById<View>(R.id.btnConfirmImport)?.setOnClickListener {
            val url = repoInput?.text?.toString()?.trim().orEmpty()
            if (url.isBlank()) {
                Toast.makeText(context, context.getString(R.string.setting_github_input_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            repoInput?.hideKeyboard(clearFocus = true)
            val importButton = contentView.findViewById<TextView>(R.id.btnConfirmImport)
            setImportButtonLoading(importButton, loading = true)
            importGithubSkill(url, popupWindow, importButton)
        }

        popupWindow.setOnDismissListener {
            if (contentView.viewTreeObserver.isAlive) {
                contentView.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
            }
            if (githubImportPopupWindow === popupWindow) {
                githubImportPopupWindow = null
            }
        }

        safeShowAtCenter(popupWindow)
        githubImportPopupWindow = popupWindow

        contentView.post {
            if (githubImportPopupWindow !== popupWindow) return@post
            val pos = IntArray(2)
            dialog?.getLocationOnScreen(pos)
            if (stableDialogYOnScreen == null && pos[1] > 0) {
                stableDialogYOnScreen = pos[1]
            }
        }
    }

    private fun showCloseSkillPopup(skill: SkillItem) {
        if (!canShowPopup()) return
        closeSkillPopupWindow?.dismiss()

        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.popup_close_skill, null, false)

        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popupWindow = PopupWindow(
            contentView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            true
        )

        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = dp(10f).toFloat()
        popupWindow.isClippingEnabled = true

        val overlay = contentView.findViewById<View>(R.id.removeConfirmOverlay)
        val dialog = contentView.findViewById<View>(R.id.removeConfirmDialog)
        val message = contentView.findViewById<TextView>(R.id.tvRemoveConfirmMessage)

        overlay?.setOnClickListener { popupWindow.dismiss() }
        dialog?.setOnClickListener { /* consume */ }

        message?.text = context.getString(R.string.setting_close_skill_message, skill.title)

        contentView.findViewById<View>(R.id.btnCancelRemove)?.setOnClickListener {
            popupWindow.dismiss()
        }

        contentView.findViewById<View>(R.id.btnConfirmRemove)?.setOnClickListener {
            if (skill.source == SkillSource.GITHUB) {
                toggleGithubSkill(skill, enable = false)
            } else if (isRuntimeSkill(skill)) {
                toggleRuntimeSkill(skill, enable = false)
            } else if (isWorkspaceSkill(skill)) {
                toggleWorkspaceSkill(skill, enable = false)
            } else {
                skill.enabled = false
                applySkillFilter(currentSkillQuery)
                Toast.makeText(
                    context,
                    context.getString(R.string.setting_skill_disabled_message, skill.title),
                    Toast.LENGTH_SHORT
                ).show()
            }
            popupWindow.dismiss()
        }

        safeShowAtCenter(popupWindow)
        closeSkillPopupWindow = popupWindow
    }

    private fun showRemoveSkillPopup(anchor: View, skill: SkillItem) {
        removeSkillPopupWindow?.dismiss()

        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.popup_remove_skill, null, false)

        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popupWindow = PopupWindow(
            contentView,
            contentView.measuredWidth,
            contentView.measuredHeight,
            true
        )

        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = dp(10f).toFloat()
        popupWindow.isClippingEnabled = true

        val optionRemove = contentView.findViewById<View>(R.id.optionRemove)
        val removable = !isNonRemovableBuiltinSkill(skill)

        optionRemove?.isEnabled = removable
        optionRemove?.isClickable = removable
        optionRemove?.isFocusable = removable
        optionRemove?.alpha = if (removable) 1f else 0.2f

        if (removable) {
            optionRemove?.setOnClickListener {
                if (skill.source == SkillSource.GITHUB) {
                    removeGithubSkill(skill)
                } else if (isRuntimeSkill(skill)) {
                    removeRuntimeSkill(skill)
                } else if (isWorkspaceSkill(skill)) {
                    removeWorkspaceSkill(skill)
                } else {
                    skills.remove(skill)
                    applySkillFilter(currentSkillQuery)
                    Toast.makeText(
                        context,
                        context.getString(R.string.setting_skill_removed_message, skill.title),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                popupWindow.dismiss()
            }
        }

        val xOff = anchor.width - contentView.measuredWidth
        popupWindow.showAsDropDown(anchor, xOff, dp(6f))
        removeSkillPopupWindow = popupWindow
    }

    private fun showSkillDetailPopup(skill: SkillItem) {
        if (!canShowPopup()) return
        skillDetailPopupWindow?.dismiss()

        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.popup_skill_detail, null, false)

        val popupWindow = PopupWindow(
            contentView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            true
        )

        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = dp(10f).toFloat()
        popupWindow.isClippingEnabled = true

        val overlay = contentView.findViewById<View>(R.id.skillDetailOverlay)
        val dialog = contentView.findViewById<View>(R.id.skillDetailDialog)
        val title = contentView.findViewById<TextView>(R.id.tvSkillDetailTitle)
        val tag = contentView.findViewById<TextView>(R.id.tvSkillDetailTag)
        val desc = contentView.findViewById<TextView>(R.id.tvSkillDetailDescription)
        val status = contentView.findViewById<TextView>(R.id.tvSkillDetailStatus)
        val removeButton = contentView.findViewById<TextView>(R.id.btnSkillDetailRemove)

        title?.text = skill.title
        tag?.text = skill.tag
        desc?.text = skill.content
        status?.text = "当前状态：${if (skill.enabled) "已开启" else "已关闭"}"

        val removable = !isNonRemovableBuiltinSkill(skill)
        removeButton?.isEnabled = removable
        removeButton?.isClickable = removable
        removeButton?.isFocusable = removable
        removeButton?.alpha = if (removable) 1f else 0.72f
        (removeButton?.background?.mutate() as? GradientDrawable)?.setStroke(
            3,
            Color.parseColor(if (removable) "#FF6C6C" else "#33000000")
        )
        removeButton?.setTextColor(if (removable) Color.parseColor("#FF6C6C") else Color.parseColor("#8F8F8F"))
        if (removable) {
            removeButton?.setOnClickListener {
                if (skill.source == SkillSource.GITHUB) {
                    removeGithubSkill(skill)
                } else if (isRuntimeSkill(skill)) {
                    removeRuntimeSkill(skill)
                } else if (isWorkspaceSkill(skill)) {
                    removeWorkspaceSkill(skill)
                } else {
                    skills.remove(skill)
                    applySkillFilter(currentSkillQuery)
                    Toast.makeText(
                        context,
                        context.getString(R.string.setting_skill_removed_message, skill.title),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                popupWindow.dismiss()
            }
        }

        overlay?.setOnClickListener { popupWindow.dismiss() }
        dialog?.setOnClickListener { /* consume */ }

        safeShowAtCenter(popupWindow)
        skillDetailPopupWindow = popupWindow
    }

    private fun canShowPopup(): Boolean {
        val activity = context as? Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) return false
        return popupHostView.windowToken != null && popupHostView.isAttachedToWindow
    }

    private fun safeShowAtCenter(popupWindow: PopupWindow): Boolean {
        if (!canShowPopup()) return false
        return try {
            popupWindow.showAtLocation(popupHostView, Gravity.CENTER, 0, 0)
            true
        } catch (_: WindowManager.BadTokenException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun dp(value: Float): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun isNonRemovableBuiltinSkill(skill: SkillItem): Boolean {
        if (skill.source != SkillSource.BUILTIN) return false
        return canonicalSkillName(skill.title) in NON_REMOVABLE_BUILTIN_SKILLS
    }

    private fun resolveBuiltinSkillDescription(skillName: String): String? {
        val skillMdPath = "skills/$skillName/SKILL.md"
        val markdown = runCatching {
            context.assets.open(skillMdPath).bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        if (markdown.isBlank()) return null
        return extractDescriptionFromSkillMarkdown(markdown)
    }

    private fun resolveWorkspaceSkillItems(): List<SkillItem> {
        val activeSkills = workspaceSkillsDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { skillDir ->
                val skillName = skillDir.name.trim()
                if (skillName.isBlank()) return@mapNotNull null

                val markdown = runCatching {
                    File(skillDir, "SKILL.md").takeIf { it.exists() }?.readText().orEmpty()
                }.getOrDefault("")
                val detailDescription = extractDescriptionFromSkillMarkdown(markdown).orEmpty()

                SkillItem(
                    id = "workspace:$skillName",
                    title = skillName,
                    content = detailDescription.ifBlank {
                        context.getString(R.string.setting_builtin_skill_content, skillName)
                    },
                    enabled = true,
                    tag = resolveUserOrBuiltinTag(skillName),
                    source = resolveUserOrBuiltinSource(skillName)
                )
            }

        val disabledSkills = workspaceDisabledSkillsDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { skillDir ->
                val skillName = skillDir.name.trim()
                if (skillName.isBlank()) return@mapNotNull null

                val markdown = runCatching {
                    File(skillDir, "SKILL.md").takeIf { it.exists() }?.readText().orEmpty()
                }.getOrDefault("")
                val detailDescription = extractDescriptionFromSkillMarkdown(markdown).orEmpty()

                SkillItem(
                    id = "workspace_disabled:$skillName",
                    title = skillName,
                    content = detailDescription.ifBlank {
                        context.getString(R.string.setting_builtin_skill_content, skillName)
                    },
                    enabled = false,
                    tag = resolveUserOrBuiltinTag(skillName),
                    source = resolveUserOrBuiltinSource(skillName)
                )
            }

        val result = (activeSkills + disabledSkills)
            .distinctBy { it.title.lowercase() }
            .sortedBy { it.title.lowercase() }
        Log.i(
            SKILL_TRACE_TAG,
            "resolveWorkspaceSkillItems result count=${result.size}, items=${result.map { "${it.id}:${it.title}:enabled=${it.enabled}" }}"
        )
        return result
    }

    private fun resolveRuntimeSkillItems(): List<SkillItem> {
        Log.i(
            SKILL_TRACE_TAG,
            "resolveRuntimeSkillItems activeDir=${runtimeSkillsDir.absolutePath}, exists=${runtimeSkillsDir.exists()}, disabledDir=${runtimeDisabledSkillsDir.absolutePath}, disabledExists=${runtimeDisabledSkillsDir.exists()}"
        )
        val activeSkills = runtimeSkillsDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { skillDir ->
                val skillName = skillDir.name.trim()
                if (skillName.isBlank()) return@mapNotNull null
                val markdown = runCatching {
                    File(skillDir, "SKILL.md").takeIf { it.exists() }?.readText().orEmpty()
                }.getOrDefault("")
                val detailDescription = extractDescriptionFromSkillMarkdown(markdown).orEmpty()
                SkillItem(
                    id = "runtime:$skillName",
                    title = skillName,
                    content = detailDescription.ifBlank {
                        context.getString(R.string.setting_builtin_skill_content, skillName)
                    },
                    enabled = true,
                    tag = resolveUserOrBuiltinTag(skillName),
                    source = resolveUserOrBuiltinSource(skillName)
                )
            }

        val disabledSkills = runtimeDisabledSkillsDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { skillDir ->
                val skillName = skillDir.name.trim()
                if (skillName.isBlank()) return@mapNotNull null
                val markdown = runCatching {
                    File(skillDir, "SKILL.md").takeIf { it.exists() }?.readText().orEmpty()
                }.getOrDefault("")
                val detailDescription = extractDescriptionFromSkillMarkdown(markdown).orEmpty()
                SkillItem(
                    id = "runtime_disabled:$skillName",
                    title = skillName,
                    content = detailDescription.ifBlank {
                        context.getString(R.string.setting_builtin_skill_content, skillName)
                    },
                    enabled = false,
                    tag = resolveUserOrBuiltinTag(skillName),
                    source = resolveUserOrBuiltinSource(skillName)
                )
            }

        val result = (activeSkills + disabledSkills)
            .distinctBy { it.title.lowercase() }
            .sortedBy { it.title.lowercase() }
        Log.i(
            SKILL_TRACE_TAG,
            "resolveRuntimeSkillItems result count=${result.size}, items=${result.map { "${it.id}:${it.title}:enabled=${it.enabled}" }}"
        )
        return result
    }

    private fun extractDescriptionFromSkillMarkdown(markdown: String): String? {
        val normalized = markdown.replace("\r\n", "\n")
        val frontMatterRegex = Regex("""(?s)^---\n(.*?)\n---\n?""")
        val frontMatter = frontMatterRegex.find(normalized)?.groupValues?.getOrNull(1).orEmpty()

        if (frontMatter.isNotBlank()) {
            val descriptionRegex = Regex("""(?im)^description\s*:\s*(.+)$""")
            val value = descriptionRegex.find(frontMatter)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (value.isNotBlank()) {
                return value.trim().trim('"', '\'')
            }
        }

        val body = normalized.substringAfter("\n---\n", normalized)
        return body
            .lines()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    !line.startsWith("name:", ignoreCase = true) &&
                    !line.startsWith("license:", ignoreCase = true)
            }
    }

    private fun skillPriority(skill: SkillItem): Int {
        return when {
            skill.source == SkillSource.GITHUB -> 4
            skill.id.startsWith("runtime:") -> 3
            skill.id.startsWith("runtime_disabled:") -> 2
            skill.id.startsWith("workspace:") -> 1
            else -> 0
        }
    }

    private fun pinnedBuiltinOrder(skill: SkillItem): Int {
        val name = canonicalSkillName(skill.title)
        return when (name) {
            "dev_ctrl" -> 0
            "multi-search-engine" -> 1
            "pdf" -> 2
            "pptx" -> 3
            "html-default" -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun canonicalSkillName(name: String): String {
        return name.substringBefore('.').trim().lowercase()
    }

    private fun resolveUserOrBuiltinSource(skillName: String): SkillSource {
        return if (canonicalSkillName(skillName) in NON_REMOVABLE_BUILTIN_SKILLS) {
            SkillSource.BUILTIN
        } else {
            SkillSource.USER_ADDED
        }
    }

    private fun resolveUserOrBuiltinTag(skillName: String): String {
        return if (resolveUserOrBuiltinSource(skillName) == SkillSource.BUILTIN) {
            context.getString(R.string.setting_builtin_skill)
        } else {
            userAddedSkillTag
        }
    }

    private fun isRuntimeSkill(skill: SkillItem): Boolean {
        return skill.id.startsWith("runtime:") || skill.id.startsWith("runtime_disabled:")
    }

    private fun isWorkspaceSkill(skill: SkillItem): Boolean {
        return skill.id.startsWith("workspace:") || skill.id.startsWith("workspace_disabled:")
    }

    private fun moveSkillDir(source: File, target: File) {
        if (!source.exists()) {
            throw IllegalStateException("技能目录不存在: ${source.absolutePath}")
        }

        target.parentFile?.mkdirs()
        if (target.exists()) {
            target.deleteRecursively()
        }

        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
        }
    }

    private fun importGithubSkill(url: String, popupWindow: PopupWindow, importButton: TextView?) {
        Toast.makeText(context, context.getString(R.string.setting_importing_from_github), Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            val result = runCatching { githubSkillImportService.importFromGithub(url) }
            rootView.post {
                result.onSuccess {
                    setImportButtonLoading(importButton, loading = false)
                    initSkills()
                    Toast.makeText(
                        context,
                        context.getString(R.string.setting_import_success_message, it.skillName, it.fileCount),
                        Toast.LENGTH_SHORT
                    ).show()
                    popupWindow.dismiss()
                }.onFailure {
                    setImportButtonLoading(importButton, loading = false)
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.setting_import_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setImportButtonLoading(button: TextView?, loading: Boolean) {
        val target = button ?: return
        target.isEnabled = !loading
        target.isClickable = !loading
        target.alpha = if (loading) 0.6f else 1f
        target.text = if (loading) {
            context.getString(R.string.setting_import_in_progress)
        } else {
            context.getString(R.string.setting_import)
        }
    }

    private fun toggleGithubSkill(skill: SkillItem, enable: Boolean) {
        ioExecutor.execute {
            val result = runCatching {
                githubSkillImportService.setSkillEnabled(skill.title, enable)
            }
            rootView.post {
                result.onSuccess {
                    initSkills()
                    Toast.makeText(
                        context,
                        context.getString(
                            if (enable) R.string.setting_skill_enabled_message else R.string.setting_skill_disabled_message,
                            skill.title
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.setting_operation_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun toggleRuntimeSkill(skill: SkillItem, enable: Boolean) {
        ioExecutor.execute {
            val result = runCatching {
                val activeDir = File(runtimeSkillsDir, skill.title)
                val disabledDir = File(runtimeDisabledSkillsDir, skill.title)

                if (enable) {
                    moveSkillDir(source = disabledDir, target = activeDir)
                } else {
                    moveSkillDir(source = activeDir, target = disabledDir)
                }
            }

            rootView.post {
                result.onSuccess {
                    initSkills()
                    Toast.makeText(
                        context,
                        context.getString(
                            if (enable) R.string.setting_skill_enabled_message else R.string.setting_skill_disabled_message,
                            skill.title
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.setting_operation_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun removeGithubSkill(skill: SkillItem) {
        ioExecutor.execute {
            val result = runCatching {
                githubSkillImportService.removeImportedSkill(skill.title)
            }
            rootView.post {
                result.onSuccess {
                    initSkills()
                    Toast.makeText(
                        context,
                        context.getString(R.string.setting_skill_removed_message, skill.title),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.setting_remove_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun removeRuntimeSkill(skill: SkillItem) {
        ioExecutor.execute {
            val result = runCatching {
                File(runtimeSkillsDir, skill.title).deleteRecursively()
                File(runtimeDisabledSkillsDir, skill.title).deleteRecursively()
            }
            rootView.post {
                result.onSuccess {
                    initSkills()
                    Toast.makeText(
                        context,
                        context.getString(R.string.setting_skill_removed_message, skill.title),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.setting_remove_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun toggleWorkspaceSkill(skill: SkillItem, enable: Boolean) {
        ioExecutor.execute {
            val result = runCatching {
                val activeDir = File(workspaceSkillsDir, skill.title)
                val disabledDir = File(workspaceDisabledSkillsDir, skill.title)

                if (enable) {
                    moveSkillDir(source = disabledDir, target = activeDir)
                } else {
                    moveSkillDir(source = activeDir, target = disabledDir)
                }
            }

            rootView.post {
                result.onSuccess {
                    initSkills()
                    Toast.makeText(
                        context,
                        context.getString(
                            if (enable) R.string.setting_skill_enabled_message else R.string.setting_skill_disabled_message,
                            skill.title
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.setting_operation_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun removeWorkspaceSkill(skill: SkillItem) {
        ioExecutor.execute {
            val workspaceActive = File(workspaceSkillsDir, skill.title)
            val workspaceDisabled = File(workspaceDisabledSkillsDir, skill.title)
            val runtimeActive = File(runtimeSkillsDir, skill.title)
            val runtimeDisabled = File(runtimeDisabledSkillsDir, skill.title)
            Log.i(
                SKILL_TRACE_TAG,
                "removeWorkspaceSkill start title=${skill.title}, workspaceActive=${workspaceActive.exists()}, workspaceDisabled=${workspaceDisabled.exists()}, runtimeActive=${runtimeActive.exists()}, runtimeDisabled=${runtimeDisabled.exists()}"
            )
            val result = runCatching {
                workspaceActive.deleteRecursively()
                workspaceDisabled.deleteRecursively()
                // “用户添加”技能可能落在 runtime 路径，清理同名目录，避免列表残留
                runtimeActive.deleteRecursively()
                runtimeDisabled.deleteRecursively()
            }
            rootView.post {
                result.onSuccess {
                    Log.i(
                        SKILL_TRACE_TAG,
                        "removeWorkspaceSkill done title=${skill.title}, workspaceActive=${workspaceActive.exists()}, workspaceDisabled=${workspaceDisabled.exists()}, runtimeActive=${runtimeActive.exists()}, runtimeDisabled=${runtimeDisabled.exists()}"
                    )
                    initSkills()
                    Toast.makeText(
                        context,
                        context.getString(R.string.setting_skill_removed_message, skill.title),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.setting_remove_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showLegalWebPopup(url: String) {
        if (!canShowPopup()) return
        legalWebPopupWindow?.dismiss()

        val webViewContent = LayoutInflater.from(context)
            .inflate(R.layout.popup_legal_webview, null, false)

        val overlayRoot = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x66000000)
        }

        val dialogContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(960, 780, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = 40f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 40f)
                    }
                }
                clipToOutline = true
            }
        }

        dialogContainer.addView(
            webViewContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 顶部遮罩方案,覆盖文档顶部整行，屏蔽登录入口区域。
        val loginEntryMask = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(60f),
                Gravity.TOP
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
            }
            isClickable = true
            isFocusable = true
        }
        dialogContainer.addView(loginEntryMask)

        overlayRoot.addView(dialogContainer)

        val popupWindow = PopupWindow(
            overlayRoot,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        )

        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = dp(10f).toFloat()
        popupWindow.isClippingEnabled = true

        overlayRoot.setOnClickListener { popupWindow.dismiss() }
        dialogContainer.setOnClickListener { /* consume */ }

        val webView = webViewContent.findViewById<WebView>(R.id.legalWebView)

        webView?.apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            loadUrl(url)
        }

        popupWindow.setOnDismissListener {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            legalWebPopupWindow = null
        }

        safeShowAtCenter(popupWindow)
        legalWebPopupWindow = popupWindow
    }

    private fun showPage(page: Page) {
        val menuUsage = rootView.findViewById<LinearLayout>(R.id.menuUsage)
        val menuSkill = rootView.findViewById<LinearLayout>(R.id.menuSkill)
        val menuAbout = rootView.findViewById<LinearLayout>(R.id.menuAbout)

        val pageUsage = rootView.findViewById<View>(R.id.pageUsage)
        val pageSkill = rootView.findViewById<View>(R.id.pageSkill)
        val pageAbout = rootView.findViewById<View>(R.id.pageAbout)

        menuUsage?.setBackgroundResource(if (page == Page.USAGE) R.drawable.bg_setting_left_selected else android.R.color.transparent)
        menuSkill?.setBackgroundResource(if (page == Page.SKILL) R.drawable.bg_setting_left_selected else android.R.color.transparent)
        menuAbout?.setBackgroundResource(if (page == Page.ABOUT) R.drawable.bg_setting_left_selected else android.R.color.transparent)

        pageUsage?.visibility = if (page == Page.USAGE) View.VISIBLE else View.GONE
        pageSkill?.visibility = if (page == Page.SKILL) View.VISIBLE else View.GONE
        pageAbout?.visibility = if (page == Page.ABOUT) View.VISIBLE else View.GONE
    }

    private enum class Page {
        USAGE,
        SKILL,
        ABOUT
    }
}
