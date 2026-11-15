package com.example.ui.dialpad

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.data.ContactEntry
import com.example.databinding.ItemT9SuggestionBinding

class T9SuggestionAdapter(
    private val onSuggestionClick: (ContactEntry) -> Unit
) : ListAdapter<ContactEntry, T9SuggestionAdapter.SuggestionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemT9SuggestionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SuggestionViewHolder(
        private val binding: ItemT9SuggestionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: ContactEntry) {
            binding.tvT9Name.text = entry.name
            binding.tvT9Number.text = entry.number
            binding.tvT9Initial.text = entry.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.root.setOnClickListener { onSuggestionClick(entry) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ContactEntry>() {
        override fun areItemsTheSame(oldItem: ContactEntry, newItem: ContactEntry) =
            oldItem.contactId == newItem.contactId && oldItem.number == newItem.number

        override fun areContentsTheSame(oldItem: ContactEntry, newItem: ContactEntry) =
            oldItem == newItem
    }
}
