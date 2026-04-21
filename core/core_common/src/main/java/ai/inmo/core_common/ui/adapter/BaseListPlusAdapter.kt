package ai.inmo.core_common.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

/**
 * 
 * Date: 2024/09/23 14:48
 * 
 */
abstract class BaseListPlusAdapter<T, VB : ViewBinding>(diffCallback: DiffUtil.ItemCallback<T>) :
    ListAdapter<T, BaseListPlusAdapter.BaseViewHolder<VB>>(diffCallback) {

    abstract fun createBinding(inflater: LayoutInflater, parent: ViewGroup): VB

    abstract fun bind(binding: VB, item: T, holder: BaseViewHolder<VB>)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder<VB> {
        val binding = createBinding(LayoutInflater.from(parent.context), parent)
        return BaseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BaseViewHolder<VB>, position: Int) {
        val item = getItem(position)
        bind(holder.binding, item, holder)
    }

    class BaseViewHolder<VB : ViewBinding>(val binding: VB) :
        RecyclerView.ViewHolder(binding.root)

}