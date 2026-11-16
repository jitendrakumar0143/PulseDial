package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.DialerPreferences
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

        // Day 0 -> 7 days remaining, access granted
        assertFalse(prefs.isTrialExpired(startTime))
        assertEquals(7, prefs.getTrialDaysRemaining(startTime))
        assertTrue(prefs.hasRecordingAccess(startTime))

        // Day 5 -> 2 days remaining, access granted
        val dayFive = startTime + (5L * DialerPreferences.ONE_DAY_MILLIS)
        assertFalse(prefs.isTrialExpired(dayFive))
        assertEquals(2, prefs.getTrialDaysRemaining(dayFive))
        assertTrue(prefs.hasRecordingAccess(dayFive))

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

        // Within 7-day trial -> active and features unlocked
        assertTrue(subscriptionManager.isSubscriptionActive(baseTime))
        assertFalse(subscriptionManager.isFeatureLocked(baseTime))
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

        // Opening protected screen while expired/unpaid redirects straight to MainActivity Payment Page
        prefs.firstLaunchTime = System.currentTimeMillis() - DialerPreferences.TRIAL_DURATION_MILLIS - 10_000L
        prefs.isPremiumUser = false
        val detailIntent = com.example.ui.history.CallDetailActivity.createIntent(
            context = app,
            phoneNumber = "+91 98765 43210"
        )
        val controller = org.robolectric.Robolectric.buildActivity(
            com.example.ui.history.CallDetailActivity::class.java,
            detailIntent
        ).create()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
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
    fun `history fragment layout provides recent calls and playable recordings playlist with mini player`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themedContext = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val root = android.view.LayoutInflater.from(themedContext).inflate(R.layout.fragment_history, null)

        assertTrue(root.findViewById<android.view.View>(R.id.recycler_history) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.recycler_recordings) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_mode_calls) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.btn_mode_recordings) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.card_recording_folder_banner) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.card_mini_player) != null)
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

        val serviceMap = pkgInfo.services.orEmpty().associateBy { it.name }
        assertTrue(serviceMap.containsKey("com.example.telecom.CustomInCallService"))
        assertTrue(serviceMap.containsKey("com.example.recording.CallRecorderForegroundService"))
        val accService = serviceMap["com.example.recording.CallRecordingAccessibilityService"]
        assertTrue(accService != null)
        assertEquals(android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE, accService?.permission)

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

        // 1. Active 7-day trial (3 days used -> 4 days remaining)
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
        assertTrue(activeTrialState.isRecordingAllowed)
        assertFalse(activeTrialState.shouldRestrictUiToAccountTab)
        assertEquals("Free Trial Active (4 days left)", activeTrialState.statusBannerText)

        // 2. Expired 7-day trial (8 days used, isPremium = false -> restricted)
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
        assertTrue(expiredTrialState.shouldRestrictUiToAccountTab)
        assertEquals("Trial Expired - Upgrade to Pro (₹299/month)", expiredTrialState.statusBannerText)

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
        assertTrue(revokedState.shouldRestrictUiToAccountTab)
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
}
