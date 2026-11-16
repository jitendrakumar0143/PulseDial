package com.example.ui.account

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.R
import com.example.billing.BillingManager
import com.example.billing.BillingUiState
import com.example.billing.SubscriptionManager
import com.example.billing.UpiPaymentHelper
import com.example.billing.UpiTxnStatus
import com.example.data.DialerPreferences
import com.example.data.firebase.DeviceIdentityProvider
import com.example.data.firebase.DeviceTrialRepository
import com.example.data.firebase.GoogleAuthHelper
import com.example.data.firebase.PaymentRepository
import com.example.data.firebase.PaymentRequest
import com.example.databinding.FragmentAccountBinding
import com.example.ui.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccountFragment : Fragment() {

    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: DialerPreferences
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var billingManager: BillingManager
    private lateinit var trialRepository: DeviceTrialRepository
    private lateinit var paymentRepository: PaymentRepository

    private var pendingUpiTxnRef: String = ""
    private var latestSubmittedTxnRef: String? = null

    private val upiPaymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val rawResponse = result.data?.getStringExtra("response")
        val fallbackStatus = result.data?.getStringExtra("Status")
            ?: result.data?.getStringExtra("status")
        val defaultRef = pendingUpiTxnRef.ifBlank { UpiPaymentHelper.generateTxnRef() }
        val parsed = UpiPaymentHelper.parseUpiResponse(rawResponse, fallbackStatus, defaultRef)

        when (parsed.status) {
            UpiTxnStatus.SUCCESS, UpiTxnStatus.SUBMITTED -> {
                submitVerifiedPaymentRequest(
                    txnRef = parsed.txnRef,
                    upiTxnId = parsed.txnId,
                    approvalRefNo = parsed.approvalRefNo,
                    verificationType = parsed.status.name
                )
            }
            UpiTxnStatus.FAILURE, UpiTxnStatus.CANCELLED -> {
                Toast.makeText(requireContext(), R.string.upi_payment_cancelled, Toast.LENGTH_LONG).show()
            }
            UpiTxnStatus.UNKNOWN -> {
                if (prefs.allowManualUtrConfig) {
                    showUpiFallbackOrConfirmDialog(
                        isNoUpiAppInstalled = false,
                        txnRef = parsed.txnRef
                    )
                } else {
                    Toast.makeText(requireContext(), R.string.upi_payment_cancelled, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            pendingUpiTxnRef = savedInstanceState.getString(KEY_PENDING_TXN_REF, "")
        }

        val ctx = requireContext()
        prefs = DialerPreferences(ctx)
        subscriptionManager = SubscriptionManager(ctx)
        billingManager = BillingManager(ctx, prefs)
        trialRepository = DeviceTrialRepository(ctx)
        paymentRepository = PaymentRepository(ctx)
        billingManager.startConnection()

        // Admin panel is hidden and opened by tapping the Account tab 12 times
        binding.cardAdminPanel.visibility = View.GONE

        bindProfile()
        syncCloudTrialStatus()

        binding.btnEditProfile.setOnClickListener {
            showEditProfileDialog()
        }

        binding.btnSignOut.setOnClickListener {
            (activity as? MainActivity)?.signOutUser()
        }

        binding.btnUpgradePremium.setOnClickListener {
            launchUpiCheckout()
        }

        binding.btnSubmitUtrDirect.setOnClickListener {
            showUpiFallbackOrConfirmDialog(
                isNoUpiAppInstalled = false,
                txnRef = UpiPaymentHelper.generateTxnRef()
            )
        }

        binding.btnWhatsappSupport.setOnClickListener {
            openWhatsAppSupport()
        }

        observeStateFlows()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_TXN_REF, pendingUpiTxnRef)
    }

    override fun onResume() {
        super.onResume()
        billingManager.queryActivePurchases()
        refreshTrialUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        billingManager.endConnection()
        _binding = null
    }

    private fun observeStateFlows() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    billingManager.billingState.collect { state ->
                        renderBillingState(state)
                    }
                }

                launch {
                    com.example.data.firebase.RealtimeSyncManager.syncState.collect { sync ->
                        if (sync.uid != null && _binding != null) {
                            latestSubmittedTxnRef = sync.myPaymentRequests.firstOrNull()?.txnRef
                            refreshTrialUi()
                            renderMyPaymentRequests(sync.myPaymentRequests)
                        }
                    }
                }

                launch {
                    com.example.billing.SubscriptionEnforcementService.state.collect { enfState ->
                        if (enfState.lastEvaluatedAtMs > 0L && _binding != null) {
                            refreshTrialUi()
                        }
                    }
                }
            }
        }
    }

    private fun launchUpiCheckout() {
        val txnRef = UpiPaymentHelper.generateTxnRef()
        pendingUpiTxnRef = txnRef
        val upiVpa = prefs.configuredUpiVpa
        val priceInr = prefs.configuredPriceInr
        val chooserIntent = UpiPaymentHelper.buildUpiChooserIntent(
            txnRef = txnRef,
            chooserTitle = "Pay ₹$priceInr with UPI App",
            upiVpa = upiVpa,
            amountInr = priceInr
        )
        try {
            upiPaymentLauncher.launch(chooserIntent)
        } catch (_: ActivityNotFoundException) {
            if (prefs.allowManualUtrConfig) {
                showUpiFallbackOrConfirmDialog(isNoUpiAppInstalled = true, txnRef = txnRef)
            } else {
                copyToClipboard(upiVpa)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.upi_id_copied, upiVpa),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showUpiFallbackOrConfirmDialog(isNoUpiAppInstalled: Boolean, txnRef: String) {
        val ctx = requireContext()
        val upiVpa = prefs.configuredUpiVpa
        val priceInr = prefs.configuredPriceInr
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        val utrInput = EditText(ctx).apply {
            hint = getString(R.string.upi_utr_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        container.addView(utrInput)

        val title = if (isNoUpiAppInstalled) {
            "Pay ₹$priceInr to UPI ID"
        } else {
            "Confirm UPI Payment (₹$priceInr)"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage("Send ₹$priceInr to $upiVpa and enter your 12-digit UPI Ref / UTR number below for Admin approval.")
            .setView(container)
            .setNeutralButton(R.string.upi_copy_id) { _, _ ->
                copyToClipboard(upiVpa)
                Toast.makeText(
                    ctx,
                    getString(R.string.upi_id_copied, upiVpa),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setPositiveButton(R.string.upi_submit_for_approval) { _, _ ->
                val utr = utrInput.text.toString().trim()
                if (utr.isEmpty()) {
                    Toast.makeText(ctx, R.string.upi_utr_required, Toast.LENGTH_SHORT).show()
                } else {
                    submitVerifiedPaymentRequest(
                        txnRef = txnRef,
                        upiTxnId = utr,
                        approvalRefNo = utr,
                        verificationType = "MANUAL_UTR"
                    )
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun submitVerifiedPaymentRequest(
        txnRef: String,
        upiTxnId: String,
        approvalRefNo: String,
        verificationType: String
    ) {
        val ctx = requireContext()
        if (!GoogleAuthHelper.isUserAuthenticated(ctx)) {
            Toast.makeText(ctx, R.string.auth_gate_title, Toast.LENGTH_SHORT).show()
            return
        }
        val deviceHash = prefs.deviceIdHash.ifBlank { DeviceIdentityProvider.getDeviceHash(ctx) }
        val shortTag = prefs.shortDeviceTag.ifBlank {
            DeviceIdentityProvider.formatShortDeviceTag(deviceHash)
        }
        val userName = GoogleAuthHelper.resolveActiveDisplayName(ctx).ifBlank { prefs.userName }
        val userEmail = GoogleAuthHelper.resolveActiveEmail(ctx).ifBlank { prefs.userEmail }

        Toast.makeText(ctx, R.string.upi_verifying_submission, Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = paymentRepository.submitUpiPaymentRequest(
                deviceHash = deviceHash,
                shortDeviceTag = shortTag,
                userName = userName,
                userEmail = userEmail,
                txnRef = txnRef,
                upiTxnId = upiTxnId,
                approvalRefNo = approvalRefNo,
                upiResponseStatus = verificationType,
                upiId = prefs.configuredUpiVpa,
                amountInr = prefs.configuredPriceInr
            )
            if (!isAdded) return@launch
            result.onSuccess {
                latestSubmittedTxnRef = txnRef
                Toast.makeText(requireContext(), R.string.upi_submitted_success, Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun renderMyPaymentRequests(requests: List<PaymentRequest>) {
        if (_binding == null) return
        val isPremiumActive = subscriptionManager.isPremiumActive() && !prefs.adminBlockedUser
        val isExpiringSoon = subscriptionManager.isSubscriptionExpiringSoon()
        if (prefs.hideSubscriptionUi || (isPremiumActive && !isExpiringSoon)) {
            binding.cardPaymentStatus.visibility = View.GONE
            return
        }

        val latest = requests.firstOrNull()
        if (latest == null || latest.status == PaymentRequest.STATUS_APPROVED) {
            binding.cardPaymentStatus.visibility = View.GONE
            latestSubmittedTxnRef = latest?.txnRef
            return
        }

        latestSubmittedTxnRef = latest.txnRef
        binding.cardPaymentStatus.visibility = View.VISIBLE
        val activeUpi = prefs.configuredUpiVpa
        val activePrice = prefs.configuredPriceInr
        if (latest.status == PaymentRequest.STATUS_REJECTED) {
            binding.tvPaymentStatusTitle.setText(R.string.payment_status_rejected_title)
            binding.tvPaymentStatusDesc.text =
                "Reason: ${latest.reviewNote.ifBlank { "Payment could not be confirmed" }}. Please pay ₹$activePrice to $activeUpi or message WhatsApp support."
            binding.cardPaymentStatus.setCardBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.md_theme_errorContainer)
            )
            binding.tvPaymentStatusTitle.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.md_theme_onErrorContainer)
            )
            binding.tvPaymentStatusDesc.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.md_theme_onErrorContainer)
            )
        } else {
            val refDisplay = latest.upiTxnId.ifBlank { latest.txnRef }
            binding.tvPaymentStatusTitle.setText(R.string.payment_status_pending_title)
            binding.tvPaymentStatusDesc.text =
                "Your ₹$activePrice UPI payment to $activeUpi ($refDisplay) is waiting for Admin check. Full access unlocks automatically once approved."
            binding.cardPaymentStatus.setCardBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.md_theme_secondaryContainer)
            )
            binding.tvPaymentStatusTitle.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.md_theme_onSecondaryContainer)
            )
            binding.tvPaymentStatusDesc.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.md_theme_onSecondaryContainer)
            )
        }
    }

    private fun openWhatsAppSupport() {
        val ctx = requireContext()
        val name = GoogleAuthHelper.resolveActiveDisplayName(ctx).ifBlank { prefs.userName }
        val email = GoogleAuthHelper.resolveActiveEmail(ctx).ifBlank { prefs.userEmail }
        val deviceTag = prefs.shortDeviceTag.ifBlank {
            DeviceIdentityProvider.formatShortDeviceTag(DeviceIdentityProvider.getDeviceHash(ctx))
        }
        val supportNum = prefs.configuredWhatsappNumber
        val intent = UpiPaymentHelper.buildWhatsAppSupportIntent(
            userName = name,
            userEmail = email,
            deviceTag = deviceTag,
            pendingTxnRef = latestSubmittedTxnRef,
            supportNumberRaw = supportNum
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val e164 = UpiPaymentHelper.formatWhatsappE164(supportNum)
            copyToClipboard("+$e164")
            Toast.makeText(
                ctx,
                "WhatsApp is not installed. Support number +$e164 copied to clipboard.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("PulseDial", text))
    }

    fun refreshTrialUi() {
        if (_binding == null) return
        bindProfile()
        renderBillingState(
            billingManager.billingState.value.copy(isPremiumUser = subscriptionManager.isPremiumActive())
        )
    }

    private fun syncCloudTrialStatus() {
        if (!GoogleAuthHelper.isUserAuthenticated(requireContext())) return
        val deviceHash = DeviceIdentityProvider.getDeviceHash(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            trialRepository.syncDeviceAndUserTrial(deviceHash)
            refreshTrialUi()
        }
    }

    private fun bindProfile() {
        val ctx = requireContext()
        val displayName = GoogleAuthHelper.resolveActiveDisplayName(ctx).ifBlank { prefs.userName }
        val email = GoogleAuthHelper.resolveActiveEmail(ctx).ifBlank { prefs.userEmail }
        binding.tvUserName.text = displayName
        if (email.isNotBlank()) {
            binding.tvUserEmail.visibility = View.VISIBLE
            binding.tvUserEmail.text = email
        } else {
            binding.tvUserEmail.visibility = View.GONE
        }
        binding.tvUserNumber.text = prefs.userPhoneNumber

        val shortDeviceTag = prefs.shortDeviceTag.ifBlank {
            DeviceIdentityProvider.formatShortDeviceTag(
                DeviceIdentityProvider.getDeviceHash(requireContext())
            )
        }
        binding.chipDeviceId.text = getString(R.string.account_device_verified, shortDeviceTag)

        val supportDisplay = prefs.configuredWhatsappNumber
        binding.btnWhatsappSupport.text = "Chat on WhatsApp • $supportDisplay"
    }

    private fun renderBillingState(state: BillingUiState) {
        val ctx = requireContext()
        val isAdmin = UpiPaymentHelper.isAdminEmail(GoogleAuthHelper.resolveActiveEmail(ctx))
        val isBlockedByAdmin = prefs.adminBlockedUser
        val isMaintenance = prefs.maintenanceMode && !isAdmin
        val isPremium = !isBlockedByAdmin && subscriptionManager.isPremiumActive()
        val hasMonthlyExpired = !isBlockedByAdmin && !isPremium && subscriptionManager.hasMonthlySubscriptionExpired()
        val totalTrialDays = prefs.getEffectiveTrialDays()
        val priceInr = prefs.configuredPriceInr
        val upiVpa = prefs.configuredUpiVpa
        val shortDeviceTag = prefs.shortDeviceTag.ifBlank {
            DeviceIdentityProvider.formatShortDeviceTag(DeviceIdentityProvider.getDeviceHash(ctx))
        }

        val hideSubUi = prefs.hideSubscriptionUi
        if (hideSubUi) {
            binding.cardPremiumSubscription.visibility = View.GONE
            binding.cardPaymentStatus.visibility = View.GONE
        } else {
            binding.cardPremiumSubscription.visibility = View.VISIBLE
        }

        if (isBlockedByAdmin) {
            binding.cardTrialLockedBanner.visibility = View.VISIBLE
            binding.tvPaywallLockedTitle.setText(R.string.admin_blocked_banner_title)
            binding.tvPaywallLockedMessage.text = getString(
                R.string.admin_blocked_banner_desc,
                prefs.configuredWhatsappNumber
            )
            binding.chipPlanBadge.visibility = View.VISIBLE
            binding.chipPlanBadge.setText(R.string.admin_user_status_blocked)
            binding.chipPlanBadge.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
            binding.chipPlanBadge.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer))
            binding.tvTrialCountdownDetail.visibility = View.VISIBLE
            binding.tvTrialCountdownDetail.setText(R.string.admin_blocked_banner_title)
            binding.tvTrialCountdownDetail.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_error))
            binding.btnUpgradePremium.visibility = View.GONE
            binding.btnSubmitUtrDirect.visibility = View.GONE
            binding.tvUpiPayeeNote.visibility = View.GONE
        } else if (isMaintenance) {
            binding.cardTrialLockedBanner.visibility = View.VISIBLE
            binding.tvPaywallLockedTitle.setText(R.string.admin_maintenance_banner_title)
            binding.tvPaywallLockedMessage.text = prefs.maintenanceMessage.ifBlank {
                "PulseDial is temporarily under maintenance. Please check back shortly."
            }
            binding.chipPlanBadge.visibility = View.VISIBLE
            binding.tvTrialCountdownDetail.visibility = View.VISIBLE
            binding.tvTrialCountdownDetail.text = prefs.announcementText.ifBlank {
                binding.tvTrialCountdownDetail.text
            }
            binding.btnUpgradePremium.visibility = View.GONE
            binding.btnSubmitUtrDirect.visibility = View.GONE
        } else if (hideSubUi) {
            binding.cardTrialLockedBanner.visibility = View.GONE
            binding.chipPlanBadge.visibility = View.GONE
            binding.tvTrialCountdownDetail.visibility = View.GONE
        } else if (isPremium) {
            binding.cardTrialLockedBanner.visibility = View.GONE
            binding.chipPlanBadge.visibility = View.VISIBLE
            binding.tvTrialCountdownDetail.visibility = View.VISIBLE

            val expMs = subscriptionManager.subscriptionExpiresAtMs
            val hasMonthlyExpiry = expMs > 0L
            val isExpiringSoon = subscriptionManager.isSubscriptionExpiringSoon()

            if (hasMonthlyExpiry) {
                val subDaysLeft = subscriptionManager.getSubscriptionDaysRemaining()
                val subCountdown = subscriptionManager.formatSubscriptionCountdown()
                val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(expMs))

                if (isExpiringSoon) {
                    binding.chipPlanBadge.text = getString(R.string.badge_premium_expiring_soon, subDaysLeft)
                    binding.chipPlanBadge.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
                    binding.chipPlanBadge.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer))

                    val warnText = "Expiring Soon: $subCountdown (ends $dateFmt) • Renew ₹$priceInr/month"
                    binding.tvTrialCountdownDetail.text = if (prefs.announcementText.isNotBlank()) {
                        "$warnText\n${prefs.announcementText}"
                    } else {
                        warnText
                    }
                    binding.tvTrialCountdownDetail.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_error))

                    binding.tvPremiumTitle.text = "Renew Monthly Plan - ₹$priceInr/month"
                    binding.tvPremiumSubtitle.text = getString(
                        R.string.premium_card_expiring_subtitle,
                        subCountdown,
                        dateFmt,
                        priceInr
                    )
                    binding.tvUpiPayeeNote.visibility = View.VISIBLE
                    binding.tvUpiPayeeNote.text = "Pay ₹$priceInr/month via any UPI app to $upiVpa to extend +30 days."
                    binding.btnUpgradePremium.visibility = View.VISIBLE
                    binding.btnUpgradePremium.text = getString(R.string.btn_renew_premium, priceInr)
                    binding.btnSubmitUtrDirect.visibility = if (prefs.allowManualUtrConfig) View.VISIBLE else View.GONE
                } else {
                    binding.chipPlanBadge.text = getString(R.string.badge_premium_monthly_days, subDaysLeft)
                    binding.chipPlanBadge.setChipBackgroundColorResource(R.color.premium_badge_bg)
                    binding.chipPlanBadge.setTextColor(ContextCompat.getColor(ctx, R.color.premium_badge_text))

                    val activeText = "Monthly Pro Active • $subCountdown (renews $dateFmt)"
                    binding.tvTrialCountdownDetail.text = if (prefs.announcementText.isNotBlank()) {
                        "$activeText\n${prefs.announcementText}"
                    } else {
                        activeText
                    }
                    binding.tvTrialCountdownDetail.setTextColor(ContextCompat.getColor(ctx, R.color.call_accept_green))

                    binding.tvPremiumTitle.text = getString(R.string.premium_card_active_title)
                    binding.tvPremiumSubtitle.text = getString(
                        R.string.premium_card_monthly_subtitle,
                        subCountdown,
                        dateFmt
                    )
                    binding.tvUpiPayeeNote.visibility = View.GONE
                    binding.btnUpgradePremium.visibility = View.GONE
                    binding.btnSubmitUtrDirect.visibility = View.GONE
                    binding.cardPaymentStatus.visibility = View.GONE
                }
            } else {
                binding.chipPlanBadge.text = getString(R.string.badge_premium_member)
                binding.chipPlanBadge.setChipBackgroundColorResource(R.color.premium_badge_bg)
                binding.chipPlanBadge.setTextColor(ContextCompat.getColor(ctx, R.color.premium_badge_text))

                binding.tvTrialCountdownDetail.text = prefs.announcementText.ifBlank {
                    getString(R.string.trial_countdown_premium)
                }
                binding.tvTrialCountdownDetail.setTextColor(ContextCompat.getColor(ctx, R.color.call_accept_green))

                binding.tvPremiumTitle.text = getString(R.string.premium_card_active_title)
                binding.tvPremiumSubtitle.text = getString(R.string.premium_card_active_subtitle)
                binding.tvUpiPayeeNote.visibility = View.GONE
                binding.btnUpgradePremium.visibility = View.GONE
                binding.btnSubmitUtrDirect.visibility = View.GONE
                binding.cardPaymentStatus.visibility = View.GONE
            }
        } else if (hasMonthlyExpired) {
            binding.cardTrialLockedBanner.visibility = View.VISIBLE
            binding.chipPlanBadge.visibility = View.VISIBLE
            binding.tvTrialCountdownDetail.visibility = View.VISIBLE
            binding.tvPaywallLockedTitle.setText(R.string.paywall_locked_monthly_title)
            binding.tvPaywallLockedMessage.text = getString(
                R.string.paywall_locked_monthly_message,
                shortDeviceTag,
                priceInr,
                upiVpa
            )

            binding.chipPlanBadge.setText(R.string.admin_user_status_sub_expired)
            binding.chipPlanBadge.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
            binding.chipPlanBadge.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer))

            binding.tvTrialCountdownDetail.text =
                "Monthly Subscription Expired • Renew ₹$priceInr/month to continue"
            binding.tvTrialCountdownDetail.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_error))

            binding.tvPremiumTitle.text = "Renew Monthly Plan - ₹$priceInr/month"
            binding.tvPremiumSubtitle.text = getString(
                R.string.paywall_locked_monthly_message,
                shortDeviceTag,
                priceInr,
                upiVpa
            )
            binding.tvUpiPayeeNote.visibility = View.VISIBLE
            binding.tvUpiPayeeNote.text = "Pay ₹$priceInr/month via any UPI app to $upiVpa to renew for 30 days."
            binding.btnUpgradePremium.visibility = View.VISIBLE
            binding.btnUpgradePremium.text = getString(R.string.btn_renew_premium, priceInr)
            binding.btnSubmitUtrDirect.visibility = if (prefs.allowManualUtrConfig) View.VISIBLE else View.GONE
        } else if (!subscriptionManager.isTrialExpired()) {
            binding.cardTrialLockedBanner.visibility = View.GONE
            binding.chipPlanBadge.visibility = View.VISIBLE
            binding.tvTrialCountdownDetail.visibility = View.VISIBLE
            val daysLeft = subscriptionManager.getTrialDaysRemaining()
            val countdownText = subscriptionManager.formatRemainingCountdown()
            binding.chipPlanBadge.text = if (daysLeft == 1) {
                getString(R.string.badge_trial_one_day_left)
            } else {
                "$totalTrialDays-Day Free Trial - $daysLeft Days Left"
            }
            binding.chipPlanBadge.setChipBackgroundColorResource(R.color.md_theme_primaryContainer)
            binding.chipPlanBadge.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onPrimaryContainer))

            val baseCountdown = "$totalTrialDays-Day Trial Counter: $daysLeft of $totalTrialDays days left ($countdownText)"
            binding.tvTrialCountdownDetail.text = if (prefs.announcementText.isNotBlank()) {
                "$baseCountdown\n${prefs.announcementText}"
            } else {
                baseCountdown
            }
            binding.tvTrialCountdownDetail.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_primary))

            binding.tvPremiumTitle.text = "Upgrade to Premium - ₹$priceInr/month"
            binding.tvPremiumSubtitle.text = "New users get $totalTrialDays days free, then ₹$priceInr/month. One trial per device."
            binding.tvUpiPayeeNote.visibility = View.VISIBLE
            binding.tvUpiPayeeNote.text = "Pay ₹$priceInr/month via any UPI app to $upiVpa after your free trial."
            binding.btnUpgradePremium.visibility = View.VISIBLE
            binding.btnUpgradePremium.text = "Subscribe via UPI • ₹$priceInr/month"
            binding.btnSubmitUtrDirect.visibility = if (prefs.allowManualUtrConfig) View.VISIBLE else View.GONE
        } else {
            binding.cardTrialLockedBanner.visibility = View.VISIBLE
            binding.chipPlanBadge.visibility = View.VISIBLE
            binding.tvTrialCountdownDetail.visibility = View.VISIBLE
            binding.tvPaywallLockedTitle.setText(
                if (prefs.trialBlockedByDevice) R.string.paywall_locked_device_used_title
                else R.string.paywall_locked_title
            )
            binding.tvPaywallLockedMessage.text =
                "Your $totalTrialDays days on this device ($shortDeviceTag) have finished. Subscribe for ₹$priceInr/month via UPI ($upiVpa) to unlock and continue using PulseDial."

            binding.chipPlanBadge.text = getString(
                if (prefs.trialBlockedByDevice) R.string.badge_device_trial_used else R.string.badge_trial_expired
            )
            binding.chipPlanBadge.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
            binding.chipPlanBadge.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onErrorContainer))

            binding.tvTrialCountdownDetail.text =
                "$totalTrialDays-Day Trial Counter: $totalTrialDays of $totalTrialDays days used • Subscription required"
            binding.tvTrialCountdownDetail.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_error))

            binding.tvPremiumTitle.text = "Upgrade to Premium - ₹$priceInr/month"
            binding.tvPremiumSubtitle.text = getString(R.string.premium_card_expired_subtitle)
            binding.tvUpiPayeeNote.visibility = View.VISIBLE
            binding.tvUpiPayeeNote.text = "Pay ₹$priceInr/month via any UPI app to $upiVpa after your free trial."
            binding.btnUpgradePremium.visibility = View.VISIBLE
            binding.btnUpgradePremium.text = "Subscribe via UPI • ₹$priceInr/month"
            binding.btnSubmitUtrDirect.visibility = if (prefs.allowManualUtrConfig) View.VISIBLE else View.GONE
        }

        val statusMsg = state.statusMessage
        if (!statusMsg.isNullOrBlank()) {
            Toast.makeText(ctx, statusMsg, Toast.LENGTH_SHORT).show()
            billingManager.clearStatusMessage()
        }
    }

    private fun showEditProfileDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val nameInput = EditText(ctx).apply {
            hint = "Full Name"
            setText(prefs.userName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val numberInput = EditText(ctx).apply {
            hint = "Phone Number"
            setText(prefs.userPhoneNumber)
            inputType = InputType.TYPE_CLASS_PHONE
        }
        container.addView(nameInput)
        container.addView(numberInput)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.account_edit_profile)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val cleanName = nameInput.text.toString().trim()
                val cleanPhone = numberInput.text.toString().trim()
                prefs.userName = cleanName
                prefs.userPhoneNumber = cleanPhone
                bindProfile()
                viewLifecycleOwner.lifecycleScope.launch {
                    trialRepository.updateUserProfilePhone(cleanName, cleanPhone)
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    companion object {
        private const val KEY_PENDING_TXN_REF = "pending_upi_txn_ref"
    }
}
