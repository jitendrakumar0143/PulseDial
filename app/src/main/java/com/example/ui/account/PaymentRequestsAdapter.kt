package com.example.ui.account

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.firebase.PaymentRepository
import com.example.data.firebase.PaymentRequest
import com.example.databinding.ItemPaymentRequestBinding
import java.util.Locale

class PaymentRequestsAdapter(
    private val onApproveClick: (PaymentRequest) -> Unit,
    private val onRejectClick: (PaymentRequest) -> Unit,
    private val onDeleteClick: (PaymentRequest) -> Unit = {}
) : ListAdapter<PaymentRequest, PaymentRequestsAdapter.PaymentRequestViewHolder>(DiffCallback) {

    var approvedUtrKeys: Set<String> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PaymentRequestViewHolder {
        val binding = ItemPaymentRequestBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PaymentRequestViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PaymentRequestViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PaymentRequestViewHolder(
        private val binding: ItemPaymentRequestBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PaymentRequest) {
            val ctx = binding.root.context
            val displayName = item.userName.ifBlank { ctx.getString(R.string.account_default_name) }
            binding.tvReqAvatar.text = extractInitials(displayName, item.userEmail)
            binding.tvReqUserName.text = displayName
            binding.tvReqUserEmail.text = item.userEmail.ifBlank { item.userId }
            binding.tvReqAmount.text = "₹${item.amountInr}"

            val timeText = item.createdAt?.toDate()?.time?.let { ts ->
                DateUtils.getRelativeTimeSpanString(
                    ts,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()
            } ?: "Just now"

            val utrKey = PaymentRepository.normalizeUtrKey(PaymentRepository.extractCanonicalUtr(item))
            val isUtrLocked = utrKey.isNotEmpty() && approvedUtrKeys.contains(utrKey)

            val utrDisplay = item.upiTxnId.ifBlank { "—" }
            val lockSuffix = if (item.status != PaymentRequest.STATUS_APPROVED && isUtrLocked) {
                " • ${ctx.getString(R.string.admin_utr_already_approved_once)}"
            } else {
                ""
            }
            binding.tvReqTxnDetails.text = "UTR: $utrDisplay  •  Ref: ${item.txnRef}$lockSuffix"
            binding.tvReqPaymentMeta.text =
                "${item.upiId} • ${item.shortDeviceTag} • ${item.upiResponseStatus} • $timeText"

            if (item.reviewNote.isNotBlank()) {
                binding.tvReqReviewNote.visibility = View.VISIBLE
                binding.tvReqReviewNote.text = "Note: ${item.reviewNote}"
            } else {
                binding.tvReqReviewNote.visibility = View.GONE
            }

            when (item.status) {
                PaymentRequest.STATUS_APPROVED -> {
                    tintAvatar(R.color.premium_badge_bg, R.color.premium_badge_text)
                    binding.chipReqStatus.setText(R.string.payment_status_badge_approved)
                    binding.chipReqStatus.setChipBackgroundColorResource(R.color.premium_badge_bg)
                    binding.chipReqStatus.setTextColor(ContextCompat.getColor(ctx, R.color.premium_badge_text))
                    binding.layoutAdminActions.visibility = View.VISIBLE
                    binding.btnApproveRequest.visibility = View.GONE
                    binding.btnRejectRequest.visibility = View.VISIBLE
                }
                PaymentRequest.STATUS_REJECTED -> {
                    tintAvatar(R.color.md_theme_errorContainer, R.color.md_theme_onErrorContainer)
                    binding.chipReqStatus.setText(R.string.payment_status_badge_rejected)
                    binding.chipReqStatus.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
                    binding.chipReqStatus.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer))
                    binding.layoutAdminActions.visibility = View.VISIBLE
                    binding.btnApproveRequest.visibility = if (isUtrLocked) View.GONE else View.VISIBLE
                    binding.btnRejectRequest.visibility = View.GONE
                }
                else -> {
                    tintAvatar(R.color.md_theme_secondaryContainer, R.color.md_theme_onSecondaryContainer)
                    binding.chipReqStatus.setText(R.string.payment_status_badge_pending)
                    binding.chipReqStatus.setChipBackgroundColorResource(R.color.md_theme_secondaryContainer)
                    binding.chipReqStatus.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onSecondaryContainer))
                    binding.layoutAdminActions.visibility = View.VISIBLE
                    binding.btnApproveRequest.visibility = if (isUtrLocked) View.GONE else View.VISIBLE
                    binding.btnRejectRequest.visibility = View.VISIBLE
                }
            }

            binding.btnApproveRequest.setOnClickListener { onApproveClick(item) }
            binding.btnRejectRequest.setOnClickListener { onRejectClick(item) }
            binding.btnDeleteRequest.setOnClickListener { onDeleteClick(item) }
        }

        private fun tintAvatar(bgRes: Int, textRes: Int) {
            val ctx = binding.root.context
            binding.tvReqAvatar.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, bgRes))
            binding.tvReqAvatar.setTextColor(ContextCompat.getColor(ctx, textRes))
        }
    }

    private fun extractInitials(name: String, email: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val chars = when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
            parts.size == 1 && parts[0].length >= 2 -> parts[0].take(2)
            parts.size == 1 -> parts[0].take(1)
            email.isNotBlank() -> email.take(2)
            else -> "D"
        }
        return chars.uppercase(Locale.getDefault())
    }

    private object DiffCallback : DiffUtil.ItemCallback<PaymentRequest>() {
        override fun areItemsTheSame(oldItem: PaymentRequest, newItem: PaymentRequest): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PaymentRequest, newItem: PaymentRequest): Boolean =
            oldItem == newItem
    }
}
