package ai.inmo.openclaw.ui.chat

import ai.inmo.openclaw.databinding.ItemChatSessionBinding
import ai.inmo.openclaw.databinding.ItemChatSessionHeaderBinding
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ChatSessionAdapter : ListAdapter<ChatSessionListItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {
    enum class SessionActionMode {
        DELETE_ALWAYS,
        MORE_ON_SELECTED_HOVER
    }

    var selectedId: String? = null
    private var hoveredId: String? = null
    var sessionActionMode: SessionActionMode = SessionActionMode.DELETE_ALWAYS
    var onSessionClick: ((ChatSessionItem) -> Unit)? = null
    var onDeleteClick: ((ChatSessionItem) -> Unit)? = null
    var onMoreClick: ((ChatSessionItem, View) -> Unit)? = null

    fun submitList(list: List<ChatSessionListItem>, selected: String?) {
        selectedId = selected
        val marked = list.map {
            if (it is ChatSessionListItem.Session) {
                it.copy(isSelected = it.session.id == selected)
            } else it
        }
        super.submitList(marked)
    }

    private fun updateHoveredSession(sessionId: String?) {
        if (hoveredId == sessionId) return
        val previousHovered = hoveredId
        hoveredId = sessionId
        notifySessionChanged(previousHovered)
        notifySessionChanged(sessionId)
    }

    private fun notifySessionChanged(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        val index = currentList.indexOfFirst {
            it is ChatSessionListItem.Session && it.session.id == sessionId
        }
        if (index >= 0) notifyItemChanged(index)
    }


    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ChatSessionListItem.Header -> VIEW_TYPE_HEADER
            is ChatSessionListItem.Session -> VIEW_TYPE_SESSION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderHolder(
                ItemChatSessionHeaderBinding.inflate(inflater, parent, false)
            )
            else -> SessionHolder(
                ItemChatSessionBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatSessionListItem.Header -> (holder as HeaderHolder).bind(item)
            is ChatSessionListItem.Session -> (holder as SessionHolder).bind(
                item.session,
                item.isSelected,
                item.session.id == hoveredId,
                sessionActionMode,
                click = onSessionClick,
                deleteClick = onDeleteClick,
                moreClick = onMoreClick,
                onHoverChanged = ::updateHoveredSession
            )
        }
    }

    class HeaderHolder(
        private val binding: ItemChatSessionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatSessionListItem.Header) {
            binding.titleView.text = item.title
        }
    }

    class SessionHolder(
        private val binding: ItemChatSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private fun renderActionVisibility(selected: Boolean, hovered: Boolean, actionMode: SessionActionMode) {
            binding.actionButton.visibility = when (actionMode) {
                SessionActionMode.DELETE_ALWAYS -> View.VISIBLE
                SessionActionMode.MORE_ON_SELECTED_HOVER -> if (selected && hovered) {
                    View.VISIBLE
                } else {
                    View.INVISIBLE
                }
            }
        }

        private fun dispatchHoverState(
            itemId: String,
            onHoverChanged: (String?) -> Unit
        ) {
            if (binding.root.isHovered || binding.actionButton.isHovered) {
                onHoverChanged(itemId)
            } else {
                onHoverChanged(null)
            }
        }

        fun bind(
            item: ChatSessionItem,
            selected: Boolean,
            hovered: Boolean,
            actionMode: SessionActionMode,
            click: ((ChatSessionItem) -> Unit)?,
            deleteClick: ((ChatSessionItem) -> Unit)?,
            moreClick: ((ChatSessionItem, View) -> Unit)?,
            onHoverChanged: (String?) -> Unit
        ) {
            binding.titleView.text = item.title
            binding.root.isSelected = selected
            binding.root.setOnClickListener { click?.invoke(item) }
            binding.actionButton.setImageResource(
                when (actionMode) {
                    SessionActionMode.DELETE_ALWAYS -> ai.inmo.openclaw.R.drawable.ic_shell_delete
                    SessionActionMode.MORE_ON_SELECTED_HOVER -> ai.inmo.openclaw.R.drawable.ic_shell_chat_more
                }
            )
            binding.actionButton.contentDescription = binding.root.context.getString(
                when (actionMode) {
                    SessionActionMode.DELETE_ALWAYS -> ai.inmo.openclaw.R.string.chat_delete_confirm
                    SessionActionMode.MORE_ON_SELECTED_HOVER -> ai.inmo.openclaw.R.string.chat_session_more
                }
            )
            binding.actionButton.setOnClickListener {
                when (actionMode) {
                    SessionActionMode.DELETE_ALWAYS -> deleteClick?.invoke(item)
                    SessionActionMode.MORE_ON_SELECTED_HOVER -> moreClick?.invoke(item, binding.actionButton)
                }
            }
            binding.root.setOnHoverListener { _, event ->
                if (actionMode != SessionActionMode.MORE_ON_SELECTED_HOVER) return@setOnHoverListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER,
                    MotionEvent.ACTION_HOVER_MOVE -> onHoverChanged(item.id)
                    MotionEvent.ACTION_HOVER_EXIT -> binding.root.post {
                        dispatchHoverState(item.id, onHoverChanged)
                    }
                }
                false
            }
            binding.actionButton.setOnHoverListener { _, event ->
                if (actionMode != SessionActionMode.MORE_ON_SELECTED_HOVER) return@setOnHoverListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER,
                    MotionEvent.ACTION_HOVER_MOVE -> onHoverChanged(item.id)
                    MotionEvent.ACTION_HOVER_EXIT -> binding.actionButton.post {
                        dispatchHoverState(item.id, onHoverChanged)
                    }
                }
                false
            }
            renderActionVisibility(selected, hovered, actionMode)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SESSION = 1

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatSessionListItem>() {
            override fun areItemsTheSame(
                oldItem: ChatSessionListItem,
                newItem: ChatSessionListItem
            ): Boolean {
                return oldItem.stableId == newItem.stableId
            }

            override fun areContentsTheSame(
                oldItem: ChatSessionListItem,
                newItem: ChatSessionListItem
            ): Boolean {
                return oldItem == newItem
            }
        }
    }
}
