package com.example.ui.admin

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.billing.UpiPaymentHelper
import com.example.data.DialerPreferences
import com.example.data.firebase.AdminRepository
import com.example.data.firebase.AdminUserAccountStatus
import com.example.data.firebase.DeviceIdentityProvider
import com.example.data.firebase.PaymentRepository
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
    private val onManageClick: (UserEntitlement) -> Unit,
    private val onApproveUtrClick: (UserEntitlement) -> Unit = {},
    private val onRejectUtrClick: (UserEntitlement) -> Unit = {},
    private val onReduceDaysClick: (UserEntitlement) -> Unit = {},
    private val onDeleteClick: (UserEntitlement) -> Unit = {}
) : ListAdapter<UserEntitlement, AdminUsersAdapter.UserViewHolder>(DiffCallback) {

    var trialDaysConfig: Int = DialerPreferences.TRIAL_DAYS
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var approvedUtrKeys: Set<String> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private val expandedKeys = mutableSetOf<String>()

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
            val rowKey = item.userId.ifBlank { item.id }
            val isExpanded = expandedKeys.contains(rowKey)

            val baseName = item.displayName.ifBlank { ctx.getString(R.string.account_default_name) }
            val isSuperAdmin = UpiPaymentHelper.isSuperAdminEmail(item.email)
            val isDelegatedAdmin = !isSuperAdmin &&
                UpiPaymentHelper.isDelegatedAdminAuthorized(item.isAdmin, item.adminGrantedBy)

            binding.tvAdminUserAvatar.text = extractInitials(baseName, item.email)
            binding.tvAdminUserName.text = when {
                isSuperAdmin -> "$baseName [${ctx.getString(R.string.admin_user_badge_super_admin)}]"
                isDelegatedAdmin -> "$baseName [${ctx.getString(R.string.admin_user_badge_admin)}]"
                else -> baseName
            }
            binding.tvAdminUserEmail.text = item.email.ifBlank { item.userId }

            val status = AdminRepository.resolveUserAccountStatus(item, trialDaysConfig)
            val daysLeft = AdminRepository.getRemainingDaysForUser(item, trialDaysConfig)
            val subDaysLeft = AdminRepository.getSubscriptionRemainingDays(item)
            val effectiveExpiryMs = AdminRepository.resolveEffectiveExpiryMs(item)
            val isPendingUtr = item.subscriptionStatus == UserEntitlement.STATUS_PENDING_APPROVAL

            val strokeDp = if (isPendingUtr || status == AdminUserAccountStatus.EXPIRING_SOON) 1.5f else 1f
            binding.cardAdminUser.strokeWidth = (strokeDp * ctx.resources.displayMetrics.density).toInt()

            when {
                isPendingUtr -> {
                    binding.cardAdminUser.setCardBackgroundColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_surface)
                    )
                    binding.cardAdminUser.strokeColor =
                        ContextCompat.getColor(ctx, R.color.md_theme_primary)
                    tintAvatar(R.color.md_theme_primaryContainer, R.color.md_theme_onPrimaryContainer)
                    binding.tvAdminUserExpiryBanner.visibility = View.VISIBLE
                    binding.tvAdminUserExpiryBanner.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_primary)
                    )
                    binding.tvAdminUserExpiryBanner.text = ctx.getString(
                        R.string.admin_pending_utr_banner,
                        item.phoneNumber.ifBlank { "—" },
                        item.paymentRefId.ifBlank { item.purchaseToken.ifBlank { "Submitted" } }
                    )
                    binding.chipAdminUserStatus.setText(R.string.admin_user_status_pending_utr)
                    binding.chipAdminUserStatus.setChipBackgroundColorResource(R.color.md_theme_primary)
                    binding.chipAdminUserStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onPrimary)
                    )
                }
                status == AdminUserAccountStatus.BLOCKED -> {
                    binding.cardAdminUser.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                    binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_outline)
                    tintAvatar(R.color.md_theme_errorContainer, R.color.md_theme_onErrorContainer)
                    binding.tvAdminUserExpiryBanner.visibility = View.GONE
                    binding.chipAdminUserStatus.setText(R.string.admin_user_status_blocked)
                    binding.chipAdminUserStatus.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
                    binding.chipAdminUserStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer)
                    )
                }
                status == AdminUserAccountStatus.EXPIRING_SOON -> {
                    binding.cardAdminUser.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                    binding.tvAdminUserExpiryBanner.visibility = View.VISIBLE
                    if (item.isPremiumUser) {
                        val safeDays = subDaysLeft.coerceAtLeast(1)
                        val fmtDate = if (effectiveExpiryMs > 0L) {
                            SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(effectiveExpiryMs))
                        } else {
                            "—"
                        }
                        binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_error)
                        tintAvatar(R.color.md_theme_errorContainer, R.color.md_theme_onErrorContainer)
                        binding.tvAdminUserExpiryBanner.setTextColor(
                            ContextCompat.getColor(ctx, R.color.md_theme_error)
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
                        binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_tertiary)
                        tintAvatar(R.color.md_theme_tertiaryContainer, R.color.md_theme_onTertiaryContainer)
                        binding.tvAdminUserExpiryBanner.setTextColor(
                            ContextCompat.getColor(ctx, R.color.md_theme_tertiary)
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
                status == AdminUserAccountStatus.PREMIUM -> {
                    binding.cardAdminUser.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                    binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_outline)
                    tintAvatar(R.color.premium_badge_bg, R.color.premium_badge_text)
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
                status == AdminUserAccountStatus.EXPIRED -> {
                    binding.cardAdminUser.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                    binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_outline)
                    tintAvatar(R.color.md_theme_surfaceContainer, R.color.md_theme_onSurfaceVariant)
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
                else -> {
                    binding.cardAdminUser.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                    binding.cardAdminUser.strokeColor = ContextCompat.getColor(ctx, R.color.md_theme_outline)
                    tintAvatar(R.color.md_theme_primaryContainer, R.color.md_theme_onPrimaryContainer)
                    binding.tvAdminUserExpiryBanner.visibility = View.GONE
                    binding.chipAdminUserStatus.text = ctx.getString(R.string.admin_user_status_trial, daysLeft)
                    binding.chipAdminUserStatus.setChipBackgroundColorResource(R.color.md_theme_primaryContainer)
                    binding.chipAdminUserStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.md_theme_onPrimaryContainer)
                    )
                }
            }

            val joinMs = item.trialStartedAt?.toDate()?.time
                ?: item.createdAt?.toDate()?.time
                ?: item.updatedAt?.toDate()?.time
                ?: 0L
            val shortFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
            val fullFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

            // Clean 1-line summary visible even when collapsed
            binding.tvAdminUserQuickInfo.text = buildString {
                if (item.phoneNumber.isNotBlank()) {
                    append(item.phoneNumber)
                }
                if (joinMs > 0L) {
                    if (isNotEmpty()) append(" • ")
                    append("Joined ").append(shortFmt.format(Date(joinMs)))
                }
                if (effectiveExpiryMs > 0L && item.isPremiumUser) {
                    if (isNotEmpty()) append(" • ")
                    append(if (subDaysLeft == 0) "Ended " else "Ends ").append(shortFmt.format(Date(effectiveExpiryMs)))
                }
                if (isEmpty()) {
                    append("UID: ").append(item.userId.take(10))
                }
            }

            // Full structured metadata inside the expandable drawer
            val deviceTag = if (item.deviceHash.length == 64) {
                DeviceIdentityProvider.formatShortDeviceTag(item.deviceHash)
            } else {
                "—"
            }
            val uidPreview = if (item.userId.length > 12) "${item.userId.take(12)}…" else item.userId
            binding.tvAdminUserMeta.text = buildString {
                append("UID: ").append(uidPreview).append(" • Device: ").append(deviceTag)
                if (joinMs > 0L) {
                    append("\nJoined: ").append(fullFmt.format(Date(joinMs)))
                }
                if (item.phoneNumber.isNotBlank()) {
                    append(" • Phone: ").append(item.phoneNumber)
                }
                if (item.paymentRefId.isNotBlank()) {
                    append("\nUTR: ").append(item.paymentRefId)
                }
                if (effectiveExpiryMs > 0L && item.isPremiumUser) {
                    append(if (item.paymentRefId.isNotBlank()) " • " else "\n")
                    append(if (subDaysLeft == 0) "Ended: " else "Ends: ").append(fullFmt.format(Date(effectiveExpiryMs)))
                }
                if (item.bonusTrialDays > 0L) {
                    append(" • Bonus: +").append(item.bonusTrialDays).append("d")
                }
                if (item.hideSubscriptionUI) {
                    append(" • ").append(ctx.getString(R.string.admin_hide_sub_ui_badge))
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

            // Expand / collapse drawer state
            binding.layoutAdminUserControls.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.ivAdminUserExpand.rotation = if (isExpanded) 180f else 0f

            val canonicalUtr = item.paymentRefId.trim().ifBlank { item.purchaseToken.trim() }
            val utrKey = PaymentRepository.normalizeUtrKey(canonicalUtr)
            val isUtrLocked = utrKey.isNotEmpty() && approvedUtrKeys.contains(utrKey)
            val showUtrContainer = isPendingUtr || item.paymentRefId.isNotBlank()
            binding.layoutAdminUserUtrActions.visibility = if (showUtrContainer) View.VISIBLE else View.GONE
            binding.btnUserApproveUtr.visibility = if (showUtrContainer && !isUtrLocked) View.VISIBLE else View.GONE
            binding.btnUserApproveUtr.setOnClickListener { onApproveUtrClick(item) }
            binding.btnUserRejectUtr.setOnClickListener { onRejectUtrClick(item) }

            val isUnexpiredPro = item.isPremiumUser && status != AdminUserAccountStatus.EXPIRED
            binding.switchUserPremium.setOnCheckedChangeListener(null)
            binding.switchUserPremium.isChecked = isUnexpiredPro
            binding.switchUserPremium.setOnCheckedChangeListener { _, _ ->
                onToggleProClick(item)
            }

            binding.switchUserBlock.setOnCheckedChangeListener(null)
            binding.switchUserBlock.isChecked = item.isBlocked
            binding.switchUserBlock.setOnCheckedChangeListener { _, _ ->
                onToggleBlockClick(item)
            }

            binding.btnUserTogglePro.setText(
                if (isUnexpiredPro) R.string.admin_revoke_pro else R.string.admin_grant_pro
            )
            binding.btnUserToggleBlock.setText(
                if (item.isBlocked) R.string.admin_unblock_user else R.string.admin_block_user
            )

            binding.btnUserTogglePro.setOnClickListener { onToggleProClick(item) }
            binding.btnUserRenewMonth.setOnClickListener { onRenewMonthClick(item) }
            binding.btnUserExtendTrial.setOnClickListener { onExtendTrialClick(item) }
            binding.btnUserReduceDays.setOnClickListener { onReduceDaysClick(item) }
            binding.btnUserToggleBlock.setOnClickListener { onToggleBlockClick(item) }
            binding.btnUserManage.setOnClickListener { onManageClick(item) }
            binding.btnUserDelete.visibility = if (isSuperAdmin) View.GONE else View.VISIBLE
            binding.btnUserDelete.setOnClickListener { onDeleteClick(item) }

            binding.cardAdminUser.setOnClickListener {
                val nowExpanded = if (expandedKeys.contains(rowKey)) {
                    expandedKeys.remove(rowKey)
                    false
                } else {
                    expandedKeys.clear()
                    expandedKeys.add(rowKey)
                    true
                }
                binding.layoutAdminUserControls.visibility = if (nowExpanded) View.VISIBLE else View.GONE
                binding.ivAdminUserExpand.animate().rotation(if (nowExpanded) 180f else 0f).setDuration(180L).start()
                notifyDataSetChanged()
            }
        }

        private fun tintAvatar(bgRes: Int, textRes: Int) {
            val ctx = binding.root.context
            binding.tvAdminUserAvatar.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, bgRes))
            binding.tvAdminUserAvatar.setTextColor(ContextCompat.getColor(ctx, textRes))
        }
    }

    private fun extractInitials(name: String, email: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val chars = when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
            parts.size == 1 && parts[0].length >= 2 -> parts[0].take(2)
            parts.size == 1 -> parts[0].take(1)
            email.isNotBlank() -> email.take(2)
            else -> "U"
        }
        return chars.uppercase(Locale.getDefault())
    }

    private object DiffCallback : DiffUtil.ItemCallback<UserEntitlement>() {
        override fun areItemsTheSame(oldItem: UserEntitlement, newItem: UserEntitlement): Boolean =
            oldItem.userId == newItem.userId && oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: UserEntitlement, newItem: UserEntitlement): Boolean =
            oldItem == newItem
    }
}
