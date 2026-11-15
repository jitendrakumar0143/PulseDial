package com.example.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.firebase.AdminRepository
import com.example.data.firebase.DeviceIdentityProvider
import com.example.data.firebase.UserEntitlement
import com.example.databinding.ItemAdminExpiringUserBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple highlighted list adapter for the Admin "Expiring (≤7d)" view backed directly by
 * Firestore range queries on UserEntitlement.subscriptionExpiresAt.
 */
class AdminExpiringUsersAdapter(
    private val onRenewMonthClick: (UserEntitlement) -> Unit,
    private val onManageClick: (UserEntitlement) -> Unit,
    private val onRevokeProClick: (UserEntitlement) -> Unit
) : ListAdapter<UserEntitlement, AdminExpiringUsersAdapter.ExpiringViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpiringViewHolder {
        val binding = ItemAdminExpiringUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ExpiringViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExpiringViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ExpiringViewHolder(
        private val binding: ItemAdminExpiringUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UserEntitlement) {
            val ctx = binding.root.context
            val daysLeft = AdminRepository.getSubscriptionRemainingDays(item)
            val expiryMs = AdminRepository.resolveEffectiveExpiryMs(item)
            val formattedDate = if (expiryMs > 0L) {
                SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(expiryMs))
            } else {
                "—"
            }

            // Tiered highlight:
            //   <= 2 days -> urgent red error highlight
            //   3..7 days -> warm amber warning highlight
            //   > 7 days or unlimited -> healthy primary active subscriber highlight
            when {
                daysLeft in 1..2 -> {
                    binding.cardExpiringUser.setCardBackgroundColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_errorContainer)
                    )
                    binding.cardExpiringUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_error)
                    binding.tvExpiringUrgencyLabel.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer)
                    )
                    binding.tvExpiringUrgencyLabel.text = if (daysLeft == 1) {
                        ctx.getString(R.string.admin_expiring_highlight_one_day, formattedDate)
                    } else {
                        ctx.getString(R.string.admin_expiring_highlight_banner, daysLeft, formattedDate)
                    }
                    binding.chipExpiringDaysLeft.text = ctx.getString(R.string.admin_user_status_expiring_pro, daysLeft)
                    binding.chipExpiringDaysLeft.setChipBackgroundColorResource(R.color.md_theme_error)
                    binding.chipExpiringDaysLeft.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onError)
                    )
                }
                daysLeft in 3..AdminRepository.SUB_EXPIRING_SOON_DAYS -> {
                    binding.cardExpiringUser.setCardBackgroundColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_tertiaryContainer)
                    )
                    binding.cardExpiringUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_tertiary)
                    binding.tvExpiringUrgencyLabel.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onTertiaryContainer)
                    )
                    binding.tvExpiringUrgencyLabel.text =
                        ctx.getString(R.string.admin_expiring_highlight_banner, daysLeft, formattedDate)
                    binding.chipExpiringDaysLeft.text = ctx.getString(R.string.admin_user_status_expiring_pro, daysLeft)
                    binding.chipExpiringDaysLeft.setChipBackgroundColorResource(R.color.md_theme_tertiary)
                    binding.chipExpiringDaysLeft.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onTertiary)
                    )
                }
                else -> {
                    binding.cardExpiringUser.setCardBackgroundColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_surface)
                    )
                    binding.cardExpiringUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_primary)
                    binding.tvExpiringUrgencyLabel.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_primary)
                    )
                    binding.tvExpiringUrgencyLabel.text = if (daysLeft > 0) {
                        ctx.getString(R.string.admin_active_highlight_banner, daysLeft, formattedDate)
                    } else {
                        ctx.getString(R.string.admin_active_highlight_open)
                    }
                    binding.chipExpiringDaysLeft.text = if (daysLeft > 0) {
                        ctx.getString(R.string.admin_user_status_expiring_pro, daysLeft)
                    } else {
                        ctx.getString(R.string.admin_user_status_premium)
                    }
                    binding.chipExpiringDaysLeft.setChipBackgroundColorResource(R.color.md_theme_primaryContainer)
                    binding.chipExpiringDaysLeft.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onPrimaryContainer)
                    )
                }
            }

            binding.tvExpiringUserName.text =
                item.displayName.ifBlank { ctx.getString(R.string.account_default_name) }
            binding.tvExpiringUserEmail.text = item.email.ifBlank { item.userId }

            val deviceTag = if (item.deviceHash.length == 64) {
                DeviceIdentityProvider.formatShortDeviceTag(item.deviceHash)
            } else {
                "—"
            }
            val uidDisplay = item.userId.ifBlank { item.id }.take(14)
            binding.tvExpiringUserMeta.text = buildString {
                if (uidDisplay.isNotBlank()) {
                    append("ID: ").append(uidDisplay)
                }
                if (item.phoneNumber.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(item.phoneNumber)
                }
                if (isNotEmpty()) append(" • ")
                append("Device: ").append(deviceTag)
                if (item.subscriptionProductId.isNotBlank()) {
                    append(" • Plan: ").append(item.subscriptionProductId)
                }
            }

            binding.btnExpiringRenew.setOnClickListener { onRenewMonthClick(item) }
            binding.btnExpiringManage.setOnClickListener { onManageClick(item) }
            binding.btnExpiringRevoke.setOnClickListener { onRevokeProClick(item) }
            binding.root.setOnClickListener { onManageClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<UserEntitlement>() {
        override fun areItemsTheSame(oldItem: UserEntitlement, newItem: UserEntitlement): Boolean =
            oldItem.userId == newItem.userId && oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: UserEntitlement, newItem: UserEntitlement): Boolean =
            oldItem == newItem
    }
}
