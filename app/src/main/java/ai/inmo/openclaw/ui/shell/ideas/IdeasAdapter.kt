package ai.inmo.openclaw.ui.shell.ideas

import ai.inmo.openclaw.databinding.ItemIdeaCardBinding
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class IdeasAdapter : RecyclerView.Adapter<IdeasAdapter.IdeaHolder>() {
    private val items = mutableListOf<IdeaTemplate>()
    var onUseClick: ((IdeaTemplate) -> Unit)? = null

    fun submitList(list: List<IdeaTemplate>) {
        val oldList = ArrayList(items)
        items.clear()
        items.addAll(list)
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].id == list[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition] == list[newItemPosition]
            }
        }).dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IdeaHolder {
        return IdeaHolder(ItemIdeaCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: IdeaHolder, position: Int) {
        holder.bind(items[position], onUseClick)
    }

    class IdeaHolder(
        private val binding: ItemIdeaCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: IdeaTemplate, onUseClick: ((IdeaTemplate) -> Unit)?) {
            binding.titleView.text = item.title
            binding.subtitleView.text = item.subtitle
            binding.tagsView.text = item.tags.joinToString(" · ")
            binding.artView.setCardBackgroundColor(ContextCompat.getColor(binding.root.context, item.accentColorRes))
            binding.useButton.setOnClickListener { onUseClick?.invoke(item) }
            binding.root.setOnClickListener { onUseClick?.invoke(item) }
        }
    }
}
