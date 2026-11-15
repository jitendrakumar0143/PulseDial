package com.example.ui.history

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.RecordingEntry
import com.example.databinding.ItemRecordingBinding
import java.util.Locale

class RecordingsAdapter(
    private val onPlayPauseClick: (RecordingEntry) -> Unit
) : ListAdapter<RecordingEntry, RecordingsAdapter.RecordingViewHolder>(DiffCallback) {

    private var activeUri: Uri? = null
    private var isPlaying: Boolean = false

    fun updatePlaybackState(uri: Uri?, playing: Boolean) {
        val prevUri = activeUri
        activeUri = uri
        isPlaying = playing
        currentList.forEachIndexed { index, item ->
            if (item.uri == prevUri || item.uri == uri) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val binding = ItemRecordingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecordingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RecordingViewHolder(
        private val binding: ItemRecordingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: RecordingEntry) {
            val ctx = binding.root.context
            binding.tvRecordingTitle.text = entry.displayName.removeSuffix(".mp3")

            val relDate = DateUtils.getRelativeTimeSpanString(
                entry.timestampMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            val durationStr = formatDuration(entry.durationMs)
            val sizeStr = Formatter.formatShortFileSize(ctx, entry.sizeBytes)
            binding.tvRecordingMeta.text = "$durationStr • $sizeStr • ${entry.sourceLabel} • $relDate"

            val isThisPlaying = entry.uri == activeUri && isPlaying
            binding.btnRecordingPlayPause.setImageResource(
                if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
            binding.btnRecordingPlayPause.contentDescription = ctx.getString(
                if (isThisPlaying) R.string.action_pause else R.string.action_play
            )

            binding.root.setOnClickListener { onPlayPauseClick(entry) }
            binding.btnRecordingPlayPause.setOnClickListener { onPlayPauseClick(entry) }
        }

        private fun formatDuration(durationMs: Long): String {
            if (durationMs <= 0L) return "00:00"
            val totalSeconds = durationMs / 1000L
            val mins = totalSeconds / 60L
            val secs = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RecordingEntry>() {
        override fun areItemsTheSame(oldItem: RecordingEntry, newItem: RecordingEntry) =
            oldItem.uri == newItem.uri

        override fun areContentsTheSame(oldItem: RecordingEntry, newItem: RecordingEntry) =
            oldItem == newItem
    }
}
