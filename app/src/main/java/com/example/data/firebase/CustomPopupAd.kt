package com.example.data.firebase

import android.os.Bundle
import java.io.Serializable

/**
 * Real-Time Custom Popup Ad campaign model stored at `/custom_popup_ad` in Firebase RTDB.
 */
data class CustomPopupAd(
    val adId: String = "",
    val isEnabled: Boolean = false,
    val showToPremiumUsers: Boolean = true,
    val triggerMode: String = TRIGGER_BOTH,
    val imageUrl: String = "",
    val title: String = "",
    val message: String = "",
    val buttonText: String = DEFAULT_BUTTON_TEXT,
    val targetUrl: String = "",
    val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
    val impressionsCount: Long = 0L,
    val clicksCount: Long = 0L
) : Serializable {

    fun matchesTrigger(trigger: String): Boolean {
        val mode = normalizedTriggerMode(triggerMode)
        return when (trigger) {
            TRIGGER_ON_APP_OPEN -> mode == TRIGGER_ON_APP_OPEN || mode == TRIGGER_BOTH
            TRIGGER_AFTER_CALL_END -> mode == TRIGGER_AFTER_CALL_END || mode == TRIGGER_BOTH
            else -> false
        }
    }

    fun shouldDisplayFor(trigger: String, isPremiumUser: Boolean): Boolean {
        if (!isEnabled) return false
        if (isPremiumUser && !showToPremiumUsers) return false
        if (!matchesTrigger(trigger)) return false
        return title.isNotBlank() || message.isNotBlank() || imageUrl.isNotBlank()
    }

    fun effectiveCampaignKey(): String {
        return adId.ifBlank { "${title.trim()}|${message.trim()}|${targetUrl.trim()}" }
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_AD_ID, adId)
        putBoolean(KEY_IS_ENABLED, isEnabled)
        putBoolean(KEY_SHOW_TO_PREMIUM, showToPremiumUsers)
        putString(KEY_TRIGGER_MODE, normalizedTriggerMode(triggerMode))
        putString(KEY_IMAGE_URL, imageUrl)
        putString(KEY_TITLE, title)
        putString(KEY_MESSAGE, message)
        putString(KEY_BUTTON_TEXT, buttonText)
        putString(KEY_TARGET_URL, targetUrl)
        putInt(KEY_COUNTDOWN_SECONDS, countdownSeconds.coerceIn(0, MAX_COUNTDOWN_SECONDS))
        putLong(KEY_IMPRESSIONS_COUNT, impressionsCount)
        putLong(KEY_CLICKS_COUNT, clicksCount)
    }

    companion object {
        private const val serialVersionUID = 1L

        const val TRIGGER_ON_APP_OPEN = "ON_APP_OPEN"
        const val TRIGGER_AFTER_CALL_END = "AFTER_CALL_END"
        const val TRIGGER_BOTH = "BOTH"

        const val DEFAULT_BUTTON_TEXT = "Open"
        const val DEFAULT_COUNTDOWN_SECONDS = 5
        const val MAX_COUNTDOWN_SECONDS = 60

        private const val KEY_AD_ID = "adId"
        private const val KEY_IS_ENABLED = "isEnabled"
        private const val KEY_SHOW_TO_PREMIUM = "showToPremiumUsers"
        private const val KEY_TRIGGER_MODE = "triggerMode"
        private const val KEY_IMAGE_URL = "imageUrl"
        private const val KEY_TITLE = "title"
        private const val KEY_MESSAGE = "message"
        private const val KEY_BUTTON_TEXT = "buttonText"
        private const val KEY_TARGET_URL = "targetUrl"
        private const val KEY_COUNTDOWN_SECONDS = "countdownSeconds"
        private const val KEY_IMPRESSIONS_COUNT = "impressionsCount"
        private const val KEY_CLICKS_COUNT = "clicksCount"

        fun normalizedTriggerMode(raw: String?): String {
            return when (raw?.trim()?.uppercase()) {
                TRIGGER_ON_APP_OPEN -> TRIGGER_ON_APP_OPEN
                TRIGGER_AFTER_CALL_END -> TRIGGER_AFTER_CALL_END
                TRIGGER_BOTH -> TRIGGER_BOTH
                else -> TRIGGER_BOTH
            }
        }

        fun normalizeTargetUrl(raw: String?): String {
            val clean = raw?.trim().orEmpty()
            if (clean.isEmpty()) return ""
            return if (!clean.contains("://") &&
                !clean.startsWith("tel:", ignoreCase = true) &&
                !clean.startsWith("mailto:", ignoreCase = true)
            ) {
                "https://$clean"
            } else {
                clean
            }
        }

        fun fromBundle(bundle: Bundle?): CustomPopupAd {
            if (bundle == null) return CustomPopupAd()
            return CustomPopupAd(
                adId = bundle.getString(KEY_AD_ID).orEmpty(),
                isEnabled = bundle.getBoolean(KEY_IS_ENABLED, false),
                showToPremiumUsers = bundle.getBoolean(KEY_SHOW_TO_PREMIUM, true),
                triggerMode = normalizedTriggerMode(bundle.getString(KEY_TRIGGER_MODE)),
                imageUrl = bundle.getString(KEY_IMAGE_URL).orEmpty(),
                title = bundle.getString(KEY_TITLE).orEmpty(),
                message = bundle.getString(KEY_MESSAGE).orEmpty(),
                buttonText = bundle.getString(KEY_BUTTON_TEXT).orEmpty().ifBlank { DEFAULT_BUTTON_TEXT },
                targetUrl = bundle.getString(KEY_TARGET_URL).orEmpty(),
                countdownSeconds = bundle.getInt(KEY_COUNTDOWN_SECONDS, DEFAULT_COUNTDOWN_SECONDS)
                    .coerceIn(0, MAX_COUNTDOWN_SECONDS),
                impressionsCount = bundle.getLong(KEY_IMPRESSIONS_COUNT, 0L),
                clicksCount = bundle.getLong(KEY_CLICKS_COUNT, 0L)
            )
        }
    }
}
