package com.example.ui.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.DialerPreferences
import com.example.data.firebase.AdminRepository
import com.example.data.firebase.AdminUserAccountStatus
import com.example.data.firebase.DeviceIdentityProvider
import com.example.data.firebase.UserEntitlement
import com.example.databinding.ItemAdminUserBinding

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminUsersAdapter(
    private val onToggleProClick: (UserEntitlement) -> Unit,
    private val onRenewMonthClick: (UserEntitlement) -> Unit,
    private val onExtendTrialClick: (UserEntitlement) -> Unit,
    private val onToggleBlockClick: (UserEntitlement) -> Unit,
    private val onManageClick: (UserEntitlement) -> Unit
) : ListAdapter<UserEntitlement, AdminUsersAdapter.UserViewHolder>(DiffCallback) {

    var trialDaysConfig: Int = DialerPreferences.TRIAL_DAYS
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val binding = ItemAdminUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class UserViewHolder(
        private val binding: ItemAdminUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UserEntitlement) {
            val ctx = binding.root.context
            binding.tvAdminUserName.text = item.displayName.ifBlank { ctx.getString(R.string.account_default_name) }
            binding.tvAdminUserEmail.text = item.email.ifBlank { item.userId }

            val status = AdminRepository.resolveUserAccountStatus(item, trialDaysConfig)
            val daysLeft = AdminRepository.getRemainingDaysForUser(item, trialDaysConfig)
            val subDaysLeft = AdminRepository.getSubscriptionRemainingDays(item)
            val effectiveExpiryMs = AdminRepository.resolveEffectiveExpiryMs(item)

            val strokeDp = if (status == AdminUserAccountStatus.EXPIRING_SOON) 2 else 1
            binding.cardAdminUser.strokeWidth = (strokeDp * ctx.resources.displayMetrics.density).toInt()
            when (status) {
                AdminUserAccountStatus.BLOCKED -> {
                    binding.cardAdminUser.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                    binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_outline)
                    binding.tvAdminUserExpiryBanner.visibility = View.GONE
                    binding.chipAdminUserStatus.setText(R.string.admin_user_status_blocked)
                    binding.chipAdminUserStatus.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
                    binding.chipAdminUserStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer)
                    )
                }
                AdminUserAccountStatus.EXPIRING_SOON -> {
                    binding.tvAdminUserExpiryBanner.visibility = View.VISIBLE
                    if (item.isPremiumUser) {
                        val safeDays = subDaysLeft.coerceAtLeast(1)
                        val fmtDate = if (effectiveExpiryMs > 0L) {
                            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(effectiveExpiryMs))
                        } else {
                            "—"
                        }
                        binding.cardAdminUser.setCardBackgroundColor(
                            ContextCompat.getColor(ctx, R.color.md_theme_errorContainer)
                        )
                        binding.cardAdminUser.strokeColor =
                            ContextCompat.getColor(ctx, R.color.md_theme_error)
                        binding.tvAdminUserExpiryBanner.setTextColor(
                            ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer)
                        )
                        binding.tvAdminUserExpiryBanner.text = if (safeDays == 1) {
                            ctx.getString(R.string.admin_expiring_highlight_one_day, fmtDate)
                        } else {
                            ctx.getString(R.string.admin_expiring_highlight_banner, safeDays, fmtDate)
                        }
                        binding.chipAdminUserStatus.text = ctx.getString(
                            R.string.admin_user_status_expiring_pro,
                            safeDays
                        )
                        binding.chipAdminUserStatus.setChipBackgroundColorResource(R.color.md_theme_error)
                        binding.chipAdminUserStatus.setTextColor(
                            ContextCompat.getColor(ctx, R.color.md_theme_onError)
                        )
                    } else {
                        val safeDays = daysLeft.coerceAtLeast(1)
                        binding.cardAdminUser.setCardBackgroundColor(
                            ContextCompat.getColor(ctx, R.color.md_theme_tertiaryContainer)
                        )
                        binding.cardAdminUser.strokeColor =
                            ContextCompat.getColor(ctx, R.color.md_theme_tertiary)
                        binding.tvAdminUserExpiryBanner.setTextColor(
                            ContextCompat.getColor(ctx, R.color.md_theme_onTertiaryContainer)
                        )
                        binding.tvAdminUserExpiryBanner.text = if (safeDays == 1) {
                            ctx.getString(R.string.admin_expiring_trial_one_day)
                        } else {
                            ctx.getString(R.string.admin_expiring_trial_banner, safeDays)
                        }
                        binding.chipAdminUserStatus.text = ctx.getString(
                            R.string.admin_user_status_expiring_trial,
                            safeDays
                        )
                        binding.chipAdminUserStatus.setChipBackgroundColorResource(R.color.md_theme_tertiary)
                        binding.chipAdminUserStatus.setTextColor(
                            ContextCompat.getColor(ctx, R.color.md_theme_onTertiary)
                        )
                    }
                }
                AdminUserAccountStatus.PREMIUM -> {
                    binding.cardAdminUser.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                    binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_outline)
                    binding.tvAdminUserExpiryBanner.visibility = View.GONE
                    binding.chipAdminUserStatus.text = if (subDaysLeft > 0) {
                        ctx.getString(R.string.badge_premium_monthly_days, subDaysLeft)
                    } else {
                        ctx.getString(R.string.admin_user_status_premium)
                    }
                    binding.chipAdminUserStatus.setChipBackgroundColorResource(R.color.premium_badge_bg)
                    binding.chipAdminUserStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.premium_badge_text)
                    )
                }
                AdminUserAccountStatus.EXPIRED -> {
                    binding.cardAdminUser.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                    binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_outline)
                    binding.tvAdminUserExpiryBanner.visibility = View.GONE
                    binding.chipAdminUserStatus.setText(
                        if (item.isPremiumUser) R.string.admin_user_status_sub_expired
                        else R.string.admin_user_status_expired
                    )
                    binding.chipAdminUserStatus.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
                    binding.chipAdminUserStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer)
                    )
                }
                AdminUserAccountStatus.TRIAL_ACTIVE -> {
                    binding.cardAdminUser.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                    binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_outline)
                    binding.tvAdminUserExpiryBanner.visibility = View.GONE
                    binding.chipAdminUserStatus.text = ctx.getString(R.string.admin_user_status_trial, daysLeft)
                    binding.chipAdminUserStatus.setChipBackgroundColorResource(R.color.md_theme_primaryContainer)
                    binding.chipAdminUserStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onPrimaryContainer)
                    )
                }
            }

            val deviceTag = if (item.deviceHash.length == 64) {
                DeviceIdentityProvider.formatShortDeviceTag(item.deviceHash)
            } else {
                "—"
            }
            val uidPreview = if (item.userId.length > 10) "${item.userId.take(10)}…" else item.userId
            binding.tvAdminUserMeta.text = buildString {
                if (item.phoneNumber.isNotBlank()) {
                    append(item.phoneNumber).append(" • ")
                }
                append("UID: ").append(uidPreview)
                append(" • Device: ").append(deviceTag)
                if (effectiveExpiryMs > 0L && item.isPremiumUser) {
                    val fmtDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                        .format(Date(effectiveExpiryMs))
                    append(" • ").append(if (subDaysLeft == 0) "Ended: " else "Ends: ").append(fmtDate)
                }
                if (item.bonusTrialDays > 0L) {
                    append(" • Bonus: +").append(item.bonusTrialDays).append("d")
                }
                if (item.hideSubscriptionUI) {
                    append(" • ").append(ctx.getString(R.string.admin_hide_sub_ui_badge))
                }
                if (item.subscriptionStatus == UserEntitlement.STATUS_PENDING_APPROVAL) {
                    append(" • Payment: Pending Approval")
                }
                if (item.subscriptionProductId.isNotBlank()) {
                    append(" • Plan: ").append(item.subscriptionProductId)
                }
            }

            if (item.adminNote.isNotBlank()) {
                binding.tvAdminUserNote.visibility = View.VISIBLE
                binding.tvAdminUserNote.text = "Note: ${item.adminNote}"
            } else {
                binding.tvAdminUserNote.visibility = View.GONE
            }

            val isUnexpiredPro = item.isPremiumUser && status != AdminUserAccountStatus.EXPIRED
            binding.btnUserTogglePro.setText(
                if (isUnexpiredPro) R.string.admin_revoke_pro else R.string.admin_grant_pro
            )
            binding.btnUserToggleBlock.setText(
                if (item.isBlocked) R.string.admin_unblock_user else R.string.admin_block_user
            )

            binding.btnUserTogglePro.setOnClickListener { onToggleProClick(item) }
            binding.btnUserRenewMonth.setOnClickListener { onRenewMonthClick(item) }
            binding.btnUserExtendTrial.setOnClickListener { onExtendTrialClick(item) }
            binding.btnUserToggleBlock.setOnClickListener { onToggleBlockClick(item) }
            binding.btnUserManage.setOnClickListener { onManageClick(item) }
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
