package com.example.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class DeviceTrial(
    @DocumentId val id: String = "",
    val deviceHash: String = "",
    val ownerUid: String = "",
    val lastUserId: String = "",
    val accountSwitchCount: Long = 0L,
    @ServerTimestamp val trialStartedAt: Timestamp? = null,
    @ServerTimestamp val lastVerifiedAt: Timestamp? = null,
    @get:PropertyName("isPremiumDevice")
    val isPremiumDevice: Boolean = false,
    val subscriptionExpiresAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UserEntitlement(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val deviceHash: String = "",
    @ServerTimestamp val trialStartedAt: Timestamp? = null,
    @get:PropertyName("isPremiumUser")
    val isPremiumUser: Boolean = false,
    val subscriptionStatus: String = STATUS_TRIAL,
    val subscriptionProductId: String = "",
    val purchaseToken: String = "",
    val paymentRefId: String = "",
    val subscriptionExpiresAt: Timestamp? = null,
    @get:PropertyName("isBlocked")
    val isBlocked: Boolean = false,
    @get:PropertyName("isAdmin")
    val isAdmin: Boolean = false,
    val adminGrantedBy: String = "",
    val bonusTrialDays: Long = 0L,
    val adminNote: String = "",
    @get:PropertyName("hideSubscriptionUI")
    val hideSubscriptionUI: Boolean = false,
    @get:PropertyName("autoCallRecording")
    val autoCallRecording: Boolean = false,
    val themeMode: String = THEME_LIGHT,
    @get:PropertyName("dialpadTonesEnabled")
    val dialpadTonesEnabled: Boolean = true,
    val blockedNumbers: List<String> = emptyList(),
    val quickDeclineResponses: List<String> = emptyList(),
    @get:PropertyName("announcerEnabled")
    val announcerEnabled: Boolean = false,
    @get:PropertyName("announcerHeadsetOnly")
    val announcerHeadsetOnly: Boolean = false,
    val unknownShieldMode: String = SHIELD_OFF,
    @get:PropertyName("autoSmsFollowUp")
    val autoSmsFollowUp: Boolean = false,
    @get:PropertyName("autoWhatsappFollowUp")
    val autoWhatsappFollowUp: Boolean = false,
    val followUpTrigger: String = TRIGGER_ALL_CALLS,
    val completedCallTemplate: String = DEFAULT_COMPLETED_CALL_TEMPLATE,
    val missedCallTemplate: String = DEFAULT_MISSED_CALL_TEMPLATE,
    @get:PropertyName("recordWhatsappCalls")
    val recordWhatsappCalls: Boolean = true,
    @get:PropertyName("recordWhatsappBusinessCalls")
    val recordWhatsappBusinessCalls: Boolean = true,
    @get:PropertyName("recordMeetCalls")
    val recordMeetCalls: Boolean = true,
    @get:PropertyName("recordTelegramCalls")
    val recordTelegramCalls: Boolean = true,
    @get:PropertyName("recordInstagramCalls")
    val recordInstagramCalls: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val uid: String
        get() = userId.ifBlank { id }

    val phone: String
        get() = phoneNumber

    val isPremium: Boolean
        get() = isPremiumUser && !isBlocked

    val autoRecordEnabled: Boolean
        get() = autoCallRecording

    val installDate: Timestamp?
        get() = trialStartedAt ?: createdAt

    companion object {
        const val STATUS_TRIAL = "trial"
        const val STATUS_PENDING_APPROVAL = "pending_approval"
        const val STATUS_ACTIVE = "active"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_BLOCKED = "blocked"

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val SHIELD_OFF = "OFF"
        const val SHIELD_SILENT_RECORD = "SILENT_RECORD"
        const val SHIELD_AUTO_REJECT = "AUTO_REJECT"

        const val TRIGGER_ALL_CALLS = "ALL_CALLS"
        const val TRIGGER_MISSED_ONLY = "MISSED_ONLY"
        const val TRIGGER_COMPLETED_ONLY = "COMPLETED_ONLY"

        const val DEFAULT_COMPLETED_CALL_TEMPLATE =
            "Thank you for calling! We have saved your number and will assist you shortly."
        const val DEFAULT_MISSED_CALL_TEMPLATE =
            "Sorry I missed your call. I am currently busy and will call you back soon."
    }
}

@IgnoreExtraProperties
data class AppConfig(
    @DocumentId val id: String = "",
    val trialDays: Long = 7L,
    val subscriptionPriceInr: Long = 299L,
    val upiVpa: String = "elx@ptaxis",
    val whatsappNumber: String = "9012634797",
    @get:PropertyName("enforceSingleDeviceTrial")
    val enforceSingleDeviceTrial: Boolean = true,
    @get:PropertyName("allowCallRecording")
    val allowCallRecording: Boolean = true,
    @get:PropertyName("allowManualUtr")
    val allowManualUtr: Boolean = true,
    @get:PropertyName("maintenanceMode")
    val maintenanceMode: Boolean = false,
    val maintenanceMessage: String = "",
    val announcementText: String = "",
    @get:PropertyName("dialpadTonesDefault")
    val dialpadTonesDefault: Boolean = true,
    @get:PropertyName("defaultAutoRecording")
    val defaultAutoRecording: Boolean = false,
    val forceThemeMode: String = THEME_USER,
    @get:PropertyName("hideSubscriptionUI")
    val hideSubscriptionUI: Boolean = false,
    @get:PropertyName("enableUpiGateway")
    val enableUpiGateway: Boolean = true,
    @get:PropertyName("enableRazorpayGateway")
    val enableRazorpayGateway: Boolean = false,
    val razorpayKeyId: String = "",
    val razorpayPaymentLink: String = "",
    @get:PropertyName("enablePayuGateway")
    val enablePayuGateway: Boolean = false,
    val payuMerchantKey: String = "",
    val payuPaymentLink: String = "",
    @get:PropertyName("enableCustomGateway")
    val enableCustomGateway: Boolean = false,
    val customGatewayName: String = "",
    val customGatewayUrl: String = "",
    val updatedByUid: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val subscriptionPrice: Long
        get() = subscriptionPriceInr

    val adminUpiId: String
        get() = upiVpa

    companion object {
        const val DOC_GLOBAL_SETTINGS = "global_settings"
        const val THEME_USER = "user"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}

enum class TrialStatus {
    FREE_TRIAL_ACTIVE,
    TRIAL_EXPIRED,
    DEVICE_TRIAL_ALREADY_USED,
    PREMIUM_ACTIVE
}

data class TrialVerificationResult(
    val status: TrialStatus,
    val deviceHash: String,
    val shortDeviceTag: String,
    val daysRemaining: Int,
    val isPremium: Boolean,
    val hasRecordingAccess: Boolean,
    val trialStartedAtMs: Long,
    val serverNowMs: Long,
    val accountSwitchCount: Long,
    val isBlocked: Boolean = false,
    val bonusTrialDays: Long = 0L,
    val subscriptionExpiresAtMs: Long = 0L
)

@IgnoreExtraProperties
data class PaymentRequest(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val userEmail: String = "",
    val deviceHash: String = "",
    val shortDeviceTag: String = "",
    val upiId: String = "",
    val amountInr: Long = 299L,
    val txnRef: String = "",
    val upiTxnId: String = "",
    val approvalRefNo: String = "",
    val upiResponseStatus: String = "SUCCESS",
    val status: String = STATUS_PENDING,
    val reviewedByUid: String = "",
    val reviewNote: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
    }
}
