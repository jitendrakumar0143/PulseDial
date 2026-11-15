package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.data.firebase.DeviceIdentityProvider
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Generates and verifies a cryptographic HMAC-SHA256 integrity seal over local entitlement,
 * trial clock, and global policy preferences so manual SharedPreferences edits (e.g., flipping
 * isPremiumUser=true or rolling back firstLaunchTime on rooted devices) are immediately detected
 * and invalidated.
 */
object EntitlementIntegrity {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "PulseDial_Entitlement_HMAC_v1"
    private const val HMAC_ALGO = "HmacSHA256"

    private val keyLock = Any()

    @Volatile
    private var cachedHmacKey: SecretKey? = null

    fun computeSignature(context: Context, payload: String): String {
        val key = getOrCreateHmacKey(context)
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(key)
        val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun verifySignature(context: Context, payload: String, storedSignature: String?): Boolean {
        if (storedSignature.isNullOrBlank()) return false
        val expected = computeSignature(context, payload)
        return constantTimeEquals(expected, storedSignature)
    }

    private fun getOrCreateHmacKey(context: Context): SecretKey {
        cachedHmacKey?.let { return it }
        synchronized(keyLock) {
            cachedHmacKey?.let { return it }
            val keystoreKey = runCatching {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                if (existing != null) return@runCatching existing

                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
                keyGen.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    ).build()
                )
                keyGen.generateKey()
            }.getOrNull()

            if (keystoreKey != null) {
                cachedHmacKey = keystoreKey
                return keystoreKey
            }

            val appSeed = "${context.packageName}:${DeviceIdentityProvider.getDeviceHash(context)}:PulseDialAntiTamperV1"
            val keyBytes = DeviceIdentityProvider.sha256Hex(appSeed).toByteArray(Charsets.UTF_8).copyOf(32)
            return SecretKeySpec(keyBytes, HMAC_ALGO).also { cachedHmacKey = it }
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
