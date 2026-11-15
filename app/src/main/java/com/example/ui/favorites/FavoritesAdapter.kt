package com.example.ui.favorites

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.data.ContactEntry
import com.example.databinding.ItemFavoriteContactBinding
import com.example.ui.common.ContactThumbnailLoader

class FavoritesAdapter(
    private val onCardClick: (ContactEntry) -> Unit,
    private val onCallClick: (ContactEntry) -> Unit,
    private val onToggleStarClick: (ContactEntry) -> Unit
) : ListAdapter<ContactEntry, FavoritesAdapter.FavoriteViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val binding = ItemFavoriteContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FavoriteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FavoriteViewHolder(
        private val binding: ItemFavoriteContactBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: ContactEntry) {
            binding.tvFavName.text = entry.name
            binding.tvFavNumber.text = entry.number
            binding.tvFavInitial.text = entry.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            ContactThumbnailLoader.bindAvatar(
                imageView = binding.ivFavAvatar,
                fallbackView = binding.tvFavInitial,
                photoUri = entry.photoUri,
                phoneNumber = entry.number,
                contactId = entry.contactId,
                displayLabel = entry.name
            )

            binding.root.setOnClickListener { onCardClick(entry) }
            binding.btnFavCall.setOnClickListener { onCallClick(entry) }
            binding.btnRemoveFavorite.setOnClickListener { onToggleStarClick(entry) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ContactEntry>() {
        override fun areItemsTheSame(oldItem: ContactEntry, newItem: ContactEntry) =
            oldItem.contactId == newItem.contactId && oldItem.number == newItem.number

        override fun areContentsTheSame(oldItem: ContactEntry, newItem: ContactEntry) =
            oldItem == newItem
    }
}
