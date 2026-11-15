package com.example.ui.account

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.firebase.PaymentRequest
import com.example.databinding.ItemPaymentRequestBinding

class PaymentRequestsAdapter(
    private val onApproveClick: (PaymentRequest) -> Unit,
    private val onRejectClick: (PaymentRequest) -> Unit
) : ListAdapter<PaymentRequest, PaymentRequestsAdapter.PaymentRequestViewHolder>(DiffCallback) {

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
            binding.tvReqUserName.text = item.userName.ifBlank { ctx.getString(R.string.account_default_name) }
            binding.tvReqUserEmail.text = item.userEmail.ifBlank { item.userId }

            val timeText = item.createdAt?.toDate()?.time?.let { ts ->
                DateUtils.getRelativeTimeSpanString(
                    ts,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()
            } ?: "Just now"

            binding.tvReqPaymentMeta.text =
                "₹${item.amountInr} • ${item.upiId} • ${item.shortDeviceTag} • $timeText"

            val txnPart = if (item.upiTxnId.isNotBlank()) "UTR/Txn: ${item.upiTxnId}" else "UTR: —"
            binding.tvReqTxnDetails.text =
                "Ref: ${item.txnRef} • $txnPart • Verify: ${item.upiResponseStatus}"

            if (item.reviewNote.isNotBlank()) {
                binding.tvReqReviewNote.visibility = View.VISIBLE
                binding.tvReqReviewNote.text = "Review Note: ${item.reviewNote}"
            } else {
                binding.tvReqReviewNote.visibility = View.GONE
            }

            when (item.status) {
                PaymentRequest.STATUS_APPROVED -> {
                    binding.chipReqStatus.setText(R.string.payment_status_badge_approved)
                    binding.chipReqStatus.setChipBackgroundColorResource(R.color.premium_badge_bg)
                    binding.chipReqStatus.setTextColor(ContextCompat.getColor(ctx, R.color.premium_badge_text))
                    binding.layoutAdminActions.visibility = View.VISIBLE
                    binding.btnApproveRequest.visibility = View.GONE
                    binding.btnRejectRequest.visibility = View.VISIBLE
                }
                PaymentRequest.STATUS_REJECTED -> {
                    binding.chipReqStatus.setText(R.string.payment_status_badge_rejected)
                    binding.chipReqStatus.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
                    binding.chipReqStatus.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer))
                    binding.layoutAdminActions.visibility = View.VISIBLE
                    binding.btnApproveRequest.visibility = View.VISIBLE
                    binding.btnRejectRequest.visibility = View.GONE
                }
                else -> {
                    binding.chipReqStatus.setText(R.string.payment_status_badge_pending)
                    binding.chipReqStatus.setChipBackgroundColorResource(R.color.md_theme_secondaryContainer)
                    binding.chipReqStatus.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onSecondaryContainer))
                    binding.layoutAdminActions.visibility = View.VISIBLE
                    binding.btnApproveRequest.visibility = View.VISIBLE
                    binding.btnRejectRequest.visibility = View.VISIBLE
                }
            }

            binding.btnApproveRequest.setOnClickListener { onApproveClick(item) }
            binding.btnRejectRequest.setOnClickListener { onRejectClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<PaymentRequest>() {
        override fun areItemsTheSame(oldItem: PaymentRequest, newItem: PaymentRequest): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PaymentRequest, newItem: PaymentRequest): Boolean =
            oldItem == newItem
    }
}
