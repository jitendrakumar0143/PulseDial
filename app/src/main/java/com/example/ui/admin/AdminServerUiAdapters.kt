package com.example.ui.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.data.firebase.DynamicSettingItem
import com.example.data.firebase.NavigationTabItem
import com.example.databinding.ItemAdminServerConfigRowBinding

class AdminNavTabsAdapter(
    private val onToggleEnabled: (NavigationTabItem, Boolean) -> Unit,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onEdit: (NavigationTabItem) -> Unit,
    private val onDelete: (NavigationTabItem) -> Unit
) : ListAdapter<NavigationTabItem, AdminNavTabsAdapter.TabViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemAdminServerConfigRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(getItem(position), position, itemCount)
    }

    inner class TabViewHolder(
        private val binding: ItemAdminServerConfigRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NavigationTabItem, position: Int, totalCount: Int) {
            val typeLabel = if (item.type == NavigationTabItem.TYPE_CUSTOM_BANNER) {
                "Custom Screen"
            } else {
                "Built-in Tab"
            }
            binding.tvAdminItemTitle.text = item.title
            binding.tvAdminItemSubtitle.text = "ID: ${item.id} • $typeLabel • Pos ${position + 1}"

            binding.switchAdminItemEnabled.setOnCheckedChangeListener(null)
            binding.switchAdminItemEnabled.isChecked = item.enabled
            binding.switchAdminItemEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(item, isChecked)
            }

            binding.btnAdminItemUp.isEnabled = position > 0
            binding.btnAdminItemDown.isEnabled = position < totalCount - 1
            binding.btnAdminItemUp.setOnClickListener { onMoveUp(position) }
            binding.btnAdminItemDown.setOnClickListener { onMoveDown(position) }
            binding.btnAdminItemEdit.setOnClickListener { onEdit(item) }

            val isCustom = item.type == NavigationTabItem.TYPE_CUSTOM_BANNER
            binding.btnAdminItemDelete.visibility = if (isCustom) View.VISIBLE else View.GONE
            binding.btnAdminItemDelete.setOnClickListener { onDelete(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<NavigationTabItem>() {
        override fun areItemsTheSame(oldItem: NavigationTabItem, newItem: NavigationTabItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: NavigationTabItem, newItem: NavigationTabItem): Boolean =
            oldItem == newItem
    }
}

class AdminSettingsItemsAdapter(
    private val onToggleEnabled: (DynamicSettingItem, Boolean) -> Unit,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onEdit: (DynamicSettingItem) -> Unit,
    private val onDelete: (DynamicSettingItem) -> Unit
) : ListAdapter<DynamicSettingItem, AdminSettingsItemsAdapter.ItemViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val binding = ItemAdminServerConfigRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(getItem(position), position, itemCount)
    }

    inner class ItemViewHolder(
        private val binding: ItemAdminServerConfigRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DynamicSettingItem, position: Int, totalCount: Int) {
            val lockText = if (item.lockedByAdmin) " • Locked (${if (item.defaultValue) "ON" else "OFF"})" else ""
            binding.tvAdminItemTitle.text = item.title
            binding.tvAdminItemSubtitle.text = "${item.description.ifBlank { item.key }} • ${item.type}$lockText"

            binding.switchAdminItemEnabled.setOnCheckedChangeListener(null)
            binding.switchAdminItemEnabled.isChecked = item.enabled
            binding.switchAdminItemEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(item, isChecked)
            }

            binding.btnAdminItemUp.isEnabled = position > 0
            binding.btnAdminItemDown.isEnabled = position < totalCount - 1
            binding.btnAdminItemUp.setOnClickListener { onMoveUp(position) }
            binding.btnAdminItemDown.setOnClickListener { onMoveDown(position) }
            binding.btnAdminItemEdit.setOnClickListener { onEdit(item) }
            binding.btnAdminItemDelete.visibility = View.VISIBLE
            binding.btnAdminItemDelete.setOnClickListener { onDelete(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DynamicSettingItem>() {
        override fun areItemsTheSame(oldItem: DynamicSettingItem, newItem: DynamicSettingItem): Boolean =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: DynamicSettingItem, newItem: DynamicSettingItem): Boolean =
            oldItem == newItem
    }
}
