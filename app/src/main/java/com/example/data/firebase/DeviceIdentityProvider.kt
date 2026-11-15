package com.example.data.firebase

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Generates a Play-Policy-compliant, non-PII, persistent 64-char SHA-256 device fingerprint.
 * Combines Settings.Secure.ANDROID_ID (app-signing-key-scoped, survives reinstalls, data clears,
 * and Google account switches) with stable Build hardware properties.
 */
object DeviceIdentityProvider {

    private const val FINGERPRINT_SALT = "pulsedial_device_trial_v1"

    @Volatile
    private var cachedDeviceHash: String? = null

    @SuppressLint("HardwareIds")
    fun getDeviceHash(context: Context): String {
        cachedDeviceHash?.let { return it }
        val appContext = context.applicationContext
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().trim()

        val hardwareSeed = listOf(
            Build.MANUFACTURER.orEmpty(),
            Build.MODEL.orEmpty(),
            Build.DEVICE.orEmpty(),
            Build.BOARD.orEmpty(),
            Build.HARDWARE.orEmpty()
        ).joinToString(":")

        val rawSeed = buildString {
            append(FINGERPRINT_SALT)
            append('|')
            if (androidId.isNotEmpty()) {
                append("aid:").append(androidId)
            }
            append('|')
            append("hw:").append(hardwareSeed)
            if (androidId.isEmpty()) {
                append(':').append(Build.FINGERPRINT.orEmpty())
            }
        }
        return sha256Hex(rawSeed).also { cachedDeviceHash = it }
    }

    fun formatShortDeviceTag(deviceHash: String): String {
        val clean = deviceHash.uppercase()
        if (clean.length < 8) return "DEV-$clean"
        return "DEV-${clean.substring(0, 4)}-${clean.substring(4, 8)}"
    }

    internal fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
