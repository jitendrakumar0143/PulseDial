package com.example.data.firebase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.billing.UpiPaymentHelper
import com.example.data.DialerPreferences
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class PaymentRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun randomDeviceHash(): String =
        DeviceIdentityProvider.sha256Hex(UUID.randomUUID().toString())

    private suspend fun verifyCurrentUserEmailInEmulator() = withContext(Dispatchers.IO) {
        val currentUser = auth.currentUser ?: error("User not signed in")
        currentUser.sendEmailVerification().awaitTask()

        // Fetch generated OOB code from Firebase Auth Emulator and apply it
        val oobUrl = URL("http://127.0.0.1:9099/emulator/v1/projects/demo-no-project/oobCodes")
        val conn = oobUrl.openConnection() as HttpURLConnection
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val regex = Regex("""oobLink"\s*:\s*"([^"]+)"""")
        val matches = regex.findAll(body).map { it.groupValues[1] }.toList()
        val verifyLink = matches.lastOrNull() ?: error("No OOB verification link found")
        val verifyConn = URL(verifyLink).openConnection() as HttpURLConnection
        verifyConn.inputStream.bufferedReader().use { it.readText() }
        verifyConn.disconnect()

        currentUser.reload().awaitTask()
        currentUser.getIdToken(true).awaitTask()
    }

    @Test
    fun userSubmitsUpiPayment_nonAdminCannotApprove_adminApprovesAndUnlocksAccess() = runBlocking {
        val userEmail = "payer_${UUID.randomUUID()}@test.com"
        val userUid = signInTestUser(userEmail)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DialerPreferences(context)
        val trialRepo = DeviceTrialRepository(firestore, auth, prefs)
        val paymentRepo = PaymentRepository(firestore, auth)
        val deviceHash = randomDeviceHash()
        val shortTag = DeviceIdentityProvider.formatShortDeviceTag(deviceHash)

        withTimeout(TIMEOUT_MS) {
            org.robolectric.shadows.ShadowLog.stream = System.out
            println("STEP 1: syncDeviceAndUserTrial")
            trialRepo.syncDeviceAndUserTrial(deviceHash).getOrThrow()
        }

        println("STEP 2: submitUpiPaymentRequest")
        val requestId = withTimeout(TIMEOUT_MS) {
            paymentRepo.submitUpiPaymentRequest(
                deviceHash = deviceHash,
                shortDeviceTag = shortTag,
                userName = "Payer User",
                userEmail = userEmail,
                txnRef = "PD1700001111",
                upiTxnId = "412345678901",
                approvalRefNo = "APP001",
                upiResponseStatus = "SUCCESS"
            ).getOrThrow()
        }
        assertTrue(requestId.isNotBlank())

        val myRequests = withTimeout(TIMEOUT_MS) {
            paymentRepo.observeMyPaymentRequests().first { list -> list.any { it.id == requestId } }
        }
        val submitted = myRequests.first { it.id == requestId }
        assertEquals(PaymentRequest.STATUS_PENDING, submitted.status)
        assertEquals(UpiPaymentHelper.UPI_VPA, submitted.upiId)

        // Verify user's document in Firestore was immediately marked subscriptionStatus == "pending_approval"
        val pendingEntitlement = withTimeout(TIMEOUT_MS) {
            trialRepo.observeUserEntitlement().first { it?.subscriptionStatus == UserEntitlement.STATUS_PENDING_APPROVAL }
        }
        assertEquals(UserEntitlement.STATUS_PENDING_APPROVAL, pendingEntitlement?.subscriptionStatus)

        // Verify SettingsManager real-time preference sync succeeds under security rules
        val settingsManager = SettingsManager(firestore, auth, prefs)
        settingsManager.setAutoCallRecording(false)
        settingsManager.setThemeMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        settingsManager.setDialpadTonesEnabled(false)
        settingsManager.addBlockedNumber("+91 98765 43210")
        withTimeout(TIMEOUT_MS) {
            settingsManager.syncAllLocalPreferencesToCloud().getOrThrow()
        }
        val syncedEntitlement = withTimeout(TIMEOUT_MS) {
            trialRepo.observeUserEntitlement().first {
                it?.themeMode == UserEntitlement.THEME_DARK && !it.autoCallRecording && !it.dialpadTonesEnabled
            }
        }
        assertEquals(UserEntitlement.THEME_DARK, syncedEntitlement?.themeMode)
        assertEquals(false, syncedEntitlement?.autoCallRecording)
        assertEquals(false, syncedEntitlement?.dialpadTonesEnabled)

        // Regular user MUST be blocked from approving their own payment request
        val selfApproveResult = withTimeout(TIMEOUT_MS) {
            paymentRepo.approvePaymentRequest(submitted, "Self")
        }
        assertTrue(selfApproveResult.isFailure)
        val selfError = selfApproveResult.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, selfError?.code)

        // Verified admin signs in, sees the request, and approves it
        signInTestUser("Jitendrakumar888126@gmail.com")
        verifyCurrentUserEmailInEmulator()

        val adminList = withTimeout(TIMEOUT_MS) {
            paymentRepo.observeAllPaymentRequestsForAdmin().first { list -> list.any { it.id == requestId } }
        }
        assertTrue(adminList.any { it.id == requestId && it.status == PaymentRequest.STATUS_PENDING })

        val approveResult = withTimeout(TIMEOUT_MS) {
            paymentRepo.approvePaymentRequest(submitted, "Verified in bank app")
        }
        assertTrue(approveResult.isSuccess)

        // Verify user & device are now unlocked for 30 days with subscriptionStatus == "active"
        signInTestUser(userEmail)
        val approvedEntitlement = withTimeout(TIMEOUT_MS) {
            trialRepo.observeUserEntitlement().first { it?.subscriptionStatus == UserEntitlement.STATUS_ACTIVE }
        }
        assertEquals(UserEntitlement.STATUS_ACTIVE, approvedEntitlement?.subscriptionStatus)
        assertEquals(true, approvedEntitlement?.isPremium)

        val refreshed = withTimeout(TIMEOUT_MS) {
            trialRepo.syncDeviceAndUserTrial(deviceHash).getOrThrow()
        }
        assertEquals(userUid.isNotEmpty(), true)
        assertEquals(TrialStatus.PREMIUM_ACTIVE, refreshed.status)
        assertTrue(refreshed.isPremium)
        assertTrue(refreshed.hasRecordingAccess)
        assertTrue(refreshed.subscriptionExpiresAtMs > refreshed.serverNowMs)
    }

    @Test
    fun unauthenticatedPaymentObservation_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val paymentRepo = PaymentRepository(firestore, auth)
        val result = paymentRepo.submitUpiPaymentRequest(
            deviceHash = randomDeviceHash(),
            shortDeviceTag = "DEV-0000-0000",
            userName = "Test",
            userEmail = "",
            txnRef = "PD123456",
            upiTxnId = "",
            approvalRefNo = "",
            upiResponseStatus = "SUCCESS"
        )
        assertTrue(result.isFailure)
    }

    private companion object {
        const val TIMEOUT_MS = 8000L
    }
}
