package com.example.data.firebase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.billing.BillingManager
import com.example.data.DialerPreferences
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.UUID

class DeviceTrialRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun randomDeviceHash(): String =
        DeviceIdentityProvider.sha256Hex(UUID.randomUUID().toString())

    @Test
    fun newDeviceAndUser_startsSevenDayFreeTrial() = runBlocking {
        signInTestUser("alice_${UUID.randomUUID()}@test.com")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DialerPreferences(context)
        prefs.applyAppConfig(AppConfig())
        prefs.isPremiumUser = false
        prefs.adminBlockedUser = false
        prefs.adminBonusTrialDays = 0
        prefs.trialBlockedByDevice = false
        prefs.firstLaunchTime = System.currentTimeMillis()
        val repo = DeviceTrialRepository(firestore, auth, prefs)
        val deviceHash = randomDeviceHash()

        val result = withTimeout(TIMEOUT_MS) {
            repo.syncDeviceAndUserTrial(deviceHash)
        }
        assertTrue(result.isSuccess)
        val verification = result.getOrThrow()
        assertEquals(TrialStatus.FREE_TRIAL_ACTIVE, verification.status)
        assertTrue(verification.hasRecordingAccess)
        assertFalse(verification.isPremium)
        assertEquals(7, verification.daysRemaining)
    }

    @Test
    fun secondAccountOnExpiredDevice_isBlockedFromReusingTrial() = runBlocking {
        val aliceUid = signInTestUser("alice_${UUID.randomUUID()}@test.com")
        val deviceHash = randomDeviceHash()
        val nineDaysAgo = Timestamp(Date(System.currentTimeMillis() - 9L * DialerPreferences.ONE_DAY_MILLIS))

        // Seed an expired device trial from 9 days ago using Alice's UID via emulator setup
        withTimeout(TIMEOUT_MS) {
            firestore.collection(DeviceTrialRepository.COLLECTION_DEVICE_TRIALS)
                .document(deviceHash)
                .set(
                    mapOf(
                        "deviceHash" to deviceHash,
                        "ownerUid" to aliceUid,
                        "lastUserId" to aliceUid,
                        "accountSwitchCount" to 0L,
                        "trialStartedAt" to nineDaysAgo,
                        "lastVerifiedAt" to nineDaysAgo,
                        "isPremiumDevice" to false,
                        "updatedAt" to nineDaysAgo
                    )
                )
                .awaitTask()
        }

        // Now Bob signs in on the SAME device after clearing local app data
        signInTestUser("bob_${UUID.randomUUID()}@test.com")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DialerPreferences(context)
        prefs.applyAppConfig(AppConfig())
        prefs.isPremiumUser = false
        prefs.adminBlockedUser = false
        prefs.adminBonusTrialDays = 0
        prefs.trialBlockedByDevice = false
        val bobRepo = DeviceTrialRepository(firestore, auth, prefs)

        val result = withTimeout(TIMEOUT_MS) {
            bobRepo.syncDeviceAndUserTrial(deviceHash)
        }
        assertTrue(result.isSuccess)
        val verification = result.getOrThrow()
        assertEquals(TrialStatus.DEVICE_TRIAL_ALREADY_USED, verification.status)
        assertFalse(verification.hasRecordingAccess)
        assertEquals(0, verification.daysRemaining)
        assertEquals(1L, verification.accountSwitchCount)
    }

    @Test
    fun crossUserEntitlementAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser("alice_${UUID.randomUUID()}@test.com")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DialerPreferences(context)
        val aliceRepo = DeviceTrialRepository(firestore, auth, prefs)
        val deviceHash = randomDeviceHash()

        withTimeout(TIMEOUT_MS) {
            aliceRepo.syncDeviceAndUserTrial(deviceHash).getOrThrow()
        }

        signInTestUser("bob_${UUID.randomUUID()}@test.com")
        var caught: Throwable? = null
        try {
            withTimeout(TIMEOUT_MS) {
                firestore.collection(DeviceTrialRepository.COLLECTION_USERS)
                    .document(aliceUid)
                    .get()
                    .awaitTask()
            }
        } catch (t: Throwable) {
            caught = t
        }

        val fsError = caught?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError?.code)
    }

    @Test
    fun unauthenticatedSync_failsFastWithoutQueryingFirestore() = runBlocking {
        auth.signOut()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DeviceTrialRepository(firestore, auth, DialerPreferences(context))

        val result = repo.syncDeviceAndUserTrial(randomDeviceHash())
        assertTrue(result.isFailure)
    }

    @Test
    fun recordPremiumPurchase_nonAdminSelfPromotion_isRejectedWithPermissionDenied() = runBlocking {
        signInTestUser("buyer_${UUID.randomUUID()}@test.com")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DialerPreferences(context)
        prefs.isPremiumUser = false
        val repo = DeviceTrialRepository(firestore, auth, prefs)
        val deviceHash = randomDeviceHash()

        withTimeout(TIMEOUT_MS) {
            repo.syncDeviceAndUserTrial(deviceHash).getOrThrow()
        }

        // Non-admin client MUST NOT be allowed to set isPremiumUser or isPremiumDevice directly
        val selfPromotion = withTimeout(TIMEOUT_MS) {
            repo.recordPremiumPurchase(
                deviceHash = deviceHash,
                productId = BillingManager.PRODUCT_ID_MONTHLY_PREMIUM,
                purchaseToken = "play_token_xyz"
            )
        }
        assertTrue(selfPromotion.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            selfPromotion.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        val refreshed = withTimeout(TIMEOUT_MS) {
            repo.syncDeviceAndUserTrial(deviceHash).getOrThrow()
        }
        assertEquals(TrialStatus.FREE_TRIAL_ACTIVE, refreshed.status)
        assertFalse(refreshed.isPremium)
    }

    private companion object {
        const val TIMEOUT_MS = 5000L
    }
}
