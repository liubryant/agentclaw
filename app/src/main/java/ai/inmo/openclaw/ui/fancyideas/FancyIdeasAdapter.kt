package ai.inmo.openclaw.ui.fancyideas

import ai.inmo.openclaw.databinding.ItemFancyideasHeaderBinding
import ai.inmo.openclaw.databinding.ItemFancyideasBinding
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class FancyIdeasAdapter(
    private val items: List<FancyIdeasListItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var onItemClick: ((FancyIdeasItem) -> Unit)? = null
    var onItemHover: ((View, FancyIdeasItem, Boolean) -> Unit)? = null

    override fun getItemViewType(position: Int): Int =
        when (items[position]) {
            is FancyIdeasHeaderItem -> VIEW_TYPE_HEADER
            is FancyIdeasItem -> VIEW_TYPE_ITEM
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> FancyHeaderViewHolder(
                ItemFancyideasHeaderBinding.inflate(inflater, parent, false)
            )
            else -> FancySkillViewHolder(
                ItemFancyideasBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FancyIdeasHeaderItem -> (holder as FancyHeaderViewHolder).bind(item)
            is FancyIdeasItem -> (holder as FancySkillViewHolder).bind(item, onItemClick, onItemHover)
        }
    }

    override fun getItemCount(): Int = items.size

    fun isHeader(position: Int): Boolean = items.getOrNull(position) is FancyIdeasHeaderItem

    private class FancyHeaderViewHolder(
        private val binding: ItemFancyideasHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FancyIdeasHeaderItem) {
            binding.categoryTitleView.text = item.title
        }
    }

    class FancySkillViewHolder(
        private val binding: ItemFancyideasBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: FancyIdeasItem,
            onItemClick: ((FancyIdeasItem) -> Unit)?,
            onItemHover: ((View, FancyIdeasItem, Boolean) -> Unit)?
        ) {
            binding.item = item
            binding.skillIconView.setImageResource(item.iconResId)
            binding.root.setOnClickListener { onItemClick?.invoke(item) }
            binding.root.setOnHoverListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER,
                    MotionEvent.ACTION_HOVER_MOVE -> onItemHover?.invoke(view, item, true)
                    MotionEvent.ACTION_HOVER_EXIT,
                    MotionEvent.ACTION_CANCEL -> onItemHover?.invoke(view, item, false)
                }
                false
            }
            binding.executePendingBindings()
        }
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_ITEM = 1
    }
}
