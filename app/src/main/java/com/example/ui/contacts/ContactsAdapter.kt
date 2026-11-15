package com.example.ui.contacts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.ContactEntry
import com.example.databinding.ItemContactBinding

class ContactsAdapter(
    private val onRowClick: (ContactEntry) -> Unit,
    private val onCallClick: (ContactEntry) -> Unit,
    private val onStarClick: (ContactEntry) -> Unit
) : ListAdapter<ContactEntry, ContactsAdapter.ContactViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ContactViewHolder(
        private val binding: ItemContactBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: ContactEntry) {
            binding.tvContactName.text = entry.name
            binding.tvContactNumber.text = entry.number
            binding.tvContactInitial.text = entry.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.btnContactStar.setImageResource(
                if (entry.isStarred) R.drawable.ic_star else R.drawable.ic_star_outline
            )

            binding.root.setOnClickListener { onRowClick(entry) }
            binding.btnContactCall.setOnClickListener { onCallClick(entry) }
            binding.btnContactStar.setOnClickListener { onStarClick(entry) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ContactEntry>() {
        override fun areItemsTheSame(oldItem: ContactEntry, newItem: ContactEntry) =
            oldItem.contactId == newItem.contactId && oldItem.number == newItem.number

        override fun areContentsTheSame(oldItem: ContactEntry, newItem: ContactEntry) =
            oldItem == newItem
    }
}
