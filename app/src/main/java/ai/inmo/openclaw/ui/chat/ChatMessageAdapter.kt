package ai.inmo.openclaw.ui.chat

import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ItemChatMessageAssistantBinding
import ai.inmo.openclaw.databinding.ItemChatMessageToolCallFlatBinding
import ai.inmo.openclaw.databinding.ItemChatMessageToolTextFlatBinding
import ai.inmo.openclaw.databinding.ItemChatMessageUserBinding
import ai.inmo.openclaw.databinding.ItemToolCallBinding
import ai.inmo.openclaw.databinding.ItemToolTimelineTextBinding
import ai.inmo.openclaw.domain.model.MessageSendStatus
import ai.inmo.core_common.ui.adapter.BaseListViewTypePlusAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.viewbinding.ViewBinding
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

class ChatMessageAdapter : BaseListViewTypePlusAdapter<ChatMessageItem, ViewBinding>(
    object : DiffUtil.ItemCallback<ChatMessageItem>() {
        override fun areItemsTheSame(
            oldItem: ChatMessageItem,
            newItem: ChatMessageItem
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: ChatMessageItem,
            newItem: ChatMessageItem
        ): Boolean = oldItem == newItem

        override fun getChangePayload(
            oldItem: ChatMessageItem,
            newItem: ChatMessageItem
        ): Any? {
            if (oldItem is ChatMessageItem.AssistantMessageItem &&
                newItem is ChatMessageItem.AssistantMessageItem &&
                newItem.isStreaming
            ) {
                return PAYLOAD_STREAMING_CONTENT
            }
            if ((oldItem is ChatMessageItem.ToolTextMessageItem &&
                newItem is ChatMessageItem.ToolTextMessageItem) ||
                (oldItem is ChatMessageItem.ToolCallMessageItem &&
                    newItem is ChatMessageItem.ToolCallMessageItem)
            ) {
                return PAYLOAD_TOOL_CHAIN_CONTENT
            }
            return null
        }
    }
) {
    var onAssistantExportClick: ((messageId: String) -> Unit)? = null
    var onUserNoticeClick: ((messageId: String) -> Unit)? = null
    private var contentWidthPx: Int = 0
    private var firstFrameRender: Boolean = false
    private var renderStats = RenderStats()

    data class RenderStats(
        val assistantBindCount: Int = 0,
        val assistantBindTotalMs: Long = 0,
        val toolTextBindCount: Int = 0,
        val toolTextBindTotalMs: Long = 0,
        val toolCallBindCount: Int = 0,
        val toolCallBindTotalMs: Long = 0,
        val markdownCount: Int = 0,
        val markdownTotalMs: Long = 0,
        val bindMaxMs: Long = 0
    )

    data class UpgradeResult(
        val upgradedAssistantCount: Int = 0,
        val upgradedToolCount: Int = 0
    )

    fun setContentWidthPx(widthPx: Int) {
        contentWidthPx = widthPx.coerceAtLeast(0)
    }

    fun currentBubbleWidthPx(): Int = contentWidthPx

    fun beginFirstFrameRender() {
        firstFrameRender = true
    }

    fun finishFirstFrameRender() {
        firstFrameRender = false
    }

    fun resetRenderStats() {
        renderStats = RenderStats()
    }

    fun snapshotRenderStats(): RenderStats = renderStats

    fun upgradeVisibleRange(visibleItems: List<ChatMessageItem>): UpgradeResult {
        // 当前实现不区分首帧/升级渲染，先返回统计占位，保证调用方可编译运行
        return UpgradeResult(
            upgradedAssistantCount = visibleItems.count { it is ChatMessageItem.AssistantMessageItem },
            upgradedToolCount = visibleItems.count {
                it is ChatMessageItem.ToolTextMessageItem || it is ChatMessageItem.ToolCallMessageItem
            }
        )
    }

    override fun getItemViewType(position: Int, item: ChatMessageItem): Int {
        return when (item) {
            is ChatMessageItem.UserMessageItem -> VIEW_TYPE_USER
            is ChatMessageItem.AssistantMessageItem -> VIEW_TYPE_ASSISTANT
            is ChatMessageItem.ToolTextMessageItem -> VIEW_TYPE_TOOL_TEXT
            is ChatMessageItem.ToolCallMessageItem -> VIEW_TYPE_TOOL_CALL
        }
    }

    override fun onCreateBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        viewType: Int
    ): ViewBinding {
        return when (viewType) {
            VIEW_TYPE_USER -> ItemChatMessageUserBinding.inflate(inflater, parent, false)
            VIEW_TYPE_ASSISTANT -> ItemChatMessageAssistantBinding.inflate(inflater, parent, false)
            VIEW_TYPE_TOOL_TEXT -> ItemChatMessageToolTextFlatBinding.inflate(inflater, parent, false)
            VIEW_TYPE_TOOL_CALL -> ItemChatMessageToolCallFlatBinding.inflate(inflater, parent, false)
            else -> error("Unknown viewType: $viewType")
        }
    }

    override fun onBindPayload(
        binding: ViewBinding,
        item: ChatMessageItem,
        holder: BaseViewHolder<ViewBinding>,
        payloads: List<Any>
    ): Boolean {
        if (payloads.contains(PAYLOAD_STREAMING_CONTENT) &&
            binding is ItemChatMessageAssistantBinding &&
            item is ChatMessageItem.AssistantMessageItem
        ) {
            bindAssistantContent(binding, item)
            return true
        }
        if (payloads.contains(PAYLOAD_TOOL_CHAIN_CONTENT) &&
            binding is ItemChatMessageToolTextFlatBinding &&
            item is ChatMessageItem.ToolTextMessageItem
        ) {
            bindToolText(binding, item)
            return true
        }
        if (payloads.contains(PAYLOAD_TOOL_CHAIN_CONTENT) &&
            binding is ItemChatMessageToolCallFlatBinding &&
            item is ChatMessageItem.ToolCallMessageItem
        ) {
            bindToolCall(binding, item)
            return true
        }
        return false
    }

    override fun onBind(
        binding: ViewBinding,
        item: ChatMessageItem,
        holder: BaseViewHolder<ViewBinding>
    ) {
        when {
            binding is ItemChatMessageUserBinding && item is ChatMessageItem.UserMessageItem -> {
                bindUser(binding, item)
            }

            binding is ItemChatMessageAssistantBinding && item is ChatMessageItem.AssistantMessageItem -> {
                bindAssistant(binding, item)
            }

            binding is ItemChatMessageToolTextFlatBinding && item is ChatMessageItem.ToolTextMessageItem -> {
                bindToolText(binding, item)
            }

            binding is ItemChatMessageToolCallFlatBinding && item is ChatMessageItem.ToolCallMessageItem -> {
                bindToolCall(binding, item)
            }

            else -> error(
                "Unexpected binding/item combination: " +
                    "${binding::class.java.simpleName} / ${item::class.java.simpleName}"
            )
        }
    }

    private fun bindUser(binding: ItemChatMessageUserBinding, item: ChatMessageItem.UserMessageItem) {
        binding.messageView.text = item.content
        binding.streamingView.visibility = if (item.isStreaming) View.VISIBLE else View.GONE
        val showNotice = item.sendStatus == MessageSendStatus.PENDING_RETRY_OFFLINE ||
                item.sendStatus == MessageSendStatus.PENDING_RETRY_TIMEOUT
        binding.ivShellNotice.visibility = if (showNotice) View.VISIBLE else View.GONE
        binding.ivShellNotice.setOnClickListener(
            if (showNotice) {
                View.OnClickListener { onUserNoticeClick?.invoke(item.id) }
            } else {
                null
            }
        )
        binding.root.setOnLongClickListener {
            copy(binding.root.context, item.content)
            true
        }
    }

    private fun bindAssistant(
        binding: ItemChatMessageAssistantBinding,
        item: ChatMessageItem.AssistantMessageItem
    ) {
        val bindStart = System.currentTimeMillis()
        if (item.isStreaming) {
            bindAssistantContent(binding, item)
        } else {
            binding.messageView.setTextIsSelectable(true)
            val markdownStart = System.currentTimeMillis()
            getMarkwon(binding.root.context).setMarkdown(binding.messageView, item.content)
            val markdownCost = System.currentTimeMillis() - markdownStart
            binding.streamingView.visibility = View.GONE
            binding.messageView.visibility =
                if (item.content.isBlank()) View.GONE else View.VISIBLE
            renderStats = renderStats.copy(
                markdownCount = renderStats.markdownCount + 1,
                markdownTotalMs = renderStats.markdownTotalMs + markdownCost
            )
        }
        binding.root.setOnLongClickListener {
            showAssistantActions(
                context = binding.root.context,
                messageId = item.id,
                content = item.content
            )
            true
        }
        val bindCost = System.currentTimeMillis() - bindStart
        renderStats = renderStats.copy(
            assistantBindCount = renderStats.assistantBindCount + 1,
            assistantBindTotalMs = renderStats.assistantBindTotalMs + bindCost,
            bindMaxMs = maxOf(renderStats.bindMaxMs, bindCost)
        )
    }

    private fun bindToolText(
        binding: ItemChatMessageToolTextFlatBinding,
        item: ChatMessageItem.ToolTextMessageItem
    ) {
        val bindStart = System.currentTimeMillis()
        bindTimelineText(
            binding = binding.toolTextItem,
            text = item.content,
            isFirst = item.isFirstInChain,
            isLast = item.isLastInChain
        )
        binding.root.setOnLongClickListener {
            onAssistantExportClick?.invoke(item.parentMessageId)
            true
        }
        val bindCost = System.currentTimeMillis() - bindStart
        renderStats = renderStats.copy(
            toolTextBindCount = renderStats.toolTextBindCount + 1,
            toolTextBindTotalMs = renderStats.toolTextBindTotalMs + bindCost,
            bindMaxMs = maxOf(renderStats.bindMaxMs, bindCost)
        )
    }

    private fun bindToolCall(
        binding: ItemChatMessageToolCallFlatBinding,
        item: ChatMessageItem.ToolCallMessageItem
    ) {
        val bindStart = System.currentTimeMillis()
        bindTimelineTool(
            binding = binding.toolItem,
            tool = item.tool,
            isFirst = item.isFirstInChain,
            isLast = item.isLastInChain
        )
        binding.root.setOnLongClickListener {
            onAssistantExportClick?.invoke(item.parentMessageId)
            true
        }
        val bindCost = System.currentTimeMillis() - bindStart
        renderStats = renderStats.copy(
            toolCallBindCount = renderStats.toolCallBindCount + 1,
            toolCallBindTotalMs = renderStats.toolCallBindTotalMs + bindCost,
            bindMaxMs = maxOf(renderStats.bindMaxMs, bindCost)
        )
    }

    private fun bindTimelineText(
        binding: ItemToolTimelineTextBinding,
        text: String,
        isFirst: Boolean,
        isLast: Boolean
    ) {
        binding.contentView.text = text
        binding.topLineView.visibility = if (isFirst) View.INVISIBLE else View.VISIBLE
        binding.bottomLineView.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
    }

    private fun bindTimelineTool(
        binding: ItemToolCallBinding,
        tool: ai.inmo.openclaw.domain.model.ToolCallUiModel,
        isFirst: Boolean,
        isLast: Boolean
    ) {
        binding.iconView.setImageResource(tool.iconResId)
        binding.contentView.text = tool.description
        binding.statusView.visibility = View.GONE
        binding.topLineView.visibility = if (isFirst) View.INVISIBLE else View.VISIBLE
        binding.bottomLineView.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
    }

    private fun bindAssistantContent(
        binding: ItemChatMessageAssistantBinding,
        item: ChatMessageItem.AssistantMessageItem
    ) {
        binding.messageView.setTextIsSelectable(false)
        binding.messageView.text = if (item.content.isBlank()) "..." else item.content
        binding.messageView.visibility = View.VISIBLE
        binding.streamingView.visibility = if (item.isStreaming) View.VISIBLE else View.GONE
    }

    private fun showAssistantActions(
        context: Context,
        messageId: String,
        content: String
    ) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setItems(
                arrayOf(
                    context.getString(R.string.chat_action_copy),
                    context.getString(R.string.chat_action_export_generated_files)
                )
            ) { _, which ->
                when (which) {
                    0 -> copy(context, content)
                    1 -> onAssistantExportClick?.invoke(messageId)
                }
            }
            .show()
    }

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_TOOL_TEXT = 3
        private const val VIEW_TYPE_TOOL_CALL = 4
        private const val PAYLOAD_STREAMING_CONTENT = "streaming_content"
        private const val PAYLOAD_TOOL_CHAIN_CONTENT = "tool_chain_content"

        @Volatile
        private var markwonInstance: Markwon? = null

        private fun getMarkwon(context: Context): Markwon {
            return markwonInstance ?: synchronized(this) {
                markwonInstance ?: Markwon.builder(context)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TablePlugin.create(context))
                    .usePlugin(object : AbstractMarkwonPlugin() {
                        override fun configureTheme(builder: MarkwonTheme.Builder) {
                            builder
                                .codeBlockTypeface(Typeface.MONOSPACE)
                                .codeTypeface(Typeface.MONOSPACE)
                                .codeBlockMargin(16)
                                .codeTextSize(
                                    (context.resources.displayMetrics.scaledDensity * 13).toInt()
                                )
                        }
                    })
                    .build()
                    .also { markwonInstance = it }
            }
        }

        private fun copy(context: Context, text: String) {
            if (text.isBlank()) return
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("chat", text))
            Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
        }
    }
}
