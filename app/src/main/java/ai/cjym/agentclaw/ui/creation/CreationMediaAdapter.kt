package ai.cjym.agentclaw.ui.creation

import ai.cjym.agentclaw.databinding.ItemCreationMediaBinding
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView

class CreationMediaAdapter(
    private val useVariableHeight: Boolean,
    private val onClick: (CreationMedia) -> Unit
) : RecyclerView.Adapter<CreationMediaAdapter.MediaHolder>() {

    private val items = mutableListOf<CreationMedia>()

    fun submitList(newItems: List<CreationMedia>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaHolder {
        return MediaHolder(
            ItemCreationMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: MediaHolder, position: Int) {
        holder.bind(items[position], useVariableHeight, onClick)
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: MediaHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class MediaHolder(
        private val binding: ItemCreationMediaBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            media: CreationMedia,
            useVariableHeight: Boolean,
            onClick: (CreationMedia) -> Unit
        ) {
            binding.playButton.visibility = if (media.type == CreationMedia.Type.VIDEO) View.VISIBLE else View.GONE
            binding.loadingShimmer.visibility = View.VISIBLE
            binding.root.setOnClickListener { onClick(media) }
            binding.playButton.setOnClickListener { onClick(media) }
            binding.root.alpha = 0f
            binding.root.translationY = 18f
            if (!useVariableHeight) {
                binding.root.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = (172 * binding.root.resources.displayMetrics.density).toInt()
                }
            }
            CreationAssetLoader.loadThumbnail(binding.thumbnailView, media) { bitmap ->
                if (useVariableHeight) binding.root.post {
                    val width = binding.root.width.takeIf { it > 0 }
                        ?: (binding.root.resources.displayMetrics.widthPixels / 2)
                    val aspect = (bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1))
                        .coerceIn(0.82f, 1.62f)
                    binding.root.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = (width * aspect).toInt()
                    }
                }
                binding.loadingShimmer.animate().alpha(0f).setDuration(220L).withEndAction {
                    binding.loadingShimmer.visibility = View.GONE
                    binding.loadingShimmer.alpha = 1f
                }.start()
            }
            binding.root.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((bindingAdapterPosition.coerceAtLeast(0) % 6) * 35L)
                .setDuration(280L)
                .start()
        }

        fun clear() {
            binding.thumbnailView.tag = null
            binding.thumbnailView.setImageDrawable(null)
            binding.root.animate().cancel()
            binding.root.updateLayoutParams<ViewGroup.LayoutParams> {
                height = (220 * binding.root.resources.displayMetrics.density).toInt()
            }
        }
    }
}
