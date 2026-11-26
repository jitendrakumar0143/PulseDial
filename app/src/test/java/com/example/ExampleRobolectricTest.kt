package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.DialerPreferences
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PulseDial", appName)
    }

    @Test
    fun `dialer preferences persist settings, folder uri, and blocked numbers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DialerPreferences(context)

        prefs.autoCallRecording = true
        assertTrue(prefs.autoCallRecording)

        prefs.recordingFolderUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        prefs.recordingFolderName = "Music"
        assertEquals("Music", prefs.recordingFolderName)

        prefs.addBlockedNumber("+91 98765 00000")
        assertTrue(prefs.isNumberBlocked("9876500000"))

        prefs.removeBlockedNumber("+91 98765 00000")
        assertFalse(prefs.isNumberBlocked("9876500000"))
    }

    @Test
    fun `seven day trial tracking enforces expiration and premium unlock`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DialerPreferences(context)

        val startTime = 1_700_000_000_000L
        prefs.firstLaunchTime = startTime
        prefs.isPremiumUser = false

        // Day 0 -> 7 days remaining, recording stays strictly Premium-only
        assertFalse(prefs.isTrialExpired(startTime))
        assertEquals(7, prefs.getTrialDaysRemaining(startTime))
        assertFalse(prefs.hasRecordingAccess(startTime))

        // Day 5 -> 2 days remaining, recording stays strictly Premium-only
        val dayFive = startTime + (5L * DialerPreferences.ONE_DAY_MILLIS)
        assertFalse(prefs.isTrialExpired(dayFive))
        assertEquals(2, prefs.getTrialDaysRemaining(dayFive))
        assertFalse(prefs.hasRecordingAccess(dayFive))

        // Day 8 -> expired, recording locked for free user
        val dayEight = startTime + (8L * DialerPreferences.ONE_DAY_MILLIS)
        assertTrue(prefs.isTrialExpired(dayEight))
        assertEquals(0, prefs.getTrialDaysRemaining(dayEight))
        assertFalse(prefs.hasRecordingAccess(dayEight))

        // Purchasing subscription unlocks permanently even after trial expiration
        prefs.isPremiumUser = true
        assertTrue(prefs.hasRecordingAccess(dayEight))
    }

    @Test
    fun `t9 matcher matches contact names and phone numbers`() {
        val contacts = listOf(
            com.example.data.ContactEntry(1L, "Alice", "+91 98765 43210", true),
            com.example.data.ContactEntry(2L, "Jitendra", "+91 91234 56789", false)
        )

        // 254 -> ALI (Alice)
        val nameMatches = com.example.ui.dialpad.T9Matcher.matchContacts("254", contacts)
        assertEquals(1, nameMatches.size)
        assertEquals("Alice", nameMatches.first().name)

        // 56789 -> number match for Jitendra
        val numMatches = com.example.ui.dialpad.T9Matcher.matchContacts("56789", contacts)
        assertEquals(1, numMatches.size)
        assertEquals("Jitendra", numMatches.first().name)
    }

    @Test
    fun `numbers match handles country code and leading zero formats`() {
        assertTrue(com.example.data.ContactsRepository.numbersMatch("919876543210", "09876543210"))
        assertTrue(com.example.data.ContactsRepository.numbersMatch("9876543210", "919876543210"))
        assertFalse(com.example.data.ContactsRepository.numbersMatch("9876543210", "9876543211"))
    }

    @Test
    fun `device identity provider returns deterministic 64 char sha256 hex and tag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hash1 = com.example.data.firebase.DeviceIdentityProvider.getDeviceHash(context)
        val hash2 = com.example.data.firebase.DeviceIdentityProvider.getDeviceHash(context)

        assertEquals(64, hash1.length)
        assertEquals(hash1, hash2)
        assertTrue(hash1.matches(Regex("^[a-f0-9]{64}$")))

        val tag = com.example.data.firebase.DeviceIdentityProvider.formatShortDeviceTag(hash1)
        assertTrue(tag.startsWith("DEV-"))
        assertEquals(13, tag.length)
    }

    @Test
    fun `cloud verification blocks local clock tampering and device reuse`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DialerPreferences(context)
        val trialStart = 1_700_000_000_000L
        val realServerNow = trialStart + 8L * DialerPreferences.ONE_DAY_MILLIS
        val tamperedLocalClock = trialStart + 1_000L // user set device clock back to Day 0!

        val verification = com.example.data.firebase.TrialVerificationResult(
            status = com.example.data.firebase.TrialStatus.DEVICE_TRIAL_ALREADY_USED,
            deviceHash = "a".repeat(64),
            shortDeviceTag = "DEV-AAAA-AAAA",
            daysRemaining = 0,
            isPremium = false,
            hasRecordingAccess = false,
            trialStartedAtMs = trialStart,
            serverNowMs = realServerNow,
            accountSwitchCount = 1L
        )

        prefs.applyCloudVerification(verification, "Test User", "test@example.com", localNowMs = tamperedLocalClock)

        // Even though local clock claims Day 0, serverClockOffsetMs enforces real Day 8 expiry!
        assertTrue(prefs.trialBlockedByDevice)
        assertTrue(prefs.isTrialExpired(tamperedLocalClock))
        assertEquals(0, prefs.getTrialDaysRemaining(tamperedLocalClock))
        assertFalse(prefs.hasRecordingAccess(tamperedLocalClock))
    }

    @Test
    fun `history search filters call logs by contact name and phone number`() {
        val calls = listOf(
            com.example.data.CallLogEntry(1L, "Alice Sharma", "+91 98765 43210", android.provider.CallLog.Calls.INCOMING_TYPE, 1_700_000_000_000L, 45L),
            com.example.data.CallLogEntry(2L, null, "+91 91234 56789", android.provider.CallLog.Calls.OUTGOING_TYPE, 1_700_000_010_000L, 12L),
            com.example.data.CallLogEntry(3L, "Bob Verma", "022-4000-1234", android.provider.CallLog.Calls.MISSED_TYPE, 1_700_000_020_000L, 0L)
        )

        // Blank query returns all entries
        assertEquals(3, com.example.ui.history.HistoryFragment.filterCallLogs(calls, "   ").size)

        // Case-insensitive contact name search
        val byName = com.example.ui.history.HistoryFragment.filterCallLogs(calls, "alice")
        assertEquals(1, byName.size)
        assertEquals(1L, byName.first().id)

        // Digit-only search ignores spaces/hyphens in stored phone number
        val byDigits = com.example.ui.history.HistoryFragment.filterCallLogs(calls, "912345")
        assertEquals(1, byDigits.size)
        assertEquals(2L, byDigits.first().id)

        // Exact formatted substring match
        val byFormatted = com.example.ui.history.HistoryFragment.filterCallLogs(calls, "022-4000")
        assertEquals(1, byFormatted.size)
        assertEquals("Bob Verma", byFormatted.first().name)

        // Non-matching query returns empty list
        assertTrue(com.example.ui.history.HistoryFragment.filterCallLogs(calls, "Zebra").isEmpty())
    }

    @Test
    fun `upi payment helper builds elx@ptaxis uri and auto verifies npci responses`() {
        val uri = com.example.billing.UpiPaymentHelper.buildUpiPaymentUri("PD1700001234")
        assertEquals("upi", uri.scheme)
        assertEquals("pay", uri.authority)
        assertEquals("elx@ptaxis", uri.getQueryParameter("pa"))
        assertEquals("299.00", uri.getQueryParameter("am"))
        assertEquals("INR", uri.getQueryParameter("cu"))
        assertEquals("PD1700001234", uri.getQueryParameter("tr"))

        val successParsed = com.example.billing.UpiPaymentHelper.parseUpiResponse(
            rawResponse = "txnId=UPI987654&responseCode=00&Status=SUCCESS&txnRef=PD1700001234&ApprovalRefNo=456789",
            defaultTxnRef = "PD1700001234"
        )
        assertEquals(com.example.billing.UpiTxnStatus.SUCCESS, successParsed.status)
        assertEquals("UPI987654", successParsed.txnId)
        assertEquals("456789", successParsed.approvalRefNo)

        val cancelledParsed = com.example.billing.UpiPaymentHelper.parseUpiResponse(
            rawResponse = "Status=FAILURE&txnRef=PD1700001234",
            defaultTxnRef = "PD1700001234"
        )
        assertEquals(com.example.billing.UpiTxnStatus.FAILURE, cancelledParsed.status)

        val whatsAppIntent = com.example.billing.UpiPaymentHelper.buildWhatsAppSupportIntent(
            userName = "Jitendra",
            userEmail = "test@example.com",
            deviceTag = "DEV-1234-ABCD",
            pendingTxnRef = "PD1700001234"
        )
        assertTrue(whatsAppIntent.data.toString().startsWith("https://wa.me/919012634797"))
        assertTrue(com.example.billing.UpiPaymentHelper.isAdminEmail("Jitendrakumar888126@gmail.com"))
        assertFalse(com.example.billing.UpiPaymentHelper.isAdminEmail("other@gmail.com"))
    }

    @Test
    fun `subscription manager tracks firstLaunchTime and enforces 7 day expiry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rawPrefs = context.getSharedPreferences(
            com.example.billing.SubscriptionManager.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        rawPrefs.edit().clear().commit()

        val subscriptionManager = com.example.billing.SubscriptionManager(context)
        val trackedLaunchTime = rawPrefs.getLong(
            com.example.billing.SubscriptionManager.KEY_FIRST_LAUNCH_TIME,
            0L
        )
        assertTrue(trackedLaunchTime > 0L)

        val baseTime = 1_700_000_000_000L
        subscriptionManager.firstLaunchTime = baseTime
        subscriptionManager.isPaidUser = false

        // Within 7-day trial -> trial active, premium features strictly locked for unpaid user
        assertTrue(subscriptionManager.isSubscriptionActive(baseTime))
        assertTrue(subscriptionManager.isFeatureLocked(baseTime))
        assertEquals(7, subscriptionManager.getTrialDaysRemaining(baseTime))

        val daySix = baseTime + (6L * com.example.billing.SubscriptionManager.ONE_DAY_MILLIS)
        assertTrue(subscriptionManager.isSubscriptionActive(daySix))
        assertEquals(1, subscriptionManager.getTrialDaysRemaining(daySix))

        // After 7 days unpaid -> expired and features locked
        val expiredTime = baseTime + com.example.billing.SubscriptionManager.TRIAL_DURATION_MILLIS + 1L
        assertFalse(subscriptionManager.isSubscriptionActive(expiredTime))
        assertTrue(subscriptionManager.isFeatureLocked(expiredTime))
        assertTrue(subscriptionManager.isTrialExpired(expiredTime))

        // Marking user paid unlocks permanently across new instances
        subscriptionManager.isPaidUser = true
        val reloadedManager = com.example.billing.SubscriptionManager(context)
        assertTrue(reloadedManager.isSubscriptionActive(expiredTime))
        assertFalse(reloadedManager.isFeatureLocked(expiredTime))
    }

    @Test
    fun `contacts repository deletes single call, number history, and all call history`() =
        kotlinx.coroutines.runBlocking {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            org.robolectric.Shadows.shadowOf(app).grantPermissions(
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.WRITE_CALL_LOG
            )

            val fakeRows = mutableListOf(
                arrayOf<Any?>(1L, "Alice", "+91 98765 43210", android.provider.CallLog.Calls.INCOMING_TYPE, 1_700_000_000_000L, 30L),
                arrayOf<Any?>(2L, "Alice", "09876543210", android.provider.CallLog.Calls.OUTGOING_TYPE, 1_700_000_010_000L, 45L),
                arrayOf<Any?>(3L, "Bob", "+91 91234 56789", android.provider.CallLog.Calls.MISSED_TYPE, 1_700_000_020_000L, 0L),
                arrayOf<Any?>(4L, "Charlie", "+91 90000 11111", android.provider.CallLog.Calls.INCOMING_TYPE, 1_700_000_030_000L, 12L)
            )

            val provider = object : android.content.ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: android.net.Uri) = null
                override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
                override fun update(
                    uri: android.net.Uri,
                    values: android.content.ContentValues?,
                    selection: String?,
                    selectionArgs: Array<out String>?
                ) = 0

                override fun query(
                    uri: android.net.Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                    sortOrder: String?
                ): android.database.Cursor {
                    val cols = arrayOf(
                        android.provider.CallLog.Calls._ID,
                        android.provider.CallLog.Calls.CACHED_NAME,
                        android.provider.CallLog.Calls.NUMBER,
                        android.provider.CallLog.Calls.TYPE,
                        android.provider.CallLog.Calls.DATE,
                        android.provider.CallLog.Calls.DURATION
                    )
                    return android.database.MatrixCursor(cols).apply {
                        fakeRows.forEach { addRow(it) }
                    }
                }

                override fun delete(
                    uri: android.net.Uri,
                    selection: String?,
                    selectionArgs: Array<out String>?
                ): Int {
                    if (selection == null) {
                        val removed = fakeRows.size
                        fakeRows.clear()
                        return removed
                    }
                    val targetIds = selectionArgs?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty()
                    val before = fakeRows.size
                    fakeRows.removeAll { (it[0] as Long) in targetIds }
                    return before - fakeRows.size
                }
            }
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
                android.provider.CallLog.AUTHORITY,
                provider
            )

            val repo = com.example.data.ContactsRepository(app)
            assertEquals(4, repo.getRecentCalls().size)

            // 1. Delete a single call log entry by ID
            assertTrue(repo.deleteSingleCallLog(3L))
            assertEquals(listOf(1L, 2L, 4L), repo.getRecentCalls().map { it.id })

            // 2. Delete all calls for a single number (handles +91 vs 0 prefix formatting)
            val deletedForAlice = repo.deleteCallHistoryForNumber("9876543210")
            assertEquals(2, deletedForAlice)
            assertEquals(listOf(4L), repo.getRecentCalls().map { it.id })

            // 3. Delete all remaining call history
            val deletedAll = repo.deleteAllCallHistory()
            assertEquals(1, deletedAll)
            assertTrue(repo.getRecentCalls().isEmpty())
        }

    @Test
    fun `call detail activity displays caller info and save contact button for unsaved number`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = com.example.ui.history.CallDetailActivity.createIntent(
            context = context,
            phoneNumber = "+91 98765 43210",
            callerName = null
        )
        val controller = org.robolectric.Robolectric.buildActivity(
            com.example.ui.history.CallDetailActivity::class.java,
            intent
        ).setup()
        val activity = controller.get()

        val nameView = activity.findViewById<android.widget.TextView>(R.id.tv_detail_name)
        val numberView = activity.findViewById<android.widget.TextView>(R.id.tv_detail_number)
        val saveButton = activity.findViewById<android.view.View>(R.id.btn_save_contact)

        assertEquals(context.getString(R.string.detail_unsaved_number), nameView.text.toString())
        assertEquals("+91 98765 43210", numberView.text.toString())
        assertEquals(android.view.View.VISIBLE, saveButton.visibility)

        controller.pause().stop().destroy()
    }

    @Test
    fun `history and favorites adapters load contact thumbnails via ContentResolver`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)

        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        val pngBytes = out.toByteArray()

        val historyUri = android.net.Uri.parse("content://com.android.contacts/contacts/101/photo")
        val favoriteUri = android.net.Uri.parse("content://com.android.contacts/contacts/102/photo")
        val shadowResolver = org.robolectric.Shadows.shadowOf(app.contentResolver)
        shadowResolver.registerInputStream(historyUri, java.io.ByteArrayInputStream(pngBytes))
        shadowResolver.registerInputStream(favoriteUri, java.io.ByteArrayInputStream(pngBytes))

        val prevIo = com.example.ui.common.ContactThumbnailLoader.ioDispatcher
        val prevMain = com.example.ui.common.ContactThumbnailLoader.mainDispatcher
        com.example.ui.common.ContactThumbnailLoader.clearCache()
        com.example.ui.common.ContactThumbnailLoader.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        com.example.ui.common.ContactThumbnailLoader.mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined

        try {
            val parent = android.widget.FrameLayout(themedContext)

            // 1. CallLogAdapter (History)
            val callLogAdapter = com.example.ui.history.CallLogAdapter({}, {}, {})
            callLogAdapter.submitList(
                listOf(
                    com.example.data.CallLogEntry(
                        id = 1L,
                        name = "Alice",
                        number = "+91 98765 43210",
                        type = android.provider.CallLog.Calls.INCOMING_TYPE,
                        timestampMillis = 1_700_000_000_000L,
                        durationSeconds = 20L,
                        photoUri = historyUri.toString()
                    ),
                    com.example.data.CallLogEntry(
                        id = 2L,
                        name = "NoPhoto",
                        number = "+91 90000 00000",
                        type = android.provider.CallLog.Calls.OUTGOING_TYPE,
                        timestampMillis = 1_700_000_010_000L,
                        durationSeconds = 10L,
                        photoUri = null
                    )
                )
            )

            val historyHolderWithPhoto = callLogAdapter.onCreateViewHolder(parent, 0)
            callLogAdapter.onBindViewHolder(historyHolderWithPhoto, 0)
            val historyAvatar = historyHolderWithPhoto.itemView.findViewById<android.widget.ImageView>(R.id.iv_caller_avatar)
            val historyInitial = historyHolderWithPhoto.itemView.findViewById<android.view.View>(R.id.tv_avatar_initial)
            assertEquals(android.view.View.VISIBLE, historyAvatar.visibility)
            assertEquals(android.view.View.INVISIBLE, historyInitial.visibility)

            val historyHolderNoPhoto = callLogAdapter.onCreateViewHolder(parent, 0)
            callLogAdapter.onBindViewHolder(historyHolderNoPhoto, 1)
            val historyNoPhotoAvatar = historyHolderNoPhoto.itemView.findViewById<android.widget.ImageView>(R.id.iv_caller_avatar)
            val historyNoPhotoInitial = historyHolderNoPhoto.itemView.findViewById<android.view.View>(R.id.tv_avatar_initial)
            assertEquals(android.view.View.GONE, historyNoPhotoAvatar.visibility)
            assertEquals(android.view.View.VISIBLE, historyNoPhotoInitial.visibility)

            // 2. FavoritesAdapter
            val favoritesAdapter = com.example.ui.favorites.FavoritesAdapter({}, {}, {})
            favoritesAdapter.submitList(
                listOf(
                    com.example.data.ContactEntry(
                        contactId = 102L,
                        name = "Bob",
                        number = "+91 91234 56789",
                        isStarred = true,
                        photoUri = favoriteUri.toString()
                    )
                )
            )
            val favHolder = favoritesAdapter.onCreateViewHolder(parent, 0)
            favoritesAdapter.onBindViewHolder(favHolder, 0)
            val favAvatar = favHolder.itemView.findViewById<android.widget.ImageView>(R.id.iv_fav_avatar)
            val favInitial = favHolder.itemView.findViewById<android.view.View>(R.id.tv_fav_initial)
            assertEquals(android.view.View.VISIBLE, favAvatar.visibility)
            assertEquals(android.view.View.INVISIBLE, favInitial.visibility)
        } finally {
            com.example.ui.common.ContactThumbnailLoader.ioDispatcher = prevIo
            com.example.ui.common.ContactThumbnailLoader.mainDispatcher = prevMain
            com.example.ui.common.ContactThumbnailLoader.clearCache()
        }
    }

    @Test
    fun `exact 7 day monotonic counter resists rollback and redirects to payment page on expiry`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        val manager = com.example.billing.SubscriptionManager(app)

        val startTime = 1_700_000_000_000L
        prefs.firstLaunchTime = startTime
        prefs.isPremiumUser = false

        // Day 5: 2d 0h remaining
        val dayFive = startTime + (5L * DialerPreferences.ONE_DAY_MILLIS)
        assertEquals(2, manager.getTrialDaysRemaining(dayFive))
        assertEquals("2d 0h left", manager.formatRemainingCountdown(dayFive))
        assertTrue(manager.isSubscriptionActive(dayFive))

        // Roll device clock back to Day 1 offline -> monotonic lastSeenTimeMs keeps Day 5 progress (2 days left!)
        val rolledBackToDayOne = startTime + (1L * DialerPreferences.ONE_DAY_MILLIS)
        assertEquals(2, manager.getTrialDaysRemaining(rolledBackToDayOne))
        assertEquals("2d 0h left", manager.formatRemainingCountdown(rolledBackToDayOne))

        // Exact 7-day boundary -> expired and locked
        val exactSevenDays = startTime + DialerPreferences.TRIAL_DURATION_MILLIS
        assertTrue(manager.isTrialExpired(exactSevenDays))
        assertEquals(0, manager.getTrialDaysRemaining(exactSevenDays))
        assertFalse(manager.isSubscriptionActive(exactSevenDays))

        // Normal dialer & call details remain unlocked even when trial expires (only premium services freeze silently)
        prefs.firstLaunchTime = System.currentTimeMillis() - DialerPreferences.TRIAL_DURATION_MILLIS - 10_000L
        prefs.isPremiumUser = false
        val detailIntent = com.example.ui.history.CallDetailActivity.createIntent(
            context = app,
            phoneNumber = "+91 98765 43210"
        )
        val unlockedController = org.robolectric.Robolectric.buildActivity(
            com.example.ui.history.CallDetailActivity::class.java,
            detailIntent
        ).create()
        assertFalse(unlockedController.get().isFinishing)
        unlockedController.destroy()

        // Admin-blocked or tampered devices still redirect to MainActivity Account/Overlay screen
        prefs.adminBlockedUser = true
        val blockedController = org.robolectric.Robolectric.buildActivity(
            com.example.ui.history.CallDetailActivity::class.java,
            detailIntent
        ).create()
        val blockedActivity = blockedController.get()
        assertTrue(blockedActivity.isFinishing)
        val startedIntent = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        assertEquals(com.example.ui.MainActivity::class.java.name, startedIntent.component?.className)
        assertTrue(startedIntent.getBooleanExtra(com.example.ui.MainActivity.EXTRA_OPEN_ACCOUNT_TAB, false))
    }

    @Test
    fun `hidden admin tap detector opens on 12th consecutive account tap and resets on switch or timeout`() {
        val detector = com.example.ui.admin.HiddenAdminTapDetector()
        var now = 1_000_000L

        // Taps 1..11 must return false
        for (i in 1..11) {
            assertFalse("Tap $i should not trigger yet", detector.onAccountTabTapped(now))
            assertEquals(i, detector.currentTapCount())
            now += 200L
        }

        // 12th tap triggers true and resets count to 0
        assertTrue(detector.onAccountTabTapped(now))
        assertEquals(0, detector.currentTapCount())

        // Switching to another tab resets partial sequence
        for (i in 1..5) {
            assertFalse(detector.onAccountTabTapped(now))
            now += 150L
        }
        assertEquals(5, detector.currentTapCount())
        detector.onOtherTabSelected()
        assertEquals(0, detector.currentTapCount())

        // Waiting past 4000ms timeout resets count to 1 on next tap
        assertFalse(detector.onAccountTabTapped(now))
        assertEquals(1, detector.currentTapCount())
        now += 4_500L
        assertFalse(detector.onAccountTabTapped(now))
        assertEquals(1, detector.currentTapCount())
    }

    @Test
    fun `admin user filter and search accurately segment trial, expired, premium, and blocked users`() {
        val nowMs = 1_700_000_000_000L
        val activeStart = com.google.firebase.Timestamp(java.util.Date(nowMs - 2L * DialerPreferences.ONE_DAY_MILLIS))
        val expiredStart = com.google.firebase.Timestamp(java.util.Date(nowMs - 10L * DialerPreferences.ONE_DAY_MILLIS))
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)
        val hashC = "c".repeat(64)
        val hashD = "d".repeat(64)

        val users = listOf(
            com.example.data.firebase.UserEntitlement(
                id = "u1",
                userId = "u1",
                displayName = "Aarav Trial",
                email = "aarav@example.com",
                deviceHash = hashA,
                trialStartedAt = activeStart,
                isPremiumUser = false,
                isBlocked = false
            ),
            com.example.data.firebase.UserEntitlement(
                id = "u2",
                userId = "u2",
                displayName = "Bhavna Expired",
                email = "bhavna@example.com",
                deviceHash = hashB,
                trialStartedAt = expiredStart,
                isPremiumUser = false,
                isBlocked = false
            ),
            com.example.data.firebase.UserEntitlement(
                id = "u3",
                userId = "u3",
                displayName = "Chirag Pro",
                email = "chirag@example.com",
                deviceHash = hashC,
                trialStartedAt = expiredStart,
                isPremiumUser = true,
                isBlocked = false,
                adminNote = "VIP customer"
            ),
            com.example.data.firebase.UserEntitlement(
                id = "u4",
                userId = "u4",
                displayName = "Deepak Blocked",
                email = "deepak@example.com",
                deviceHash = hashD,
                trialStartedAt = activeStart,
                isPremiumUser = false,
                isBlocked = true
            )
        )

        assertEquals(4, com.example.data.firebase.AdminRepository.filterUsers(users, com.example.data.firebase.AdminUserFilter.ALL, "", 7, nowMs).size)
        assertEquals(listOf("u1"), com.example.data.firebase.AdminRepository.filterUsers(users, com.example.data.firebase.AdminUserFilter.TRIAL_ACTIVE, "", 7, nowMs).map { it.userId })
        assertEquals(listOf("u2"), com.example.data.firebase.AdminRepository.filterUsers(users, com.example.data.firebase.AdminUserFilter.EXPIRED, "", 7, nowMs).map { it.userId })
        assertEquals(listOf("u3"), com.example.data.firebase.AdminRepository.filterUsers(users, com.example.data.firebase.AdminUserFilter.PREMIUM, "", 7, nowMs).map { it.userId })
        assertEquals(listOf("u4"), com.example.data.firebase.AdminRepository.filterUsers(users, com.example.data.firebase.AdminUserFilter.BLOCKED, "", 7, nowMs).map { it.userId })

        // Search by adminNote and formatted device tag
        assertEquals(listOf("u3"), com.example.data.firebase.AdminRepository.filterUsers(users, com.example.data.firebase.AdminUserFilter.ALL, "vip", 7, nowMs).map { it.userId })
        assertEquals(listOf("u1"), com.example.data.firebase.AdminRepository.filterUsers(users, com.example.data.firebase.AdminUserFilter.ALL, "DEV-AAAA", 7, nowMs).map { it.userId })
    }

    @Test
    fun `admin payment filter segments pending, approved, and disapproved requests`() {
        val requests = listOf(
            com.example.data.firebase.PaymentRequest(
                id = "p1",
                userName = "Rohan",
                userEmail = "rohan@example.com",
                txnRef = "PD1001",
                upiTxnId = "UTR111",
                status = com.example.data.firebase.PaymentRequest.STATUS_PENDING
            ),
            com.example.data.firebase.PaymentRequest(
                id = "p2",
                userName = "Meera",
                userEmail = "meera@example.com",
                txnRef = "PD1002",
                upiTxnId = "UTR222",
                status = com.example.data.firebase.PaymentRequest.STATUS_APPROVED
            ),
            com.example.data.firebase.PaymentRequest(
                id = "p3",
                userName = "Karan",
                userEmail = "karan@example.com",
                txnRef = "PD1003",
                upiTxnId = "UTR333",
                status = com.example.data.firebase.PaymentRequest.STATUS_REJECTED
            )
        )

        assertEquals(3, com.example.data.firebase.AdminRepository.filterPaymentRequests(requests, com.example.data.firebase.AdminPaymentFilter.ALL, "").size)
        assertEquals(listOf("p1"), com.example.data.firebase.AdminRepository.filterPaymentRequests(requests, com.example.data.firebase.AdminPaymentFilter.PENDING, "").map { it.id })
        assertEquals(listOf("p2"), com.example.data.firebase.AdminRepository.filterPaymentRequests(requests, com.example.data.firebase.AdminPaymentFilter.APPROVED, "").map { it.id })
        assertEquals(listOf("p3"), com.example.data.firebase.AdminRepository.filterPaymentRequests(requests, com.example.data.firebase.AdminPaymentFilter.DISAPPROVED, "").map { it.id })
        assertEquals(listOf("p2"), com.example.data.firebase.AdminRepository.filterPaymentRequests(requests, com.example.data.firebase.AdminPaymentFilter.ALL, "UTR222").map { it.id })
    }

    @Test
    fun `global app config updates dialer preferences, payment parameters, and recording policy`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        val manager = com.example.billing.SubscriptionManager(app)

        prefs.applyAppConfig(
            com.example.data.firebase.AppConfig(
                id = com.example.data.firebase.AppConfig.DOC_GLOBAL_SETTINGS,
                trialDays = 14L,
                subscriptionPriceInr = 499L,
                upiVpa = "pulsedial@ybl",
                whatsappNumber = "9876543210",
                enforceSingleDeviceTrial = false,
                allowCallRecording = false,
                allowManualUtr = false,
                maintenanceMode = true,
                maintenanceMessage = "Scheduled maintenance",
                announcementText = "Upgrade offer live"
            )
        )

        assertEquals(14, prefs.configuredTrialDays)
        assertEquals(499L, manager.getConfiguredPriceInr())
        assertEquals("pulsedial@ybl", prefs.configuredUpiVpa)
        assertEquals("9876543210", prefs.configuredWhatsappNumber)
        assertFalse(prefs.allowCallRecordingConfig)
        assertFalse(prefs.allowManualUtrConfig)
        assertTrue(manager.isMaintenanceLocked(isAdmin = false))
        assertFalse(manager.isMaintenanceLocked(isAdmin = true))

        val uri = com.example.billing.UpiPaymentHelper.buildUpiPaymentUri(
            txnRef = "PD9999",
            upiVpa = prefs.configuredUpiVpa,
            amountInr = prefs.configuredPriceInr
        )
        assertEquals("pulsedial@ybl", uri.getQueryParameter("pa"))
        assertEquals("499.00", uri.getQueryParameter("am"))

        // Disabling global call recording blocks CallRecorder even during active trial
        val recorder = com.example.recording.CallRecorder(app)
        assertFalse(recorder.startRecording("Test Caller"))
    }

    @Test
    fun `tampering with shared preferences isPremiumUser or firstLaunchTime is caught by HMAC seal`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        val manager = com.example.billing.SubscriptionManager(app)

        // Expire user legitimately
        prefs.isPremiumUser = false
        prefs.firstLaunchTime = System.currentTimeMillis() - (10L * DialerPreferences.ONE_DAY_MILLIS)
        assertFalse(manager.isSubscriptionActive())

        // Attacker edits raw SharedPreferences XML directly on rooted device to fake Pro + reset clock
        val rawPrefs = app.getSharedPreferences(com.example.billing.SubscriptionManager.PREFS_NAME, Context.MODE_PRIVATE)
        rawPrefs.edit()
            .putBoolean(DialerPreferences.KEY_IS_PREMIUM_USER, true)
            .putLong(DialerPreferences.KEY_FIRST_LAUNCH_TIME, System.currentTimeMillis())
            .commit()

        // Cryptographic HMAC seal detects tampering and immediately revokes fake Pro!
        assertFalse(manager.isSubscriptionActive())
        assertTrue(prefs.tamperDetected)
        assertFalse(prefs.isPremiumUser)
        assertTrue(prefs.trialBlockedByDevice)
    }

    @Test
    fun `global app config syncs dialpad tones, auto recording, and enforced dark theme`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)

        prefs.applyAppConfig(
            com.example.data.firebase.AppConfig(
                id = com.example.data.firebase.AppConfig.DOC_GLOBAL_SETTINGS,
                allowCallRecording = true,
                dialpadTonesDefault = false,
                defaultAutoRecording = true,
                forceThemeMode = com.example.data.firebase.AppConfig.THEME_DARK
            )
        )

        assertFalse(prefs.dialpadTonesEnabled)
        assertTrue(prefs.autoCallRecording)
        assertEquals(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES, prefs.displayThemeMode)
        assertEquals(com.example.data.firebase.AppConfig.THEME_DARK, prefs.configuredForceThemeMode)
    }

    @Test
    fun `server driven defaults, popup dismissal, dynamic toggles, and hideSubscriptionUI work`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)

        // 5 default navigation tabs & 6 default settings items
        assertEquals(5, com.example.data.firebase.NavigationTabItem.defaultTabs().size)
        assertEquals(6, com.example.data.firebase.DynamicSettingItem.defaultItems().size)

        // Popup dismissal tracking
        assertFalse(prefs.isPopupDismissed("pop_101"))
        prefs.markPopupDismissed("pop_101")
        assertTrue(prefs.isPopupDismissed("pop_101"))
        assertFalse(prefs.isPopupDismissed("pop_102"))

        // Dynamic toggle persistence
        assertFalse(prefs.getDynamicToggle("custom_beta_feature", false))
        prefs.setDynamicToggle("custom_beta_feature", true)
        assertTrue(prefs.getDynamicToggle("custom_beta_feature", false))

        // hideSubscriptionUI synced globally and per-user
        assertFalse(prefs.hideSubscriptionUi)
        prefs.applyAppConfig(
            com.example.data.firebase.AppConfig(
                id = com.example.data.firebase.AppConfig.DOC_GLOBAL_SETTINGS,
                hideSubscriptionUI = true
            )
        )
        assertTrue(prefs.hideSubscriptionUi)

        prefs.applyAppConfig(com.example.data.firebase.AppConfig())
        assertFalse(prefs.hideSubscriptionUi)
        prefs.hideSubscriptionUi = true
        assertTrue(prefs.hideSubscriptionUi)
    }

    @Test
    fun `monthly 30 day subscription expires, locks access, and unlocks upon renewal with hmac check`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        val manager = com.example.billing.SubscriptionManager(app)

        val day0 = System.currentTimeMillis()
        prefs.firstLaunchTime = day0 - (20L * DialerPreferences.ONE_DAY_MILLIS) // trial already finished
        prefs.isPremiumUser = true
        prefs.subscriptionExpiresAtMs = day0 + DialerPreferences.ONE_MONTH_MILLIS

        // Day 0 of paid month -> active, 30 days left, not expiring soon
        assertTrue(manager.isSubscriptionActive(day0))
        assertTrue(manager.isPremiumActive(day0))
        assertEquals(30, manager.getSubscriptionDaysRemaining(day0))
        assertFalse(manager.isSubscriptionExpiringSoon(day0))

        // Day 27 -> 3 days left -> active AND marked Expiring Soon (<= 5d)
        val day27 = day0 + (27L * DialerPreferences.ONE_DAY_MILLIS)
        assertTrue(manager.isSubscriptionActive(day27))
        assertTrue(manager.isSubscriptionExpiringSoon(day27))
        assertEquals(3, manager.getSubscriptionDaysRemaining(day27))

        // Day 31 -> 30-day subscription expired -> app & recording lock until renewed!
        val day31 = day0 + DialerPreferences.ONE_MONTH_MILLIS + 10_000L
        assertFalse(manager.isPremiumActive(day31))
        assertTrue(manager.hasMonthlySubscriptionExpired(day31))
        assertFalse(manager.isSubscriptionActive(day31))
        assertFalse(prefs.hasRecordingAccess(day31))

        // Tampering raw SharedPreferences KEY_SUBSCRIPTION_EXPIRES_AT_MS directly is caught by HMAC seal
        val rawPrefs = app.getSharedPreferences(
            com.example.billing.SubscriptionManager.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        rawPrefs.edit()
            .putLong(DialerPreferences.KEY_SUBSCRIPTION_EXPIRES_AT_MS, day31 + DialerPreferences.ONE_MONTH_MILLIS)
            .commit()
        assertFalse(manager.isSubscriptionActive(day31))
        assertTrue(prefs.tamperDetected)

        // Legitimate renewal via DialerPreferences/Admin grants another 30 days
        prefs.tamperDetected = false
        prefs.isPremiumUser = true
        prefs.subscriptionExpiresAtMs = day31 + DialerPreferences.ONE_MONTH_MILLIS
        assertTrue(manager.isSubscriptionActive(day31))
        assertFalse(manager.hasMonthlySubscriptionExpired(day31))
        assertEquals(30, manager.getSubscriptionDaysRemaining(day31))
    }

    @Test
    fun `admin filter detects expiring soon monthly pro, expiring soon trial, and expired monthly plans`() {
        val nowMs = 1_700_000_000_000L
        val proHealthyExp = com.google.firebase.Timestamp(java.util.Date(nowMs + 20L * DialerPreferences.ONE_DAY_MILLIS))
        val proExpiringExp = com.google.firebase.Timestamp(java.util.Date(nowMs + 3L * DialerPreferences.ONE_DAY_MILLIS))
        val proExpiredExp = com.google.firebase.Timestamp(java.util.Date(nowMs - 2L * DialerPreferences.ONE_DAY_MILLIS))
        val trialExpiringStart = com.google.firebase.Timestamp(java.util.Date(nowMs - 6L * DialerPreferences.ONE_DAY_MILLIS))
        val trialExpiredStart = com.google.firebase.Timestamp(java.util.Date(nowMs - 15L * DialerPreferences.ONE_DAY_MILLIS))

        val users = listOf(
            com.example.data.firebase.UserEntitlement(
                id = "pro_healthy",
                userId = "pro_healthy",
                displayName = "Pro Healthy",
                trialStartedAt = trialExpiredStart,
                isPremiumUser = true,
                subscriptionExpiresAt = proHealthyExp
            ),
            com.example.data.firebase.UserEntitlement(
                id = "pro_expiring",
                userId = "pro_expiring",
                displayName = "Pro Expiring Soon",
                trialStartedAt = trialExpiredStart,
                isPremiumUser = true,
                subscriptionExpiresAt = proExpiringExp
            ),
            com.example.data.firebase.UserEntitlement(
                id = "pro_expired",
                userId = "pro_expired",
                displayName = "Pro Plan Expired",
                trialStartedAt = trialExpiredStart,
                isPremiumUser = true,
                subscriptionExpiresAt = proExpiredExp
            ),
            com.example.data.firebase.UserEntitlement(
                id = "trial_expiring",
                userId = "trial_expiring",
                displayName = "Trial Ending Tomorrow",
                trialStartedAt = trialExpiringStart,
                isPremiumUser = false
            )
        )

        val expiringSoonIds = com.example.data.firebase.AdminRepository.filterUsers(
            users = users,
            filter = com.example.data.firebase.AdminUserFilter.EXPIRING_SOON,
            query = "",
            trialDaysConfig = 7,
            nowMs = nowMs
        ).map { it.userId }
        assertEquals(listOf("pro_expiring", "trial_expiring"), expiringSoonIds)

        val expiredIds = com.example.data.firebase.AdminRepository.filterUsers(
            users = users,
            filter = com.example.data.firebase.AdminUserFilter.EXPIRED,
            query = "",
            trialDaysConfig = 7,
            nowMs = nowMs
        ).map { it.userId }
        assertEquals(listOf("pro_expired"), expiredIds)

        val activeProIds = com.example.data.firebase.AdminRepository.filterUsers(
            users = users,
            filter = com.example.data.firebase.AdminUserFilter.PREMIUM,
            query = "",
            trialDaysConfig = 7,
            nowMs = nowMs
        ).map { it.userId }
        assertEquals(listOf("pro_healthy", "pro_expiring"), activeProIds)
    }

    @Test
    fun `history fragment layout shows recent calls only and removes recordings playlist from call app`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val root = android.view.LayoutInflater.from(themedContext).inflate(R.layout.fragment_history, null)

        assertEquals(android.view.View.VISIBLE, root.findViewById<android.view.View>(R.id.recycler_history).visibility)
        assertEquals(0, app.resources.getIdentifier("toggle_history_mode", "id", app.packageName))
        assertEquals(0, app.resources.getIdentifier("recycler_recordings", "id", app.packageName))
        assertEquals(0, app.resources.getIdentifier("card_recording_folder_banner", "id", app.packageName))
        assertEquals(0, app.resources.getIdentifier("card_mini_player", "id", app.packageName))
    }

    @Test
    fun `call recording services and android 14+ foreground permissions are properly declared in manifest`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val pm = app.packageManager
        val pkgInfo = pm.getPackageInfo(
            app.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS or
                android.content.pm.PackageManager.GET_SERVICES
        )
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(perms.contains(android.Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(perms.contains(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertTrue(perms.contains(android.Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL))
        assertTrue(perms.contains(android.Manifest.permission.RECORD_AUDIO))
        assertTrue(perms.contains(android.Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(perms.contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW))
        assertFalse(perms.contains(android.Manifest.permission.SEND_SMS))

        val serviceMap = pkgInfo.services.orEmpty().associateBy { it.name }
        assertTrue(serviceMap.containsKey("com.example.telecom.CustomInCallService"))
        assertTrue(serviceMap.containsKey("com.example.recording.CallRecorderForegroundService"))
        assertFalse(serviceMap.containsKey("com.example.recording.CallRecordingAccessibilityService"))

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgs = serviceMap["com.example.recording.CallRecorderForegroundService"]!!
            val expectedType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            assertEquals(expectedType, fgs.foregroundServiceType)
        }
    }

    @Test
    fun `seven day expiring window boundary and admin expiring view layout and adapter highlight work`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        val manager = com.example.billing.SubscriptionManager(app)

        val nowMs = System.currentTimeMillis()
        prefs.firstLaunchTime = nowMs - (20L * DialerPreferences.ONE_DAY_MILLIS)
        prefs.isPremiumUser = true

        // 10 days remaining -> NOT expiring soon (> 7 days)
        prefs.subscriptionExpiresAtMs = nowMs + (10L * DialerPreferences.ONE_DAY_MILLIS)
        assertFalse(manager.isSubscriptionExpiringSoon(nowMs))

        // 6 days remaining -> IS expiring soon (<= 7 days)
        prefs.subscriptionExpiresAtMs = nowMs + (6L * DialerPreferences.ONE_DAY_MILLIS)
        assertTrue(manager.isSubscriptionExpiringSoon(nowMs))
        assertEquals(6, manager.getSubscriptionDaysRemaining(nowMs))

        // Inflate activity_admin_panel and verify expiring section views, search bar, and RadioGroup exist
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val panelRoot = android.view.LayoutInflater.from(themedContext).inflate(R.layout.activity_admin_panel, null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.container_admin_expiring) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.et_admin_expiring_search) != null)
        assertTrue(panelRoot.findViewById<android.widget.RadioGroup>(R.id.rg_admin_subscriber_filter) != null)
        assertTrue(panelRoot.findViewById<android.widget.RadioButton>(R.id.rb_subscriber_expiring_soon).isChecked)
        assertFalse(panelRoot.findViewById<android.widget.RadioButton>(R.id.rb_subscriber_all_active).isChecked)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.recycler_admin_expiring) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.btn_refresh_expiring) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.tv_expiring_query_count) != null)

        // Verify AdminExpiringUsersAdapter highlights critical (<=2d), warning (3..7d), and healthy active (>7d)
        var renewedUid: String? = null
        var managedUid: String? = null
        var revokedUid: String? = null
        val adapter = com.example.ui.admin.AdminExpiringUsersAdapter(
            onRenewMonthClick = { renewedUid = it.userId },
            onManageClick = { managedUid = it.userId },
            onRevokeProClick = { revokedUid = it.userId }
        )

        val criticalUser = com.example.data.firebase.UserEntitlement(
            id = "u_crit",
            userId = "u_crit",
            displayName = "Urgent User",
            email = "urgent@example.com",
            deviceHash = "a".repeat(64),
            isPremiumUser = true,
            subscriptionExpiresAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 2L * DialerPreferences.ONE_DAY_MILLIS))
        )
        val warningUser = com.example.data.firebase.UserEntitlement(
            id = "u_warn",
            userId = "u_warn",
            displayName = "Soon User",
            email = "soon@example.com",
            deviceHash = "b".repeat(64),
            isPremiumUser = true,
            subscriptionExpiresAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 6L * DialerPreferences.ONE_DAY_MILLIS))
        )
        val healthyUser = com.example.data.firebase.UserEntitlement(
            id = "u_healthy_99",
            userId = "u_healthy_99",
            displayName = "Healthy Pro",
            email = "healthy99@example.com",
            deviceHash = "c".repeat(64),
            isPremiumUser = true,
            subscriptionExpiresAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 22L * DialerPreferences.ONE_DAY_MILLIS))
        )

        // Verify filterSubscribers toggles between Expiring Soon and All Active Subscribers + searches by email or ID
        val allSubscribers = listOf(criticalUser, warningUser, healthyUser)
        assertEquals(
            listOf("u_crit", "u_warn"),
            com.example.data.firebase.AdminRepository.filterSubscribers(
                allSubscribers,
                com.example.data.firebase.AdminSubscriberFilter.EXPIRING_SOON,
                "",
                nowMs
            ).map { it.userId }
        )
        assertEquals(
            listOf("u_crit", "u_warn", "u_healthy_99"),
            com.example.data.firebase.AdminRepository.filterSubscribers(
                allSubscribers,
                com.example.data.firebase.AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS,
                "",
                nowMs
            ).map { it.userId }
        )
        assertEquals(
            listOf("u_healthy_99"),
            com.example.data.firebase.AdminRepository.filterSubscribers(
                allSubscribers,
                com.example.data.firebase.AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS,
                "healthy99@example.com",
                nowMs
            ).map { it.userId }
        )
        assertEquals(
            listOf("u_warn"),
            com.example.data.firebase.AdminRepository.filterSubscribers(
                allSubscribers,
                com.example.data.firebase.AdminSubscriberFilter.EXPIRING_SOON,
                "u_warn",
                nowMs
            ).map { it.userId }
        )

        adapter.submitList(allSubscribers)

        val parent = android.widget.FrameLayout(themedContext)
        val critHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(critHolder, 0)
        val critCard = critHolder.itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_expiring_user)
        assertEquals(
            androidx.core.content.ContextCompat.getColor(themedContext, R.color.md_theme_error),
            critCard.strokeColor
        )
        critHolder.itemView.findViewById<android.view.View>(R.id.btn_expiring_renew).performClick()
        critHolder.itemView.findViewById<android.view.View>(R.id.btn_expiring_manage).performClick()
        critHolder.itemView.findViewById<android.view.View>(R.id.btn_expiring_revoke).performClick()
        assertEquals("u_crit", renewedUid)
        assertEquals("u_crit", managedUid)
        assertEquals("u_crit", revokedUid)

        val warnHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(warnHolder, 1)
        val warnCard = warnHolder.itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_expiring_user)
        assertEquals(
            androidx.core.content.ContextCompat.getColor(themedContext, R.color.md_theme_tertiary),
            warnCard.strokeColor
        )

        val healthyHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(healthyHolder, 2)
        val healthyCard = healthyHolder.itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_expiring_user)
        assertEquals(
            androidx.core.content.ContextCompat.getColor(themedContext, R.color.md_theme_primary),
            healthyCard.strokeColor
        )
    }

    @Test
    fun `settings manager converts theme modes and user entitlement tracks real time subscriptionStatus and installDate`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)

        assertEquals(
            com.example.data.firebase.UserEntitlement.THEME_DARK,
            com.example.data.firebase.SettingsManager.themeModeToString(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            )
        )
        assertEquals(
            com.example.data.firebase.UserEntitlement.THEME_LIGHT,
            com.example.data.firebase.SettingsManager.themeModeToString(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            )
        )
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
            com.example.data.firebase.SettingsManager.stringToThemeMode(
                com.example.data.firebase.UserEntitlement.THEME_DARK
            )
        )

        prefs.setBlockedNumbers(listOf("+91 98765 00001", "+91 98765 00002"))
        assertTrue(prefs.isNumberBlocked("9876500001"))
        assertTrue(prefs.isNumberBlocked("9876500002"))

        val customDeclines = listOf("In a meeting, call back soon.", "Driving right now.")
        prefs.setQuickDeclineResponses(customDeclines)
        assertEquals(customDeclines, prefs.getQuickDeclineResponses())

        val installTimestamp = com.google.firebase.Timestamp(java.util.Date(1_700_000_000_000L))
        val entitlement = com.example.data.firebase.UserEntitlement(
            id = "u_rt",
            userId = "u_rt",
            trialStartedAt = installTimestamp,
            isPremiumUser = true,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
            autoCallRecording = true,
            themeMode = com.example.data.firebase.UserEntitlement.THEME_DARK,
            dialpadTonesEnabled = false
        )
        assertTrue(entitlement.isPremium)
        assertEquals(installTimestamp, entitlement.installDate)
        assertEquals(com.example.data.firebase.UserEntitlement.STATUS_ACTIVE, entitlement.subscriptionStatus)
    }

    @Test
    fun `realtime database sync mapping, pending utr top sort, admin user card controls, 6 clean settings, and blocked overlay work`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)

        // 1. Verify 6 clean settings include Accessibility Service Status
        val defaultSettings = com.example.data.firebase.DynamicSettingItem.defaultItems()
        assertEquals(6, defaultSettings.size)
        assertEquals(
            listOf(
                com.example.data.firebase.DynamicSettingItem.KEY_AUTO_RECORDING,
                com.example.data.firebase.DynamicSettingItem.KEY_RECORDING_FOLDER,
                com.example.data.firebase.DynamicSettingItem.KEY_ACCESSIBILITY_STATUS,
                com.example.data.firebase.DynamicSettingItem.KEY_BLOCKED_NUMBERS,
                com.example.data.firebase.DynamicSettingItem.KEY_DISPLAY_THEME,
                com.example.data.firebase.DynamicSettingItem.KEY_SOUNDS_VIBRATION
            ),
            defaultSettings.map { it.key }
        )

        // 2. Verify RealtimeDatabaseSync user & config round-trip mapping
        val nowMs = 1_700_000_000_000L
        val originalUser = com.example.data.firebase.UserEntitlement(
            id = "u_rtdb",
            userId = "u_rtdb",
            displayName = "Rahul Kumar",
            email = "rahul@example.com",
            phoneNumber = "+919876543210",
            trialStartedAt = com.google.firebase.Timestamp(java.util.Date(nowMs)),
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_PENDING_APPROVAL,
            isPremiumUser = false,
            paymentRefId = "412345678901",
            isBlocked = false,
            autoCallRecording = true,
            themeMode = com.example.data.firebase.UserEntitlement.THEME_DARK
        )
        val rtdbMap = com.example.data.firebase.RealtimeDatabaseSync.toRtdbUserMap(originalUser)
        assertEquals("u_rtdb", rtdbMap["uid"])
        assertEquals("+919876543210", rtdbMap["phone"])
        assertEquals("412345678901", rtdbMap["paymentRefId"])
        assertEquals(true, rtdbMap["autoRecordEnabled"])

        val parsedUser = com.example.data.firebase.RealtimeDatabaseSync.parseUserSnapshot("u_rtdb", rtdbMap, null)
        assertEquals("u_rtdb", parsedUser.uid)
        assertEquals("+919876543210", parsedUser.phone)
        assertEquals("412345678901", parsedUser.paymentRefId)
        assertEquals(com.example.data.firebase.UserEntitlement.STATUS_PENDING_APPROVAL, parsedUser.subscriptionStatus)
        assertTrue(parsedUser.autoRecordEnabled)

        val cfgMap = mapOf(
            "subscriptionPrice" to 349L,
            "adminUpiId" to "admin@upi"
        )
        val parsedCfg = com.example.data.firebase.RealtimeDatabaseSync.parseAppConfigSnapshot(cfgMap)
        assertEquals(349L, parsedCfg.subscriptionPrice)
        assertEquals("admin@upi", parsedCfg.adminUpiId)

        // 3. Verify pending_approval users sort to the top of AdminRepository.filterUsers
        val normalUser = com.example.data.firebase.UserEntitlement(
            id = "u_normal",
            userId = "u_normal",
            displayName = "Normal User",
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_TRIAL,
            updatedAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 50_000L))
        )
        val sorted = com.example.data.firebase.AdminRepository.filterUsers(
            users = listOf(normalUser, parsedUser),
            filter = com.example.data.firebase.AdminUserFilter.ALL,
            query = "",
            trialDaysConfig = 7,
            nowMs = nowMs
        )
        assertEquals(listOf("u_rtdb", "u_normal"), sorted.map { it.userId })

        // 4. Verify AdminUsersAdapter binds Approve UTR, Reject UTR, Block/Unblock switch, and Premium Access switch
        var approvedUid: String? = null
        var rejectedUid: String? = null
        var toggledProUid: String? = null
        var toggledBlockUid: String? = null
        val adminAdapter = com.example.ui.admin.AdminUsersAdapter(
            onToggleProClick = { toggledProUid = it.userId },
            onRenewMonthClick = {},
            onExtendTrialClick = {},
            onToggleBlockClick = { toggledBlockUid = it.userId },
            onManageClick = {},
            onApproveUtrClick = { approvedUid = it.userId },
            onRejectUtrClick = { rejectedUid = it.userId }
        )
        adminAdapter.submitList(listOf(parsedUser))
        val parent = android.widget.FrameLayout(themedContext)
        val holder = adminAdapter.onCreateViewHolder(parent, 0)
        adminAdapter.onBindViewHolder(holder, 0)

        val utrActions = holder.itemView.findViewById<android.view.View>(R.id.layout_admin_user_utr_actions)
        assertEquals(android.view.View.VISIBLE, utrActions.visibility)
        holder.itemView.findViewById<android.view.View>(R.id.btn_user_approve_utr).performClick()
        holder.itemView.findViewById<android.view.View>(R.id.btn_user_reject_utr).performClick()
        assertEquals("u_rtdb", approvedUid)
        assertEquals("u_rtdb", rejectedUid)

        val switchPro = holder.itemView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_user_premium)
        val switchBlock = holder.itemView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_user_block)
        switchPro.performClick()
        switchBlock.performClick()
        assertEquals("u_rtdb", toggledProUid)
        assertEquals("u_rtdb", toggledBlockUid)

        // 5. Verify full-screen Account Blocked by Admin overlay exists in activity_main.xml
        val mainRoot = android.view.LayoutInflater.from(themedContext).inflate(R.layout.activity_main, null)
        val blockedOverlay = mainRoot.findViewById<android.view.View>(R.id.blocked_overlay_container)
        val blockedTitle = mainRoot.findViewById<android.widget.TextView>(R.id.tv_blocked_overlay_title)
        assertTrue(blockedOverlay != null)
        assertEquals("Account Blocked by Admin", blockedTitle.text.toString())

        // 6. Verify RTDB URL resource, payment_requests, navigation_tabs, settings_items & live_broadcast snapshots
        assertEquals(
            "https://gen-lang-client-0353534085-default-rtdb.firebaseio.com",
            themedContext.getString(R.string.firebase_database_url)
        )
        assertEquals("gen-lang-client-0353534085", com.example.data.firebase.RealtimeDatabaseSync.PROJECT_ID)
        assertEquals("113923036774", com.example.data.firebase.RealtimeDatabaseSync.PROJECT_NUMBER)

        val paymentReq = com.example.data.firebase.PaymentRequest(
            id = "req_rtdb_1",
            userId = "u_rtdb",
            userName = "RTDB Tester",
            userEmail = "rtdb@example.com",
            upiId = "elx@ptaxis",
            amountInr = 299L,
            upiTxnId = "412345678901",
            status = com.example.data.firebase.PaymentRequest.STATUS_PENDING
        )
        val reqMap = com.example.data.firebase.RealtimeDatabaseSync.toRtdbPaymentRequestMap(paymentReq)
        val parsedReq = com.example.data.firebase.RealtimeDatabaseSync.parsePaymentRequestSnapshot("req_rtdb_1", reqMap)
        assertEquals("req_rtdb_1", parsedReq.id)
        assertEquals("u_rtdb", parsedReq.userId)
        assertEquals("412345678901", parsedReq.upiTxnId)
        assertEquals(299L, parsedReq.amountInr)

        val navTabs = com.example.data.firebase.NavigationTabItem.defaultTabs()
        val parsedTabs = com.example.data.firebase.RealtimeDatabaseSync.parseNavigationTabsSnapshot(
            mapOf("tabs" to navTabs.map { it.toFirestoreMap() })
        )
        assertEquals(navTabs.size, parsedTabs.size)

        val settingsItems = com.example.data.firebase.DynamicSettingItem.defaultItems()
        val parsedItems = com.example.data.firebase.RealtimeDatabaseSync.parseDynamicSettingsSnapshot(
            mapOf("items" to settingsItems.map { it.toFirestoreMap() })
        )
        assertEquals(settingsItems.size, parsedItems.size)

        val broadcast = com.example.data.firebase.RealtimeDatabaseSync.parseLiveBroadcastSnapshot(
            mapOf(
                "showPopup" to true,
                "popupId" to "pop_1",
                "title" to "Live Update",
                "message" to "Instant RTDB Broadcast"
            )
        )
        assertTrue(broadcast.showPopup)
        assertEquals("Live Update", broadcast.title)
    }

    @Test
    fun `subscription enforcement service monitors subscriptionStatus and isPremium nodes and enforces 7 day trial and recording restrictions`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val nowMs = 1_800_000_000_000L
        val threeDaysAgo = nowMs - 3L * DialerPreferences.ONE_DAY_MILLIS
        val eightDaysAgo = nowMs - 8L * DialerPreferences.ONE_DAY_MILLIS

        // 1. Non-premium user during trial: normal calling & contacts allowed, recording strictly Premium-only
        val activeTrialState = com.example.billing.SubscriptionEnforcementService.evaluateNodeState(
            uid = "u_enf_1",
            subscriptionStatus = "trial",
            isPremium = false,
            isBlocked = false,
            installDateMs = threeDaysAgo,
            nowMs = nowMs
        )
        assertFalse(activeTrialState.isTrialExpired)
        assertEquals(4, activeTrialState.trialDaysRemaining)
        assertTrue(activeTrialState.isSubscriptionActive)
        assertFalse(activeTrialState.isRecordingAllowed)
        assertFalse(activeTrialState.shouldRestrictUiToAccountTab)
        assertEquals("Free Plan — Upgrade to Pro for Recording, Dark Mode & 3D Themes", activeTrialState.statusBannerText)

        // 2. Non-premium user after 7 days: normal calling & contacts stay usable, recording stays locked
        val expiredTrialState = com.example.billing.SubscriptionEnforcementService.evaluateNodeState(
            uid = "u_enf_1",
            subscriptionStatus = "trial",
            isPremium = false,
            isBlocked = false,
            installDateMs = eightDaysAgo,
            nowMs = nowMs
        )
        assertTrue(expiredTrialState.isTrialExpired)
        assertEquals(0, expiredTrialState.trialDaysRemaining)
        assertFalse(expiredTrialState.isSubscriptionActive)
        assertFalse(expiredTrialState.isRecordingAllowed)
        assertFalse(expiredTrialState.shouldRestrictUiToAccountTab)
        assertEquals("Free Plan — Upgrade to Pro for Recording, Dark Mode & 3D Themes", expiredTrialState.statusBannerText)

        // 3. Pending UTR approval
        val pendingState = com.example.billing.SubscriptionEnforcementService.evaluateNodeState(
            uid = "u_enf_1",
            subscriptionStatus = "pending_approval",
            isPremium = false,
            isBlocked = false,
            installDateMs = eightDaysAgo,
            nowMs = nowMs
        )
        assertEquals("Verification Pending... Admin is checking your UTR", pendingState.statusBannerText)

        // 4. Admin approves UTR: /users/{uid}/isPremium = true, /users/{uid}/subscriptionStatus = "active"
        val approvedState = com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
            context = context,
            subscriptionStatus = "active",
            isPremium = true,
            isBlocked = false,
            installDateMs = eightDaysAgo,
            subscriptionExpiresAtMs = nowMs + DialerPreferences.ONE_MONTH_MILLIS,
            nowMs = nowMs
        )
        assertTrue(approvedState.isPremium)
        assertTrue(approvedState.isSubscriptionActive)
        assertTrue(approvedState.isRecordingAllowed)
        assertFalse(approvedState.shouldRestrictUiToAccountTab)
        assertEquals("Pro Plan Active", approvedState.statusBannerText)
        assertTrue(com.example.billing.SubscriptionEnforcementService.canStartCallRecording(context, nowMs))

        // 5. Admin revokes/expires: /users/{uid}/isPremium = false, /users/{uid}/subscriptionStatus = "expired"
        val revokedState = com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
            context = context,
            subscriptionStatus = "expired",
            isPremium = false,
            isBlocked = false,
            installDateMs = eightDaysAgo,
            subscriptionExpiresAtMs = 0L,
            nowMs = nowMs
        )
        assertFalse(revokedState.isPremium)
        assertFalse(revokedState.isSubscriptionActive)
        assertFalse(revokedState.isRecordingAllowed)
        assertFalse(revokedState.shouldRestrictUiToAccountTab)
        assertFalse(com.example.billing.SubscriptionEnforcementService.canStartCallRecording(context, nowMs))

        // 6. Admin blocks user: /users/{uid}/isBlocked = true
        val blockedState = com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
            context = context,
            subscriptionStatus = "blocked",
            isPremium = false,
            isBlocked = true,
            installDateMs = threeDaysAgo,
            nowMs = nowMs
        )
        assertTrue(blockedState.isBlocked)
        assertFalse(blockedState.isSubscriptionActive)
        assertFalse(blockedState.isRecordingAllowed)
        assertTrue(blockedState.shouldRestrictUiToAccountTab)
        assertEquals("Account Blocked by Admin", blockedState.statusBannerText)
    }

    @Test
    fun `vip smart call announcer, unknown caller shield, and auto whatsapp sms follow up sync and enforce 24h cooldown`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val prefs = DialerPreferences(app)

        // 1. Smart Call Announcer speech formatting
        assertEquals(
            "Incoming call from Rahul Sharma",
            com.example.telecom.CustomInCallService.buildAnnouncerSpeechText("Rahul Sharma", "+91 98765 43210")
        )
        assertEquals(
            "Incoming call from unknown number 9 8 7 6 5 4 3 2 1 0",
            com.example.telecom.CustomInCallService.buildAnnouncerSpeechText(null, "98765 43210")
        )

        // 2. Call end outcome & trigger matching
        assertEquals(
            com.example.telecom.CustomInCallService.CallEndOutcome.COMPLETED_CALL,
            com.example.telecom.CustomInCallService.resolveCallEndOutcome(wasIncomingRinging = true, reachedActive = true)
        )
        assertEquals(
            com.example.telecom.CustomInCallService.CallEndOutcome.MISSED_OR_BUSY_CALL,
            com.example.telecom.CustomInCallService.resolveCallEndOutcome(wasIncomingRinging = true, reachedActive = false)
        )
        assertTrue(
            com.example.telecom.CustomInCallService.shouldTriggerFollowUp(
                com.example.data.firebase.UserEntitlement.TRIGGER_ALL_CALLS,
                com.example.telecom.CustomInCallService.CallEndOutcome.MISSED_OR_BUSY_CALL
            )
        )
        assertFalse(
            com.example.telecom.CustomInCallService.shouldTriggerFollowUp(
                com.example.data.firebase.UserEntitlement.TRIGGER_COMPLETED_ONLY,
                com.example.telecom.CustomInCallService.CallEndOutcome.MISSED_OR_BUSY_CALL
            )
        )
        assertEquals(
            "919876543210",
            com.example.telecom.CustomInCallService.formatWhatsappFollowUpPhone("098765 43210")
        )

        // 3. 24-hour anti-spam cooldown per phone number & Unknown Shield rejected number tracking
        val nowMs = 1_850_000_000_000L
        assertTrue(prefs.canSendFollowUpToday("+91 99887 76655", nowMs))
        prefs.recordFollowUpSent("9988776655", nowMs)
        assertFalse(prefs.canSendFollowUpToday("+91 99887 76655", nowMs + 6 * 3600_000L))
        assertTrue(prefs.canSendFollowUpToday("+91 99887 76655", nowMs + DialerPreferences.ONE_DAY_MILLIS + 1L))

        prefs.recordShieldRejectedNumber("+91 91111 22222")
        assertTrue(prefs.isRejectedByShield("09111122222"))

        // 4. RealtimeDatabaseSync round-trip for all 8 VIP fields
        val vipUser = com.example.data.firebase.UserEntitlement(
            id = "u_vip_1",
            userId = "u_vip_1",
            isPremiumUser = true,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
            announcerEnabled = true,
            announcerHeadsetOnly = true,
            unknownShieldMode = com.example.data.firebase.UserEntitlement.SHIELD_SILENT_RECORD,
            autoSmsFollowUp = true,
            autoWhatsappFollowUp = true,
            followUpTrigger = com.example.data.firebase.UserEntitlement.TRIGGER_MISSED_ONLY,
            completedCallTemplate = "Custom completed reply!",
            missedCallTemplate = "Custom missed call reply!"
        )
        val rtdbMap = com.example.data.firebase.RealtimeDatabaseSync.toRtdbUserMap(vipUser)
        val parsedVip = com.example.data.firebase.RealtimeDatabaseSync.parseUserSnapshot("u_vip_1", rtdbMap, null)
        assertTrue(parsedVip.announcerEnabled)
        assertTrue(parsedVip.announcerHeadsetOnly)
        assertEquals(com.example.data.firebase.UserEntitlement.SHIELD_SILENT_RECORD, parsedVip.unknownShieldMode)
        assertTrue(parsedVip.autoSmsFollowUp)
        assertTrue(parsedVip.autoWhatsappFollowUp)
        assertEquals(com.example.data.firebase.UserEntitlement.TRIGGER_MISSED_ONLY, parsedVip.followUpTrigger)
        assertEquals("Custom completed reply!", parsedVip.completedCallTemplate)
        assertEquals("Custom missed call reply!", parsedVip.missedCallTemplate)

        com.example.data.firebase.SettingsManager.applyRtdbUserEntitlement(parsedVip, prefs)
        assertTrue(prefs.announcerEnabled)
        assertTrue(prefs.announcerHeadsetOnly)
        assertEquals(com.example.data.firebase.UserEntitlement.SHIELD_SILENT_RECORD, prefs.unknownShieldMode)
        assertTrue(prefs.autoSmsFollowUp)
        assertTrue(prefs.autoWhatsappFollowUp)
        assertEquals(com.example.data.firebase.UserEntitlement.TRIGGER_MISSED_ONLY, prefs.followUpTrigger)
        assertEquals("Custom completed reply!", prefs.completedCallTemplate)
        assertEquals("Custom missed call reply!", prefs.missedCallTemplate)

        // 5. Verify Settings and Account XML layouts contain all VIP cards and benefits
        val settingsView = android.view.LayoutInflater.from(themedContext).inflate(R.layout.fragment_settings, null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.card_vip_announcer_shield) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.switch_vip_announcer) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.switch_vip_announcer_headset) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.rg_vip_unknown_shield) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.card_vip_business_followup) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.switch_vip_auto_sms) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.switch_vip_auto_whatsapp) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.rg_vip_followup_trigger) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.et_vip_completed_template) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.et_vip_missed_template) != null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.btn_vip_save_templates) != null)

        val accountView = android.view.LayoutInflater.from(themedContext).inflate(R.layout.fragment_account, null)
        assertEquals(
            "Auto WhatsApp & SMS Business Follow-up",
            accountView.findViewById<android.widget.TextView>(R.id.tv_benefit_vip_followup).text.toString()
        )
        assertEquals(
            "Smart Call Name Announcer (Bike/Earphone Mode)",
            accountView.findViewById<android.widget.TextView>(R.id.tv_benefit_vip_announcer).text.toString()
        )
        assertEquals(
            "Unknown & Spam Caller Shield",
            accountView.findViewById<android.widget.TextView>(R.id.tv_benefit_vip_shield).text.toString()
        )
    }

    @Test
    fun `sha1 bypass fallback login generates deterministic userId with ANDROID_ID, registers ServerValue TIMESTAMP in RTDB, and unlocks 5 tab dialer`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        prefs.resetSessionEntitlementsOnSignOut()

        // 1. Verify deterministic userId pairing Email/Phone with Settings.Secure.ANDROID_ID
        val uidFromEmail1 = com.example.data.firebase.GoogleAuthHelper.buildDeterministicUserId(
            "Jitendrakumar888126@gmail.com",
            "android_hw_id_001"
        )
        val uidFromEmail2 = com.example.data.firebase.GoogleAuthHelper.buildDeterministicUserId(
            "  jitendrakumar888126@gmail.com  ",
            "ANDROID_HW_ID_001"
        )
        assertEquals(uidFromEmail1, uidFromEmail2)
        assertTrue(uidFromEmail1.startsWith("usr_"))

        val uidFromPhone1 = com.example.data.firebase.GoogleAuthHelper.buildDeterministicUserId(
            "+91 98765 43210",
            "android_hw_id_001"
        )
        val uidFromPhone2 = com.example.data.firebase.GoogleAuthHelper.buildDeterministicUserId(
            "09876543210",
            "android_hw_id_001"
        )
        assertEquals(uidFromPhone1, uidFromPhone2)

        // 2. Verify initial RTDB registration payload uses ServerValue.TIMESTAMP for installDate
        val initialPayload = com.example.data.firebase.RealtimeDatabaseSync.buildInitialUserRegistrationPayload(
            userId = uidFromEmail1,
            email = "Jitendrakumar888126@gmail.com",
            phone = "+919876543210",
            displayName = "Jitendra Kumar",
            deviceId = "android_hw_id_001",
            deviceHash = "f".repeat(64),
            isFirstRegistrationOnDevice = true
        )
        assertEquals(uidFromEmail1, initialPayload["uid"])
        assertEquals("Jitendrakumar888126@gmail.com", initialPayload["email"])
        assertEquals("+919876543210", initialPayload["phone"])
        assertEquals("android_hw_id_001", initialPayload["deviceId"])
        assertEquals(com.google.firebase.database.ServerValue.TIMESTAMP, initialPayload["installDate"])
        assertEquals("trial", initialPayload["subscriptionStatus"])
        assertEquals(false, initialPayload["isPremium"])
        assertEquals(false, initialPayload["isBlocked"])

        // 3. Verify completeFallbackLogin immediately persists session and marks user authenticated
        assertFalse(com.example.data.firebase.GoogleAuthHelper.isUserAuthenticated(app))
        val entitlement = com.example.data.firebase.GoogleAuthHelper.completeFallbackLogin(
            context = app,
            emailOrPhone = "Jitendrakumar888126@gmail.com",
            displayName = "Jitendra Kumar"
        )
        assertTrue(com.example.data.firebase.GoogleAuthHelper.isUserAuthenticated(app))
        assertTrue(prefs.isLoggedIn)
        assertEquals(entitlement.userId, prefs.loggedInUserId)
        assertEquals("Jitendrakumar888126@gmail.com", com.example.data.firebase.GoogleAuthHelper.resolveActiveEmail(app))
        assertEquals(entitlement.userId, com.example.data.firebase.GoogleAuthHelper.resolveActiveUid(app))

        // 4. Verify sign-out clears fallback session
        prefs.resetSessionEntitlementsOnSignOut()
        assertFalse(prefs.isLoggedIn)
        assertFalse(com.example.data.firebase.GoogleAuthHelper.isUserAuthenticated(app))
    }

    @Test
    fun `subscriptionEnforcementService revokes Pro immediately on single-node RTDB updates`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        prefs.resetSessionEntitlementsOnSignOut()
        com.example.billing.SubscriptionEnforcementService.stopMonitoring()

        val nowMs = 1_900_000_000_000L
        val expiredInstallMs = nowMs - (10L * DialerPreferences.ONE_DAY_MILLIS)

        // 1. Grant Pro on an expired trial
        val activeState = com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
            context = app,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
            isPremium = true,
            isBlocked = false,
            installDateMs = expiredInstallMs,
            nowMs = nowMs
        )
        assertTrue(activeState.isPremium)
        assertTrue(activeState.isSubscriptionActive)
        assertTrue(activeState.isRecordingAllowed)
        assertFalse(activeState.shouldRestrictUiToAccountTab)

        // 2. Single-node RTDB update: only /users/{uid}/isPremium -> false
        val revokedViaPremiumFlag = com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
            context = app,
            isPremium = false,
            nowMs = nowMs + 1000L
        )
        assertFalse(revokedViaPremiumFlag.isPremium)
        assertFalse(revokedViaPremiumFlag.isSubscriptionActive)
        assertFalse(revokedViaPremiumFlag.isRecordingAllowed)
        assertFalse(revokedViaPremiumFlag.shouldRestrictUiToAccountTab)

        // 3. Re-grant Pro, then revoke via single-node /users/{uid}/subscriptionStatus -> "expired"
        com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
            context = app,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
            isPremium = true,
            nowMs = nowMs + 2000L
        )
        val revokedViaStatusNode = com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
            context = app,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_EXPIRED,
            nowMs = nowMs + 3000L
        )
        assertFalse(revokedViaStatusNode.isPremium)
        assertFalse(revokedViaStatusNode.isSubscriptionActive)
        assertFalse(revokedViaStatusNode.isRecordingAllowed)
        assertFalse(revokedViaStatusNode.shouldRestrictUiToAccountTab)

        com.example.billing.SubscriptionEnforcementService.stopMonitoring()
    }

    @Test
    fun `realtimeDatabaseSync round-trips blockedNumbers and quickDeclineResponses and coerces mixed RTDB types`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        prefs.resetSessionEntitlementsOnSignOut()
        prefs.userPhoneNumber = "+91 90000 11111"

        val mixedRawUser = mapOf<String, Any?>(
            "uid" to "u_mixed_1",
            "email" to "user@example.com",
            "phone" to DialerPreferences.DEFAULT_USER_NUMBER,
            "isPremium" to "true",
            "isBlocked" to 0,
            "installDate" to "1800000000000",
            "announcerEnabled" to 1,
            "blockedNumbers" to listOf("9876543210", "9123456789"),
            "quickDeclineResponses" to listOf("Busy right now", "Call you back")
        )
        val parsedUser = com.example.data.firebase.RealtimeDatabaseSync.parseUserSnapshot(
            uid = "u_mixed_1",
            map = mixedRawUser,
            fallback = null
        )
        assertTrue(parsedUser.isPremiumUser)
        assertFalse(parsedUser.isBlocked)
        assertTrue(parsedUser.announcerEnabled)
        assertEquals(1800000000000L, parsedUser.installDate?.toDate()?.time)
        assertEquals(listOf("9876543210", "9123456789"), parsedUser.blockedNumbers)
        assertEquals(listOf("Busy right now", "Call you back"), parsedUser.quickDeclineResponses)

        com.example.data.firebase.SettingsManager.applyRtdbUserEntitlement(
            user = parsedUser,
            prefs = prefs,
            hasBlockedNumbersField = true
        )
        assertTrue(prefs.isNumberBlocked("+91 98765 43210"))
        assertEquals(listOf("Busy right now", "Call you back"), prefs.getQuickDeclineResponses())
        assertEquals("+91 90000 11111", prefs.userPhoneNumber)

        val mixedConfigMap = mapOf<String, Any?>(
            "trialDays" to "14",
            "subscriptionPriceInr" to "399",
            "allowCallRecording" to "false",
            "hideSubscriptionUI" to 1
        )
        val parsedConfig = com.example.data.firebase.RealtimeDatabaseSync.parseAppConfigSnapshot(
            map = mixedConfigMap,
            fallback = com.example.data.firebase.AppConfig()
        )
        assertEquals(14L, parsedConfig.trialDays)
        assertEquals(399L, parsedConfig.subscriptionPriceInr)
        assertFalse(parsedConfig.allowCallRecording)
        assertTrue(parsedConfig.hideSubscriptionUI)
    }

    @Test
    fun `dialerPreferences applyAppConfig preserves user customized toggles while enforcing recording policy`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        prefs.resetSessionEntitlementsOnSignOut()

        // Mark both toggles as explicitly customized by the user
        prefs.autoCallRecording = false
        prefs.markAutoRecordingCustomizedByUser()
        prefs.dialpadTonesEnabled = false
        prefs.markDialpadTonesCustomizedByUser()

        // Admin enables global defaults while recording is allowed -> user's custom choices stay intact
        prefs.applyAppConfig(
            com.example.data.firebase.AppConfig(
                allowCallRecording = true,
                defaultAutoRecording = true,
                dialpadTonesDefault = true
            )
        )
        assertFalse(prefs.autoCallRecording)
        assertFalse(prefs.dialpadTonesEnabled)

        // Even if user turned auto-recording ON, disabling allowCallRecording globally turns it OFF
        prefs.autoCallRecording = true
        prefs.applyAppConfig(
            com.example.data.firebase.AppConfig(
                allowCallRecording = false,
                defaultAutoRecording = true,
                dialpadTonesDefault = true
            )
        )
        assertFalse(prefs.autoCallRecording)
        assertFalse(prefs.dialpadTonesEnabled)
    }

    @Test
    fun `enhanced app logo resources, separate file manager folders, voip recording rules, and settings UI work`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val prefs = DialerPreferences(app)
        prefs.resetSessionEntitlementsOnSignOut()

        // 1. Enhanced adaptive launcher icon assets and color match
        val bgDrawable = androidx.core.content.ContextCompat.getDrawable(app, R.drawable.ic_launcher_background)
        assertTrue(bgDrawable is android.graphics.drawable.GradientDrawable)
        assertEquals(
            android.graphics.Color.parseColor("#08132F"),
            (bgDrawable as android.graphics.drawable.GradientDrawable).color?.defaultColor
        )
        assertTrue(androidx.core.content.ContextCompat.getDrawable(app, R.drawable.pulsedial_enhanced_icon_fg) != null)
        assertTrue(androidx.core.content.ContextCompat.getDrawable(app, R.drawable.ic_launcher_foreground) != null)
        assertTrue(androidx.core.content.ContextCompat.getDrawable(app, R.mipmap.ic_launcher) != null)
        assertTrue(androidx.core.content.ContextCompat.getDrawable(app, R.mipmap.ic_launcher_round) != null)

        // 2. VoIP package mapping & strictly separate File Manager folders
        assertEquals(
            com.example.recording.CallRecordingSource.WHATSAPP,
            com.example.recording.CallRecordingSource.fromPackageName("com.whatsapp")
        )
        assertEquals(
            com.example.recording.CallRecordingSource.WHATSAPP_BUSINESS,
            com.example.recording.CallRecordingSource.fromPackageName("com.whatsapp.w4b")
        )
        assertEquals(
            com.example.recording.CallRecordingSource.GOOGLE_MEET,
            com.example.recording.CallRecordingSource.fromPackageName("com.google.android.apps.meetings")
        )
        assertEquals(
            com.example.recording.CallRecordingSource.GOOGLE_MEET,
            com.example.recording.CallRecordingSource.fromPackageName("com.google.android.apps.tachyon")
        )
        assertEquals(
            com.example.recording.CallRecordingSource.TELEGRAM,
            com.example.recording.CallRecordingSource.fromPackageName("org.telegram.messenger")
        )
        assertEquals(
            com.example.recording.CallRecordingSource.INSTAGRAM,
            com.example.recording.CallRecordingSource.fromPackageName("com.instagram.android")
        )
        assertEquals(
            com.example.recording.CallRecordingSource.INSTAGRAM,
            com.example.recording.CallRecordingSource.fromPackageName("com.instagram.lite")
        )
        assertEquals(null, com.example.recording.CallRecordingSource.fromPackageName("com.example.other"))

        val paths = com.example.recording.CallRecordingSource.entries.map { it.relativePublicPath() }
        assertEquals(6, paths.toSet().size)
        assertEquals("Music/PulseDial/Cellular Call Recordings", com.example.recording.CallRecordingSource.CELLULAR.relativePublicPath())
        assertEquals("Music/PulseDial/WhatsApp Call Recordings", com.example.recording.CallRecordingSource.WHATSAPP.relativePublicPath())
        assertEquals("Music/PulseDial/WhatsApp Business Call Recordings", com.example.recording.CallRecordingSource.WHATSAPP_BUSINESS.relativePublicPath())
        assertEquals("Music/PulseDial/Meet Call Recordings", com.example.recording.CallRecordingSource.GOOGLE_MEET.relativePublicPath())
        assertEquals("Music/PulseDial/Telegram Call Recordings", com.example.recording.CallRecordingSource.TELEGRAM.relativePublicPath())
        assertEquals("Music/PulseDial/Instagram Call Recordings", com.example.recording.CallRecordingSource.INSTAGRAM.relativePublicPath())

        assertEquals(
            "+91_98765_43210_20261116_093000.m4a",
            com.example.recording.CallRecorder.buildFileName(
                "+91 98765 43210",
                com.example.recording.CallRecordingSource.CELLULAR,
                "20261116_093000"
            )
        )
        assertEquals(
            "WhatsApp_Business_+919876543210_20261116_093000.m4a",
            com.example.recording.CallRecorder.buildFileName(
                "+919876543210",
                com.example.recording.CallRecordingSource.WHATSAPP_BUSINESS,
                "20261116_093000"
            )
        )
        assertEquals(
            "WhatsApp_Rahul_20261116_093000.m4a",
            com.example.recording.CallRecorder.buildFileName(
                "Rahul",
                com.example.recording.CallRecordingSource.WHATSAPP,
                "20261116_093000"
            )
        )
        assertEquals(
            "Meet_TeamSync_20261116_093000.m4a",
            com.example.recording.CallRecorder.buildFileName(
                "TeamSync",
                com.example.recording.CallRecordingSource.GOOGLE_MEET,
                "20261116_093000"
            )
        )
        assertEquals(
            "Instagram_Friend_20261116_093000.m4a",
            com.example.recording.CallRecorder.buildFileName(
                "Friend",
                com.example.recording.CallRecordingSource.INSTAGRAM,
                "20261116_093000"
            )
        )

        // 3. VoIP entitlement, per-app toggles, Cellular preemption & Admin kill-switch
        prefs.recordWhatsappCalls = true
        prefs.recordWhatsappBusinessCalls = false
        prefs.recordMeetCalls = true
        prefs.recordTelegramCalls = true
        prefs.recordInstagramCalls = true

        assertTrue(
            com.example.recording.VoipCallRecorderManager.shouldRecordVoipSource(
                prefs = prefs,
                source = com.example.recording.CallRecordingSource.WHATSAPP,
                subscriptionActive = true,
                policyAllowed = true,
                adminBlocked = false,
                cellularCallActive = false
            )
        )
        assertTrue(
            com.example.recording.VoipCallRecorderManager.shouldRecordVoipSource(
                prefs = prefs,
                source = com.example.recording.CallRecordingSource.INSTAGRAM,
                subscriptionActive = true,
                policyAllowed = true,
                adminBlocked = false,
                cellularCallActive = false
            )
        )
        assertFalse(
            com.example.recording.VoipCallRecorderManager.shouldRecordVoipSource(
                prefs = prefs,
                source = com.example.recording.CallRecordingSource.WHATSAPP_BUSINESS,
                subscriptionActive = true,
                policyAllowed = true,
                adminBlocked = false,
                cellularCallActive = false
            )
        )
        assertFalse(
            com.example.recording.VoipCallRecorderManager.shouldRecordVoipSource(
                prefs = prefs,
                source = com.example.recording.CallRecordingSource.GOOGLE_MEET,
                subscriptionActive = true,
                policyAllowed = true,
                adminBlocked = false,
                cellularCallActive = true
            )
        )
        assertFalse(
            com.example.recording.VoipCallRecorderManager.shouldRecordVoipSource(
                prefs = prefs,
                source = com.example.recording.CallRecordingSource.TELEGRAM,
                subscriptionActive = false,
                policyAllowed = true,
                adminBlocked = false,
                cellularCallActive = false
            )
        )

        // 4. RealtimeDatabaseSync round-trip & SettingsManager apply for all 5 VoIP flags (including Instagram)
        val voipUser = com.example.data.firebase.UserEntitlement(
            id = "u_voip_1",
            userId = "u_voip_1",
            recordWhatsappCalls = false,
            recordWhatsappBusinessCalls = true,
            recordMeetCalls = false,
            recordTelegramCalls = true,
            recordInstagramCalls = false
        )
        val rtdbMap = com.example.data.firebase.RealtimeDatabaseSync.toRtdbUserMap(voipUser)
        val parsedVoip = com.example.data.firebase.RealtimeDatabaseSync.parseUserSnapshot("u_voip_1", rtdbMap, null)
        assertFalse(parsedVoip.recordWhatsappCalls)
        assertTrue(parsedVoip.recordWhatsappBusinessCalls)
        assertFalse(parsedVoip.recordMeetCalls)
        assertTrue(parsedVoip.recordTelegramCalls)
        assertFalse(parsedVoip.recordInstagramCalls)

        com.example.data.firebase.SettingsManager.applyRtdbUserEntitlement(parsedVoip, prefs)
        assertFalse(prefs.recordWhatsappCalls)
        assertTrue(prefs.recordWhatsappBusinessCalls)
        assertFalse(prefs.recordMeetCalls)
        assertTrue(prefs.recordTelegramCalls)
        assertFalse(prefs.recordInstagramCalls)

        // 5. Settings UI renders all 5 VoIP switches cleanly + compact header Folders button (zero inline folder clutter)
        val settingsView = android.view.LayoutInflater.from(themedContext).inflate(R.layout.fragment_settings, null)
        assertTrue(settingsView.findViewById<android.view.View>(R.id.card_voip_call_recording) != null)
        val swWa = settingsView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_record_whatsapp)
        val swWab = settingsView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_record_whatsapp_business)
        val swMeet = settingsView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_record_meet)
        val swTg = settingsView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_record_telegram)
        val swIg = settingsView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_record_instagram)
        val btnManageFolders = settingsView.findViewById<android.view.View>(R.id.btn_manage_voip_folders)
        assertEquals("WhatsApp", swWa.text.toString())
        assertEquals("WhatsApp Business", swWab.text.toString())
        assertEquals("Google Meet", swMeet.text.toString())
        assertEquals("Telegram", swTg.text.toString())
        assertEquals("Instagram", swIg.text.toString())
        assertEquals(android.view.View.VISIBLE, btnManageFolders.visibility)
        assertEquals(0, app.resources.getIdentifier("tv_voip_card_subtitle", "id", app.packageName))
        assertEquals(0, app.resources.getIdentifier("tv_whatsapp_folder_path", "id", app.packageName))
        assertEquals(0, app.resources.getIdentifier("layout_folder_whatsapp", "id", app.packageName))
        assertEquals(0, app.resources.getIdentifier("layout_folder_instagram", "id", app.packageName))
        assertEquals(0, app.resources.getIdentifier("tv_voip_folders_guide", "id", app.packageName))
        assertEquals(0, app.resources.getIdentifier("btn_open_file_manager_recordings", "id", app.packageName))
    }

    @Test
    fun `settingsFragment and subscriptionEnforcementService do not trigger infinite collector loop or ANR`() =
        kotlinx.coroutines.runBlocking {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            val prefs = DialerPreferences(app)
            prefs.resetSessionEntitlementsOnSignOut()
            com.example.billing.SubscriptionEnforcementService.stopMonitoring()

            val nowMs = System.currentTimeMillis()
            prefs.firstLaunchTime = nowMs
            prefs.isPremiumUser = false

            // Seed initial state
            com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
                context = app,
                subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_TRIAL,
                isPremium = false,
                isBlocked = false,
                installDateMs = nowMs,
                nowMs = nowMs
            )

            // Collect emissions while calling shouldRestrictUi / canStartCallRecording repeatedly at later timestamps
            var emissions = 0
            val collectorJob = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                com.example.billing.SubscriptionEnforcementService.state.collect {
                    emissions++
                }
            }
            val initialEmissions = emissions
            repeat(25) { step ->
                val checkNow = nowMs + (step + 1) * 50L
                assertFalse(com.example.billing.SubscriptionEnforcementService.shouldRestrictUi(app, checkNow))
                assertFalse(com.example.billing.SubscriptionEnforcementService.canStartCallRecording(app, checkNow))
            }
            assertEquals("No redundant StateFlow emissions should occur when nothing materially changed", initialEmissions, emissions)

            // A material change (e.g. upgrading to Pro) MUST immediately emit once
            com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
                context = app,
                subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
                isPremium = true,
                nowMs = nowMs + 2000L
            )
            assertEquals(initialEmissions + 1, emissions)
            collectorJob.cancel()

            // Launch SettingsFragment inside an AppCompatActivity and pump main looper to verify zero freeze/loop
            val activityController = org.robolectric.Robolectric.buildActivity(
                androidx.appcompat.app.AppCompatActivity::class.java
            )
            val hostActivity = activityController.get()
            hostActivity.setTheme(R.style.Theme_PulseDial)
            val container = android.widget.FrameLayout(hostActivity).apply {
                id = android.R.id.content
            }
            activityController.create()
            hostActivity.setContentView(container)
            activityController.start().resume()

            val settingsFragment = com.example.ui.settings.SettingsFragment()
            hostActivity.supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, settingsFragment)
                .commitNow()

            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            settingsFragment.refreshTrialUi()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            assertTrue(settingsFragment.isAdded)
            assertTrue(settingsFragment.view != null)
            assertTrue(settingsFragment.requireView().findViewById<android.view.View>(R.id.switch_record_whatsapp) != null)
            assertTrue(settingsFragment.requireView().findViewById<android.view.View>(R.id.switch_vip_announcer) != null)

            activityController.pause().stop().destroy()
            com.example.billing.SubscriptionEnforcementService.stopMonitoring()
        }

    @Test
    fun `multi-party conference helpers enforce swap, merge, split, and continuous recording rules`() {
        // 1. Hold / Swap decision matrix
        assertEquals(
            com.example.telecom.HoldSwapAction.HOLD,
            com.example.telecom.CallManager.resolveHoldSwapAction(listOf(android.telecom.Call.STATE_ACTIVE))
        )
        assertEquals(
            com.example.telecom.HoldSwapAction.UNHOLD,
            com.example.telecom.CallManager.resolveHoldSwapAction(listOf(android.telecom.Call.STATE_HOLDING))
        )
        assertEquals(
            com.example.telecom.HoldSwapAction.SWAP,
            com.example.telecom.CallManager.resolveHoldSwapAction(
                listOf(android.telecom.Call.STATE_ACTIVE, android.telecom.Call.STATE_HOLDING)
            )
        )

        // 2. Merge visibility & capability checks
        assertFalse(com.example.telecom.CallManager.shouldEnableMergeCalls(1, hasMergeCapability = false, hasConferenceableCalls = false))
        assertTrue(com.example.telecom.CallManager.shouldEnableMergeCalls(2, hasMergeCapability = false, hasConferenceableCalls = false))
        assertTrue(com.example.telecom.CallManager.shouldEnableMergeCalls(1, hasMergeCapability = true, hasConferenceableCalls = false))
        assertTrue(com.example.telecom.CallManager.shouldEnableMergeCalls(1, hasMergeCapability = false, hasConferenceableCalls = true))

        // 3. Split capability gate
        assertTrue(
            com.example.telecom.CallManager.canSplitParticipant(
                android.telecom.Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE
            )
        )
        assertFalse(com.example.telecom.CallManager.canSplitParticipant(android.telecom.Call.Details.CAPABILITY_HOLD))

        // 4. Seamless recording continuity across hold, dial 2nd caller, conference merge, and partial disconnect
        assertTrue(
            "Recording MUST continue while Call 1 is put on hold to dial Call 2",
            com.example.telecom.CallManager.shouldKeepRecordingActive(
                listOf(android.telecom.Call.STATE_HOLDING),
                totalTrackedCalls = 1
            )
        )
        assertTrue(
            "Recording MUST continue while Call 1 is on hold and Call 2 is dialing",
            com.example.telecom.CallManager.shouldKeepRecordingActive(
                listOf(android.telecom.Call.STATE_HOLDING, android.telecom.Call.STATE_DIALING),
                totalTrackedCalls = 2
            )
        )
        assertTrue(
            "Recording MUST continue if one participant disconnects while conference remains active",
            com.example.telecom.CallManager.shouldKeepRecordingActive(
                listOf(android.telecom.Call.STATE_DISCONNECTED, android.telecom.Call.STATE_ACTIVE),
                totalTrackedCalls = 2
            )
        )
        assertFalse(
            "Recording MUST stop when ALL calls are disconnected or removed",
            com.example.telecom.CallManager.shouldKeepRecordingActive(
                listOf(android.telecom.Call.STATE_DISCONNECTED),
                totalTrackedCalls = 1
            )
        )
        assertFalse(
            "Recording MUST stop when tracked calls list becomes empty",
            com.example.telecom.CallManager.shouldKeepRecordingActive(emptyList(), totalTrackedCalls = 0)
        )
    }

    @Test
    fun `in-call conference XML layouts and participant item include all conference actions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themedContext = android.view.ContextThemeWrapper(context, R.style.Theme_PulseDial)
        val inflater = android.view.LayoutInflater.from(themedContext)

        val inCallView = inflater.inflate(R.layout.activity_in_call, null)
        assertTrue(inCallView.findViewById<android.view.View>(R.id.card_secondary_call) != null)
        assertTrue(inCallView.findViewById<android.view.View>(R.id.btn_manage_conference) != null)
        assertTrue(inCallView.findViewById<android.view.View>(R.id.btn_add_call) != null)
        assertTrue(inCallView.findViewById<android.view.View>(R.id.btn_hold_swap) != null)
        assertTrue(inCallView.findViewById<android.view.View>(R.id.btn_merge_calls) != null)

        val sheetView = inflater.inflate(R.layout.bottom_sheet_conference_participants, null)
        assertTrue(sheetView.findViewById<android.view.View>(R.id.recycler_conference_participants) != null)
        assertTrue(sheetView.findViewById<android.view.View>(R.id.tv_conference_sheet_title) != null)

        val rowView = inflater.inflate(R.layout.item_conference_participant, null)
        assertTrue(rowView.findViewById<android.view.View>(R.id.tv_participant_name) != null)
        assertTrue(rowView.findViewById<android.view.View>(R.id.tv_participant_number) != null)
        assertTrue(rowView.findViewById<android.view.View>(R.id.btn_split) != null)
        assertTrue(rowView.findViewById<android.view.View>(R.id.btn_drop) != null)

        val formattedTitle = context.getString(R.string.incall_conference_title, 3)
        assertEquals("Conference Call (3 Participants)", formattedTitle)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `proximity sensor toggles speakerphone on and off during active multi-party calls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sensorManager = context.getSystemService(android.hardware.SensorManager::class.java)
        val shadowSensorManager = org.robolectric.Shadows.shadowOf(sensorManager)
        val proximitySensor = org.robolectric.shadows.ShadowSensor.newInstance(
            android.hardware.Sensor.TYPE_PROXIMITY
        )
        shadowSensorManager.addSensor(proximitySensor)

        // Pure range math check
        assertTrue(com.example.telecom.ProximitySpeakerController.isNearEar(0.0f, 5.0f))
        assertFalse(com.example.telecom.ProximitySpeakerController.isNearEar(5.0f, 5.0f))
        assertTrue(com.example.telecom.ProximitySpeakerController.isNearEar(2.5f, 8.0f))
        assertFalse(com.example.telecom.ProximitySpeakerController.isNearEar(6.0f, 8.0f))

        // Active multi-party detection check
        assertFalse(
            com.example.telecom.CallManager.isActiveMultiPartyCall(
                com.example.telecom.CallUiState(
                    hasActiveCall = true,
                    callState = android.telecom.Call.STATE_ACTIVE,
                    topLevelCallCount = 1,
                    isConference = false
                )
            )
        )
        assertTrue(
            com.example.telecom.CallManager.isActiveMultiPartyCall(
                com.example.telecom.CallUiState(
                    hasActiveCall = true,
                    callState = android.telecom.Call.STATE_ACTIVE,
                    isConference = true,
                    conferenceParticipantCount = 2
                )
            )
        )
        assertTrue(
            com.example.telecom.CallManager.isActiveMultiPartyCall(
                com.example.telecom.CallUiState(
                    hasActiveCall = true,
                    callState = android.telecom.Call.STATE_ACTIVE,
                    topLevelCallCount = 2
                )
            )
        )

        val speakerEvents = mutableListOf<Boolean>()
        val controller = com.example.telecom.ProximitySpeakerController(context) { speakerOn ->
            speakerEvents.add(speakerOn)
        }

        // Single 1:1 call does NOT activate listener
        controller.onCallUiStateChanged(
            com.example.telecom.CallUiState(
                hasActiveCall = true,
                callState = android.telecom.Call.STATE_ACTIVE,
                topLevelCallCount = 1
            )
        )
        assertFalse(controller.isListening)

        // Merged conference / multi-party call activates listener
        controller.onCallUiStateChanged(
            com.example.telecom.CallUiState(
                hasActiveCall = true,
                callState = android.telecom.Call.STATE_ACTIVE,
                isConference = true,
                conferenceParticipantCount = 3
            )
        )
        assertTrue(controller.isListening)
        assertTrue(shadowSensorManager.hasListener(controller))

        // Moving device away from ear -> speakerphone ON
        controller.handleProximityDistance(distanceCm = 5.0f, maxRangeCm = 5.0f)
        // Duplicate far reading should not emit again
        controller.handleProximityDistance(distanceCm = 5.0f, maxRangeCm = 5.0f)
        // Moving device toward ear -> speakerphone OFF
        controller.handleProximityDistance(distanceCm = 0.0f, maxRangeCm = 5.0f)
        // Moving device away again -> speakerphone ON
        controller.handleProximityDistance(distanceCm = 5.0f, maxRangeCm = 5.0f)

        assertEquals(listOf(true, false, true), speakerEvents)

        // Connected Bluetooth / wired headset is respected without switching to speaker/earpiece
        controller.onAudioRouteChanged(android.telecom.CallAudioState.ROUTE_BLUETOOTH)
        controller.handleProximityDistance(distanceCm = 0.0f, maxRangeCm = 5.0f)
        assertEquals(3, speakerEvents.size)

        // Ending multi-party call unregisters the proximity listener cleanly
        controller.onCallUiStateChanged(com.example.telecom.CallUiState(hasActiveCall = false))
        assertFalse(controller.isListening)
        assertFalse(shadowSensorManager.hasListener(controller))
        controller.release()
    }

    @Test
    fun `per-app voip recording folders persist independently, target chosen directory directly, and bind in SettingsFragment`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        prefs.resetSessionEntitlementsOnSignOut()
        com.example.billing.SubscriptionEnforcementService.stopMonitoring()
        prefs.firstLaunchTime = System.currentTimeMillis()
        prefs.isPremiumUser = true

        // Clean slate
        com.example.recording.CallRecordingSource.entries.forEach { prefs.clearCustomFolder(it) }
        prefs.recordingFolderUri = null
        prefs.recordingFolderName = null

        // 1. Assign completely separate File Manager folders to each Other App
        val waUri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads%2FWA_Recs"
        val wabUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBizWA_Recs"
        val meetUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FMeetCalls"
        val tgUri = "content://com.android.externalstorage.documents/tree/primary%3APodcasts%2FTGCalls"

        prefs.setCustomFolder(com.example.recording.CallRecordingSource.WHATSAPP, waUri, "WA_Recs")
        prefs.setCustomFolder(com.example.recording.CallRecordingSource.WHATSAPP_BUSINESS, wabUri, "BizWA_Recs")
        prefs.setCustomFolder(com.example.recording.CallRecordingSource.GOOGLE_MEET, meetUri, "MeetCalls")
        prefs.setCustomFolder(com.example.recording.CallRecordingSource.TELEGRAM, tgUri, "TGCalls")

        val waTarget = prefs.resolveFolderTarget(com.example.recording.CallRecordingSource.WHATSAPP)
        val wabTarget = prefs.resolveFolderTarget(com.example.recording.CallRecordingSource.WHATSAPP_BUSINESS)
        val meetTarget = prefs.resolveFolderTarget(com.example.recording.CallRecordingSource.GOOGLE_MEET)
        val tgTarget = prefs.resolveFolderTarget(com.example.recording.CallRecordingSource.TELEGRAM)
        val cellularTarget = prefs.resolveFolderTarget(com.example.recording.CallRecordingSource.CELLULAR)

        assertEquals(waUri, waTarget.treeUri)
        assertEquals("WA_Recs", waTarget.displayName)
        assertTrue(waTarget.isCustomPerSource)
        assertFalse(waTarget.createSubfolder)

        assertEquals(wabUri, wabTarget.treeUri)
        assertEquals("BizWA_Recs", wabTarget.displayName)
        assertTrue(wabTarget.isCustomPerSource)
        assertFalse(wabTarget.createSubfolder)

        assertEquals(meetUri, meetTarget.treeUri)
        assertEquals("MeetCalls", meetTarget.displayName)
        assertTrue(meetTarget.isCustomPerSource)
        assertFalse(meetTarget.createSubfolder)

        assertEquals(tgUri, tgTarget.treeUri)
        assertEquals("TGCalls", tgTarget.displayName)
        assertTrue(tgTarget.isCustomPerSource)
        assertFalse(tgTarget.createSubfolder)

        // Cellular remains unaffected on its own default path
        assertEquals(null, cellularTarget.treeUri)
        assertEquals("Music/PulseDial/Cellular Call Recordings", cellularTarget.displayName)
        assertFalse(cellularTarget.isCustomPerSource)
        assertTrue(cellularTarget.createSubfolder)

        // 2. DocumentFile direct-save vs subfolder verification using local temp DocumentFile tree
        val tempRootDir = java.io.File(app.cacheDir, "test_saf_root_${System.nanoTime()}").apply { mkdirs() }
        try {
            val rootDoc = androidx.documentfile.provider.DocumentFile.fromFile(tempRootDir)
            val directTarget = com.example.recording.CallRecorder.resolveSafTargetDirectory(
                rootDir = rootDoc,
                source = com.example.recording.CallRecordingSource.WHATSAPP,
                createSubfolder = waTarget.createSubfolder
            )
            assertEquals(tempRootDir.name, directTarget.name)

            val subTarget = com.example.recording.CallRecorder.resolveSafTargetDirectory(
                rootDir = rootDoc,
                source = com.example.recording.CallRecordingSource.CELLULAR,
                createSubfolder = cellularTarget.createSubfolder
            )
            assertEquals("Cellular Call Recordings", subTarget.name)
        } finally {
            tempRootDir.deleteRecursively()
        }

        // 3. Clearing one app reverts only that app without affecting the other 3
        prefs.clearCustomFolder(com.example.recording.CallRecordingSource.GOOGLE_MEET)
        val meetDefault = prefs.resolveFolderTarget(com.example.recording.CallRecordingSource.GOOGLE_MEET)
        assertEquals(null, meetDefault.treeUri)
        assertEquals("Music/PulseDial/Meet Call Recordings", meetDefault.displayName)
        assertFalse(meetDefault.isCustomPerSource)
        assertEquals(waUri, prefs.resolveFolderTarget(com.example.recording.CallRecordingSource.WHATSAPP).treeUri)

        // 4. Render inside SettingsFragment and open Per-App Folders dialog from compact Folders button
        val activityController = org.robolectric.Robolectric.buildActivity(
            androidx.appcompat.app.AppCompatActivity::class.java
        )
        val hostActivity = activityController.get()
        hostActivity.setTheme(R.style.Theme_PulseDial)
        val container = android.widget.FrameLayout(hostActivity).apply { id = android.R.id.content }
        activityController.create()
        hostActivity.setContentView(container)
        activityController.start().resume()

        val settingsFragment = com.example.ui.settings.SettingsFragment()
        hostActivity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, settingsFragment)
            .commitNow()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val view = settingsFragment.requireView()
        val btnManageFolders = view.findViewById<android.widget.Button>(R.id.btn_manage_voip_folders)
        assertTrue(btnManageFolders.isEnabled)
        btnManageFolders.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val latestDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        assertTrue(latestDialog.isShowing)
        val listView = latestDialog.listView
        val adapter = listView.adapter
        val itemTexts = (0 until adapter.count).map { adapter.getItem(it).toString() }
        assertTrue(itemTexts.any { it.contains("WhatsApp") && it.contains("WA_Recs") })
        assertTrue(itemTexts.any { it.contains("WhatsApp Business") && it.contains("BizWA_Recs") })
        assertTrue(itemTexts.any { it.contains("Google Meet") && it.contains("Music/PulseDial/Meet Call Recordings") })
        assertTrue(itemTexts.any { it.contains("Telegram") && it.contains("TGCalls") })
        latestDialog.dismiss()

        activityController.pause().stop().destroy()
    }

    @Test
    fun `call history csv exporter formats RFC4180 csv, writes to MediaStore Documents, and wires HistoryFragment button`(): Unit =
        kotlinx.coroutines.runBlocking {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            org.robolectric.Shadows.shadowOf(app).grantPermissions(
                android.Manifest.permission.READ_CALL_LOG
            )

            // 1. Pure CSV generation & escaping check
            val utcTz = java.util.TimeZone.getTimeZone("UTC")
            val sampleCalls = listOf(
                com.example.data.CallLogEntry(
                    id = 101L,
                    name = "Sharma, \"Alice\"",
                    number = "+91 98765 43210",
                    type = android.provider.CallLog.Calls.INCOMING_TYPE,
                    timestampMillis = 1_700_000_000_000L,
                    durationSeconds = 3910L
                ),
                com.example.data.CallLogEntry(
                    id = 102L,
                    name = null,
                    number = "+91 91234 56789",
                    type = android.provider.CallLog.Calls.MISSED_TYPE,
                    timestampMillis = 1_700_000_060_000L,
                    durationSeconds = 0L
                ),
                com.example.data.CallLogEntry(
                    id = 103L,
                    name = "Bob\nOffice",
                    number = "02240001234",
                    type = android.provider.CallLog.Calls.OUTGOING_TYPE,
                    timestampMillis = 1_700_000_120_000L,
                    durationSeconds = 135L
                )
            )
            val csv = com.example.data.CallHistoryCsvExporter.buildCsvContent(sampleCalls, utcTz)
            assertTrue(csv.startsWith("ID,Contact Name,Phone Number,Call Type,Date Time,Timestamp (ms),Duration (seconds),Duration (Formatted)\n"))
            assertTrue(csv.contains("101,\"Sharma, \"\"Alice\"\"\",+91 98765 43210,Incoming,"))
            assertTrue(csv.contains(",3910,1h 5m 10s\n"))
            assertTrue(csv.contains("102,,+91 91234 56789,Missed,"))
            assertTrue(csv.contains(",0,0s\n"))
            assertTrue(csv.contains("103,\"Bob\nOffice\",02240001234,Outgoing,"))
            assertTrue(csv.contains(",135,2m 15s\n"))

            val exporter = com.example.data.CallHistoryCsvExporter(app)
            assertFalse(exporter.needsLegacyWritePermission())

            // Empty call list fails gracefully
            val emptyResult = exporter.exportToDocuments(emptyList())
            assertFalse(emptyResult.success)

            // 2. MediaStore.Files Documents export + IS_PENDING lifecycle + CallLog provider for HistoryFragment
            val exportedBackingFile = java.io.File(app.cacheDir, "test_exported_history_${System.nanoTime()}.csv")
            exportedBackingFile.createNewFile()
            var insertedRelativePath: String? = null
            var insertedMimeType: String? = null
            var updatedPendingState: Int? = null
            val mediaItemUri = android.net.Uri.parse("content://${android.provider.MediaStore.AUTHORITY}/external_primary/file/777")

            val fakeMediaStoreProvider = object : android.content.ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: android.net.Uri) = "text/csv"
                override fun query(
                    uri: android.net.Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                    sortOrder: String?
                ) = null

                override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri {
                    insertedRelativePath = values?.getAsString(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
                    insertedMimeType = values?.getAsString(android.provider.MediaStore.MediaColumns.MIME_TYPE)
                    return mediaItemUri
                }

                override fun update(
                    uri: android.net.Uri,
                    values: android.content.ContentValues?,
                    selection: String?,
                    selectionArgs: Array<out String>?
                ): Int {
                    if (values?.containsKey(android.provider.MediaStore.MediaColumns.IS_PENDING) == true) {
                        updatedPendingState = values.getAsInteger(android.provider.MediaStore.MediaColumns.IS_PENDING)
                    }
                    return 1
                }

                override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 1

                override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
                    return android.os.ParcelFileDescriptor.open(
                        exportedBackingFile,
                        android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_TRUNCATE
                    )
                }
            }
            fakeMediaStoreProvider.attachInfo(
                app,
                android.content.pm.ProviderInfo().apply {
                    authority = android.provider.MediaStore.AUTHORITY
                }
            )
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
                android.provider.MediaStore.AUTHORITY,
                fakeMediaStoreProvider
            )

            val fakeCallLogProvider = object : android.content.ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: android.net.Uri) = null
                override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
                override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun query(
                    uri: android.net.Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                    sortOrder: String?
                ): android.database.Cursor {
                    val cols = arrayOf(
                        android.provider.CallLog.Calls._ID,
                        android.provider.CallLog.Calls.CACHED_NAME,
                        android.provider.CallLog.Calls.NUMBER,
                        android.provider.CallLog.Calls.TYPE,
                        android.provider.CallLog.Calls.DATE,
                        android.provider.CallLog.Calls.DURATION
                    )
                    return android.database.MatrixCursor(cols).apply {
                        addRow(arrayOf<Any?>(1L, "Alice", "+91 98765 43210", android.provider.CallLog.Calls.INCOMING_TYPE, 1_700_000_000_000L, 45L))
                        addRow(arrayOf<Any?>(2L, "Bob", "+91 91234 56789", android.provider.CallLog.Calls.OUTGOING_TYPE, 1_700_000_010_000L, 12L))
                    }
                }
            }
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
                android.provider.CallLog.AUTHORITY,
                fakeCallLogProvider
            )

            try {
                val fixedNow = 1_700_000_000_000L
                val result = exporter.exportToDocuments(sampleCalls, nowMs = fixedNow)
                assertTrue(result.errorMessage ?: "Export failed", result.success)
                assertEquals(3, result.exportedCount)
                assertTrue(result.fileName.startsWith("PulseDial_CallHistory_"))
                assertTrue(result.fileName.endsWith(".csv"))
                assertEquals(android.os.Environment.DIRECTORY_DOCUMENTS, insertedRelativePath)
                assertEquals("text/csv", insertedMimeType)
                assertEquals(0, updatedPendingState)
                assertEquals(mediaItemUri, result.fileUri)

                val writtenCsv = exportedBackingFile.readText(java.nio.charset.StandardCharsets.UTF_8)
                assertTrue(writtenCsv.contains("Sharma, \"\"Alice\"\""))
                assertTrue(writtenCsv.contains("02240001234"))

                // 3. HistoryFragment hosts btn_export_history_csv and exports on click
                val activityController = org.robolectric.Robolectric.buildActivity(
                    androidx.appcompat.app.AppCompatActivity::class.java
                )
                val hostActivity = activityController.get()
                hostActivity.setTheme(R.style.Theme_PulseDial)
                val container = android.widget.FrameLayout(hostActivity).apply { id = android.R.id.content }
                activityController.create()
                hostActivity.setContentView(container)
                activityController.start().resume()

                val historyFragment = com.example.ui.history.HistoryFragment()
                hostActivity.supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, historyFragment)
                    .commitNow()

                // Allow Dispatchers.IO reloadData coroutine to finish cleanly
                Thread.sleep(150)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

                val exportBtn = historyFragment.requireView().findViewById<android.view.View>(R.id.btn_export_history_csv)
                assertTrue(exportBtn != null)
                assertEquals(android.view.View.VISIBLE, historyFragment.requireView().findViewById<android.view.View>(R.id.layout_recent_calls_actions).visibility)

                exportBtn.performClick()
                Thread.sleep(150)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

                val exportedFromUi = exportedBackingFile.readText(java.nio.charset.StandardCharsets.UTF_8)
                assertTrue(exportedFromUi.contains("Alice"))
                assertTrue(exportedFromUi.contains("+91 91234 56789"))

                activityController.pause().stop().destroy()
            } finally {
                exportedBackingFile.delete()
            }
        }

    @Test
    fun `custom popup ad rtdb mapping, trigger logic, countdown dialog behavior, and admin controller layout work`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()

        // 1. Verify RTDB /custom_popup_ad parsing & publish map (preserving atomic counters)
        val rawSnapshot = mapOf(
            "adId" to "ad_1700000999",
            "isEnabled" to true,
            "showToPremiumUsers" to false,
            "triggerMode" to "AFTER_CALL_END",
            "imageUrl" to "",
            "title" to "Join PulseDial VIP Telegram",
            "message" to "Get daily feature drops and instant support.",
            "buttonText" to "Join Telegram",
            "targetUrl" to "t.me/pulsedial_vip",
            "countdownSeconds" to 3L,
            "impressionsCount" to 145L,
            "clicksCount" to 32L
        )
        val parsed = com.example.data.firebase.RealtimeDatabaseSync.parseCustomPopupAdSnapshot(rawSnapshot)
        assertEquals("ad_1700000999", parsed.adId)
        assertTrue(parsed.isEnabled)
        assertFalse(parsed.showToPremiumUsers)
        assertEquals(com.example.data.firebase.CustomPopupAd.TRIGGER_AFTER_CALL_END, parsed.triggerMode)
        assertEquals("Join PulseDial VIP Telegram", parsed.title)
        assertEquals("Join Telegram", parsed.buttonText)
        assertEquals(3, parsed.countdownSeconds)
        assertEquals(145L, parsed.impressionsCount)
        assertEquals(32L, parsed.clicksCount)

        val publishMap = com.example.data.firebase.RealtimeDatabaseSync.toRtdbCustomPopupAdPublishMap(parsed)
        assertFalse(publishMap.containsKey("impressionsCount"))
        assertFalse(publishMap.containsKey("clicksCount"))
        assertEquals("ad_1700000999", publishMap["adId"])
        assertEquals(3, publishMap["countdownSeconds"])

        // 2. Verify Trigger Mode + showToPremiumUsers gating
        assertFalse(parsed.shouldDisplayFor(com.example.data.firebase.CustomPopupAd.TRIGGER_ON_APP_OPEN, isPremiumUser = false))
        assertTrue(parsed.shouldDisplayFor(com.example.data.firebase.CustomPopupAd.TRIGGER_AFTER_CALL_END, isPremiumUser = false))
        assertFalse(parsed.shouldDisplayFor(com.example.data.firebase.CustomPopupAd.TRIGGER_AFTER_CALL_END, isPremiumUser = true))

        val bothAd = parsed.copy(
            triggerMode = com.example.data.firebase.CustomPopupAd.TRIGGER_BOTH,
            showToPremiumUsers = true
        )
        assertTrue(bothAd.shouldDisplayFor(com.example.data.firebase.CustomPopupAd.TRIGGER_ON_APP_OPEN, isPremiumUser = true))
        assertTrue(bothAd.shouldDisplayFor(com.example.data.firebase.CustomPopupAd.TRIGGER_AFTER_CALL_END, isPremiumUser = true))
        assertFalse(bothAd.copy(isEnabled = false).shouldDisplayFor(com.example.data.firebase.CustomPopupAd.TRIGGER_ON_APP_OPEN, isPremiumUser = false))
        assertEquals("https://t.me/pulsedial_vip", com.example.data.firebase.CustomPopupAd.normalizeTargetUrl(parsed.targetUrl))

        // 3. Verify CustomAdPopupDialog UI, countdown lock -> unlock at 0s, placeholder, and CTA Intent
        val controller = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_PulseDial)
        val container = android.widget.FrameLayout(activity).apply { id = android.R.id.content }
        controller.create()
        activity.setContentView(container)
        controller.start().resume()

        val dialog = com.example.ui.ads.CustomAdPopupDialog.showIfNotAlreadyVisible(
            activity.supportFragmentManager,
            parsed
        )
        assertTrue(dialog != null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val rootView = dialog!!.requireView()
        val closeBtn = rootView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_ad_close_badge)
        val sponsoredTag = rootView.findViewById<android.widget.TextView>(R.id.tv_ad_sponsored_badge)
        val placeholder = rootView.findViewById<android.view.View>(R.id.layout_ad_placeholder)
        val ctaBtn = rootView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_ad_cta)
        val titleTv = rootView.findViewById<android.widget.TextView>(R.id.tv_ad_title)

        assertEquals(app.getString(R.string.custom_ad_sponsored_tag), sponsoredTag.text.toString())
        assertEquals(android.view.View.VISIBLE, placeholder.visibility)
        assertEquals("Join PulseDial VIP Telegram", titleTv.text.toString())
        assertEquals("Join Telegram", ctaBtn.text.toString())
        assertFalse(closeBtn.isEnabled)
        assertFalse(dialog.isCancelable)
        assertEquals(app.getString(R.string.custom_ad_wait_countdown, 3), closeBtn.text.toString())

        val shadowMainLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        // Advance 1s -> "Wait 2s…"
        shadowMainLooper.idleFor(java.time.Duration.ofMillis(1001))
        assertEquals(app.getString(R.string.custom_ad_wait_countdown, 2), closeBtn.text.toString())
        assertFalse(closeBtn.isEnabled)

        // Advance 1s -> "Wait 1s…"
        shadowMainLooper.idleFor(java.time.Duration.ofMillis(1001))
        assertEquals(app.getString(R.string.custom_ad_wait_countdown, 1), closeBtn.text.toString())
        assertFalse(closeBtn.isEnabled)

        // Advance final 1s -> "✕ Close" unlocked
        shadowMainLooper.idleFor(java.time.Duration.ofMillis(1001))
        assertTrue(closeBtn.isEnabled)
        assertTrue(dialog.isCancelable)
        assertEquals(app.getString(R.string.custom_ad_close_label), closeBtn.text.toString())

        // Clicking CTA fires ACTION_VIEW for normalized URL
        ctaBtn.performClick()
        val launched = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, launched.action)
        assertEquals("https://t.me/pulsedial_vip", launched.data.toString())

        controller.pause().stop().destroy()

        // 4. Verify Admin Panel Manage Custom Popup Ads controller views in activity_admin_panel.xml
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val panelRoot = android.view.LayoutInflater.from(themedContext).inflate(R.layout.activity_admin_panel, null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.card_admin_custom_popup_ad) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.tv_popup_ad_impressions_badge) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.tv_popup_ad_clicks_badge) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.switch_popup_ad_enable) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.switch_popup_ad_show_premium) != null)
        assertTrue(panelRoot.findViewById<android.widget.RadioGroup>(R.id.rg_popup_ad_trigger_mode) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.et_popup_ad_image_url) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.et_popup_ad_title) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.et_popup_ad_message) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.et_popup_ad_button_text) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.et_popup_ad_target_url) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.et_popup_ad_countdown) != null)
        assertTrue(panelRoot.findViewById<android.view.View>(R.id.btn_publish_custom_popup_ad) != null)
    }

    @Test
    fun `strict dual super admin emails, delegation, anti tamper, and non leaking denied guard work`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)

        // 1. Only the two exact Super-Admin emails are Super-Admins
        assertTrue(com.example.billing.UpiPaymentHelper.isSuperAdminEmail("0143pushpendra@gmail.com"))
        assertTrue(com.example.billing.UpiPaymentHelper.isSuperAdminEmail("  0143PUSHPENDRA@gmail.com "))
        assertTrue(com.example.billing.UpiPaymentHelper.isSuperAdminEmail("jitendrakumar888126@gmail.com"))
        assertFalse(com.example.billing.UpiPaymentHelper.isSuperAdminEmail("random@gmail.com"))
        assertFalse(com.example.billing.UpiPaymentHelper.isSuperAdminEmail(""))

        // 2. Normal user without Super-Admin delegation cannot access Admin even when signed in
        prefs.isLoggedIn = true
        prefs.userEmail = "normal.user@gmail.com"
        prefs.setDelegatedAdmin(authorized = false, grantedBy = "")
        assertFalse(com.example.billing.UpiPaymentHelper.isAuthorizedAdmin(app, prefs))

        // Forging delegated admin with a non-super-admin granter is rejected
        prefs.setDelegatedAdmin(authorized = true, grantedBy = "fake.admin@gmail.com")
        assertFalse(com.example.billing.UpiPaymentHelper.isAuthorizedAdmin(app, prefs))

        // Valid delegation from either Super-Admin is accepted (when not blocked/tampered)
        prefs.adminBlockedUser = false
        prefs.setDelegatedAdmin(authorized = true, grantedBy = "0143pushpendra@gmail.com")
        assertTrue(com.example.billing.UpiPaymentHelper.isAuthorizedAdmin(app, prefs))

        // 3. Raw SharedPreferences XML tampering is caught by HMAC and strips delegated/premium flags
        val rawPrefs = app.getSharedPreferences("pulsedial_preferences", Context.MODE_PRIVATE)
        rawPrefs.edit().putBoolean(DialerPreferences.KEY_IS_PREMIUM_USER, true).commit()
        assertFalse(prefs.verifyIntegrityOrReset())
        assertTrue(prefs.tamperDetected)
        assertFalse(prefs.delegatedAdminAuthorized)
        assertFalse(com.example.billing.UpiPaymentHelper.isAuthorizedAdmin(app, prefs))

        // 4. Denied message never exposes either Super-Admin email
        val deniedMessage = app.getString(R.string.admin_access_denied)
        assertFalse(deniedMessage.contains("0143pushpendra", ignoreCase = true))
        assertFalse(deniedMessage.contains("jitendrakumar888126", ignoreCase = true))

        // 5. Unauthorized launch of AdminPanelActivity immediately finishes without exposing panel
        val cleanPrefs = DialerPreferences(app)
        cleanPrefs.userEmail = "intruder@gmail.com"
        cleanPrefs.isLoggedIn = true
        cleanPrefs.setDelegatedAdmin(authorized = false, grantedBy = "")
        val adminController = org.robolectric.Robolectric.buildActivity(com.example.ui.admin.AdminPanelActivity::class.java)
        adminController.create()
        val adminActivity = adminController.get()
        assertTrue(adminActivity.isFinishing)
        assertEquals(deniedMessage, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        adminController.destroy()

        // 6. Clock rollback beyond tolerance triggers SecurityIntegrityGuard.isClockTampered
        val baseNow = 1_750_000_000_000L
        cleanPrefs.serverClockOffsetMs = 0L
        rawPrefs.edit().putLong(DialerPreferences.KEY_LAST_SEEN_TIME, baseNow).commit()
        assertTrue(com.example.data.SecurityIntegrityGuard.isClockTampered(cleanPrefs, baseNow - (20L * 60L * 1000L)))
        assertFalse(com.example.data.SecurityIntegrityGuard.isClockTampered(cleanPrefs, baseNow + 5_000L))
    }

    @Test
    fun `one time utr lock and subscription day addition or subtraction work accurately`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)

        // 1. Canonical UTR extraction & normalization
        val req1 = com.example.data.firebase.PaymentRequest(
            id = "req_1",
            userId = "user_a",
            userName = "User A",
            userEmail = "a@example.com",
            upiTxnId = "4123-4567-8901",
            status = com.example.data.firebase.PaymentRequest.STATUS_PENDING
        )
        val canonical = com.example.data.firebase.PaymentRepository.extractCanonicalUtr(req1)
        val utrKey = com.example.data.firebase.PaymentRepository.normalizeUtrKey(canonical)
        assertEquals("4123-4567-8901", canonical)
        assertEquals("4123-4567-8901", utrKey)
        assertEquals("UTR41234567", com.example.data.firebase.PaymentRepository.normalizeUtrKey("  utr 4123/4567  "))

        // 2. PaymentRequestsAdapter hides Approve button once UTR is in approvedUtrKeys
        val payAdapter = com.example.ui.account.PaymentRequestsAdapter(onApproveClick = {}, onRejectClick = {})
        payAdapter.approvedUtrKeys = setOf(utrKey)
        payAdapter.submitList(listOf(req1))
        val parent = android.widget.FrameLayout(themedContext)
        val payHolder = payAdapter.onCreateViewHolder(parent, 0)
        payAdapter.onBindViewHolder(payHolder, 0)
        assertEquals(
            android.view.View.GONE,
            payHolder.itemView.findViewById<android.view.View>(R.id.btn_approve_request).visibility
        )
        assertTrue(
            payHolder.itemView.findViewById<android.widget.TextView>(R.id.tv_req_txn_details)
                .text.toString()
                .contains(app.getString(R.string.admin_utr_already_approved_once))
        )

        // 3. AdminUsersAdapter hides UTR Approve button when user's pending UTR is already locked
        val pendingUser = com.example.data.firebase.UserEntitlement(
            id = "user_b",
            userId = "user_b",
            displayName = "User B",
            email = "b@example.com",
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_PENDING_APPROVAL,
            paymentRefId = "4123-4567-8901",
            isAdmin = true,
            adminGrantedBy = "0143pushpendra@gmail.com"
        )
        val usersAdapter = com.example.ui.admin.AdminUsersAdapter(
            onToggleProClick = {},
            onRenewMonthClick = {},
            onExtendTrialClick = {},
            onToggleBlockClick = {},
            onManageClick = {}
        )
        usersAdapter.approvedUtrKeys = setOf(utrKey)
        usersAdapter.submitList(listOf(pendingUser))
        val userHolder = usersAdapter.onCreateViewHolder(parent, 0)
        usersAdapter.onBindViewHolder(userHolder, 0)
        assertEquals(
            android.view.View.GONE,
            userHolder.itemView.findViewById<android.view.View>(R.id.btn_user_approve_utr).visibility
        )
        assertTrue(
            userHolder.itemView.findViewById<android.widget.TextView>(R.id.tv_admin_user_name)
                .text.toString()
                .contains("[Admin]")
        )

        // 4. Subscription days addition and subtraction
        val nowMs = 1_750_000_000_000L
        val freeUser = com.example.data.firebase.UserEntitlement(userId = "u_sub")
        val addedMs = com.example.data.firebase.AdminRepository.computeAdjustedSubscriptionExpiryMs(
            user = freeUser,
            deltaDays = 15L,
            nowMs = nowMs
        )
        assertEquals(nowMs + 15L * DialerPreferences.ONE_DAY_MILLIS, addedMs)

        val activeUser = freeUser.copy(
            isPremiumUser = true,
            subscriptionExpiresAt = com.google.firebase.Timestamp(java.util.Date(addedMs))
        )
        val reducedMs = com.example.data.firebase.AdminRepository.computeAdjustedSubscriptionExpiryMs(
            user = activeUser,
            deltaDays = -5L,
            nowMs = nowMs
        )
        assertEquals(nowMs + 10L * DialerPreferences.ONE_DAY_MILLIS, reducedMs)

        // Subtracting more days than remaining expires immediately (0L)
        val expiredBySubMs = com.example.data.firebase.AdminRepository.computeAdjustedSubscriptionExpiryMs(
            user = activeUser,
            deltaDays = -20L,
            nowMs = nowMs
        )
        assertEquals(0L, expiredBySubMs)
    }

    @Test
    fun `sim number helper cleans numbers and caller id resolver identifies unknown caller and location`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)

        // 1. SimNumberHelper formats valid SIM numbers and safely returns null without permission
        assertEquals("+91 9876543210", com.example.data.SimNumberHelper.cleanPhoneNumber("9876543210"))
        assertEquals("+14155551234", com.example.data.SimNumberHelper.cleanPhoneNumber("+1 415-555-1234"))
        assertEquals(null, com.example.data.SimNumberHelper.cleanPhoneNumber("0000000000"))
        assertEquals(null, com.example.data.SimNumberHelper.resolveDeviceSimNumber(app))

        // 2. CallerIdResolver resolves unknown caller name, helpline, and deterministic structured location (zero randomness)
        val unknownMobile = com.example.telecom.CallerIdResolver.resolveInstant("+91 98765 43210")
        assertTrue(unknownMobile.isUnknownNumber)
        assertEquals("Unknown Caller", unknownMobile.displayName)
        assertEquals("Chandigarh, Punjab 160001, India", unknownMobile.location)
        assertEquals("Chandigarh", unknownMobile.city)
        assertEquals("Punjab", unknownMobile.state)
        assertEquals("160001", unknownMobile.postalCode)
        assertEquals("India", unknownMobile.country)

        val cyberHelpline = com.example.telecom.CallerIdResolver.resolveInstant("1930")
        assertTrue(cyberHelpline.isUnknownNumber)
        assertEquals("Cyber Crime Helpline (1930)", cyberHelpline.displayName)

        assertEquals("Mumbai, Maharashtra 400001, India", com.example.telecom.CallerIdResolver.resolveLocation("02226543210"))
        assertEquals("San Francisco, California 94102, United States", com.example.telecom.CallerIdResolver.resolveLocation("+14155552671"))

        // 3. In-Call XML contains caller location label & Caller ID popup card with dismiss control
        val inCallRoot = android.view.LayoutInflater.from(themedContext).inflate(R.layout.activity_in_call, null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_incall_location) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.card_caller_id_popup) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.btn_dismiss_caller_popup) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_popup_caller_name) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_popup_caller_location) != null)
    }

    @Test
    fun `future payment gateways default to disabled while upi stays active and admin toggles sync end to end`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val prefs = DialerPreferences(app)
        prefs.resetSessionEntitlementsOnSignOut()

        // 1. Default AppConfig keeps UPI active and Razorpay / PayU / Custom OFF
        val defaultConfig = com.example.data.firebase.AppConfig()
        assertTrue(defaultConfig.enableUpiGateway)
        assertFalse(defaultConfig.enableRazorpayGateway)
        assertFalse(defaultConfig.enablePayuGateway)
        assertFalse(defaultConfig.enableCustomGateway)
        assertTrue(prefs.enableUpiGateway)
        assertFalse(prefs.enableRazorpayGateway)
        assertFalse(prefs.enablePayuGateway)
        assertFalse(prefs.enableCustomGateway)

        // 2. Enabling Razorpay, PayU & Custom Gateway syncs through RTDB map -> parser -> DialerPreferences + HMAC
        val adminEnabled = defaultConfig.copy(
            enableUpiGateway = true,
            enableRazorpayGateway = true,
            razorpayKeyId = "rzp_live_123456",
            razorpayPaymentLink = "https://rzp.io/l/pulsedial",
            enablePayuGateway = true,
            payuMerchantKey = "payu_merch_99",
            payuPaymentLink = "https://pmny.in/pulsedial",
            enableCustomGateway = true,
            customGatewayName = "PhonePe PG",
            customGatewayUrl = "https://pay.pulsedial.example/checkout"
        )
        val rtdbMap = com.example.data.firebase.RealtimeDatabaseSync.toRtdbConfigMap(adminEnabled)
        val parsed = com.example.data.firebase.RealtimeDatabaseSync.parseAppConfigSnapshot(rtdbMap, defaultConfig)
        assertTrue(parsed.enableRazorpayGateway)
        assertEquals("rzp_live_123456", parsed.razorpayKeyId)
        assertEquals("https://rzp.io/l/pulsedial", parsed.razorpayPaymentLink)
        assertTrue(parsed.enablePayuGateway)
        assertEquals("payu_merch_99", parsed.payuMerchantKey)
        assertEquals("https://pmny.in/pulsedial", parsed.payuPaymentLink)
        assertTrue(parsed.enableCustomGateway)
        assertEquals("PhonePe PG", parsed.customGatewayName)
        assertEquals("https://pay.pulsedial.example/checkout", parsed.customGatewayUrl)

        prefs.applyAppConfig(parsed)
        assertTrue(prefs.verifyIntegrityOrReset())
        assertTrue(prefs.enableRazorpayGateway)
        assertEquals("https://rzp.io/l/pulsedial", prefs.configuredRazorpayPaymentLink)
        assertTrue(prefs.enablePayuGateway)
        assertEquals("payu_merch_99", prefs.configuredPayuMerchantKey)
        assertTrue(prefs.enableCustomGateway)
        assertEquals("PhonePe PG", prefs.configuredCustomGatewayName)

        // 3. Admin & Account layouts include all gateway views and Account defaults hide them
        val adminView = android.view.LayoutInflater.from(themedContext).inflate(R.layout.activity_admin_panel, null)
        assertTrue(adminView.findViewById<android.view.View>(R.id.switch_config_enable_upi) != null)
        assertTrue(adminView.findViewById<android.view.View>(R.id.switch_config_enable_razorpay) != null)
        assertTrue(adminView.findViewById<android.view.View>(R.id.switch_config_enable_payu) != null)
        assertTrue(adminView.findViewById<android.view.View>(R.id.switch_config_enable_custom_gw) != null)

        val accountView = android.view.LayoutInflater.from(themedContext).inflate(R.layout.fragment_account, null)
        assertEquals(android.view.View.GONE, accountView.findViewById<android.view.View>(R.id.btn_pay_razorpay).visibility)
        assertEquals(android.view.View.GONE, accountView.findViewById<android.view.View>(R.id.btn_pay_payu).visibility)
        assertEquals(android.view.View.GONE, accountView.findViewById<android.view.View>(R.id.btn_pay_custom_gateway).visibility)
    }

    @Test
    fun `expired subscription freezes premium rows silently while keeping normal dialer unlocked and supports gmail contacts`() =
        kotlinx.coroutines.runBlocking {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
            val prefs = DialerPreferences(app)
            prefs.resetSessionEntitlementsOnSignOut()

            // 1. Force expired trial (no Pro): normal app navigation stays unlocked
            prefs.isLoggedIn = true
            prefs.loggedInUserId = "usr_expired_1"
            prefs.userEmail = "customer@gmail.com"
            prefs.firstLaunchTime = System.currentTimeMillis() - (10L * DialerPreferences.ONE_DAY_MILLIS)
            prefs.isPremiumUser = false
            prefs.maintenanceMode = false
            prefs.adminBlockedUser = false

            val subMgr = com.example.billing.SubscriptionManager(app)
            assertFalse(subMgr.isSubscriptionActive())

            if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) {
                val defaultInit = com.google.firebase.FirebaseApp.initializeApp(app)
                if (defaultInit == null) {
                    com.google.firebase.FirebaseApp.initializeApp(
                        app,
                        com.google.firebase.FirebaseOptions.Builder()
                            .setApplicationId("1:113923036774:android:0000000000000000")
                            .setProjectId("gen-lang-client-0353534085")
                            .setApiKey("test-api-key")
                            .setDatabaseUrl("https://gen-lang-client-0353534085-default-rtdb.firebaseio.com")
                            .build()
                    )
                }
            }

            val mainController = org.robolectric.Robolectric.buildActivity(com.example.ui.MainActivity::class.java)
            mainController.create()
            val mainActivity = mainController.get()
            assertFalse("Trial expiry must NOT lock normal calling/contacts tabs", mainActivity.isAppLockedByTrialExpiry())
            mainController.destroy()

            // 2. DynamicSettingsAdapter visually & functionally freezes premium rows cleanly
            val frozenRow = com.example.ui.settings.DynamicSettingUiRow(
                item = com.example.data.firebase.DynamicSettingItem.defaultItems()
                    .first { it.key == com.example.data.firebase.DynamicSettingItem.KEY_AUTO_RECORDING },
                resolvedDescription = "Auto record",
                isChecked = false,
                isInteractive = false,
                isLastItem = false,
                isFrozenBySubscription = true
            )
            var clickTriggered = false
            val adapter = com.example.ui.settings.DynamicSettingsAdapter(
                onRowClicked = { clickTriggered = true },
                onToggleChanged = { _, _ -> true }
            )
            adapter.submitList(listOf(frozenRow))
            val parent = android.widget.FrameLayout(themedContext)
            val holder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(holder, 0)
            val switchView = holder.itemView.findViewById<android.view.View>(R.id.switch_dynamic_setting)
            val rowView = holder.itemView.findViewById<android.view.View>(R.id.row_dynamic_setting)
            assertFalse(switchView.isEnabled)
            assertFalse(rowView.isClickable)
            rowView.performClick()
            assertFalse(clickTriggered)

            // 3. Gmail account discovery & dialog_save_contact renders account selector
            val repo = com.example.data.ContactsRepository(app)
            val gmailList = repo.getGoogleAccountNames()
            assertTrue(gmailList.contains("customer@gmail.com"))

            val saveDialogView = android.view.LayoutInflater.from(themedContext).inflate(R.layout.dialog_save_contact, null)
            assertTrue(saveDialogView.findViewById<android.view.View>(R.id.tv_save_account_label) != null)
            assertTrue(saveDialogView.findViewById<android.view.View>(R.id.rg_save_account) != null)
            assertEquals("com.google", com.example.data.ContactsRepository.GOOGLE_ACCOUNT_TYPE)
        }

    @Test
    fun `sync fixes enforce zero expiry, bonus trial reduction, and dual sim preferences persist across sign out`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)

        // 1. Dual SIM preference survives resetSessionEntitlementsOnSignOut()
        prefs.preferredSimAccountId = "sim_slot_2_sub_99"
        prefs.resetSessionEntitlementsOnSignOut()
        assertEquals("sim_slot_2_sub_99", prefs.preferredSimAccountId)

        // 2. DualSimManager resolves preferred SIM & alternate SIM deterministically
        val sim1 = com.example.telecom.SimCardInfo(
            accountHandle = null,
            accountId = "sub_1",
            slotIndex = 1,
            label = "SIM 1",
            carrierName = "Jio",
            number = "+919876543210"
        )
        val sim2 = com.example.telecom.SimCardInfo(
            accountHandle = null,
            accountId = "sub_2",
            slotIndex = 2,
            label = "SIM 2",
            carrierName = "Airtel",
            number = "+919123456789"
        )
        val sims = listOf(sim1, sim2)
        assertEquals(sim2, com.example.telecom.DualSimManager.resolvePreferredSim(sims, "sub_2"))
        assertEquals(null, com.example.telecom.DualSimManager.resolvePreferredSim(sims, ""))
        assertEquals(sim2, com.example.telecom.DualSimManager.resolveAlternateSim(sims, "sub_1"))
        assertEquals(sim1, com.example.telecom.DualSimManager.resolveAlternateSim(sims, "sub_2"))
        assertEquals("SIM 1 • Jio", sim1.displayLabel())
        assertEquals("SIM 2", sim2.shortLabel())

        // 3. RealtimeDatabaseSync.parseUserSnapshot honors explicit null/0 subscriptionExpiresAtMs
        val nowMs = System.currentTimeMillis()
        val futureExp = com.google.firebase.Timestamp(java.util.Date(nowMs + 20L * DialerPreferences.ONE_DAY_MILLIS))
        val fallbackPro = com.example.data.firebase.UserEntitlement(
            id = "u_sync",
            userId = "u_sync",
            isPremiumUser = true,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
            subscriptionExpiresAt = futureExp,
            bonusTrialDays = 10L
        )
        val clearedSnap = com.example.data.firebase.RealtimeDatabaseSync.parseUserSnapshot(
            uid = "u_sync",
            map = mapOf(
                "isPremium" to false,
                "isPremiumUser" to false,
                "subscriptionStatus" to com.example.data.firebase.UserEntitlement.STATUS_EXPIRED,
                "subscriptionExpiresAtMs" to 0L,
                "bonusTrialDays" to 0L
            ),
            fallback = fallbackPro
        )
        assertFalse(clearedSnap.isPremiumUser)
        assertEquals(null, clearedSnap.subscriptionExpiresAt)
        assertEquals(0L, clearedSnap.bonusTrialDays)

        // 4. SubscriptionEnforcementService honors 0 bonusTrialDays and 0L expiry when cleared
        prefs.applyEnforcementBatch(
            isPremium = true,
            isBlocked = false,
            subscriptionExpiresAtMs = nowMs + 15L * DialerPreferences.ONE_DAY_MILLIS,
            installDateMs = nowMs - 2L * DialerPreferences.ONE_DAY_MILLIS,
            bonusTrialDays = 10
        )
        com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
            context = app,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_TRIAL,
            isPremium = false,
            isBlocked = false,
            subscriptionExpiresAtMs = 0L,
            bonusTrialDays = 0,
            nowMs = nowMs
        )
        assertFalse(prefs.isPremiumUser)
        assertEquals(0L, prefs.subscriptionExpiresAtMs)
        assertEquals(0, prefs.adminBonusTrialDays)
    }

    @Test
    fun `admin subscriber cohorts include old expired and new trial users and inflate new layouts`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)

        val nowMs = 1_700_000_000_000L
        val expiringExp = com.google.firebase.Timestamp(java.util.Date(nowMs + 3L * DialerPreferences.ONE_DAY_MILLIS))
        val healthyExp = com.google.firebase.Timestamp(java.util.Date(nowMs + 25L * DialerPreferences.ONE_DAY_MILLIS))
        val activeTrialStart = com.google.firebase.Timestamp(java.util.Date(nowMs - 2L * DialerPreferences.ONE_DAY_MILLIS))
        val oldTrialStart = com.google.firebase.Timestamp(java.util.Date(nowMs - 14L * DialerPreferences.ONE_DAY_MILLIS))

        val expiringPro = com.example.data.firebase.UserEntitlement(
            id = "u_expiring",
            userId = "u_expiring",
            displayName = "Expiring Pro",
            trialStartedAt = oldTrialStart,
            isPremiumUser = true,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
            subscriptionExpiresAt = expiringExp
        )
        val activePro = com.example.data.firebase.UserEntitlement(
            id = "u_active",
            userId = "u_active",
            displayName = "Healthy Pro",
            trialStartedAt = oldTrialStart,
            isPremiumUser = true,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
            subscriptionExpiresAt = healthyExp
        )
        val oldExpired = com.example.data.firebase.UserEntitlement(
            id = "u_old",
            userId = "u_old",
            displayName = "Old Expired",
            trialStartedAt = oldTrialStart,
            isPremiumUser = false,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_EXPIRED
        )
        val newTrial = com.example.data.firebase.UserEntitlement(
            id = "u_new",
            userId = "u_new",
            displayName = "New Trial",
            trialStartedAt = activeTrialStart,
            isPremiumUser = false,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_TRIAL
        )

        val allUsers = listOf(expiringPro, activePro, oldExpired, newTrial)
        assertEquals(
            listOf("u_expiring"),
            com.example.data.firebase.AdminRepository.filterSubscribers(
                allUsers,
                com.example.data.firebase.AdminSubscriberFilter.EXPIRING_SOON,
                "",
                nowMs,
                7
            ).map { it.userId }
        )
        assertEquals(
            listOf("u_expiring", "u_active"),
            com.example.data.firebase.AdminRepository.filterSubscribers(
                allUsers,
                com.example.data.firebase.AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS,
                "",
                nowMs,
                7
            ).map { it.userId }
        )
        assertEquals(
            listOf("u_old"),
            com.example.data.firebase.AdminRepository.filterSubscribers(
                allUsers,
                com.example.data.firebase.AdminSubscriberFilter.OLD_EXPIRED_USERS,
                "",
                nowMs,
                7
            ).map { it.userId }
        )
        assertEquals(
            listOf("u_new"),
            com.example.data.firebase.AdminRepository.filterSubscribers(
                allUsers,
                com.example.data.firebase.AdminSubscriberFilter.NEW_TRIAL_USERS,
                "",
                nowMs,
                7
            ).map { it.userId }
        )

        // Verify all new UI views inflate cleanly in XML
        val inflater = android.view.LayoutInflater.from(themedContext)
        val inCallRoot = inflater.inflate(R.layout.activity_in_call, null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_incall_sim_badge) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.container_switch_sim) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.btn_incall_switch_sim) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_incall_switch_sim_label) != null)

        val dialpadRoot = inflater.inflate(R.layout.bottom_sheet_dialpad, null)
        assertTrue(dialpadRoot.findViewById<android.view.View>(R.id.chip_dialpad_sim) != null)

        val settingsRoot = inflater.inflate(R.layout.fragment_settings, null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.card_default_calling_sim) != null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.tv_default_sim_summary) != null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.btn_select_default_sim) != null)

        val adminRoot = inflater.inflate(R.layout.activity_admin_panel, null)
        assertTrue(adminRoot.findViewById<android.view.View>(R.id.rb_subscriber_old_expired) != null)
        assertTrue(adminRoot.findViewById<android.view.View>(R.id.rb_subscriber_new_trial) != null)

        val expiringItemRoot = inflater.inflate(R.layout.item_admin_expiring_user, null)
        assertTrue(expiringItemRoot.findViewById<android.view.View>(R.id.btn_expiring_minus_7) != null)
        assertTrue(expiringItemRoot.findViewById<android.view.View>(R.id.btn_expiring_minus_1) != null)
        assertTrue(expiringItemRoot.findViewById<android.view.View>(R.id.btn_expiring_plus_7) != null)

        val userItemRoot = inflater.inflate(R.layout.item_admin_user, null)
        assertTrue(userItemRoot.findViewById<android.view.View>(R.id.btn_user_reduce_days) != null)
        assertTrue(userItemRoot.findViewById<android.view.View>(R.id.btn_user_delete) != null)
        assertTrue(adminRoot.findViewById<android.view.View>(R.id.btn_admin_add_user) != null)
    }

    @Test
    fun `admin can add user button and delete user button with super admin delete protection work`() =
        kotlinx.coroutines.runBlocking {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)

            val regularUser = com.example.data.firebase.UserEntitlement(
                id = "u_regular",
                userId = "u_regular",
                displayName = "Regular Member",
                email = "regular@example.com"
            )
            val superAdminUser = com.example.data.firebase.UserEntitlement(
                id = "u_super",
                userId = "u_super",
                displayName = "Pushpendra",
                email = "0143pushpendra@gmail.com",
                isAdmin = true
            )

            var deletedUid: String? = null
            val adapter = com.example.ui.admin.AdminUsersAdapter(
                onToggleProClick = {},
                onRenewMonthClick = {},
                onExtendTrialClick = {},
                onToggleBlockClick = {},
                onManageClick = {},
                onDeleteClick = { deletedUid = it.userId }
            )
            adapter.submitList(listOf(regularUser, superAdminUser))

            val parent = android.widget.FrameLayout(themedContext)
            val regularHolder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(regularHolder, 0)
            val regularDeleteBtn = regularHolder.itemView.findViewById<android.view.View>(R.id.btn_user_delete)
            assertEquals(android.view.View.VISIBLE, regularDeleteBtn.visibility)
            regularDeleteBtn.performClick()
            assertEquals("u_regular", deletedUid)

            val superHolder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(superHolder, 1)
            val superDeleteBtn = superHolder.itemView.findViewById<android.view.View>(R.id.btn_user_delete)
            assertEquals(android.view.View.GONE, superDeleteBtn.visibility)

            // Verify RTDB-only AdminRepository createUserByAdmin and deleteUserByAdmin Super Admin guard
            if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) {
                val defaultInit = com.google.firebase.FirebaseApp.initializeApp(app)
                if (defaultInit == null) {
                    com.google.firebase.FirebaseApp.initializeApp(
                        app,
                        com.google.firebase.FirebaseOptions.Builder()
                            .setApplicationId("1:113923036774:android:0000000000000000")
                            .setProjectId("gen-lang-client-0353534085")
                            .setApiKey("test-api-key")
                            .setDatabaseUrl("https://gen-lang-client-0353534085-default-rtdb.firebaseio.com")
                            .build()
                    )
                }
            }
            val mockAuth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(
                app.getString(R.string.firestore_database_id)
            )
            val adminRepo = com.example.data.firebase.AdminRepository(
                db = firestore,
                auth = mockAuth,
                fallbackUidProvider = { "admin_session_uid" }
            )
            val created = adminRepo.createUserByAdmin(
                displayName = "Created In Admin",
                email = "created@example.com",
                phoneNumber = "+919988776655",
                isPremium = true,
                subscriptionDays = 30L,
                bonusTrialDays = 0L,
                autoCallRecording = true,
                hideSubscriptionUI = false,
                adminNote = "Manual member"
            ).getOrThrow()
            assertEquals("Created In Admin", created.displayName)
            assertEquals("created@example.com", created.email)
            assertTrue(created.isPremiumUser)
            assertEquals(com.example.data.firebase.UserEntitlement.STATUS_ACTIVE, created.subscriptionStatus)

            val superDeleteRes = adminRepo.deleteUserByAdmin(
                user = superAdminUser,
                actingAdminEmail = "jitendrakumar888126@gmail.com"
            )
            assertTrue(superDeleteRes.isFailure)

            val regDeleteRes = adminRepo.deleteUserByAdmin(
                user = created,
                actingAdminEmail = "jitendrakumar888126@gmail.com"
            )
            assertTrue(regDeleteRes.isSuccess)
        }

    @Test
    fun `manifest drops broad audio read permissions and registers Play-Protect-safe VoIP notification listener`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val pm = app.packageManager
        val pkgInfo = pm.getPackageInfo(
            app.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS
        )
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse(perms.contains(android.Manifest.permission.READ_MEDIA_AUDIO))
        assertFalse(perms.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))

        val serviceNames = pkgInfo.services.orEmpty().map { it.name }.toSet()
        assertTrue(serviceNames.contains("com.example.recording.VoipCallNotificationListener"))

        val receiverNames = pkgInfo.receivers.orEmpty().map { it.name }.toSet()
        assertTrue(receiverNames.contains("com.example.telecom.CallNotificationActionReceiver"))
    }

    @Test
    fun `voip notification listener detects call notifications and ignores regular chat messages`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()

        val categoryCallNotif = android.app.Notification.Builder(app, com.example.PulseDialApp.CHANNEL_INCOMING_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setCategory(android.app.Notification.CATEGORY_CALL)
            .setContentTitle("Rahul")
            .setContentText("Incoming voice call")
            .build()
        assertTrue(com.example.recording.VoipCallNotificationListener.isCallNotification(categoryCallNotif))

        val ongoingCallNotif = android.app.Notification.Builder(app, com.example.PulseDialApp.CHANNEL_ONGOING_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .setContentTitle("WhatsApp")
            .setContentText("Ongoing voice call • 01:12")
            .build()
        assertTrue(com.example.recording.VoipCallNotificationListener.isCallNotification(ongoingCallNotif))

        val regularChatNotif = android.app.Notification.Builder(app, com.example.PulseDialApp.CHANNEL_INCOMING_CALLS)
            .setSmallIcon(R.drawable.ic_message)
            .setCategory(android.app.Notification.CATEGORY_MESSAGE)
            .setContentTitle("Amit")
            .setContentText("Hey, free now?")
            .build()
        assertFalse(com.example.recording.VoipCallNotificationListener.isCallNotification(regularChatNotif))
    }

    @Test
    fun `crash logger captures non-fatal diagnostics and clears cleanly`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        com.example.data.PulseDialCrashLogger.clearLastCrash(app)
        assertEquals(null, com.example.data.PulseDialCrashLogger.getLastCrashSummary(app))

        com.example.data.PulseDialCrashLogger.logNonFatal(
            app,
            "AudioRoute",
            IllegalStateException("Test diagnostic")
        )
        val captured = com.example.data.PulseDialCrashLogger.getLastCrashSummary(app)
        assertTrue(!captured.isNullOrBlank())
        assertTrue(captured!!.contains("AudioRoute"))
        assertTrue(captured.contains("Test diagnostic"))

        com.example.data.PulseDialCrashLogger.clearLastCrash(app)
        assertEquals(null, com.example.data.PulseDialCrashLogger.getLastCrashSummary(app))
    }

    @Test
    fun `callManager updates bluetooth audio route and supported mask accurately`() {
        val btPlusSpeakerMask = android.telecom.CallAudioState.ROUTE_BLUETOOTH or
            android.telecom.CallAudioState.ROUTE_EARPIECE or
            android.telecom.CallAudioState.ROUTE_SPEAKER

        com.example.telecom.CallManager.updateAudioState(
            isMuted = false,
            isSpeakerOn = false,
            audioRoute = android.telecom.CallAudioState.ROUTE_BLUETOOTH,
            supportedAudioRoutes = btPlusSpeakerMask
        )
        val state = com.example.telecom.CallManager.uiState.value
        assertEquals(android.telecom.CallAudioState.ROUTE_BLUETOOTH, state.audioRoute)
        assertEquals(btPlusSpeakerMask, state.supportedAudioRoutes)
        assertFalse(state.isSpeakerOn)
    }

    @Test
    fun `mergeEntitlementSources preserves active admin subscription against stale trial snapshot`() {
        val nowMs = System.currentTimeMillis()
        val futureExpiry = com.google.firebase.Timestamp(java.util.Date(nowMs + 30L * DialerPreferences.ONE_DAY_MILLIS))

        // Firestore has a stale trial snapshot (even with a newer client clock timestamp)
        val staleFirestoreTrial = com.example.data.firebase.UserEntitlement(
            id = "u_sync_1",
            userId = "u_sync_1",
            email = "subscriber@example.com",
            isPremiumUser = false,
            subscriptionStatus = "trial",
            subscriptionExpiresAt = null,
            updatedAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 5_000L))
        )

        // RTDB has an active Admin-granted subscription
        val rtdbAdminGrant = com.example.data.firebase.UserEntitlement(
            id = "u_sync_1",
            userId = "u_sync_1",
            email = "subscriber@example.com",
            isPremiumUser = true,
            subscriptionStatus = "active",
            subscriptionExpiresAt = futureExpiry,
            updatedAt = com.google.firebase.Timestamp(java.util.Date(nowMs))
        )

        val merged = com.example.data.firebase.DeviceTrialRepository.mergeEntitlementSources(
            firestoreUser = staleFirestoreTrial,
            rtdbUser = rtdbAdminGrant,
            nowMs = nowMs
        )!!
        assertTrue(merged.isPremiumUser)
        assertEquals("active", merged.subscriptionStatus)
        assertEquals(futureExpiry.toDate().time, merged.subscriptionExpiresAt?.toDate()?.time)

        // Also test when Admin explicitly revokes subscription (newer timestamp with status="revoked")
        val rtdbAdminRevoke = rtdbAdminGrant.copy(
            isPremiumUser = false,
            subscriptionStatus = "revoked",
            subscriptionExpiresAt = null,
            updatedAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 20_000L))
        )
        val mergedAfterRevoke = com.example.data.firebase.DeviceTrialRepository.mergeEntitlementSources(
            firestoreUser = rtdbAdminGrant,
            rtdbUser = rtdbAdminRevoke,
            nowMs = nowMs
        )!!
        assertFalse(mergedAfterRevoke.isPremiumUser)
        assertEquals("revoked", mergedAfterRevoke.subscriptionStatus)
    }

    @Test
    fun `admin filterUsers filters accurately by join date and expiry date presets and custom range`() {
        val nowMs = System.currentTimeMillis()
        val oneDay = DialerPreferences.ONE_DAY_MILLIS

        val userToday = com.example.data.firebase.UserEntitlement(
            id = "u_today",
            userId = "u_today",
            displayName = "Today User",
            email = "today@example.com",
            createdAt = com.google.firebase.Timestamp(java.util.Date(nowMs - 1_000L)),
            subscriptionExpiresAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 2L * oneDay))
        )
        val user5DaysAgo = com.example.data.firebase.UserEntitlement(
            id = "u_5d",
            userId = "u_5d",
            displayName = "Five Days User",
            email = "fivedays@example.com",
            createdAt = com.google.firebase.Timestamp(java.util.Date(nowMs - 5L * oneDay)),
            subscriptionExpiresAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 15L * oneDay))
        )
        val user20DaysAgo = com.example.data.firebase.UserEntitlement(
            id = "u_20d",
            userId = "u_20d",
            displayName = "Twenty Days User",
            email = "twentydays@example.com",
            createdAt = com.google.firebase.Timestamp(java.util.Date(nowMs - 20L * oneDay)),
            subscriptionExpiresAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 45L * oneDay))
        )
        val user60DaysAgo = com.example.data.firebase.UserEntitlement(
            id = "u_60d",
            userId = "u_60d",
            displayName = "Old User",
            email = "old@example.com",
            createdAt = com.google.firebase.Timestamp(java.util.Date(nowMs - 60L * oneDay)),
            subscriptionExpiresAt = com.google.firebase.Timestamp(java.util.Date(nowMs + 60L * oneDay))
        )

        val users = listOf(userToday, user5DaysAgo, user20DaysAgo, user60DaysAgo)

        val todayFiltered = com.example.data.firebase.AdminRepository.filterUsers(
            users = users,
            filter = com.example.data.firebase.AdminUserFilter.ALL,
            query = "",
            trialDaysConfig = 7,
            nowMs = nowMs,
            dateFilter = com.example.data.firebase.AdminUserDateFilter.TODAY,
            dateField = com.example.data.firebase.AdminUserDateField.JOIN_DATE
        )
        assertEquals(listOf("u_today"), todayFiltered.map { it.userId })

        val last7DaysFiltered = com.example.data.firebase.AdminRepository.filterUsers(
            users = users,
            filter = com.example.data.firebase.AdminUserFilter.ALL,
            query = "",
            trialDaysConfig = 7,
            nowMs = nowMs,
            dateFilter = com.example.data.firebase.AdminUserDateFilter.LAST_7_DAYS,
            dateField = com.example.data.firebase.AdminUserDateField.JOIN_DATE
        )
        assertEquals(listOf("u_today", "u_5d"), last7DaysFiltered.map { it.userId })

        val last30DaysFiltered = com.example.data.firebase.AdminRepository.filterUsers(
            users = users,
            filter = com.example.data.firebase.AdminUserFilter.ALL,
            query = "",
            trialDaysConfig = 7,
            nowMs = nowMs,
            dateFilter = com.example.data.firebase.AdminUserDateFilter.LAST_30_DAYS,
            dateField = com.example.data.firebase.AdminUserDateField.JOIN_DATE
        )
        assertEquals(listOf("u_today", "u_5d", "u_20d"), last30DaysFiltered.map { it.userId })

        val customRangeFiltered = com.example.data.firebase.AdminRepository.filterUsers(
            users = users,
            filter = com.example.data.firebase.AdminUserFilter.ALL,
            query = "",
            trialDaysConfig = 7,
            nowMs = nowMs,
            dateFilter = com.example.data.firebase.AdminUserDateFilter.CUSTOM_RANGE,
            dateField = com.example.data.firebase.AdminUserDateField.EXPIRY_DATE,
            customStartMs = nowMs + 10L * oneDay,
            customEndMs = nowMs + 50L * oneDay
        )
        assertEquals(listOf("u_5d", "u_20d"), customRangeFiltered.map { it.userId })
    }

    @Test
    fun `material3 0 to 9 numeric keypad dock and admin date and live image popup views inflate cleanly`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val inflater = android.view.LayoutInflater.from(themedContext)

        // 1. History tab keeps inline keypad dock hidden (View.GONE) so only Search & Recent Calls show
        val historyRoot = inflater.inflate(R.layout.fragment_history, null)
        val homeKeypadDock = historyRoot.findViewById<android.view.View>(R.id.card_home_keypad_dock)
        val toggleHomeKeypad = historyRoot.findViewById<android.view.View>(R.id.btn_toggle_home_keypad)
        assertTrue(homeKeypadDock != null)
        assertEquals(android.view.View.GONE, homeKeypadDock.visibility)
        assertEquals(android.view.View.GONE, toggleHomeKeypad.visibility)

        // 2. Bottom sheet M3 0-9 numeric keypad in bottom_sheet_dialpad.xml with compact hint & VIP badge
        val dialpadSheet = inflater.inflate(R.layout.bottom_sheet_dialpad, null)
        assertTrue(dialpadSheet.findViewById<android.view.View>(R.id.card_dialpad_display) != null)
        assertTrue(dialpadSheet.findViewById<android.view.View>(R.id.tv_dialpad_vip_badge) != null)
        assertTrue(dialpadSheet.findViewById<android.view.View>(R.id.btn_key_0) != null)
        assertTrue(dialpadSheet.findViewById<android.view.View>(R.id.btn_key_9) != null)

        // 2b. MainActivity VIP 3D accent strip & crown badge for Premium subscribers
        val mainRoot = inflater.inflate(R.layout.activity_main, null)
        assertTrue(mainRoot.findViewById<android.view.View>(R.id.view_vip_3d_top_accent_bar) != null)
        assertTrue(mainRoot.findViewById<android.view.View>(R.id.tv_vip_pro_header_badge) != null)

        // 3. Admin Panel date filter & live image popup controls
        val adminRoot = inflater.inflate(R.layout.activity_admin_panel, null)
        assertTrue(adminRoot.findViewById<android.view.View>(R.id.chip_group_user_date_filter) != null)
        assertTrue(adminRoot.findViewById<android.view.View>(R.id.chip_date_field_toggle) != null)
        assertTrue(adminRoot.findViewById<android.view.View>(R.id.tv_admin_user_date_summary) != null)
        assertTrue(adminRoot.findViewById<android.view.View>(R.id.btn_popup_ad_send_live_now) != null)
        assertTrue(adminRoot.findViewById<android.view.View>(R.id.btn_broadcast_preview) != null)

        // 4. Live Broadcast Popup clickable image wrapper & link badge
        val broadcastDialog = inflater.inflate(R.layout.dialog_live_broadcast, null)
        assertTrue(broadcastDialog.findViewById<android.view.View>(R.id.card_popup_image_wrapper) != null)
        assertTrue(broadcastDialog.findViewById<android.view.View>(R.id.tv_popup_image_link_badge) != null)

        // 5. Settings Theme Studio card (System Default / Light / Dark + Dynamic Colors + 8 3D Mix & Premium Themes)
        val settingsRoot = inflater.inflate(R.layout.fragment_settings, null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.card_theme_studio) != null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.chip_theme_mode_system) != null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.chip_theme_mode_light) != null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.chip_theme_mode_dark) != null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.switch_dynamic_colors) != null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.chip_group_theme_palettes) != null)
        assertTrue(settingsRoot.findViewById<android.view.View>(R.id.btn_open_theme_gallery) != null)

        assertEquals(
            com.example.data.firebase.UserEntitlement.THEME_SYSTEM,
            com.example.data.firebase.SettingsManager.themeModeToString(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        )
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            com.example.data.firebase.SettingsManager.stringToThemeMode("system")
        )
        assertEquals(3, com.example.ui.theme.AppThemePalette.premium3DMixThemes().size)
        assertEquals(4, com.example.ui.theme.AppThemePalette.entries.size)
        val cyber = com.example.ui.theme.AppThemePalette.fromId("cyber_neon_3d")
        assertTrue(cyber.is3DMix)
        assertTrue(cyber.isPremiumTheme)
        assertTrue(cyber.create3DGradientDrawable(24f) != null)
        assertTrue(cyber.createFullApp3DBackgroundDrawable() != null)

        // 6. Verify the 3 Full-App 3D Mix-Color Themes are strictly gated to active Premium subscribers
        val prefs = DialerPreferences(app)
        prefs.adminBlockedUser = false
        prefs.isPremiumUser = false
        prefs.subscriptionExpiresAtMs = 0L
        prefs.selectedThemePaletteId = "cyber_neon_3d"
        assertFalse(com.example.ui.theme.ThemeStudioManager.isUserEntitledToPremiumThemes(prefs))
        assertFalse(com.example.ui.theme.ThemeStudioManager.isFullApp3DMixThemeActive(prefs))
        assertEquals(
            com.example.ui.theme.AppThemePalette.EMERALD_PULSE,
            com.example.ui.theme.ThemeStudioManager.resolveEffectivePalette(prefs)
        )

        prefs.isPremiumUser = true
        prefs.subscriptionExpiresAtMs = System.currentTimeMillis() + DialerPreferences.ONE_MONTH_MILLIS
        assertTrue(com.example.ui.theme.ThemeStudioManager.isUserEntitledToPremiumThemes(prefs))
        assertTrue(com.example.ui.theme.ThemeStudioManager.isFullApp3DMixThemeActive(prefs))
        assertEquals(
            com.example.ui.theme.AppThemePalette.CYBER_NEON_3D,
            com.example.ui.theme.ThemeStudioManager.resolveEffectivePalette(prefs)
        )
        com.example.ui.theme.ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(settingsRoot, prefs)
        assertEquals(
            cyber.cardBgColorInt(),
            settingsRoot.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_theme_studio).cardBackgroundColor.defaultColor
        )

        // 7. Verify login is strictly restricted to the Google email currently signed in on the mobile device
        val accountManager = android.accounts.AccountManager.get(app)
        val shadowAccountManager = org.robolectric.Shadows.shadowOf(accountManager)
        val deviceAccount = android.accounts.Account("mobile.owner@gmail.com", com.example.data.firebase.GoogleAuthHelper.GOOGLE_ACCOUNT_TYPE)
        shadowAccountManager.addAccount(deviceAccount)

        assertEquals(
            listOf("mobile.owner@gmail.com"),
            com.example.data.firebase.GoogleAuthHelper.getDeviceSignedInGoogleEmails(app)
        )
        assertTrue(com.example.data.firebase.GoogleAuthHelper.isEmailSignedInOnDevice(app, "Mobile.Owner@gmail.com"))
        assertFalse(com.example.data.firebase.GoogleAuthHelper.isEmailSignedInOnDevice(app, "other.person@gmail.com"))

        // Attempting to log in with a non-device email fails
        val nonDeviceAttempt = runCatching {
            com.example.data.firebase.GoogleAuthHelper.completeFallbackLogin(
                context = app,
                emailOrPhone = "other.person@gmail.com"
            )
        }
        assertTrue(nonDeviceAttempt.isFailure)

        // Logging in with the device's signed-in email succeeds
        val deviceLoginEntitlement = com.example.data.firebase.GoogleAuthHelper.completeFallbackLogin(
            context = app,
            emailOrPhone = "mobile.owner@gmail.com"
        )
        assertEquals("mobile.owner@gmail.com", deviceLoginEntitlement.email)
        assertTrue(com.example.data.firebase.GoogleAuthHelper.isUserAuthenticated(app))

        // If prefs.userEmail is tampered with to an email not signed in on the device, isUserAuthenticated returns false
        prefs.userEmail = "spoofed@gmail.com"
        assertFalse(com.example.data.firebase.GoogleAuthHelper.isUserAuthenticated(app))

        // 8. Verify Enhanced 3D Color Mixing, Incoming Call 3D Popup, Slide-to-Answer & Top Pull-Down 3D Call Info Popup
        com.example.billing.SubscriptionEnforcementService.stopMonitoring()
        com.example.data.firebase.RealtimeSyncManager.stop()
        prefs.adminBlockedUser = false
        prefs.isPremiumUser = true
        prefs.subscriptionExpiresAtMs = System.currentTimeMillis() + DialerPreferences.ONE_MONTH_MILLIS
        prefs.selectedThemePaletteId = com.example.ui.theme.AppThemePalette.CYBER_NEON_3D.id
        assertTrue(cyber.create3DGlassCardDrawable(24f) != null)
        assertEquals(
            com.example.ui.theme.AppThemePalette.CYBER_NEON_3D,
            com.example.ui.theme.ThemeStudioManager.resolveInCall3DPalette(prefs)
        )
        prefs.isPremiumUser = false
        prefs.subscriptionExpiresAtMs = 0L
        assertEquals(
            com.example.ui.theme.AppThemePalette.AURORA_3D_MIX,
            com.example.ui.theme.ThemeStudioManager.resolveInCall3DPalette(prefs)
        )

        val inCallRoot = inflater.inflate(R.layout.activity_in_call, null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.layout_top_pull_handle) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.card_top_3d_popup_drawer) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_drawer_live_timer) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_drawer_sim_info) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_drawer_audio_route) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_drawer_mic_status) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.tv_drawer_recording_status) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.view_incoming_pulse_outer) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.view_incoming_pulse_inner) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.layout_slide_to_answer_track) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.btn_slide_answer_thumb) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.card_incoming_actions_dock) != null)
        assertTrue(inCallRoot.findViewById<android.view.View>(R.id.card_active_controls_deck) != null)

        val notifCompact = inflater.inflate(R.layout.notification_call_3d_compact, null)
        assertTrue(notifCompact.findViewById<android.widget.Chronometer>(R.id.chrono_notif_compact_timer) != null)
        assertTrue(notifCompact.findViewById<android.widget.TextView>(R.id.tv_notif_compact_name) != null)

        val notifPopup = inflater.inflate(R.layout.notification_call_3d_popup, null)
        assertTrue(notifPopup.findViewById<android.widget.Chronometer>(R.id.chrono_notif_call_timer) != null)
        assertTrue(notifPopup.findViewById<android.widget.TextView>(R.id.tv_notif_popup_details) != null)
        assertTrue(notifPopup.findViewById<android.widget.Button>(R.id.btn_notif_mute) != null)
        assertTrue(notifPopup.findViewById<android.widget.Button>(R.id.btn_notif_speaker) != null)
        assertTrue(notifPopup.findViewById<android.widget.Button>(R.id.btn_notif_end) != null)

        assertEquals(
            "com.example.telecom.ACTION_TOGGLE_MUTE",
            com.example.telecom.CallNotificationActionReceiver.ACTION_TOGGLE_MUTE
        )
        assertEquals(
            "com.example.telecom.ACTION_TOGGLE_SPEAKER",
            com.example.telecom.CallNotificationActionReceiver.ACTION_TOGGLE_SPEAKER
        )

        shadowAccountManager.removeAllAccounts()
    }

    @Test
    fun `admin premium grant heals clock skew and tamper flags, free tier stays light without recording, and premium dark mode persists across syncs`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = DialerPreferences(app)
        val nowMs = System.currentTimeMillis()

        // 1. Free user: strictly simple light theme, no recording, no 3D themes, no dark mode — calling/contacts remain open
        prefs.applyEnforcementBatch(
            isPremium = false,
            isBlocked = false,
            subscriptionExpiresAtMs = 0L,
            bonusTrialDays = 0
        )
        prefs.displayThemeMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        prefs.selectedThemePaletteId = com.example.ui.theme.AppThemePalette.CYBER_NEON_3D.id

        assertFalse(prefs.isPremiumActive(nowMs))
        assertFalse(prefs.hasRecordingAccess(nowMs))
        assertFalse(com.example.billing.SubscriptionEnforcementService.canStartCallRecording(app, nowMs))
        assertFalse(com.example.ui.theme.ThemeStudioManager.isUserEntitledToPremiumThemes(prefs))
        assertEquals(
            com.example.ui.theme.AppThemePalette.EMERALD_PULSE,
            com.example.ui.theme.ThemeStudioManager.resolveEffectivePalette(prefs)
        )
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
            com.example.ui.theme.ThemeStudioManager.resolveEffectiveNightMode(prefs)
        )

        // 2. Simulate sticky future clock skew + tamperDetected + trialBlockedByDevice before Admin approves Premium
        prefs.tamperDetected = true
        prefs.trialBlockedByDevice = true
        prefs.lastSeenTimeMs = nowMs + 60L * DialerPreferences.ONE_DAY_MILLIS

        val grantedState = com.example.billing.SubscriptionEnforcementService.applyNodeUpdate(
            context = app,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
            isPremium = true,
            isBlocked = false,
            installDateMs = nowMs - 15L * DialerPreferences.ONE_DAY_MILLIS,
            subscriptionExpiresAtMs = nowMs + DialerPreferences.ONE_MONTH_MILLIS,
            nowMs = nowMs
        )
        assertTrue(grantedState.isPremium)
        assertTrue(grantedState.isRecordingAllowed)
        assertFalse(prefs.tamperDetected)
        assertFalse(prefs.trialBlockedByDevice)
        assertTrue(prefs.isPremiumActive(nowMs))
        assertTrue(prefs.hasRecordingAccess(nowMs))
        assertTrue(com.example.billing.SubscriptionEnforcementService.canStartCallRecording(app, nowMs))
        assertTrue(com.example.ui.theme.ThemeStudioManager.isUserEntitledToPremiumThemes(prefs))

        // 3. Premium user enables Dark Mode: must NOT revert to Light Mode on AppConfig or stale cloud entitlement syncs
        val settingsManager = com.example.data.firebase.SettingsManager(
            db = null,
            auth = null,
            prefs = prefs
        )
        settingsManager.setThemeMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        assertTrue(prefs.hasLocalThemeCustomization)
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
            com.example.ui.theme.ThemeStudioManager.resolveEffectiveNightMode(prefs)
        )

        // Simulate global AppConfig sync attempting to force light theme
        prefs.applyAppConfig(
            com.example.data.firebase.AppConfig(
                forceThemeMode = com.example.data.firebase.AppConfig.THEME_LIGHT
            )
        )
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
            prefs.displayThemeMode
        )
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
            com.example.ui.theme.ThemeStudioManager.resolveEffectiveNightMode(prefs)
        )

        // Simulate stale RTDB/Firestore entitlement snapshot containing themeMode = "light"
        val staleLightEntitlement = com.example.data.firebase.UserEntitlement(
            userId = "u_prem_dark",
            isPremiumUser = true,
            subscriptionStatus = com.example.data.firebase.UserEntitlement.STATUS_ACTIVE,
            subscriptionExpiresAt = com.google.firebase.Timestamp(java.util.Date(nowMs + DialerPreferences.ONE_MONTH_MILLIS)),
            themeMode = com.example.data.firebase.UserEntitlement.THEME_LIGHT
        )
        com.example.data.firebase.SettingsManager.applyRtdbUserEntitlement(staleLightEntitlement, prefs)
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
            prefs.displayThemeMode
        )
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
            com.example.ui.theme.ThemeStudioManager.resolveEffectiveNightMode(prefs)
        )
    }

    @Test
    fun `floating 3D call pill supports 3-stage incoming animation, 55 percent sideways drag controls, active call bar, and reverse dismiss`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedCtx = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val inflater = android.view.LayoutInflater.from(themedCtx)

        // 1. Verify both InCallActivity and MainActivity layouts inflate with Floating3DCallPillView
        val inCallRoot = inflater.inflate(R.layout.activity_in_call, null)
        val inCallPill = inCallRoot.findViewById<com.example.ui.incall.Floating3DCallPillView>(R.id.floating_3d_call_pill)
        assertTrue(inCallPill != null)

        val mainRoot = inflater.inflate(R.layout.activity_main, null)
        val mainPill = mainRoot.findViewById<com.example.ui.incall.Floating3DCallPillView>(R.id.floating_3d_call_pill)
        assertTrue(mainPill != null)

        // 2. Verify exact spec constants & responsive min(94vw, 390dp) width calculation
        assertEquals(76f, com.example.ui.incall.Floating3DCallPillView.PILL_HEIGHT_DP)
        assertEquals(68f, com.example.ui.incall.Floating3DCallPillView.ORB_SIZE_DP)
        assertEquals(68f, com.example.ui.incall.Floating3DCallPillView.COLLAPSED_WIDTH_DP)
        assertEquals(38f, com.example.ui.incall.Floating3DCallPillView.PILL_CORNER_RADIUS_DP)
        assertEquals(52f, com.example.ui.incall.Floating3DCallPillView.JEWEL_BUTTON_SIZE_DP)
        assertEquals(0.94f, com.example.ui.incall.Floating3DCallPillView.MAX_WIDTH_FRACTION)
        assertEquals(390f, com.example.ui.incall.Floating3DCallPillView.MAX_EXPANDED_WIDTH_DP)
        assertEquals(460L, com.example.ui.incall.Floating3DCallPillView.STAGE_1_DROP_MS)
        assertEquals(920L, com.example.ui.incall.Floating3DCallPillView.STAGE_2_UNFOLD_MS)
        assertEquals(700L, com.example.ui.incall.Floating3DCallPillView.STAGE_2_HEIGHT_MS)
        assertEquals(750L, com.example.ui.incall.Floating3DCallPillView.SEAM_GLINT_MS)
        assertEquals(420L, com.example.ui.incall.Floating3DCallPillView.DISMISS_COLLAPSE_MS)
        assertEquals(380L, com.example.ui.incall.Floating3DCallPillView.DISMISS_RETRACT_MS)
        assertEquals(5000L, com.example.ui.incall.Floating3DCallPillView.RIM_SPIN_DURATION_MS)
        assertEquals(0.55f, com.example.ui.incall.Floating3DCallPillView.DRAG_TRIGGER_THRESHOLD)

        val pill = com.example.ui.incall.Floating3DCallPillView(themedCtx)
        val expectedWidth400 = pill.computeExpandedWidthPx(400)
        assertEquals(376f, expectedWidth400, 1.5f)
        assertEquals(390f * themedCtx.resources.displayMetrics.density, pill.computeExpandedWidthPx(800), 1.5f)

        // Measure and layout on a compact 400px phone width
        pill.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.AT_MOST)
        )
        pill.layout(0, 0, pill.measuredWidth, pill.measuredHeight)

        var answered = 0
        var declined = 0
        var muteToggled = 0
        var speakerToggled = 0
        var ended = 0
        var dismissed = 0

        pill.listener = object : com.example.ui.incall.Floating3DCallPillView.CallPillListener {
            override fun onAnswerCall() { answered++ }
            override fun onDeclineCall() { declined++ }
            override fun onToggleMute() { muteToggled++ }
            override fun onToggleSpeaker() { speakerToggled++ }
            override fun onEndCall() { ended++ }
            override fun onDismissAnimationComplete() { dismissed++ }
        }

        // 3. Stage 1 -> Stage 2 -> Stage 3 incoming choreography
        pill.showIncomingCall("Aarav Sharma", "+91 98765 43210")
        assertEquals(android.view.View.VISIBLE, pill.visibility)
        assertEquals(
            com.example.ui.incall.Floating3DCallPillView.PillMode.STAGE_1_DROP,
            pill.currentMode
        )

        // 4. Sideways drag-to-answer (Green right) and 55% threshold status text switching
        pill.applyDragProgressForTestOrGesture(
            com.example.ui.incall.Floating3DCallPillView.DragSide.ANSWER_GREEN,
            0.30f
        )
        assertEquals(themedCtx.getString(R.string.incall_pill_slide_to_answer), pill.lastStatusMessage)

        pill.applyDragProgressForTestOrGesture(
            com.example.ui.incall.Floating3DCallPillView.DragSide.ANSWER_GREEN,
            0.60f
        )
        assertEquals(themedCtx.getString(R.string.incall_pill_release_to_answer), pill.lastStatusMessage)
        pill.completeDragAction(com.example.ui.incall.Floating3DCallPillView.DragSide.ANSWER_GREEN)
        assertEquals(1, answered)

        // 5. Sideways drag-to-decline (Red left) and 55% threshold status text switching
        pill.applyDragProgressForTestOrGesture(
            com.example.ui.incall.Floating3DCallPillView.DragSide.DECLINE_RED,
            0.35f
        )
        assertEquals(themedCtx.getString(R.string.incall_pill_slide_to_decline), pill.lastStatusMessage)

        pill.applyDragProgressForTestOrGesture(
            com.example.ui.incall.Floating3DCallPillView.DragSide.DECLINE_RED,
            0.62f
        )
        assertEquals(themedCtx.getString(R.string.incall_pill_release_to_decline), pill.lastStatusMessage)
        pill.completeDragAction(com.example.ui.incall.Floating3DCallPillView.DragSide.DECLINE_RED)
        assertEquals(1, declined)

        // 6. Tap-to-nudge hint flashes "Drag sideways"
        pill.triggerTapToNudgeHint(com.example.ui.incall.Floating3DCallPillView.DragSide.ANSWER_GREEN)
        assertEquals(themedCtx.getString(R.string.incall_pill_drag_sideways), pill.lastStatusMessage)

        // 7. Active "On Call" compact bar morph + Mute / Speaker / End Call callbacks
        pill.morphToActiveCallBar(
            callerName = "Aarav Sharma",
            formattedTimer = "01:24",
            isMuted = false,
            isSpeakerOn = true,
            isBluetooth = false
        )
        assertEquals(
            com.example.ui.incall.Floating3DCallPillView.PillMode.ACTIVE_CALL,
            pill.currentMode
        )
        pill.findViewById<android.view.View>(R.id.btn_pill_active_mute).performClick()
        pill.findViewById<android.view.View>(R.id.btn_pill_active_speaker).performClick()
        pill.findViewById<android.view.View>(R.id.btn_pill_active_end).performClick()
        assertEquals(1, muteToggled)
        assertEquals(1, speakerToggled)
        assertEquals(1, ended)

        // 8. Reverse dismiss animation starts cleanly into DISMISSING state
        var finishCallbackFired = false
        pill.dismissWithReverseAnimation { finishCallbackFired = true }
        assertEquals(
            com.example.ui.incall.Floating3DCallPillView.PillMode.DISMISSING,
            pill.currentMode
        )
        pill.hideImmediately()
        assertEquals(
            com.example.ui.incall.Floating3DCallPillView.PillMode.HIDDEN,
            pill.currentMode
        )
        assertEquals(android.view.View.GONE, pill.visibility)
        // Avoid unused local warning
        assertFalse(finishCallbackFired && dismissed < 0)
    }

    @Test
    fun `full-screen 3D smoked-sapphire incoming and active call UI inflates all sections and runs sideways drag dock physics`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedCtx = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val inflater = android.view.LayoutInflater.from(themedCtx)

        val root = inflater.inflate(R.layout.activity_in_call, null)

        // 1. Aurora Backdrop (with dynamic Record/Hold ambient states), Minimized Top Bar, Header Minimize & Smart Status Pill
        val aurora = root.findViewById<com.example.ui.incall.AuroraSilkBackdropView>(R.id.aurora_backdrop)
        assertTrue(aurora != null)
        aurora!!.setRecordingAmbient(true)
        assertTrue(aurora.isRecordingAmbientActive)
        aurora.setHoldAmbient(true)
        assertTrue(aurora.isHoldAmbientActive)
        aurora.setRecordingAmbient(false)
        aurora.setHoldAmbient(false)
        assertFalse(aurora.isRecordingAmbientActive)
        assertFalse(aurora.isHoldAmbientActive)

        assertTrue(root.findViewById<com.example.ui.incall.Floating3DCallPillView>(R.id.floating_3d_call_pill) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incall_minimize) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.layout_smart_status_pill) != null)
        assertTrue(root.findViewById<android.widget.TextView>(R.id.tv_incall_hd_badge) != null)
        assertTrue(root.findViewById<android.widget.TextView>(R.id.tv_smart_sim_network_badge) != null)
        assertTrue(root.findViewById<com.example.ui.incall.LiveSignalBarsView>(R.id.view_live_signal_bars) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.card_live_rec_pill) != null)
        assertTrue(root.findViewById<android.widget.TextView>(R.id.tv_live_rec_timer) != null)

        // 2. Center Caller Showcase: 116dp Voice-Reactive Liquid Avatar + Muted/Hold States + "Call on Hold" Badge + Live Waveform
        val avatarView = root.findViewById<com.example.ui.incall.LiquidPlatinumAvatarView>(R.id.view_liquid_platinum_avatar)
        assertTrue(avatarView != null)
        assertEquals(116f, com.example.ui.incall.LiquidPlatinumAvatarView.AVATAR_CORE_SIZE_DP)
        avatarView!!.setCallVisualState(ringing = false, active = true, muted = true, onHold = false)
        assertTrue(avatarView.isActiveState)
        assertTrue(avatarView.isMutedState)
        assertFalse(avatarView.isOnHoldState)
        avatarView.setCallVisualState(ringing = false, active = true, muted = false, onHold = true)
        assertTrue(avatarView.isOnHoldState)
        assertFalse(avatarView.isMutedState)
        assertTrue(root.findViewById<android.widget.TextView>(R.id.tv_avatar_hold_badge) != null)
        assertTrue(root.findViewById<com.example.ui.incall.LiveCallWaveformView>(R.id.view_live_call_waveform) != null)

        // 3. Quick-Action Pills (Message + Silence) & Frosted Quick-Reply Tray
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incoming_quick_decline) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incoming_silence) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.card_quick_reply_tray) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.layout_quick_reply_list) != null)

        // 4. Bottom 3D Sideways Drag Dock exact specs & 55% drag physics + tap-to-nudge
        val dock = root.findViewById<com.example.ui.incall.SidewaysCallDragDockView>(R.id.sideways_drag_dock)
        assertTrue(dock != null)
        assertEquals(76f, com.example.ui.incall.SidewaysCallDragDockView.DOCK_HEIGHT_DP)
        assertEquals(0.90f, com.example.ui.incall.SidewaysCallDragDockView.DOCK_WIDTH_FRACTION)
        assertEquals(38f, com.example.ui.incall.SidewaysCallDragDockView.DOCK_CORNER_RADIUS_DP)
        assertEquals(56f, com.example.ui.incall.SidewaysCallDragDockView.JEWEL_SIZE_DP)
        assertEquals(0.55f, com.example.ui.incall.SidewaysCallDragDockView.DRAG_TRIGGER_THRESHOLD)
        assertEquals(360f, dock.computeDockWidthPx(400), 1.5f)

        dock.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.AT_MOST)
        )
        dock.layout(0, 0, dock.measuredWidth, dock.measuredHeight)

        var dockAnswered = 0
        var dockDeclined = 0
        dock.listener = object : com.example.ui.incall.SidewaysCallDragDockView.DragDockListener {
            override fun onAnswerCall() { dockAnswered++ }
            override fun onDeclineCall() { dockDeclined++ }
        }

        dock.applyDragProgressForTestOrGesture(com.example.ui.incall.SidewaysCallDragDockView.DragSide.ANSWER_GREEN, 0.30f)
        assertEquals(themedCtx.getString(R.string.incall_pill_slide_to_answer), dock.lastStatusMessage)
        dock.applyDragProgressForTestOrGesture(com.example.ui.incall.SidewaysCallDragDockView.DragSide.ANSWER_GREEN, 0.60f)
        assertEquals(themedCtx.getString(R.string.incall_pill_release_to_answer), dock.lastStatusMessage)
        dock.completeDragAction(com.example.ui.incall.SidewaysCallDragDockView.DragSide.ANSWER_GREEN)
        assertEquals(1, dockAnswered)

        dock.applyDragProgressForTestOrGesture(com.example.ui.incall.SidewaysCallDragDockView.DragSide.DECLINE_RED, 0.35f)
        assertEquals(themedCtx.getString(R.string.incall_pill_slide_to_decline), dock.lastStatusMessage)
        dock.applyDragProgressForTestOrGesture(com.example.ui.incall.SidewaysCallDragDockView.DragSide.DECLINE_RED, 0.62f)
        assertEquals(themedCtx.getString(R.string.incall_pill_release_to_decline), dock.lastStatusMessage)
        dock.completeDragAction(com.example.ui.incall.SidewaysCallDragDockView.DragSide.DECLINE_RED)
        assertEquals(1, dockDeclined)

        dock.triggerTapToNudgeHint(com.example.ui.incall.SidewaysCallDragDockView.DragSide.ANSWER_GREEN)
        assertEquals(themedCtx.getString(R.string.incall_pill_drag_sideways), dock.lastStatusMessage)

        // 5. Sculpted 3D Smoked-Sapphire Control Dock + Speaker Ripple + Slide-Up DTMF Keypad w/ Backspace + Audio Output Sheet + Ruby-Glass End Call Pedestal
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incall_mute) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incall_keypad) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incall_speaker) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.view_speaker_ripple) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_add_call) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_hold_swap) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incall_record) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.tv_record_grid_rec_badge) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incall_keypad_close) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incall_dtmf_backspace) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.card_audio_output_sheet) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_audio_route_speaker) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_audio_route_earpiece) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_audio_route_bluetooth) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.view_end_call_breathing_glow) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_incall_end) != null)
        assertEquals(500L, com.example.ui.incall.InCallActivity.CALL_ENDED_HOLD_MS)
    }
}








