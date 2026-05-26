package ai.cjym.agentclaw.ui.fancyideas

import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.databinding.DialogFancyIdeaDetailBinding
import ai.cjym.agentclaw.databinding.FragmentFancyideasBinding
import ai.cjym.agentclaw.ui.common.BaseBindingFragment
import ai.cjym.agentclaw.ui.shell.ShellHoverTooltipPopupWindow
import ai.cjym.agentclaw.ui.shell.ShellSharedViewModel
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FancyIdeasFragment :
    BaseBindingFragment<FragmentFancyideasBinding>(FragmentFancyideasBinding::inflate) {

    private val shellViewModel: ShellSharedViewModel by activityViewModels()
    private val viewModel: FancyIdeasViewModel by viewModels()
    private val adapter by lazy { FancyIdeasAdapter(viewModel.listItems) }
    private var hoveredIdeaId: String? = null
    private var hoveredAnchor: View? = null
    private val hoverLayoutChangeListener =
        View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (view !== hoveredAnchor) return@OnLayoutChangeListener
            if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom) return@OnLayoutChangeListener
            val ideaId = hoveredIdeaId ?: return@OnLayoutChangeListener
            val item = viewModel.items.firstOrNull { it.id == ideaId } ?: return@OnLayoutChangeListener
            if (!view.isHovered) return@OnLayoutChangeListener
            showHoverTooltip(view, item)
        }
    private var detailDialogBinding: DialogFancyIdeaDetailBinding? = null
    private var detailDialog: AlertDialog? = null
    private var hoverTooltipPopup: ShellHoverTooltipPopupWindow? = null

    override fun initView(savedInstanceState: Bundle?) {
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        binding.skillRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        // Header占满2列
                        if (this@FancyIdeasFragment.adapter.isHeader(position)) return 2

                        // 计算当前分组内的item索引（排除header）
                        var itemIndexInGroup = 0
                        for (index in position - 1 downTo 0) {
                            if (this@FancyIdeasFragment.adapter.isHeader(index)) break
                            itemIndexInGroup++
                        }

                        // 第三个卡片（索引为2）占满2列，其他占1列
                        return if (itemIndexInGroup == 2) 2 else 1
                    }
                }
            }
            adapter = this@FancyIdeasFragment.adapter
            setHasFixedSize(true)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            updatePadding(
                left = dp(16f),
                top = dp(12f),
                right = dp(16f),
                bottom = dp(20f)
            )
            addItemDecoration(
                GridSpacingItemDecoration(
                    adapter = this@FancyIdeasFragment.adapter,
                    spanCount = 2,
                    spacingPx = dp(10f)
                )
            )
        }
        adapter.onItemClick = ::showIdeaDetailDialog
        adapter.onItemHover = ::handleItemHover
    }

    private fun showIdeaDetailDialog(item: FancyIdeasItem) {
        ensureDetailDialog()
        val dialogBinding = requireNotNull(detailDialogBinding)
        val dialog = requireNotNull(detailDialog)
        dialogBinding.ideaIconView.setImageResource(item.iconResId)
        dialogBinding.ideaTitleView.text = item.title
        dialogBinding.ideaSubtitleView.text = item.subtitle
        dialogBinding.ideaScenarioContentView.text = item.scenario
        dialogBinding.ideaPromptContentView.text = item.prompt
        dialogBinding.useNowButton.setOnClickListener {
            shellViewModel.launchIdeaPresetConversation(
                sourceId = item.id,
                userPrompt = item.presetUserPrompt,
                assistantReply = item.presetAssistantReply
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun ensureDetailDialog() {
        if (detailDialog != null && detailDialogBinding != null) return

        val dialogBinding = DialogFancyIdeaDetailBinding.inflate(LayoutInflater.from(requireContext()))
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            // 设置弹窗宽度为屏幕宽度的90%，高度自适应，最大1200dp
            val displayMetrics = context.resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.9f).toInt()
            val maxHeight = (1200 * displayMetrics.density).toInt()
            attributes = attributes?.apply {
                this.width = width
                this.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            }
        }

        detailDialogBinding = dialogBinding
        detailDialog = dialog
    }

    private fun handleItemHover(anchor: View, item: FancyIdeasItem, hovering: Boolean) {
        if (hovering) {
            hoveredIdeaId = item.id
            if (hoveredAnchor !== anchor) {
                hoveredAnchor?.removeOnLayoutChangeListener(hoverLayoutChangeListener)
                hoveredAnchor = anchor
                hoveredAnchor?.addOnLayoutChangeListener(hoverLayoutChangeListener)
            }
            anchor.post {
                if (hoveredIdeaId != item.id || !anchor.isHovered) return@post
                showHoverTooltip(anchor, item)
            }
        } else {
            if (hoveredIdeaId == item.id) {
                hoveredIdeaId = null
            }
            if (hoveredAnchor === anchor) {
                hoveredAnchor?.removeOnLayoutChangeListener(hoverLayoutChangeListener)
                hoveredAnchor = null
            }
            hoverTooltipPopup?.dismiss()
        }
    }

    private fun showHoverTooltip(anchor: View, item: FancyIdeasItem) {
        if (hoverTooltipPopup == null) {
            hoverTooltipPopup = ShellHoverTooltipPopupWindow(requireContext())
        }
        hoverTooltipPopup?.show(anchor, item.subtitle, Gravity.LEFT, anchor.width)
    }

    override fun onDestroyView() {
        hoveredIdeaId = null
        hoveredAnchor?.removeOnLayoutChangeListener(hoverLayoutChangeListener)
        hoveredAnchor = null
        hoverTooltipPopup?.dismiss()
        hoverTooltipPopup = null
        detailDialog?.dismiss()
        detailDialog = null
        detailDialogBinding = null
        super.onDestroyView()
    }

    private fun dp(value: Float): Int =
        (value * requireContext().resources.displayMetrics.density).toInt()
}

private class GridSpacingItemDecoration(
    private val adapter: FancyIdeasAdapter,
    private val spanCount: Int,
    private val spacingPx: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        if (adapter.isHeader(position)) {
            outRect.set(0, if (position == 0) 0 else spacingPx * 2, 0, 0)
            return
        }

        var itemIndexInGroup = 0
        for (index in position - 1 downTo 0) {
            if (adapter.isHeader(index)) break
            itemIndexInGroup++
        }
        val column = itemIndexInGroup % spanCount
        outRect.left = spacingPx * column / spanCount
        outRect.right = spacingPx - (spacingPx * (column + 1) / spanCount)
        if (itemIndexInGroup >= spanCount) {
            outRect.top = spacingPx
        }
    }
}
