package com.example.ui.history

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
import com.example.databinding.ItemCallLogBinding
import com.example.telecom.CallerIdResolver
import com.example.telecom.CallerIdentityInfo
import com.example.telecom.PhoneGeoDatabase
import com.example.ui.common.ContactThumbnailLoader

class CallLogAdapter(
    private val onRowClick: (CallLogEntry) -> Unit,
    private val onCallClick: (CallLogEntry) -> Unit,
    private val onBlockClick: (CallLogEntry) -> Unit
) : ListAdapter<CallLogEntry, CallLogAdapter.CallLogViewHolder>(DiffCallback) {

    private var resolvedIdentities: Map<String, CallerIdentityInfo> = emptyMap()

    fun updateResolvedIdentities(identities: Map<String, CallerIdentityInfo>) {
        resolvedIdentities = identities
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallLogViewHolder {
        val binding = ItemCallLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CallLogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CallLogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CallLogViewHolder(
        private val binding: ItemCallLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: CallLogEntry) {
            val ctx = binding.root.context
            val key = PhoneGeoDatabase.toE164Digits(entry.number)
            val identity = resolvedIdentities[key] ?: CallerIdResolver.resolveInstant(
                rawNumber = entry.number,
                savedContactName = entry.name
            )
            val resolvedRealName = entry.name?.takeIf { it.isNotBlank() }
                ?: identity.displayName.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Unknown Caller", ignoreCase = true) &&
                        !it.equals("Private / Hidden Number", ignoreCase = true)
                }
            val title = resolvedRealName ?: entry.number
            binding.tvCallerName.text = title
            binding.tvAvatarInitial.text = title.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            ContactThumbnailLoader.bindAvatar(
                imageView = binding.ivCallerAvatar,
                fallbackView = binding.tvAvatarInitial,
                photoUri = entry.photoUri,
                phoneNumber = entry.number,
                displayLabel = title
            )

            val (iconRes, tintRes, labelRes) = when (entry.type) {
                CallLog.Calls.INCOMING_TYPE -> Triple(R.drawable.ic_call_received, R.color.call_incoming_teal, R.string.call_type_incoming)
                CallLog.Calls.OUTGOING_TYPE -> Triple(R.drawable.ic_call_made, R.color.call_outgoing_blue, R.string.call_type_outgoing)
                CallLog.Calls.MISSED_TYPE -> Triple(R.drawable.ic_call_missed, R.color.call_missed_red, R.string.call_type_missed)
                CallLog.Calls.REJECTED_TYPE -> Triple(R.drawable.ic_call_missed, R.color.call_missed_red, R.string.call_type_rejected)
                else -> Triple(R.drawable.ic_call_made, R.color.md_theme_onSurfaceVariant, R.string.call_type_outgoing)
            }

            binding.ivCallType.setImageResource(iconRes)
            binding.ivCallType.setColorFilter(ContextCompat.getColor(ctx, tintRes))

            val relTime = DateUtils.getRelativeTimeSpanString(
                entry.timestampMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            val durationText = if (entry.durationSeconds > 0) {
                " • ${entry.durationSeconds}s"
            } else {
                ""
            }
            val typeLabel = ctx.getString(labelRes)
            val subNumber = if (resolvedRealName != null) "${entry.number} • " else ""
            val shieldBadge = if (entry.name == null &&
                com.example.data.DialerPreferences(ctx).isRejectedByShield(entry.number)
            ) {
                " • Blocked by Shield"
            } else {
                ""
            }
            binding.tvCallMeta.text = "$subNumber$typeLabel • $relTime$durationText$shieldBadge"

            val geoLine = if (identity.geo.hasLocation) identity.structuredLocationLine() else ""
            val socialLine = identity.socialBadgesSummary()
            val locationAndSocial = listOf(geoLine, socialLine)
                .filter { it.isNotBlank() && it != CallerIdResolver.UNKNOWN_LOCATION }
                .joinToString(" • ")
            if (locationAndSocial.isNotEmpty()) {
                binding.tvCallerLocationLine.text = locationAndSocial
                binding.tvCallerLocationLine.visibility = View.VISIBLE
            } else {
                binding.tvCallerLocationLine.visibility = View.GONE
            }

            binding.root.setOnClickListener { onRowClick(entry) }
            binding.btnCallBack.setOnClickListener { onCallClick(entry) }
            binding.btnBlockCallLog.setOnClickListener { onBlockClick(entry) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<CallLogEntry>() {
        override fun areItemsTheSame(oldItem: CallLogEntry, newItem: CallLogEntry) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: CallLogEntry, newItem: CallLogEntry) = oldItem == newItem
    }
}
