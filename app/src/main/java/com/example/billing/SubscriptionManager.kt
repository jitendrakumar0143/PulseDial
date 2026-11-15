package com.example.billing

import android.content.Context
import android.content.SharedPreferences
import com.example.data.DialerPreferences

/**
 * Helper class that tracks `firstLaunchTime` in SharedPreferences and provides
 * `isSubscriptionActive()` to enforce the 7-day free trial expiry and lock premium
 * features (call recording, auto-recording) if the user has not paid.
 */
class SubscriptionManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: DialerPreferences = DialerPreferences(appContext)
    private val sharedPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureFirstLaunchTracked()
    }

    /**
     * Persists `firstLaunchTime` in SharedPreferences on the very first launch if not set.
     */
    fun ensureFirstLaunchTracked(nowMillis: Long = System.currentTimeMillis()): Long {
        val existing = sharedPrefs.getLong(KEY_FIRST_LAUNCH_TIME, 0L)
        if (existing <= 0L) {
            prefs.firstLaunchTime = nowMillis
            return nowMillis
        }
        return existing
    }

    var firstLaunchTime: Long
        get() = ensureFirstLaunchTracked()
        set(value) {
            prefs.firstLaunchTime = value
        }

    var isPaidUser: Boolean
        get() = prefs.isPremiumUser
        set(value) {
            prefs.isPremiumUser = value
            if (value) {
                prefs.trialBlockedByDevice = false
            } else {
                prefs.subscriptionExpiresAtMs = 0L
            }
        }

    var subscriptionExpiresAtMs: Long
        get() = prefs.subscriptionExpiresAtMs
        set(value) {
            prefs.subscriptionExpiresAtMs = value
        }

    fun isPremiumActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        prefs.isPremiumActive(nowMillis)

    fun hasMonthlySubscriptionExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        prefs.hasMonthlySubscriptionExpired(nowMillis)

    fun getSubscriptionRemainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        prefs.getSubscriptionRemainingMillis(nowMillis)

    fun getSubscriptionDaysRemaining(nowMillis: Long = System.currentTimeMillis()): Int =
        prefs.getSubscriptionDaysRemaining(nowMillis)

    fun isSubscriptionExpiringSoon(nowMillis: Long = System.currentTimeMillis()): Boolean =
        prefs.isSubscriptionExpiringSoon(nowMillis)

    fun formatSubscriptionCountdown(nowMillis: Long = System.currentTimeMillis()): String =
        prefs.formatSubscriptionCountdown(nowMillis)

    fun isBlockedByAdmin(): Boolean = prefs.adminBlockedUser

    fun isMaintenanceLocked(isAdmin: Boolean = false): Boolean =
        prefs.maintenanceMode && !isAdmin

    fun getConfiguredTrialDays(): Int = prefs.getEffectiveTrialDays()

    fun getConfiguredPriceInr(): Long = prefs.configuredPriceInr

    /**
     * Returns true when the user is not blocked by admin AND (has an unexpired monthly subscription OR
     * their free trial has not expired). Once 7 days trial OR the 30-day paid month expires, returns false
     * until renewed and approved.
     */
    fun isSubscriptionActive(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!prefs.verifyIntegrityOrReset()) return false
        if (prefs.adminBlockedUser) return false
        if (prefs.isPremiumActive(nowMillis)) return true
        if (prefs.enforceSingleDeviceTrialConfig && prefs.trialBlockedByDevice && isTrialExpired(nowMillis)) {
            return false
        }
        return !isTrialExpired(nowMillis)
    }

    fun isTrialExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return prefs.isTrialExpired(nowMillis)
    }

    fun getTrialRemainingMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        return prefs.getTrialRemainingMillis(nowMillis)
    }

    fun getTrialDaysRemaining(nowMillis: Long = System.currentTimeMillis()): Int {
        return prefs.getTrialDaysRemaining(nowMillis)
    }

    fun formatRemainingCountdown(nowMillis: Long = System.currentTimeMillis()): String {
        return prefs.formatRemainingCountdown(nowMillis)
    }

    fun isFeatureLocked(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return !isSubscriptionActive(nowMillis)
    }

    companion object {
        const val PREFS_NAME = "pulsedial_preferences"
        const val KEY_FIRST_LAUNCH_TIME = DialerPreferences.KEY_FIRST_LAUNCH_TIME
        const val KEY_IS_PREMIUM_USER = DialerPreferences.KEY_IS_PREMIUM_USER
        const val KEY_SUBSCRIPTION_EXPIRES_AT_MS = DialerPreferences.KEY_SUBSCRIPTION_EXPIRES_AT_MS
        const val TRIAL_DAYS = DialerPreferences.TRIAL_DAYS
        const val MONTHLY_SUBSCRIPTION_DAYS = DialerPreferences.MONTHLY_SUBSCRIPTION_DAYS
        const val ONE_DAY_MILLIS = DialerPreferences.ONE_DAY_MILLIS
        const val ONE_MONTH_MILLIS = DialerPreferences.ONE_MONTH_MILLIS
        const val TRIAL_DURATION_MILLIS = DialerPreferences.TRIAL_DURATION_MILLIS
    }
}
