package ai.cjym.agentclaw.ui.creation

import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.databinding.FragmentCreationGalleryBinding
import ai.cjym.agentclaw.pay.PaymentApi
import ai.cjym.agentclaw.quota.QuotaManager
import ai.cjym.agentclaw.ui.common.BaseBindingFragment
import ai.cjym.agentclaw.util.requireView
import ai.cjym.agentclaw.ui.shell.ShellSharedViewModel
import ai.cjym.agentclaw.ui.vip.VipUpgradeBottomSheet
import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class CreationGalleryFragment :
    BaseBindingFragment<FragmentCreationGalleryBinding>(FragmentCreationGalleryBinding::inflate) {

    private val shellViewModel: ShellSharedViewModel by activityViewModels()
    private var currentPage = PAGE_IMAGE
    private var imageItems = emptyList<CreationMedia>()
    private var videoItems = emptyList<CreationMedia>()
    private var imageAdapter: CreationMediaAdapter? = null
    private var videoAdapter: CreationMediaAdapter? = null
    private var imageRecycler: RecyclerView? = null
    private var videoRecycler: RecyclerView? = null

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        when (result.data?.getStringExtra(CreationViewerActivity.EXTRA_MEDIA_TYPE)) {
            CreationMedia.Type.VIDEO.name -> tryLaunchVideoChat()
            else -> tryLaunchImageChat(
                result.data?.getStringExtra(CreationViewerActivity.EXTRA_PROMPT_ZH).orEmpty()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshVipStatusInBackground()
    }

    private fun refreshVipStatusInBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { PaymentApi(requireContext()).refreshVipStatus() }.getOrNull()
            if (result?.showRenewReminder == true) {
                withContext(Dispatchers.Main) {
                    showVipRenewReminder()
                }
            }
        }
    }

    private fun showVipRenewReminder() {
        val ctx = context ?: return
        val prefs = ai.cjym.agentclaw.data.local.prefs.PreferencesManager(ctx)
        val expires = prefs.vipExpiresAt ?: return
        val today = java.time.LocalDate.now()
        val expiry = runCatching { java.time.LocalDate.parse(expires) }.getOrNull() ?: return
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, expiry)
        val msg = if (days <= 0) "会员已到期，续费后继续享受会员权益" else "会员还有 ${days} 天到期，续费后不间断享受权益"
        android.app.AlertDialog.Builder(ctx)
            .setTitle("会员即将到期")
            .setMessage(msg)
            .setPositiveButton("立即续费") { _, _ ->
                startActivity(ai.cjym.agentclaw.VipActivity.createIntent(ctx))
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun tryLaunchVideoChat() {
        val ctx = requireContext()
        if (!QuotaManager.canGenerateVideo(ctx)) {
            VipUpgradeBottomSheet.forVideo()
                .show(parentFragmentManager, "vip_upgrade_video")
            return
        }
        shellViewModel.launchVideoChat()
    }

    private fun tryLaunchImageChat(prompt: String = "") {
        val ctx = requireContext()
        if (!QuotaManager.canGenerateImage(ctx)) {
            VipUpgradeBottomSheet.forImage()
                .show(parentFragmentManager, "vip_upgrade_image")
            return
        }
        shellViewModel.launchImageChat(prompt)
    }

    override fun initView(savedInstanceState: Bundle?) {
        val inflater = LayoutInflater.from(requireContext())
        val imagePage = inflater.inflate(R.layout.view_creation_gallery_page, binding.pageFlipper, false)
        val videoPage = inflater.inflate(R.layout.view_creation_gallery_page, binding.pageFlipper, false)
        binding.pageFlipper.addView(imagePage)
        binding.pageFlipper.addView(videoPage)

        val promptsByImage = loadImagePrompts()
        imageItems = requireContext().assets.list(IMAGE_ASSET_DIR).orEmpty()
            .filter { it.endsWith(".png", true) || it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
            .sorted()
            .map { filename ->
                CreationMedia(
                    assetPath = "$IMAGE_ASSET_DIR/$filename",
                    type = CreationMedia.Type.IMAGE,
                    promptZh = promptsByImage[filename].orEmpty()
                )
            }
        videoItems = requireContext().assets.list(VIDEO_ASSET_DIR).orEmpty()
            .filter { it.endsWith(".mp4", true) }
            .sorted()
            .map { CreationMedia("$VIDEO_ASSET_DIR/$it", CreationMedia.Type.VIDEO) }

        setupPage(imagePage, isVideo = false, imageItems)
        setupPage(videoPage, isVideo = true, videoItems)
        binding.pageFlipper.onSwipeToPage = ::selectPage
        selectPage(PAGE_IMAGE, animate = false)

        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(260L).start()
    }

    private fun loadImagePrompts(): Map<String, String> {
        return runCatching {
            val json = requireContext().assets.open(IMAGE_PROMPTS_ASSET).bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val image = item.optString("image").trim()
                    if (image.isNotBlank()) {
                        put(image, item.optString("prompt_zh").trim())
                    }
                }
            }
        }.getOrElse { emptyMap() }
    }

    override fun initEvent() {
        binding.imageTab.setOnClickListener { selectPage(PAGE_IMAGE) }
        binding.videoTab.setOnClickListener { selectPage(PAGE_VIDEO) }
    }

    fun shuffleMedia() {
        if (view == null) return
        imageItems = shuffledDifferently(imageItems)
        videoItems = shuffledDifferently(videoItems)
        imageAdapter?.submitList(imageItems)
        videoAdapter?.submitList(videoItems)
        imageRecycler?.scrollToPosition(0)
        videoRecycler?.scrollToPosition(0)
        binding.pageFlipper.animate().alpha(0.78f).setDuration(80L).withEndAction {
            binding.pageFlipper.animate().alpha(1f).setDuration(180L).start()
        }.start()
    }

    private fun setupPage(page: View, isVideo: Boolean, items: List<CreationMedia>) {
        page.requireView<TextView>(R.id.primaryFeatureTitle).setText(
            if (isVideo) R.string.creation_text_to_video else R.string.creation_text_to_image
        )
        page.requireView<TextView>(R.id.primaryFeatureHint).setText(
            if (isVideo) R.string.creation_text_to_video_hint else R.string.creation_text_to_image_hint
        )
        page.requireView<TextView>(R.id.secondaryFeatureTitle).setText(
            if (isVideo) R.string.creation_image_to_video else R.string.creation_image_to_image
        )
        page.requireView<TextView>(R.id.secondaryFeatureHint).setText(
            if (isVideo) R.string.creation_image_to_video_hint else R.string.creation_image_to_image_hint
        )

        val launchCreation = {
            if (isVideo) tryLaunchVideoChat() else tryLaunchImageChat()
        }
        page.requireView<View>(R.id.primaryFeatureCard).setOnClickListener { view ->
            animateCardPress(view, launchCreation)
        }
        page.requireView<View>(R.id.secondaryFeatureCard).setOnClickListener { view ->
            animateCardPress(view, launchCreation)
        }

        page.requireView<RecyclerView>(R.id.mediaRecycler).apply {
            layoutManager = if (isVideo) {
                GridLayoutManager(requireContext(), 2)
            } else {
                StaggeredGridLayoutManager(
                    2,
                    StaggeredGridLayoutManager.VERTICAL
                ).apply {
                    gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
                }
            }
            val mediaAdapter = CreationMediaAdapter(useVariableHeight = !isVideo, onClick = ::openViewer)
                .also { it.submitList(items) }
            adapter = mediaAdapter
            if (isVideo) {
                videoAdapter = mediaAdapter
                videoRecycler = this
            } else {
                imageAdapter = mediaAdapter
                imageRecycler = this
            }
            setHasFixedSize(false)
            itemAnimator = null
            setItemViewCacheSize(8)
        }
    }

    private fun shuffledDifferently(items: List<CreationMedia>): List<CreationMedia> {
        if (items.size < 2) return items
        val shuffled = items.shuffled()
        return if (shuffled == items) items.drop(1) + items.first() else shuffled
    }

    private fun animateCardPress(view: View, action: () -> Unit) {
        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90L).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(140L).withEndAction(action).start()
        }.start()
    }

    private fun openViewer(media: CreationMedia) {
        viewerLauncher.launch(CreationViewerActivity.createIntent(requireContext(), media))
        requireActivity().overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun selectPage(page: Int, animate: Boolean = true) {
        if (page !in PAGE_IMAGE..PAGE_VIDEO) return
        if (animate && page != currentPage) {
            val direction = if (page > currentPage) 1f else -1f
            binding.pageFlipper.inAnimation = pageAnimation(direction * 0.18f, 0f, true)
            binding.pageFlipper.outAnimation = pageAnimation(0f, -direction * 0.12f, false)
        } else {
            binding.pageFlipper.inAnimation = null
            binding.pageFlipper.outAnimation = null
        }
        currentPage = page
        binding.pageFlipper.displayedChild = page
        binding.imageTab.isSelected = page == PAGE_IMAGE
        binding.videoTab.isSelected = page == PAGE_VIDEO
        binding.imageTab.animate().scaleX(if (page == PAGE_IMAGE) 1f else 0.97f).scaleY(if (page == PAGE_IMAGE) 1f else 0.97f).setDuration(180L).start()
        binding.videoTab.animate().scaleX(if (page == PAGE_VIDEO) 1f else 0.97f).scaleY(if (page == PAGE_VIDEO) 1f else 0.97f).setDuration(180L).start()
    }

    private fun pageAnimation(fromX: Float, toX: Float, entering: Boolean): AnimationSet {
        return AnimationSet(true).apply {
            interpolator = AccelerateDecelerateInterpolator()
            duration = 260L
            addAnimation(TranslateAnimation(
                TranslateAnimation.RELATIVE_TO_SELF, fromX,
                TranslateAnimation.RELATIVE_TO_SELF, toX,
                TranslateAnimation.RELATIVE_TO_SELF, 0f,
                TranslateAnimation.RELATIVE_TO_SELF, 0f
            ))
            addAnimation(AlphaAnimation(if (entering) 0.55f else 1f, if (entering) 1f else 0.55f))
        }
    }

    private companion object {
        const val PAGE_IMAGE = 0
        const val PAGE_VIDEO = 1
        const val IMAGE_ASSET_DIR = "creation/images"
        const val VIDEO_ASSET_DIR = "creation/videos"
        const val IMAGE_PROMPTS_ASSET = "creation/image_prompts_zh.json"
    }
}
