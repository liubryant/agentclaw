package ai.inmo.core_common.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

/**
 * 
 * Date: 2024/10/01 17:47
 * 
 */
abstract class BaseListViewTypePlusAdapter<T, VB : ViewBinding>(diffCallback: DiffUtil.ItemCallback<T>) :
    ListAdapter<T, BaseListViewTypePlusAdapter.BaseViewHolder<VB>>(diffCallback) {
    protected abstract fun getItemViewType(position: Int, item: T): Int

    protected abstract fun onCreateBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        viewType: Int
    ): VB

    protected abstract fun onBind(binding: VB, item: T, holder: BaseViewHolder<VB>)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder<VB> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = onCreateBinding(inflater, parent, viewType)
        return BaseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BaseViewHolder<VB>, position: Int) {
        val item = getItem(position)
        onBind(holder.binding, item, holder)
    }

    override fun onBindViewHolder(holder: BaseViewHolder<VB>, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val item = getItem(position)
            if (onBindPayload(holder.binding, item, holder, payloads)) return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    protected open fun onBindPayload(
        binding: VB,
        item: T,
        holder: BaseViewHolder<VB>,
        payloads: List<Any>
    ): Boolean = false

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return getItemViewType(position, item)
    }

    class BaseViewHolder<VB : ViewBinding>(val binding: VB) :
        RecyclerView.ViewHolder(binding.root)

}