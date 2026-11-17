package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.example.billing.UpiPaymentHelper
import com.example.data.firebase.AppConfig
import com.example.data.firebase.TrialStatus
import com.example.data.firebase.TrialVerificationResult
import com.example.recording.CallRecordingSource
import kotlin.math.ceil

data class RecordingFolderTarget(
    val treeUri: String?,
    val displayName: String,
    val isCustomPerSource: Boolean
) {
    /**
     * When user picks a dedicated folder specifically for an app (e.g. WhatsApp -> MyWAFolder),
     * write directly inside that folder (`createSubfolder = false`).
     * When using the global folder fallback, isolate each app inside its own subfolder.
     */
    val createSubfolder: Boolean get() = !isCustomPerSource
}

class DialerPreferences(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureFirstLaunchRecorded()
        verifyIntegrityOrReset()
    }

    fun ensureFirstLaunchRecorded(now: Long = System.currentTimeMillis()) {
        if (!prefs.contains(KEY_FIRST_LAUNCH_TIME) || prefs.getLong(KEY_FIRST_LAUNCH_TIME, 0L) <= 0L) {
            prefs.edit {
                putLong(KEY_FIRST_LAUNCH_TIME, now)
                putLong(KEY_LAST_SEEN_TIME, maxOf(prefs.getLong(KEY_LAST_SEEN_TIME, 0L), now))
            }
            refreshIntegritySeal()
        }
    }

    private fun buildProtectedPayload(): String {
        return listOf(
            prefs.getLong(KEY_FIRST_LAUNCH_TIME, 0L).toString(),
            prefs.getLong(KEY_LAST_SEEN_TIME, 0L).toString(),
            prefs.getLong(KEY_SERVER_CLOCK_OFFSET_MS, 0L).toString(),
            prefs.getBoolean(KEY_IS_PREMIUM_USER, false).toString(),
            prefs.getLong(KEY_SUBSCRIPTION_EXPIRES_AT_MS, 0L).toString(),
            prefs.getBoolean(KEY_TRIAL_BLOCKED_BY_DEVICE, false).toString(),
            prefs.getBoolean(KEY_ADMIN_BLOCKED_USER, false).toString(),
            prefs.getInt(KEY_ADMIN_BONUS_TRIAL_DAYS, 0).toString(),
            prefs.getInt(KEY_CONFIG_TRIAL_DAYS, TRIAL_DAYS).toString(),
            prefs.getLong(KEY_CONFIG_PRICE_INR, UpiPaymentHelper.UPI_AMOUNT_LONG).toString(),
            prefs.getString(KEY_CONFIG_UPI_VPA, UpiPaymentHelper.UPI_VPA).orEmpty(),
            prefs.getString(KEY_CONFIG_WHATSAPP, UpiPaymentHelper.WHATSAPP_SUPPORT_LOCAL).orEmpty(),
            prefs.getBoolean(KEY_CONFIG_SINGLE_DEVICE_TRIAL, true).toString(),
            prefs.getBoolean(KEY_CONFIG_ALLOW_RECORDING, true).toString(),
            prefs.getBoolean(KEY_CONFIG_ALLOW_MANUAL_UTR, true).toString(),
            prefs.getBoolean(KEY_CONFIG_MAINTENANCE_MODE, false).toString(),
            prefs.getString(KEY_CONFIG_FORCE_THEME, AppConfig.THEME_USER).orEmpty(),
            prefs.getBoolean(KEY_HIDE_SUBSCRIPTION_UI, false).toString(),
            prefs.getString(KEY_DEVICE_ID_HASH, "").orEmpty(),
            prefs.getString(KEY_USER_EMAIL, "").orEmpty()
        ).joinToString("|")
    }

    private fun refreshIntegritySeal() {
        val payload = buildProtectedPayload()
        val sig = EntitlementIntegrity.computeSignature(appContext, payload)
        prefs.edit { putString(KEY_INTEGRITY_SIG, sig) }
        synchronized(sealCacheLock) {
            cachedVerifiedPayload = payload
            cachedVerifiedSig = sig
        }
    }

    /**
     * Verifies cryptographic HMAC integrity of local preferences. If someone manually edited
     * SharedPreferences XML to fake isPremiumUser=true or roll back trial timestamps, immediately
     * invalidates local entitlement and forces cloud verification.
     */
    fun verifyIntegrityOrReset(): Boolean {
        val storedSig = prefs.getString(KEY_INTEGRITY_SIG, null)
        if (storedSig == null) {
            refreshIntegritySeal()
            return true
        }
        val payload = buildProtectedPayload()
        synchronized(sealCacheLock) {
            if (storedSig == cachedVerifiedSig && payload == cachedVerifiedPayload) {
                return true
            }
        }
        val valid = EntitlementIntegrity.verifySignature(appContext, payload, storedSig)
        if (!valid) {
            val now = System.currentTimeMillis()
            prefs.edit {
                putBoolean(KEY_IS_PREMIUM_USER, false)
                putLong(KEY_SUBSCRIPTION_EXPIRES_AT_MS, 0L)
                putBoolean(KEY_TRIAL_BLOCKED_BY_DEVICE, true)
                putBoolean(KEY_ADMIN_BLOCKED_USER, false)
                putInt(KEY_ADMIN_BONUS_TRIAL_DAYS, 0)
                putLong(KEY_LAST_SEEN_TIME, maxOf(prefs.getLong(KEY_LAST_SEEN_TIME, 0L), now))
                putBoolean(KEY_TAMPER_DETECTED, true)
            }
            refreshIntegritySeal()
        } else {
            synchronized(sealCacheLock) {
                cachedVerifiedPayload = payload
                cachedVerifiedSig = storedSig
            }
        }
        return valid
    }

    var tamperDetected: Boolean
        get() = prefs.getBoolean(KEY_TAMPER_DETECTED, false)
        set(value) = prefs.edit { putBoolean(KEY_TAMPER_DETECTED, value) }

    var firstLaunchTime: Long
        get() = prefs.getLong(KEY_FIRST_LAUNCH_TIME, System.currentTimeMillis())
        set(value) {
            prefs.edit {
                putLong(KEY_FIRST_LAUNCH_TIME, value)
                putLong(KEY_LAST_SEEN_TIME, value)
            }
            refreshIntegritySeal()
        }

    var lastSeenTimeMs: Long
        get() = prefs.getLong(KEY_LAST_SEEN_TIME, 0L)
        set(value) {
            prefs.edit { putLong(KEY_LAST_SEEN_TIME, value) }
            refreshIntegritySeal()
        }

    var serverClockOffsetMs: Long
        get() = prefs.getLong(KEY_SERVER_CLOCK_OFFSET_MS, 0L)
        set(value) {
            prefs.edit { putLong(KEY_SERVER_CLOCK_OFFSET_MS, value) }
            refreshIntegritySeal()
        }

    var deviceIdHash: String
        get() = prefs.getString(KEY_DEVICE_ID_HASH, "").orEmpty()
        set(value) {
            prefs.edit { putString(KEY_DEVICE_ID_HASH, value) }
            refreshIntegritySeal()
        }

    var shortDeviceTag: String
        get() = prefs.getString(KEY_SHORT_DEVICE_TAG, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SHORT_DEVICE_TAG, value) }

    var trialBlockedByDevice: Boolean
        get() = prefs.getBoolean(KEY_TRIAL_BLOCKED_BY_DEVICE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_TRIAL_BLOCKED_BY_DEVICE, value) }
            refreshIntegritySeal()
        }

    var accountSwitchCount: Long
        get() = prefs.getLong(KEY_ACCOUNT_SWITCH_COUNT, 0L)
        set(value) = prefs.edit { putLong(KEY_ACCOUNT_SWITCH_COUNT, value) }

    var adminBlockedUser: Boolean
        get() = prefs.getBoolean(KEY_ADMIN_BLOCKED_USER, false)
        set(value) {
            prefs.edit { putBoolean(KEY_ADMIN_BLOCKED_USER, value) }
            refreshIntegritySeal()
        }

    var adminBonusTrialDays: Int
        get() = prefs.getInt(KEY_ADMIN_BONUS_TRIAL_DAYS, 0).coerceAtLeast(0)
        set(value) {
            prefs.edit { putInt(KEY_ADMIN_BONUS_TRIAL_DAYS, value.coerceAtLeast(0)) }
            refreshIntegritySeal()
        }

    // Admin-managed global config (synced from Firestore /app_config/global_settings)
    var configuredTrialDays: Int
        get() = prefs.getInt(KEY_CONFIG_TRIAL_DAYS, TRIAL_DAYS).coerceIn(1, 365)
        set(value) {
            prefs.edit { putInt(KEY_CONFIG_TRIAL_DAYS, value.coerceIn(1, 365)) }
            refreshIntegritySeal()
        }

    var configuredPriceInr: Long
        get() = prefs.getLong(KEY_CONFIG_PRICE_INR, UpiPaymentHelper.UPI_AMOUNT_LONG).coerceIn(1L, 100000L)
        set(value) {
            prefs.edit { putLong(KEY_CONFIG_PRICE_INR, value.coerceIn(1L, 100000L)) }
            refreshIntegritySeal()
        }

    var configuredUpiVpa: String
        get() = prefs.getString(KEY_CONFIG_UPI_VPA, UpiPaymentHelper.UPI_VPA)
            ?.ifBlank { UpiPaymentHelper.UPI_VPA }
            ?: UpiPaymentHelper.UPI_VPA
        set(value) {
            prefs.edit {
                putString(KEY_CONFIG_UPI_VPA, value.trim().ifBlank { UpiPaymentHelper.UPI_VPA })
            }
            refreshIntegritySeal()
        }

    var configuredWhatsappNumber: String
        get() = prefs.getString(KEY_CONFIG_WHATSAPP, UpiPaymentHelper.WHATSAPP_SUPPORT_LOCAL)
            ?.ifBlank { UpiPaymentHelper.WHATSAPP_SUPPORT_LOCAL }
            ?: UpiPaymentHelper.WHATSAPP_SUPPORT_LOCAL
        set(value) {
            prefs.edit {
                val digits = value.filter { it.isDigit() }
                putString(KEY_CONFIG_WHATSAPP, digits.ifBlank { UpiPaymentHelper.WHATSAPP_SUPPORT_LOCAL })
            }
            refreshIntegritySeal()
        }

    var enforceSingleDeviceTrialConfig: Boolean
        get() = prefs.getBoolean(KEY_CONFIG_SINGLE_DEVICE_TRIAL, true)
        set(value) {
            prefs.edit { putBoolean(KEY_CONFIG_SINGLE_DEVICE_TRIAL, value) }
            refreshIntegritySeal()
        }

    var allowCallRecordingConfig: Boolean
        get() = prefs.getBoolean(KEY_CONFIG_ALLOW_RECORDING, true)
        set(value) {
            prefs.edit { putBoolean(KEY_CONFIG_ALLOW_RECORDING, value) }
            refreshIntegritySeal()
        }

    var allowManualUtrConfig: Boolean
        get() = prefs.getBoolean(KEY_CONFIG_ALLOW_MANUAL_UTR, true)
        set(value) {
            prefs.edit { putBoolean(KEY_CONFIG_ALLOW_MANUAL_UTR, value) }
            refreshIntegritySeal()
        }

    var maintenanceMode: Boolean
        get() = prefs.getBoolean(KEY_CONFIG_MAINTENANCE_MODE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_CONFIG_MAINTENANCE_MODE, value) }
            refreshIntegritySeal()
        }

    var maintenanceMessage: String
        get() = prefs.getString(KEY_CONFIG_MAINTENANCE_MSG, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CONFIG_MAINTENANCE_MSG, value.trim()) }

    var announcementText: String
        get() = prefs.getString(KEY_CONFIG_ANNOUNCEMENT, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CONFIG_ANNOUNCEMENT, value.trim()) }

    var configuredForceThemeMode: String
        get() = prefs.getString(KEY_CONFIG_FORCE_THEME, AppConfig.THEME_USER) ?: AppConfig.THEME_USER
        set(value) {
            val clean = when (value) {
                AppConfig.THEME_LIGHT, AppConfig.THEME_DARK -> value
                else -> AppConfig.THEME_USER
            }
            prefs.edit { putString(KEY_CONFIG_FORCE_THEME, clean) }
            refreshIntegritySeal()
        }

    fun getEffectiveTrialDays(): Int = (configuredTrialDays + adminBonusTrialDays).coerceIn(1, 4000)

    fun getEffectiveTrialDurationMillis(): Long = getEffectiveTrialDays().toLong() * ONE_DAY_MILLIS

    fun applyAppConfig(config: AppConfig) {
        val cleanTheme = when (config.forceThemeMode) {
            AppConfig.THEME_LIGHT, AppConfig.THEME_DARK -> config.forceThemeMode
            else -> AppConfig.THEME_USER
        }
        val waDigits = config.whatsappNumber.filter { it.isDigit() }
        prefs.edit {
            putInt(KEY_CONFIG_TRIAL_DAYS, config.trialDays.toInt().coerceIn(1, 365))
            putLong(KEY_CONFIG_PRICE_INR, config.subscriptionPriceInr.coerceIn(1L, 100000L))
            putString(KEY_CONFIG_UPI_VPA, config.upiVpa.trim().ifBlank { UpiPaymentHelper.UPI_VPA })
            putString(KEY_CONFIG_WHATSAPP, waDigits.ifBlank { UpiPaymentHelper.WHATSAPP_SUPPORT_LOCAL })
            putBoolean(KEY_CONFIG_SINGLE_DEVICE_TRIAL, config.enforceSingleDeviceTrial)
            putBoolean(KEY_CONFIG_ALLOW_RECORDING, config.allowCallRecording)
            putBoolean(KEY_CONFIG_ALLOW_MANUAL_UTR, config.allowManualUtr)
            putBoolean(KEY_CONFIG_MAINTENANCE_MODE, config.maintenanceMode)
            putString(KEY_CONFIG_MAINTENANCE_MSG, config.maintenanceMessage.trim())
            putString(KEY_CONFIG_ANNOUNCEMENT, config.announcementText.trim())
            putString(KEY_CONFIG_FORCE_THEME, cleanTheme)
            putBoolean(KEY_CONFIG_HIDE_SUBSCRIPTION_UI, config.hideSubscriptionUI)
            if (!prefs.getBoolean(KEY_USER_CUSTOM_DIALPAD_TONES, false)) {
                putBoolean(KEY_DIALPAD_TONES, config.dialpadTonesDefault)
            }
            if (!config.allowCallRecording) {
                putBoolean(KEY_AUTO_RECORDING, false)
            } else if (!prefs.getBoolean(KEY_USER_CUSTOM_AUTO_RECORDING, false)) {
                putBoolean(KEY_AUTO_RECORDING, config.defaultAutoRecording)
            }
            if (cleanTheme == AppConfig.THEME_DARK) {
                putInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_YES)
            } else if (cleanTheme == AppConfig.THEME_LIGHT) {
                putInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
        refreshIntegritySeal()
    }

    private fun effectiveNow(now: Long): Long {
        val adjusted = now + serverClockOffsetMs
        val storedLastSeen = lastSeenTimeMs
        val monotonic = maxOf(adjusted, storedLastSeen)
        if (storedLastSeen <= 0L || (monotonic - storedLastSeen) >= LAST_SEEN_PERSIST_INTERVAL_MS) {
            prefs.edit { putLong(KEY_LAST_SEEN_TIME, monotonic) }
            refreshIntegritySeal()
        }
        return monotonic
    }

    fun getTrialRemainingMillis(now: Long = System.currentTimeMillis()): Long {
        verifyIntegrityOrReset()
        val effective = effectiveNow(now)
        val durationMs = getEffectiveTrialDurationMillis()
        if (enforceSingleDeviceTrialConfig && trialBlockedByDevice && (effective - firstLaunchTime) >= durationMs) {
            return 0L
        }
        return ((firstLaunchTime + durationMs) - effective).coerceAtLeast(0L)
    }

    fun isTrialExpired(now: Long = System.currentTimeMillis()): Boolean {
        verifyIntegrityOrReset()
        return (effectiveNow(now) - firstLaunchTime) >= getEffectiveTrialDurationMillis()
    }

    fun getTrialDaysRemaining(now: Long = System.currentTimeMillis()): Int {
        val remainingMs = getTrialRemainingMillis(now)
        if (remainingMs <= 0L) return 0
        val days = ceil(remainingMs.toDouble() / ONE_DAY_MILLIS.toDouble()).toInt()
        return days.coerceIn(1, getEffectiveTrialDays())
    }

    fun formatRemainingCountdown(now: Long = System.currentTimeMillis()): String {
        val remainingMs = getTrialRemainingMillis(now)
        if (remainingMs <= 0L) return "0h 0m left"
        val totalHours = remainingMs / ONE_HOUR_MILLIS
        val days = totalHours / 24L
        val hours = totalHours % 24L
        val minutes = (remainingMs % ONE_HOUR_MILLIS) / ONE_MINUTE_MILLIS
        return when {
            days > 0L -> "${days}d ${hours}h left"
            hours > 0L -> "${hours}h ${minutes}m left"
            else -> "${minutes.coerceAtLeast(1L)}m left"
        }
    }

    fun isPremiumActive(now: Long = System.currentTimeMillis()): Boolean {
        if (!verifyIntegrityOrReset()) return false
        if (adminBlockedUser || !isPremiumUser) return false
        val expMs = subscriptionExpiresAtMs
        if (expMs <= 0L) return true
        return effectiveNow(now) < expMs
    }

    fun hasMonthlySubscriptionExpired(now: Long = System.currentTimeMillis()): Boolean {
        verifyIntegrityOrReset()
        val expMs = subscriptionExpiresAtMs
        return expMs > 0L && effectiveNow(now) >= expMs
    }

    fun getSubscriptionRemainingMillis(now: Long = System.currentTimeMillis()): Long {
        if (!isPremiumActive(now)) return 0L
        val expMs = subscriptionExpiresAtMs
        if (expMs <= 0L) return 0L
        return (expMs - effectiveNow(now)).coerceAtLeast(0L)
    }

    fun getSubscriptionDaysRemaining(now: Long = System.currentTimeMillis()): Int {
        val remainingMs = getSubscriptionRemainingMillis(now)
        if (remainingMs <= 0L) return 0
        return ceil(remainingMs.toDouble() / ONE_DAY_MILLIS.toDouble())
            .toInt()
            .coerceAtLeast(1)
    }

    fun isSubscriptionExpiringSoon(now: Long = System.currentTimeMillis()): Boolean {
        val expMs = subscriptionExpiresAtMs
        if (expMs <= 0L || !isPremiumActive(now)) return false
        return getSubscriptionDaysRemaining(now) <= EXPIRING_SOON_WINDOW_DAYS
    }

    fun formatSubscriptionCountdown(now: Long = System.currentTimeMillis()): String {
        val remainingMs = getSubscriptionRemainingMillis(now)
        if (remainingMs <= 0L) return "Expired"
        val totalHours = remainingMs / ONE_HOUR_MILLIS
        val days = totalHours / 24L
        val hours = totalHours % 24L
        val minutes = (remainingMs % ONE_HOUR_MILLIS) / ONE_MINUTE_MILLIS
        return when {
            days > 0L -> "${days}d ${hours}h left"
            hours > 0L -> "${hours}h ${minutes}m left"
            else -> "${minutes.coerceAtLeast(1L)}m left"
        }
    }

    fun hasRecordingAccess(now: Long = System.currentTimeMillis()): Boolean {
        if (!verifyIntegrityOrReset()) return false
        if (adminBlockedUser) return false
        if (isPremiumActive(now)) return true
        if (enforceSingleDeviceTrialConfig && trialBlockedByDevice && isTrialExpired(now)) return false
        return !isTrialExpired(now)
    }

    fun applyCloudVerification(
        result: TrialVerificationResult,
        displayName: String?,
        email: String?,
        localNowMs: Long = System.currentTimeMillis()
    ) {
        prefs.edit {
            putLong(KEY_FIRST_LAUNCH_TIME, result.trialStartedAtMs)
            putLong(KEY_SERVER_CLOCK_OFFSET_MS, result.serverNowMs - localNowMs)
            putLong(KEY_LAST_SEEN_TIME, result.serverNowMs)
            putBoolean(KEY_IS_PREMIUM_USER, result.isPremium)
            putLong(KEY_SUBSCRIPTION_EXPIRES_AT_MS, result.subscriptionExpiresAtMs)
            putBoolean(KEY_ADMIN_BLOCKED_USER, result.isBlocked)
            putInt(KEY_ADMIN_BONUS_TRIAL_DAYS, result.bonusTrialDays.toInt().coerceAtLeast(0))
            putString(KEY_DEVICE_ID_HASH, result.deviceHash)
            putString(KEY_SHORT_DEVICE_TAG, result.shortDeviceTag)
            putBoolean(
                KEY_TRIAL_BLOCKED_BY_DEVICE,
                result.status == TrialStatus.DEVICE_TRIAL_ALREADY_USED
            )
            putLong(KEY_ACCOUNT_SWITCH_COUNT, result.accountSwitchCount)
            putBoolean(KEY_TAMPER_DETECTED, false)
            if (!displayName.isNullOrBlank()) {
                putString(KEY_USER_NAME, displayName)
            }
            if (!email.isNullOrBlank()) {
                putString(KEY_USER_EMAIL, email)
            }
        }
        refreshIntegritySeal()
    }

    fun resetSessionEntitlementsOnSignOut() {
        prefs.edit {
            putBoolean(KEY_IS_LOGGED_IN, false)
            remove(KEY_LOGGED_IN_USER_ID)
            putBoolean(KEY_IS_PREMIUM_USER, false)
            putLong(KEY_SUBSCRIPTION_EXPIRES_AT_MS, 0L)
            putBoolean(KEY_ADMIN_BLOCKED_USER, false)
            putBoolean(KEY_HIDE_SUBSCRIPTION_UI, false)
            putInt(KEY_ADMIN_BONUS_TRIAL_DAYS, 0)
            putBoolean(KEY_TAMPER_DETECTED, false)
            remove(KEY_USER_EMAIL)
        }
        refreshIntegritySeal()
    }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_LOGGED_IN, value) }

    var loggedInUserId: String
        get() = prefs.getString(KEY_LOGGED_IN_USER_ID, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_LOGGED_IN_USER_ID, value.trim()) }

    var rawAndroidId: String
        get() = prefs.getString(KEY_RAW_ANDROID_ID, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_RAW_ANDROID_ID, value.trim()) }

    var initialDeviceRegisteredInRtdb: Boolean
        get() = prefs.getBoolean(KEY_INITIAL_DEVICE_REGISTERED_RTDB, false)
        set(value) = prefs.edit { putBoolean(KEY_INITIAL_DEVICE_REGISTERED_RTDB, value) }

    var autoCallRecording: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECORDING, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_RECORDING, value) }

    var recordingFolderUri: String?
        get() = prefs.getString(KEY_RECORDING_FOLDER_URI, null)
        set(value) = prefs.edit { putString(KEY_RECORDING_FOLDER_URI, value) }

    var recordingFolderName: String?
        get() = prefs.getString(KEY_RECORDING_FOLDER_NAME, null)
        set(value) = prefs.edit { putString(KEY_RECORDING_FOLDER_NAME, value) }

    fun getCustomFolderUri(source: CallRecordingSource): String? =
        prefs.getString(KEY_SOURCE_FOLDER_URI_PREFIX + source.name, null)

    fun getCustomFolderName(source: CallRecordingSource): String? =
        prefs.getString(KEY_SOURCE_FOLDER_NAME_PREFIX + source.name, null)

    fun setCustomFolder(source: CallRecordingSource, uri: String?, name: String?) {
        val uriKey = KEY_SOURCE_FOLDER_URI_PREFIX + source.name
        val nameKey = KEY_SOURCE_FOLDER_NAME_PREFIX + source.name
        if (uri.isNullOrBlank()) {
            prefs.edit {
                remove(uriKey)
                remove(nameKey)
            }
        } else {
            val cleanName = name?.trim()?.ifBlank { null } ?: source.folderName
            prefs.edit {
                putString(uriKey, uri.trim())
                putString(nameKey, cleanName)
            }
        }
    }

    fun clearCustomFolder(source: CallRecordingSource) {
        setCustomFolder(source, null, null)
    }

    fun resolveFolderTarget(source: CallRecordingSource): RecordingFolderTarget {
        val perSourceUri = getCustomFolderUri(source)
        if (!perSourceUri.isNullOrBlank()) {
            val perSourceName = getCustomFolderName(source)?.ifBlank { null } ?: source.folderName
            return RecordingFolderTarget(
                treeUri = perSourceUri,
                displayName = perSourceName,
                isCustomPerSource = true
            )
        }
        val globalUri = recordingFolderUri
        if (!globalUri.isNullOrBlank()) {
            val globalRoot = recordingFolderName?.ifBlank { null } ?: CallRecordingSource.ROOT_FOLDER_NAME
            return RecordingFolderTarget(
                treeUri = globalUri,
                displayName = "$globalRoot/${source.folderName}",
                isCustomPerSource = false
            )
        }
        return RecordingFolderTarget(
            treeUri = null,
            displayName = source.relativePublicPath(),
            isCustomPerSource = false
        )
    }

    fun markAutoRecordingCustomizedByUser() {
        prefs.edit { putBoolean(KEY_USER_CUSTOM_AUTO_RECORDING, true) }
    }

    fun markDialpadTonesCustomizedByUser() {
        prefs.edit { putBoolean(KEY_USER_CUSTOM_DIALPAD_TONES, true) }
    }

    var dialpadTonesEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIALPAD_TONES, true)
        set(value) = prefs.edit { putBoolean(KEY_DIALPAD_TONES, value) }

    var displayThemeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_NO)
        set(value) = prefs.edit { putInt(KEY_THEME_MODE, value) }

    var isPremiumUser: Boolean
        get() = prefs.getBoolean(KEY_IS_PREMIUM_USER, false)
        set(value) {
            prefs.edit { putBoolean(KEY_IS_PREMIUM_USER, value) }
            refreshIntegritySeal()
        }

    var subscriptionExpiresAtMs: Long
        get() = prefs.getLong(KEY_SUBSCRIPTION_EXPIRES_AT_MS, 0L)
        set(value) {
            prefs.edit { putLong(KEY_SUBSCRIPTION_EXPIRES_AT_MS, value.coerceAtLeast(0L)) }
            refreshIntegritySeal()
        }

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME
        set(value) = prefs.edit { putString(KEY_USER_NAME, value.ifBlank { DEFAULT_USER_NAME }) }

    var userEmail: String
        get() = prefs.getString(KEY_USER_EMAIL, "").orEmpty()
        set(value) {
            prefs.edit { putString(KEY_USER_EMAIL, value.trim()) }
            refreshIntegritySeal()
        }

    var userPhoneNumber: String
        get() = prefs.getString(KEY_USER_NUMBER, DEFAULT_USER_NUMBER) ?: DEFAULT_USER_NUMBER
        set(value) = prefs.edit { putString(KEY_USER_NUMBER, value.ifBlank { DEFAULT_USER_NUMBER }) }

    var configHideSubscriptionUi: Boolean
        get() = prefs.getBoolean(KEY_CONFIG_HIDE_SUBSCRIPTION_UI, false)
        set(value) {
            prefs.edit { putBoolean(KEY_CONFIG_HIDE_SUBSCRIPTION_UI, value) }
            refreshIntegritySeal()
        }

    var hideSubscriptionUi: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SUBSCRIPTION_UI, false) || configHideSubscriptionUi
        set(value) {
            prefs.edit { putBoolean(KEY_HIDE_SUBSCRIPTION_UI, value) }
            refreshIntegritySeal()
        }

    fun isPopupDismissed(popupId: String): Boolean {
        if (popupId.isBlank()) return false
        val dismissed = prefs.getStringSet(KEY_DISMISSED_POPUP_IDS, emptySet()).orEmpty()
        return dismissed.contains(popupId)
    }

    fun markPopupDismissed(popupId: String) {
        if (popupId.isBlank()) return
        val updated = prefs.getStringSet(KEY_DISMISSED_POPUP_IDS, emptySet()).orEmpty().toMutableSet()
        updated.add(popupId)
        if (updated.size > 50) {
            val trimmed = updated.toList().takeLast(50).toSet()
            prefs.edit { putStringSet(KEY_DISMISSED_POPUP_IDS, trimmed) }
        } else {
            prefs.edit { putStringSet(KEY_DISMISSED_POPUP_IDS, updated) }
        }
    }

    fun getDynamicToggle(key: String, defaultValue: Boolean = false): Boolean {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) return defaultValue
        return prefs.getBoolean(KEY_DYNAMIC_TOGGLE_PREFIX + cleanKey, defaultValue)
    }

    fun setDynamicToggle(key: String, enabled: Boolean) {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) return
        prefs.edit { putBoolean(KEY_DYNAMIC_TOGGLE_PREFIX + cleanKey, enabled) }
    }

    fun getBlockedNumbers(): List<String> {
        val raw = prefs.getStringSet(KEY_BLOCKED_NUMBERS, emptySet()).orEmpty()
        return raw.sorted()
    }

    fun setBlockedNumbers(numbers: List<String>) {
        val cleaned = numbers.map { it.trim() }.filter { it.isNotEmpty() }.take(500).toSet()
        prefs.edit { putStringSet(KEY_BLOCKED_NUMBERS, cleaned) }
    }

    fun addBlockedNumber(number: String) {
        val cleaned = number.trim()
        if (cleaned.isEmpty()) return
        val updated = prefs.getStringSet(KEY_BLOCKED_NUMBERS, emptySet()).orEmpty().toMutableSet()
        updated.add(cleaned)
        prefs.edit { putStringSet(KEY_BLOCKED_NUMBERS, updated) }
    }

    fun removeBlockedNumber(number: String) {
        val updated = prefs.getStringSet(KEY_BLOCKED_NUMBERS, emptySet()).orEmpty().toMutableSet()
        updated.remove(number)
        prefs.edit { putStringSet(KEY_BLOCKED_NUMBERS, updated) }
    }

    fun isNumberBlocked(number: String): Boolean {
        val target = normalizePhone(number)
        if (target.isEmpty()) return false
        return getBlockedNumbers().any { blocked ->
            val normalizedBlocked = normalizePhone(blocked)
            normalizedBlocked.isNotEmpty() &&
                (target == normalizedBlocked || target.endsWith(normalizedBlocked) || normalizedBlocked.endsWith(target))
        }
    }

    fun getQuickDeclineResponses(): List<String> {
        val stored = prefs.getString(KEY_QUICK_DECLINE_RESPONSES, null)
        if (stored.isNullOrBlank()) {
            return DEFAULT_QUICK_DECLINE_RESPONSES
        }
        return stored.split(DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_QUICK_DECLINE_RESPONSES }
    }

    fun setQuickDeclineResponses(items: List<String>) {
        val cleaned = items.map { it.trim() }.filter { it.isNotEmpty() }.take(50)
        if (cleaned.isNotEmpty()) {
            saveQuickDeclineResponses(cleaned)
        }
    }

    fun addQuickDeclineResponse(message: String) {
        val cleaned = message.trim()
        if (cleaned.isEmpty()) return
        val current = getQuickDeclineResponses().toMutableList()
        if (!current.contains(cleaned)) {
            current.add(cleaned)
            saveQuickDeclineResponses(current)
        }
    }

    fun removeQuickDeclineResponse(message: String) {
        val updated = getQuickDeclineResponses().filterNot { it == message }
        saveQuickDeclineResponses(updated)
    }

    private fun saveQuickDeclineResponses(items: List<String>) {
        prefs.edit { putString(KEY_QUICK_DECLINE_RESPONSES, items.joinToString(DELIMITER)) }
    }

    // --- VIP Module 1: Smart Call Announcer & Unknown Caller Shield ---
    var announcerEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANNOUNCER_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ANNOUNCER_ENABLED, value) }

    var announcerHeadsetOnly: Boolean
        get() = prefs.getBoolean(KEY_ANNOUNCER_HEADSET_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_ANNOUNCER_HEADSET_ONLY, value) }

    var unknownShieldMode: String
        get() = prefs.getString(KEY_UNKNOWN_SHIELD_MODE, com.example.data.firebase.UserEntitlement.SHIELD_OFF)
            ?: com.example.data.firebase.UserEntitlement.SHIELD_OFF
        set(value) {
            val normalized = when (value.trim().uppercase()) {
                com.example.data.firebase.UserEntitlement.SHIELD_SILENT_RECORD ->
                    com.example.data.firebase.UserEntitlement.SHIELD_SILENT_RECORD
                com.example.data.firebase.UserEntitlement.SHIELD_AUTO_REJECT ->
                    com.example.data.firebase.UserEntitlement.SHIELD_AUTO_REJECT
                else -> com.example.data.firebase.UserEntitlement.SHIELD_OFF
            }
            prefs.edit { putString(KEY_UNKNOWN_SHIELD_MODE, normalized) }
        }

    fun recordShieldRejectedNumber(number: String) {
        val clean = normalizePhone(number)
        if (clean.isEmpty()) return
        val updated = prefs.getStringSet(KEY_SHIELD_REJECTED_NUMBERS, emptySet()).orEmpty().toMutableSet()
        updated.add(clean)
        if (updated.size > 300) {
            val trimmed = updated.toList().takeLast(300).toSet()
            prefs.edit { putStringSet(KEY_SHIELD_REJECTED_NUMBERS, trimmed) }
        } else {
            prefs.edit { putStringSet(KEY_SHIELD_REJECTED_NUMBERS, updated) }
        }
    }

    fun isRejectedByShield(number: String): Boolean {
        val target = normalizePhone(number)
        if (target.isEmpty()) return false
        val rejected = prefs.getStringSet(KEY_SHIELD_REJECTED_NUMBERS, emptySet()).orEmpty()
        return rejected.any { item ->
            item.isNotEmpty() && (
                target == item ||
                    target.endsWith(item) ||
                    item.endsWith(target) ||
                    ContactsRepository.numbersMatch(target, item)
                )
        }
    }

    // --- VIP Module 2: Auto WhatsApp & SMS Follow-up ---
    var autoSmsFollowUp: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SMS_FOLLOW_UP, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SMS_FOLLOW_UP, value) }

    var autoWhatsappFollowUp: Boolean
        get() = prefs.getBoolean(KEY_AUTO_WHATSAPP_FOLLOW_UP, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_WHATSAPP_FOLLOW_UP, value) }

    var followUpTrigger: String
        get() = prefs.getString(KEY_FOLLOW_UP_TRIGGER, com.example.data.firebase.UserEntitlement.TRIGGER_ALL_CALLS)
            ?: com.example.data.firebase.UserEntitlement.TRIGGER_ALL_CALLS
        set(value) {
            val normalized = when (value.trim().uppercase()) {
                com.example.data.firebase.UserEntitlement.TRIGGER_MISSED_ONLY ->
                    com.example.data.firebase.UserEntitlement.TRIGGER_MISSED_ONLY
                com.example.data.firebase.UserEntitlement.TRIGGER_COMPLETED_ONLY ->
                    com.example.data.firebase.UserEntitlement.TRIGGER_COMPLETED_ONLY
                else -> com.example.data.firebase.UserEntitlement.TRIGGER_ALL_CALLS
            }
            prefs.edit { putString(KEY_FOLLOW_UP_TRIGGER, normalized) }
        }

    var completedCallTemplate: String
        get() = prefs.getString(
            KEY_COMPLETED_CALL_TEMPLATE,
            com.example.data.firebase.UserEntitlement.DEFAULT_COMPLETED_CALL_TEMPLATE
        )?.ifBlank {
            com.example.data.firebase.UserEntitlement.DEFAULT_COMPLETED_CALL_TEMPLATE
        } ?: com.example.data.firebase.UserEntitlement.DEFAULT_COMPLETED_CALL_TEMPLATE
        set(value) {
            val clean = value.trim().take(500).ifBlank {
                com.example.data.firebase.UserEntitlement.DEFAULT_COMPLETED_CALL_TEMPLATE
            }
            prefs.edit { putString(KEY_COMPLETED_CALL_TEMPLATE, clean) }
        }

    var missedCallTemplate: String
        get() = prefs.getString(
            KEY_MISSED_CALL_TEMPLATE,
            com.example.data.firebase.UserEntitlement.DEFAULT_MISSED_CALL_TEMPLATE
        )?.ifBlank {
            com.example.data.firebase.UserEntitlement.DEFAULT_MISSED_CALL_TEMPLATE
        } ?: com.example.data.firebase.UserEntitlement.DEFAULT_MISSED_CALL_TEMPLATE
        set(value) {
            val clean = value.trim().take(500).ifBlank {
                com.example.data.firebase.UserEntitlement.DEFAULT_MISSED_CALL_TEMPLATE
            }
            prefs.edit { putString(KEY_MISSED_CALL_TEMPLATE, clean) }
        }

    // --- VoIP & App Call Recording (WhatsApp, WhatsApp Business, Meet, Telegram) ---
    var recordWhatsappCalls: Boolean
        get() = prefs.getBoolean(KEY_RECORD_WHATSAPP_CALLS, true)
        set(value) = prefs.edit { putBoolean(KEY_RECORD_WHATSAPP_CALLS, value) }

    var recordWhatsappBusinessCalls: Boolean
        get() = prefs.getBoolean(KEY_RECORD_WHATSAPP_BUSINESS_CALLS, true)
        set(value) = prefs.edit { putBoolean(KEY_RECORD_WHATSAPP_BUSINESS_CALLS, value) }

    var recordMeetCalls: Boolean
        get() = prefs.getBoolean(KEY_RECORD_MEET_CALLS, true)
        set(value) = prefs.edit { putBoolean(KEY_RECORD_MEET_CALLS, value) }

    var recordTelegramCalls: Boolean
        get() = prefs.getBoolean(KEY_RECORD_TELEGRAM_CALLS, true)
        set(value) = prefs.edit { putBoolean(KEY_RECORD_TELEGRAM_CALLS, value) }

    fun isVoipSourceEnabled(source: com.example.recording.CallRecordingSource): Boolean = when (source) {
        com.example.recording.CallRecordingSource.CELLULAR -> autoCallRecording
        com.example.recording.CallRecordingSource.WHATSAPP -> recordWhatsappCalls
        com.example.recording.CallRecordingSource.WHATSAPP_BUSINESS -> recordWhatsappBusinessCalls
        com.example.recording.CallRecordingSource.GOOGLE_MEET -> recordMeetCalls
        com.example.recording.CallRecordingSource.TELEGRAM -> recordTelegramCalls
    }

    /**
     * 24-hour anti-spam cooldown per phone number so the same caller does not receive
     * repeated automated follow-up messages on the same day.
     */
    fun canSendFollowUpToday(phoneNumber: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = normalizePhone(phoneNumber).takeLast(10)
        if (key.isEmpty()) return false
        val lastSentMs = prefs.getLong(KEY_FOLLOW_UP_COOLDOWN_PREFIX + key, 0L)
        if (lastSentMs <= 0L) return true
        return (nowMs - lastSentMs) >= ONE_DAY_MILLIS
    }

    fun recordFollowUpSent(phoneNumber: String, nowMs: Long = System.currentTimeMillis()) {
        val key = normalizePhone(phoneNumber).takeLast(10)
        if (key.isEmpty()) return
        prefs.edit { putLong(KEY_FOLLOW_UP_COOLDOWN_PREFIX + key, nowMs) }
    }

    private fun normalizePhone(raw: String): String = raw.filter { it.isDigit() }

    companion object {
        private const val PREFS_NAME = "pulsedial_preferences"
        const val KEY_FIRST_LAUNCH_TIME = "firstLaunchTime"
        const val KEY_LAST_SEEN_TIME = "last_seen_time_ms"
        const val KEY_RECORDING_FOLDER_URI = "recording_folder_uri"
        const val KEY_RECORDING_FOLDER_NAME = "recording_folder_name"
        private const val KEY_SOURCE_FOLDER_URI_PREFIX = "recording_folder_uri_"
        private const val KEY_SOURCE_FOLDER_NAME_PREFIX = "recording_folder_name_"
        const val KEY_IS_PREMIUM_USER = "isPremiumUser"
        const val KEY_SUBSCRIPTION_EXPIRES_AT_MS = "subscription_expires_at_ms"
        const val KEY_SERVER_CLOCK_OFFSET_MS = "server_clock_offset_ms"
        const val KEY_DEVICE_ID_HASH = "device_id_hash"
        const val KEY_SHORT_DEVICE_TAG = "short_device_tag"
        const val KEY_TRIAL_BLOCKED_BY_DEVICE = "trial_blocked_by_device"
        const val KEY_ACCOUNT_SWITCH_COUNT = "account_switch_count"
        const val KEY_ADMIN_BLOCKED_USER = "admin_blocked_user"
        const val KEY_ADMIN_BONUS_TRIAL_DAYS = "admin_bonus_trial_days"
        const val KEY_INTEGRITY_SIG = "entitlement_integrity_sig"
        const val KEY_TAMPER_DETECTED = "tamper_detected"

        const val KEY_CONFIG_TRIAL_DAYS = "config_trial_days"
        const val KEY_CONFIG_PRICE_INR = "config_price_inr"
        const val KEY_CONFIG_UPI_VPA = "config_upi_vpa"
        const val KEY_CONFIG_WHATSAPP = "config_whatsapp"
        const val KEY_CONFIG_SINGLE_DEVICE_TRIAL = "config_single_device_trial"
        const val KEY_CONFIG_ALLOW_RECORDING = "config_allow_recording"
        const val KEY_CONFIG_ALLOW_MANUAL_UTR = "config_allow_manual_utr"
        const val KEY_CONFIG_MAINTENANCE_MODE = "config_maintenance_mode"
        const val KEY_CONFIG_MAINTENANCE_MSG = "config_maintenance_msg"
        const val KEY_CONFIG_ANNOUNCEMENT = "config_announcement"
        const val KEY_CONFIG_FORCE_THEME = "config_force_theme"
        const val KEY_CONFIG_HIDE_SUBSCRIPTION_UI = "config_hide_subscription_ui"
        const val KEY_HIDE_SUBSCRIPTION_UI = "hide_subscription_ui"
        private const val KEY_DISMISSED_POPUP_IDS = "dismissed_popup_ids"
        private const val KEY_DYNAMIC_TOGGLE_PREFIX = "dynamic_toggle_"

        private const val KEY_AUTO_RECORDING = "auto_call_recording"
        private const val KEY_DIALPAD_TONES = "dialpad_tones_enabled"
        private const val KEY_USER_CUSTOM_AUTO_RECORDING = "user_custom_auto_recording"
        private const val KEY_USER_CUSTOM_DIALPAD_TONES = "user_custom_dialpad_tones"
        private const val KEY_THEME_MODE = "display_theme_mode"
        private const val KEY_BLOCKED_NUMBERS = "blocked_numbers"
        private const val KEY_QUICK_DECLINE_RESPONSES = "quick_decline_responses"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NUMBER = "user_number"
        const val KEY_IS_LOGGED_IN = "isLoggedIn"
        const val KEY_LOGGED_IN_USER_ID = "userId"
        const val KEY_RAW_ANDROID_ID = "deviceId"
        private const val KEY_INITIAL_DEVICE_REGISTERED_RTDB = "initial_device_registered_rtdb"
        private const val DELIMITER = "|||"

        const val KEY_ANNOUNCER_ENABLED = "announcer_enabled"
        const val KEY_ANNOUNCER_HEADSET_ONLY = "announcer_headset_only"
        const val KEY_UNKNOWN_SHIELD_MODE = "unknown_shield_mode"
        private const val KEY_SHIELD_REJECTED_NUMBERS = "shield_rejected_numbers"
        const val KEY_AUTO_SMS_FOLLOW_UP = "auto_sms_follow_up"
        const val KEY_AUTO_WHATSAPP_FOLLOW_UP = "auto_whatsapp_follow_up"
        const val KEY_FOLLOW_UP_TRIGGER = "follow_up_trigger"
        const val KEY_COMPLETED_CALL_TEMPLATE = "completed_call_template"
        const val KEY_MISSED_CALL_TEMPLATE = "missed_call_template"
        private const val KEY_FOLLOW_UP_COOLDOWN_PREFIX = "follow_up_cooldown_"

        const val KEY_RECORD_WHATSAPP_CALLS = "record_whatsapp_calls"
        const val KEY_RECORD_WHATSAPP_BUSINESS_CALLS = "record_whatsapp_business_calls"
        const val KEY_RECORD_MEET_CALLS = "record_meet_calls"
        const val KEY_RECORD_TELEGRAM_CALLS = "record_telegram_calls"

        const val TRIAL_DAYS = 7
        const val MONTHLY_SUBSCRIPTION_DAYS = 30
        const val EXPIRING_SOON_WINDOW_DAYS = 7
        const val ONE_MINUTE_MILLIS = 60L * 1000L
        const val ONE_HOUR_MILLIS = 60L * ONE_MINUTE_MILLIS
        const val ONE_DAY_MILLIS = 24L * ONE_HOUR_MILLIS
        const val TRIAL_DURATION_MILLIS = TRIAL_DAYS * ONE_DAY_MILLIS
        const val ONE_MONTH_MILLIS = MONTHLY_SUBSCRIPTION_DAYS.toLong() * ONE_DAY_MILLIS

        private const val DEFAULT_USER_NAME = "PulseDial User"
        const val DEFAULT_USER_NUMBER = "+91 98765 43210"

        private const val LAST_SEEN_PERSIST_INTERVAL_MS = 30_000L
        private val sealCacheLock = Any()

        @Volatile
        private var cachedVerifiedPayload: String? = null

        @Volatile
        private var cachedVerifiedSig: String? = null

        val DEFAULT_QUICK_DECLINE_RESPONSES = listOf(
            "Can't talk right now. I'll call you back.",
            "In a meeting. Will text you shortly.",
            "Driving right now, call you soon."
        )
    }
}
