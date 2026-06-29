package ai.cjym.agentclaw.ui.vip

import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.VipActivity
import ai.cjym.agentclaw.quota.QuotaManager
import ai.cjym.agentclaw.util.requireView
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class VipUpgradeBottomSheet : BottomSheetDialogFragment() {

    enum class QuotaType { VIDEO, IMAGE }

    private var quotaType: QuotaType = QuotaType.VIDEO

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_vip_upgrade, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()

        val videoUsed = QuotaManager.todayVideoCount(ctx)
        val imageUsed = QuotaManager.todayImageCount(ctx)
        val videoLimit = QuotaManager.freeVideoLimit(ctx)
        val imageLimit = QuotaManager.freeImageLimit(ctx)

        view.requireView<TextView>(R.id.upgrade_title).text = when (quotaType) {
            QuotaType.VIDEO -> "今日视频次数已用完"
            QuotaType.IMAGE -> "今日图片次数已用完"
        }

        // Quota bar & text for video
        val videoBarView = view.requireView<View>(R.id.quota_video_bar)
        val videoText = view.requireView<TextView>(R.id.quota_video_text)
        val videoRatio = (videoUsed.toFloat() / videoLimit).coerceIn(0f, 1f)
        videoText.text = "$videoUsed/$videoLimit"
        view.post {
            val parentWidth = (videoBarView.parent as FrameLayout).width
            val barWidth = (parentWidth * videoRatio).toInt().coerceAtLeast(if (videoRatio > 0f) 8 else 0)
            videoBarView.layoutParams = FrameLayout.LayoutParams(barWidth, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        view.requireView<TextView>(R.id.quota_video_free_limit).text = "${QuotaManager.freeVideoLimit(ctx)}次/天"
        view.requireView<TextView>(R.id.quota_video_vip_limit).text = "${QuotaManager.vipVideoLimit(ctx)}次/天"

        // Quota bar & text for image
        val imageBarView = view.requireView<View>(R.id.quota_image_bar)
        val imageText = view.requireView<TextView>(R.id.quota_image_text)
        val imageRatio = (imageUsed.toFloat() / imageLimit).coerceIn(0f, 1f)
        imageText.text = "$imageUsed/$imageLimit"
        view.post {
            val parentWidth = (imageBarView.parent as FrameLayout).width
            val barWidth = (parentWidth * imageRatio).toInt().coerceAtLeast(if (imageRatio > 0f) 8 else 0)
            imageBarView.layoutParams = FrameLayout.LayoutParams(barWidth, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        view.requireView<TextView>(R.id.quota_image_free_limit).text = "${QuotaManager.freeImageLimit(ctx)}张/天"
        view.requireView<TextView>(R.id.quota_image_vip_limit).text = "${QuotaManager.vipImageLimit(ctx)}张/天"

        view.requireView<Button>(R.id.upgrade_button).setOnClickListener {
            dismiss()
            startActivity(VipActivity.createIntent(requireContext()))
        }
        view.requireView<TextView>(R.id.upgrade_cancel).setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.setBackgroundColor(Color.TRANSPARENT)
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    companion object {
        fun forVideo() = VipUpgradeBottomSheet().apply { quotaType = QuotaType.VIDEO }
        fun forImage() = VipUpgradeBottomSheet().apply { quotaType = QuotaType.IMAGE }
    }
}
