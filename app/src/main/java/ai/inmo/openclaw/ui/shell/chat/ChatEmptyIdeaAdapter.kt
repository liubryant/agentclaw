package ai.inmo.openclaw.ui.shell.chat

import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ItemChatEmptyIdeaBinding
import ai.inmo.openclaw.ui.shell.ideas.IdeaTemplate
import ai.inmo.core_common.ui.adapter.BaseListViewTypePlusAdapter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil

class ChatEmptyIdeaAdapter : BaseListViewTypePlusAdapter<IdeaTemplate, ItemChatEmptyIdeaBinding>(
    object : DiffUtil.ItemCallback<IdeaTemplate>() {
        override fun areItemsTheSame(oldItem: IdeaTemplate, newItem: IdeaTemplate): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: IdeaTemplate, newItem: IdeaTemplate): Boolean {
            return oldItem == newItem
        }
    }
) {
    var onItemClick: ((IdeaTemplate) -> Unit)? = null

    override fun getItemViewType(position: Int, item: IdeaTemplate): Int {
        return 0
    }

    override fun onCreateBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        viewType: Int
    ): ItemChatEmptyIdeaBinding {
        return ItemChatEmptyIdeaBinding.inflate(inflater, parent, false)
    }

    override fun onBind(
        binding: ItemChatEmptyIdeaBinding,
        item: IdeaTemplate,
        holder: BaseViewHolder<ItemChatEmptyIdeaBinding>
    ) {
        binding.titleView.text = item.title
        binding.subtitleView.text = item.subtitle
        binding.artView.setImageResource(
            when (item.id) {
                "idea_skill_onboarding" -> R.drawable.img_chat_idea_skill
                "idea_life_assistant" -> R.drawable.img_chat_idea_life
                else -> R.drawable.img_chat_idea_docs
            }
        )
        binding.root.background = holder.itemView.context.getDrawable(
            when (item.id) {
                "idea_skill_onboarding" -> R.drawable.bg_chat_idea_skill
                "idea_life_assistant" -> R.drawable.bg_chat_idea_life
                else -> R.drawable.bg_chat_idea_docs
            }
        )
        binding.root.setOnClickListener { onItemClick?.invoke(item) }
    }
}
