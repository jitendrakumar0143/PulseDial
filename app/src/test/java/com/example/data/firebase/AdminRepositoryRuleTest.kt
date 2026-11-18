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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AdminRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun randomDeviceHash(): String =
        DeviceIdentityProvider.sha256Hex(UUID.randomUUID().toString())

    private suspend fun verifyCurrentUserEmailInEmulator() = withContext(Dispatchers.IO) {
        val currentUser = auth.currentUser ?: error("User not signed in")
        currentUser.sendEmailVerification().awaitTask()

        val oobUrl = URL("http://127.0.0.1:9099/emulator/v1/projects/demo-no-project/oobCodes")
        val conn = oobUrl.openConnection() as HttpURLConnection
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val regex = Regex("""oobLink"\s*:\s*"([^"]+)"""")
        val verifyLink = regex.findAll(body).map { it.groupValues[1] }.lastOrNull()
            ?: error("No OOB verification link found")
        val verifyConn = URL(verifyLink).openConnection() as HttpURLConnection
        verifyConn.inputStream.bufferedReader().use { it.readText() }
        verifyConn.disconnect()

        currentUser.reload().awaitTask()
        currentUser.getIdToken(true).awaitTask()
    }

    @Test
    fun adminManagesAllUsers_disapprovesApprovedPayment_andUpdatesGlobalSettings() = runBlocking {
        val userEmail = "member_${UUID.randomUUID()}@test.com"
        val userUid = signInTestUser(userEmail)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DialerPreferences(context)
        val trialRepo = DeviceTrialRepository(firestore, auth, prefs)
        val paymentRepo = PaymentRepository(firestore, auth)
        val adminRepo = AdminRepository(firestore, auth)
        val deviceHash = randomDeviceHash()
        val shortTag = DeviceIdentityProvider.formatShortDeviceTag(deviceHash)

        withTimeout(TIMEOUT_MS) {
            trialRepo.syncDeviceAndUserTrial(deviceHash).getOrThrow()
        }

        // Non-admin CANNOT save global app config or list all users
        val nonAdminSave = withTimeout(TIMEOUT_MS) {
            adminRepo.saveGlobalAppConfig(AppConfig(trialDays = 30))
        }
        assertTrue(nonAdminSave.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            nonAdminSave.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        val requestId = withTimeout(TIMEOUT_MS) {
            paymentRepo.submitUpiPaymentRequest(
                deviceHash = deviceHash,
                shortDeviceTag = shortTag,
                userName = "Member One",
                userEmail = userEmail,
                txnRef = "PD1700008888",
                upiTxnId = "UTR888877776666",
                approvalRefNo = "APP888",
                upiResponseStatus = "SUCCESS"
            ).getOrThrow()
        }

        // Sign in as verified Admin
        signInTestUser("Jitendrakumar888126@gmail.com")
        verifyCurrentUserEmailInEmulator()

        // 1. Admin updates global settings (all fields sync to all devices)
        withTimeout(TIMEOUT_MS) {
            adminRepo.saveGlobalAppConfig(
                AppConfig(
                    trialDays = 10L,
                    subscriptionPriceInr = 299L,
                    upiVpa = UpiPaymentHelper.UPI_VPA,
                    whatsappNumber = UpiPaymentHelper.WHATSAPP_SUPPORT_LOCAL,
                    enforceSingleDeviceTrial = true,
                    allowCallRecording = true,
                    allowManualUtr = true,
                    maintenanceMode = false,
                    maintenanceMessage = "",
                    announcementText = "Admin Broadcast",
                    dialpadTonesDefault = false,
                    defaultAutoRecording = true,
                    forceThemeMode = AppConfig.THEME_DARK
                )
            ).getOrThrow()
        }

        val loadedConfig = withTimeout(TIMEOUT_MS) {
            adminRepo.getGlobalAppConfig().getOrThrow()
        }
        assertEquals(10L, loadedConfig.trialDays)
        assertEquals("Admin Broadcast", loadedConfig.announcementText)
        assertFalse(loadedConfig.dialpadTonesDefault)
        assertTrue(loadedConfig.defaultAutoRecording)
        assertEquals(AppConfig.THEME_DARK, loadedConfig.forceThemeMode)

        // 2. Admin observes all users & approves user's payment request
        val allUsers = withTimeout(TIMEOUT_MS) {
            adminRepo.observeAllUsers().first { list -> list.any { it.userId == userUid } }
        }
        val targetUser = allUsers.first { it.userId == userUid }

        val allRequests = withTimeout(TIMEOUT_MS) {
            paymentRepo.observeAllPaymentRequestsForAdmin().first { list -> list.any { it.id == requestId } }
        }
        val targetRequest = allRequests.first { it.id == requestId }

        withTimeout(TIMEOUT_MS) {
            paymentRepo.approvePaymentRequest(targetRequest, "Approved by Admin").getOrThrow()
        }
        val approvedUser = withTimeout(TIMEOUT_MS) {
            adminRepo.observeAllUsers().first { list ->
                list.any { it.userId == userUid && it.isPremiumUser && it.subscriptionExpiresAt != null }
            }
        }.first { it.userId == userUid }
        assertTrue(approvedUser.subscriptionExpiresAt!!.toDate().time > System.currentTimeMillis())

        // 3. Admin can subsequently disapprove the previously approved payment request -> revokes pro access
        withTimeout(TIMEOUT_MS) {
            paymentRepo.rejectPaymentRequest(targetRequest, "Disapproved after audit").getOrThrow()
        }
        val rejectedAfter = withTimeout(TIMEOUT_MS) {
            paymentRepo.observeAllPaymentRequestsForAdmin()
                .first { list -> list.any { it.id == requestId && it.status == PaymentRequest.STATUS_REJECTED } }
        }
        assertEquals(PaymentRequest.STATUS_REJECTED, rejectedAfter.first { it.id == requestId }.status)
        val revokedUser = withTimeout(TIMEOUT_MS) {
            adminRepo.observeAllUsers().first { list ->
                list.any { it.userId == userUid && !it.isPremiumUser && it.subscriptionExpiresAt == null }
            }
        }.first { it.userId == userUid }
        assertFalse(revokedUser.isPremiumUser)

        // 4. Admin grants +7 bonus days, hides subscription UI for user, and blocks user
        withTimeout(TIMEOUT_MS) {
            adminRepo.updateUserAdminDetails(
                user = targetUser,
                isPremium = false,
                isBlocked = true,
                bonusTrialDays = 7L,
                adminNote = "Account paused",
                hideSubscriptionUI = true
            ).getOrThrow()
        }

        // 5. Admin saves dynamic navigation tabs, settings items & live broadcast popup
        withTimeout(TIMEOUT_MS) {
            adminRepo.saveNavigationTabs(
                NavigationTabItem.defaultTabs() + NavigationTabItem(
                    id = "custom_offers",
                    title = "Offers",
                    iconKey = NavigationTabItem.ICON_ANNOUNCEMENT,
                    enabled = true,
                    order = 5,
                    type = NavigationTabItem.TYPE_CUSTOM_BANNER,
                    customTitle = "Special Deal",
                    customBody = "Save on PulseDial Pro"
                )
            ).getOrThrow()
            adminRepo.saveDynamicSettings(DynamicSettingItem.defaultItems()).getOrThrow()
            adminRepo.saveLiveBroadcast(
                LiveBroadcast(
                    showPopup = true,
                    popupId = "pop_rule_test",
                    title = "Live Update",
                    message = "Instant broadcast verified",
                    buttonText = "OK",
                    isCancelable = true,
                    showBannerAd = true,
                    bannerText = "Ad Strip Active"
                )
            ).getOrThrow()
        }

        val loadedTabs = withTimeout(TIMEOUT_MS) { adminRepo.observeNavigationTabs().first() }
        assertEquals(6, loadedTabs.tabs.size)
        val loadedBroadcast = withTimeout(TIMEOUT_MS) { adminRepo.observeLiveBroadcast().first() }
        assertTrue(loadedBroadcast.showPopup)
        assertEquals("pop_rule_test", loadedBroadcast.popupId)

        // Restore default global settings (7 days) & clear popup so shared emulator remains clean
        withTimeout(TIMEOUT_MS) {
            adminRepo.saveLiveBroadcast(LiveBroadcast()).getOrThrow()
            adminRepo.saveNavigationTabs(NavigationTabItem.defaultTabs()).getOrThrow()
            adminRepo.saveGlobalAppConfig(AppConfig()).getOrThrow()
        }

        // Verify member receives blocked status on sync
        signInTestUser(userEmail)
        val memberSync = withTimeout(TIMEOUT_MS) {
            trialRepo.syncDeviceAndUserTrial(deviceHash).getOrThrow()
        }
        assertTrue(memberSync.isBlocked)
        assertFalse(memberSync.hasRecordingAccess)
        assertEquals(7L, memberSync.bonusTrialDays)

        // Unauthenticated caller fails fast
        auth.signOut()
        assertTrue(adminRepo.getGlobalAppConfig().isFailure)
    }

    @Test
    fun adminQueriesFirestore_forUsersExpiringWithinSevenDays() = runBlocking {
        val baseNowMs = System.currentTimeMillis()
        val adminRepo = AdminRepository(firestore, auth)

        // Create 5 distinct member accounts & sync their initial entitlements
        val member2DaysEmail = "exp2d_${UUID.randomUUID()}@test.com"
        val uid2Days = signInTestUser(member2DaysEmail)
        val prefs = DialerPreferences(ApplicationProvider.getApplicationContext())
        DeviceTrialRepository(firestore, auth, prefs)
            .syncDeviceAndUserTrial(randomDeviceHash()).getOrThrow()

        val member6DaysEmail = "exp6d_${UUID.randomUUID()}@test.com"
        val uid6Days = signInTestUser(member6DaysEmail)
        DeviceTrialRepository(firestore, auth, prefs)
            .syncDeviceAndUserTrial(randomDeviceHash()).getOrThrow()

        val member15DaysEmail = "exp15d_${UUID.randomUUID()}@test.com"
        val uid15Days = signInTestUser(member15DaysEmail)
        DeviceTrialRepository(firestore, auth, prefs)
            .syncDeviceAndUserTrial(randomDeviceHash()).getOrThrow()

        val memberExpiredEmail = "expired_${UUID.randomUUID()}@test.com"
        val uidExpired = signInTestUser(memberExpiredEmail)
        DeviceTrialRepository(firestore, auth, prefs)
            .syncDeviceAndUserTrial(randomDeviceHash()).getOrThrow()

        val memberBlockedEmail = "blocked_${UUID.randomUUID()}@test.com"
        val uidBlocked = signInTestUser(memberBlockedEmail)
        DeviceTrialRepository(firestore, auth, prefs)
            .syncDeviceAndUserTrial(randomDeviceHash()).getOrThrow()

        // Non-admin member MUST be denied permission to run the expiring-subscriptions query
        val nonAdminResult = withTimeout(TIMEOUT_MS) {
            adminRepo.fetchUsersExpiringWithinDays(days = 7, nowMs = baseNowMs)
        }
        assertTrue(nonAdminResult.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            nonAdminResult.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        // Sign in as verified Admin and set exact subscriptionExpiresAt timestamps
        signInTestUser("Jitendrakumar888126@gmail.com")
        verifyCurrentUserEmailInEmulator()

        val allUsers = withTimeout(TIMEOUT_MS) {
            adminRepo.observeAllUsers().first { list ->
                val ids = list.map { it.userId }.toSet()
                ids.containsAll(listOf(uid2Days, uid6Days, uid15Days, uidExpired, uidBlocked))
            }
        }
        val byUid = allUsers.associateBy { it.userId }

        fun tsDaysOffset(days: Long) =
            com.google.firebase.Timestamp(java.util.Date(baseNowMs + days * DialerPreferences.ONE_DAY_MILLIS))

        withTimeout(TIMEOUT_MS) {
            adminRepo.updateUserAdminDetails(
                user = byUid.getValue(uid2Days),
                isPremium = true,
                isBlocked = false,
                bonusTrialDays = 0L,
                adminNote = "Expiring in 2d",
                subscriptionExpiresAt = tsDaysOffset(2L)
            ).getOrThrow()
            adminRepo.updateUserAdminDetails(
                user = byUid.getValue(uid6Days),
                isPremium = true,
                isBlocked = false,
                bonusTrialDays = 0L,
                adminNote = "Expiring in 6d",
                subscriptionExpiresAt = tsDaysOffset(6L)
            ).getOrThrow()
            adminRepo.updateUserAdminDetails(
                user = byUid.getValue(uid15Days),
                isPremium = true,
                isBlocked = false,
                bonusTrialDays = 0L,
                adminNote = "Healthy 15d",
                subscriptionExpiresAt = tsDaysOffset(15L)
            ).getOrThrow()
            adminRepo.updateUserAdminDetails(
                user = byUid.getValue(uidExpired),
                isPremium = true,
                isBlocked = false,
                bonusTrialDays = 0L,
                adminNote = "Already expired",
                subscriptionExpiresAt = tsDaysOffset(-1L)
            ).getOrThrow()
            adminRepo.updateUserAdminDetails(
                user = byUid.getValue(uidBlocked),
                isPremium = true,
                isBlocked = true,
                bonusTrialDays = 0L,
                adminNote = "Blocked 3d",
                subscriptionExpiresAt = tsDaysOffset(3L)
            ).getOrThrow()
        }

        // Firestore range query returns ONLY the active non-blocked users expiring in (now, now + 7d], ordered soonest-first
        val fetched = withTimeout(TIMEOUT_MS) {
            adminRepo.fetchUsersExpiringWithinDays(days = 7, nowMs = baseNowMs).getOrThrow()
        }
        val testUserIds = setOf(uid2Days, uid6Days, uid15Days, uidExpired, uidBlocked)
        assertEquals(
            listOf(uid2Days, uid6Days),
            fetched.map { it.userId }.filter { it in testUserIds }
        )

        // Real-time SnapshotListener query also emits ONLY [uid2Days, uid6Days] in ascending order
        val observed = withTimeout(TIMEOUT_MS) {
            adminRepo.observeUsersExpiringWithinDays(days = 7, nowMs = baseNowMs)
                .first { list -> list.any { it.userId == uid2Days } && list.any { it.userId == uid6Days } }
        }
        assertEquals(
            listOf(uid2Days, uid6Days),
            observed.map { it.userId }.filter { it in testUserIds }
        )

        // All Active Subscribers query returns [uid2Days, uid6Days, uid15Days] (excludes expired and blocked)
        val allActiveFetched = withTimeout(TIMEOUT_MS) {
            adminRepo.fetchAllActiveSubscribers(nowMs = baseNowMs).getOrThrow()
        }
        assertEquals(
            listOf(uid2Days, uid6Days, uid15Days),
            allActiveFetched.map { it.userId }.filter { it in testUserIds }
        )

        val allActiveObserved = withTimeout(TIMEOUT_MS) {
            adminRepo.observeAllActiveSubscribers(nowMs = baseNowMs)
                .first { list -> list.any { it.userId == uid15Days } }
        }
        assertEquals(
            listOf(uid2Days, uid6Days, uid15Days),
            allActiveObserved.map { it.userId }.filter { it in testUserIds }
        )

        // Renewing uid2Days by 30 days moves it outside the 7-day expiring window immediately
        withTimeout(TIMEOUT_MS) {
            adminRepo.renewUserMonthlySubscription(byUid.getValue(uid2Days), extraDays = 30L).getOrThrow()
        }
        val afterRenewal = withTimeout(TIMEOUT_MS) {
            adminRepo.fetchUsersExpiringWithinDays(days = 7, nowMs = baseNowMs).getOrThrow()
        }
        assertEquals(
            listOf(uid6Days),
            afterRenewal.map { it.userId }.filter { it in testUserIds }
        )
    }

    @Test
    fun adminCanCreateAndDeleteRegularUserInFirestore() = runBlocking {
        val adminRepo = AdminRepository(firestore, auth)
        signInTestUser("Jitendrakumar888126@gmail.com")
        verifyCurrentUserEmailInEmulator()

        val created = withTimeout(TIMEOUT_MS) {
            adminRepo.createUserByAdmin(
                displayName = "Added By Admin",
                email = "added_${UUID.randomUUID()}@example.com",
                phoneNumber = "+919876543211",
                isPremium = true,
                subscriptionDays = 30L,
                bonusTrialDays = 0L,
                autoCallRecording = true,
                hideSubscriptionUI = false,
                adminNote = "Emulator Admin Add Test",
                actingAdminEmail = "Jitendrakumar888126@gmail.com"
            ).getOrThrow()
        }

        val usersAfterAdd = withTimeout(TIMEOUT_MS) {
            adminRepo.observeAllUsers().first { list -> list.any { it.userId == created.userId } }
        }
        assertTrue(usersAfterAdd.any { it.userId == created.userId && it.isPremiumUser })

        withTimeout(TIMEOUT_MS) {
            adminRepo.deleteUserByAdmin(
                user = created,
                actingAdminEmail = "Jitendrakumar888126@gmail.com"
            ).getOrThrow()
        }

        val usersAfterDelete = withTimeout(TIMEOUT_MS) {
            adminRepo.observeAllUsers().first { list -> list.none { it.userId == created.userId } }
        }
        assertFalse(usersAfterDelete.any { it.userId == created.userId })
    }

    private companion object {
        const val TIMEOUT_MS = 8000L
    }
}
