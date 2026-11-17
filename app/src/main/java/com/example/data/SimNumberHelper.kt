package com.example.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.data.firebase.GoogleAuthHelper
import com.example.data.firebase.RealtimeDatabaseSync

/**
 * Safely reads the active device SIM phone number when runtime permissions are granted and
 * auto-fills [DialerPreferences.userPhoneNumber] + syncs it to `/users/{uid}` in RTDB.
 */
object SimNumberHelper {

    fun hasPhoneNumberPermission(context: Context): Boolean {
        val readPhoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_NUMBERS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        val readPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        return readPhoneNumbers || readPhoneState
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun resolveDeviceSimNumber(context: Context): String? {
        if (!hasPhoneNumberPermission(context)) return null

        val subscriptionManager = runCatching {
            context.getSystemService(SubscriptionManager::class.java)
        }.getOrNull()

        if (subscriptionManager != null) {
            val activeSubs = runCatching { subscriptionManager.activeSubscriptionInfoList }
                .getOrNull()
                .orEmpty()
            for (subInfo in activeSubs) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val api33Num = runCatching {
                        subscriptionManager.getPhoneNumber(subInfo.subscriptionId)
                    }.getOrNull()?.trim()
                    val formatted = cleanPhoneNumber(api33Num)
                    if (formatted != null) return formatted
                }
                @Suppress("DEPRECATION")
                val legacySubNum = runCatching { subInfo.number }.getOrNull()?.trim()
                val formattedLegacy = cleanPhoneNumber(legacySubNum)
                if (formattedLegacy != null) return formattedLegacy
            }
        }

        val telephonyManager = runCatching {
            context.getSystemService(TelephonyManager::class.java)
        }.getOrNull()
        if (telephonyManager != null) {
            @Suppress("DEPRECATION")
            val line1 = runCatching { telephonyManager.line1Number }.getOrNull()?.trim()
            val formattedLine1 = cleanPhoneNumber(line1)
            if (formattedLine1 != null) return formattedLine1
        }
        return null
    }

    fun cleanPhoneNumber(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 7) return null
        if (digits.all { it == '0' } || digits == "1234567890") return null
        return if (trimmed.startsWith("+")) {
            "+$digits"
        } else if (digits.length == 10) {
            "+91 $digits"
        } else {
            digits
        }
    }

    /**
     * Populates [DialerPreferences.userPhoneNumber] with the device's SIM number if the saved
     * value is blank or the placeholder [DialerPreferences.DEFAULT_USER_NUMBER] (or when
     * [forceOverwrite] is true), and syncs `"phone"`/`"phoneNumber"` to RTDB `/users/{uid}`.
     *
     * Returns the newly resolved SIM number if updated, or null otherwise.
     */
    fun autoFillAndSyncSimNumber(
        context: Context,
        forceOverwrite: Boolean = false
    ): String? {
        val appContext = context.applicationContext
        val prefs = DialerPreferences(appContext)
        val current = prefs.userPhoneNumber.trim()
        val needsFill = forceOverwrite ||
            current.isBlank() ||
            current == DialerPreferences.DEFAULT_USER_NUMBER

        if (!needsFill) return null

        val simNumber = resolveDeviceSimNumber(appContext) ?: return null
        prefs.userPhoneNumber = simNumber

        val uid = GoogleAuthHelper.resolveActiveUid(appContext)
        if (!uid.isNullOrBlank()) {
            RealtimeDatabaseSync.pushUserFields(
                uid = uid,
                fields = mapOf(
                    "phone" to simNumber,
                    "phoneNumber" to simNumber,
                    "updatedAtMs" to System.currentTimeMillis()
                ),
                context = appContext
            )
        }
        return simNumber
    }
}
