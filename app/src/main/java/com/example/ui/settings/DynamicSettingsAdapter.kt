package com.example.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.firebase.DynamicSettingItem
import com.example.databinding.ItemDynamicSettingBinding

data class DynamicSettingUiRow(
    val item: DynamicSettingItem,
    val resolvedDescription: String,
    val isChecked: Boolean,
    val isInteractive: Boolean,
    val isLastItem: Boolean
)

class DynamicSettingsAdapter(
    private val onRowClicked: (DynamicSettingUiRow) -> Unit,
    private val onToggleChanged: (DynamicSettingUiRow, Boolean) -> Boolean
) : ListAdapter<DynamicSettingUiRow, DynamicSettingsAdapter.SettingViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingViewHolder {
        val binding = ItemDynamicSettingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SettingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SettingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SettingViewHolder(
        private val binding: ItemDynamicSettingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: DynamicSettingUiRow) {
            val item = row.item
            binding.ivSettingIcon.setImageResource(iconResForKey(item.iconKey))
            binding.tvSettingTitle.text = item.title
            binding.tvSettingDesc.text = row.resolvedDescription
            binding.dividerSetting.visibility = if (row.isLastItem) View.GONE else View.VISIBLE

            binding.rowDynamicSetting.alpha = if (row.isInteractive) 1f else 0.58f

            if (item.type == DynamicSettingItem.TYPE_TOGGLE) {
                binding.switchDynamicSetting.visibility = View.VISIBLE
                binding.switchDynamicSetting.contentDescription = item.title
                binding.switchDynamicSetting.setOnCheckedChangeListener(null)
                binding.switchDynamicSetting.isChecked = row.isChecked
                binding.switchDynamicSetting.isEnabled = row.isInteractive
                binding.switchDynamicSetting.setOnCheckedChangeListener { buttonView, isChecked ->
                    val accepted = onToggleChanged(row, isChecked)
                    if (!accepted) {
                        buttonView.setOnCheckedChangeListener(null)
                        buttonView.isChecked = !isChecked
                        bindToggleListener(row)
                    }
                }
                binding.rowDynamicSetting.setOnClickListener {
                    if (row.isInteractive) {
                        binding.switchDynamicSetting.toggle()
                    } else {
                        onRowClicked(row)
                    }
                }
            } else {
                binding.switchDynamicSetting.setOnCheckedChangeListener(null)
                binding.switchDynamicSetting.visibility = View.GONE
                binding.rowDynamicSetting.setOnClickListener {
                    onRowClicked(row)
                }
            }
        }

        private fun bindToggleListener(row: DynamicSettingUiRow) {
            binding.switchDynamicSetting.setOnCheckedChangeListener { buttonView, isChecked ->
                val accepted = onToggleChanged(row, isChecked)
                if (!accepted) {
                    buttonView.setOnCheckedChangeListener(null)
                    buttonView.isChecked = !isChecked
                    bindToggleListener(row)
                }
            }
        }

        private fun iconResForKey(iconKey: String): Int = when (iconKey) {
            DynamicSettingItem.ICON_RECORD -> R.drawable.ic_record_dot
            DynamicSettingItem.ICON_FOLDER -> R.drawable.ic_folder
            DynamicSettingItem.ICON_ACCESSIBILITY -> R.drawable.ic_verified_shield
            DynamicSettingItem.ICON_BLOCK -> R.drawable.ic_block
            DynamicSettingItem.ICON_PALETTE -> R.drawable.ic_palette
            DynamicSettingItem.ICON_VOLUME -> R.drawable.ic_volume_tune
            DynamicSettingItem.ICON_REPLY -> R.drawable.ic_quick_reply
            DynamicSettingItem.ICON_INFO -> R.drawable.ic_verified_shield
            else -> R.drawable.ic_settings
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DynamicSettingUiRow>() {
        override fun areItemsTheSame(oldItem: DynamicSettingUiRow, newItem: DynamicSettingUiRow): Boolean =
            oldItem.item.key == newItem.item.key

        override fun areContentsTheSame(oldItem: DynamicSettingUiRow, newItem: DynamicSettingUiRow): Boolean =
            oldItem == newItem
    }
}
