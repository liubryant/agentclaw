package ai.inmo.openclaw.ui.shell.schedule

import ai.inmo.openclaw.R
import ai.inmo.openclaw.databinding.ItemScheduleTaskBinding
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.TaskHolder>() {
    private val items = mutableListOf<ScheduledTaskUiModel>()
    var onToggleChanged: ((ScheduledTaskUiModel, Boolean) -> Unit)? = null
    var onCarryToChat: ((ScheduledTaskUiModel) -> Unit)? = null

    fun submitList(list: List<ScheduledTaskUiModel>) {
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskHolder {
        return TaskHolder(ItemScheduleTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: TaskHolder, position: Int) {
        holder.bind(items[position], onToggleChanged, onCarryToChat)
    }

    class TaskHolder(
        private val binding: ItemScheduleTaskBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: ScheduledTaskUiModel,
            onToggleChanged: ((ScheduledTaskUiModel, Boolean) -> Unit)?,
            onCarryToChat: ((ScheduledTaskUiModel) -> Unit)?
        ) {
            binding.titleView.text = item.title
            binding.summaryView.text = item.summary
            binding.scheduleView.text = item.scheduleText
            binding.statusView.text = when (item.status) {
                ScheduleStatus.RUNNING -> itemView.context.getString(R.string.schedule_status_running)
                ScheduleStatus.PAUSED -> itemView.context.getString(R.string.schedule_status_paused)
                ScheduleStatus.COMPLETED -> itemView.context.getString(R.string.schedule_status_completed)
            }
            binding.statusView.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    when (item.status) {
                        ScheduleStatus.RUNNING -> R.color.schedule_running
                        ScheduleStatus.PAUSED -> R.color.schedule_paused
                        ScheduleStatus.COMPLETED -> R.color.schedule_completed
                    }
                )
            )
            binding.lastRunView.text = itemView.context.getString(R.string.schedule_last_run, item.lastRunAt)
            binding.nextRunView.text = itemView.context.getString(R.string.schedule_next_run, item.nextRunAt)
            binding.enabledSwitch.setOnCheckedChangeListener(null)
            binding.enabledSwitch.isChecked = item.enabled
            binding.enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggleChanged?.invoke(item, isChecked)
            }
            binding.carryButton.setOnClickListener { onCarryToChat?.invoke(item) }
        }
    }
}
