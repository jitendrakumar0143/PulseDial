package com.example.ui.common

import android.provider.CallLog
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.CallLogEntry
import com.example.databinding.ItemNumberHistoryBinding

class NumberHistoryAdapter(
    private val onDeleteClick: ((CallLogEntry) -> Unit)? = null
) : ListAdapter<CallLogEntry, NumberHistoryAdapter.HistoryRowViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryRowViewHolder {
        val binding = ItemNumberHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryRowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryRowViewHolder, position: Int) {
        holder.bind(getItem(position), onDeleteClick)
    }

    class HistoryRowViewHolder(
        private val binding: ItemNumberHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: CallLogEntry, onDeleteClick: ((CallLogEntry) -> Unit)?) {
            val ctx = binding.root.context
            val (iconRes, tintRes, labelRes) = when (entry.type) {
                CallLog.Calls.INCOMING_TYPE -> Triple(R.drawable.ic_call_received, R.color.call_incoming_teal, R.string.call_type_incoming)
                CallLog.Calls.OUTGOING_TYPE -> Triple(R.drawable.ic_call_made, R.color.call_outgoing_blue, R.string.call_type_outgoing)
                CallLog.Calls.MISSED_TYPE -> Triple(R.drawable.ic_call_missed, R.color.call_missed_red, R.string.call_type_missed)
                CallLog.Calls.REJECTED_TYPE -> Triple(R.drawable.ic_call_missed, R.color.call_missed_red, R.string.call_type_rejected)
                else -> Triple(R.drawable.ic_call_made, R.color.md_theme_onSurfaceVariant, R.string.call_type_outgoing)
            }

            binding.ivNhType.setImageResource(iconRes)
            binding.ivNhType.setColorFilter(ContextCompat.getColor(ctx, tintRes))
            binding.tvNhTypeLabel.text = ctx.getString(labelRes)
            binding.tvNhTime.text = DateUtils.formatDateTime(
                ctx,
                entry.timestampMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
            )
            binding.tvNhDuration.text = if (entry.durationSeconds > 0) {
                val mins = entry.durationSeconds / 60
                val secs = entry.durationSeconds % 60
                if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
            } else {
                "0s"
            }

            if (onDeleteClick != null) {
                binding.btnNhDelete.visibility = View.VISIBLE
                binding.btnNhDelete.setOnClickListener { onDeleteClick(entry) }
            } else {
                binding.btnNhDelete.visibility = View.GONE
                binding.btnNhDelete.setOnClickListener(null)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<CallLogEntry>() {
        override fun areItemsTheSame(oldItem: CallLogEntry, newItem: CallLogEntry) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: CallLogEntry, newItem: CallLogEntry) = oldItem == newItem
    }
}
