package com.example

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.example.billing.SubscriptionEnforcementService
import com.example.billing.SubscriptionRestrictionEvent
import com.example.data.DialerPreferences
import com.example.data.firebase.AdminUserDateFilter
import com.example.data.firebase.GoogleAuthHelper
import com.example.data.firebase.RealtimeDatabaseSync
import com.example.data.firebase.UserEntitlement
import com.example.telecom.CallManager
import com.example.telecom.CallUiState
import com.example.ui.MainActivity
import com.example.ui.admin.AdminPanelActivity
import com.example.ui.history.CallDetailActivity
import com.example.ui.incall.InCallActivity
import com.example.ui.incall.ProximityScreenLockController
import com.example.ui.theme.AppThemePalette
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import com.google.firebase.Timestamp
import java.util.Date
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AllTabsAndInCallSmokeTest {

    private lateinit var app: Application
    private lateinit var prefs: DialerPreferences

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs = DialerPreferences(app)
        prefs.resetSessionEntitlementsOnSignOut()
        prefs.selectedThemePaletteId = AppThemePalette.EMERALD_PULSE.id
        prefs.displayThemeMode = AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        CallManager.publishStateForTesting(CallUiState())
    }

    @After
    fun tearDown() {
        CallManager.publishStateForTesting(CallUiState())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }

    @Test
    fun `launch MainActivity signed out without crashing`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        assertNotNull(activity.findViewById(R.id.auth_gate_container))
        controller.pause().stop().destroy()
    }

    @Test
    fun `launch MainActivity signed in free tier and click every single tab plus dialpad`() {
        GoogleAuthHelper.completeFallbackLogin(app, "freeuser@gmail.com", "Free User")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
        assertNotNull(bottomNav)

        val tabs = listOf(
            R.id.nav_history,
            R.id.nav_favorites,
            R.id.nav_contacts,
            R.id.nav_settings,
            R.id.nav_account,
            R.id.nav_history
        )
        tabs.forEach { tabId ->
            bottomNav.selectedItemId = tabId
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(tabId, bottomNav.selectedItemId)
        }

        // Open Dialpad bottom sheet
        activity.findViewById<View>(R.id.fab_dialpad)?.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        controller.pause().stop().destroy()
    }

    @Test
    fun `launch MainActivity with every Premium 3D theme and dark mode and click every single tab`() {
        GoogleAuthHelper.completeFallbackLogin(app, "vipuser@gmail.com", "VIP User")
        prefs.isPremiumUser = true
        prefs.subscriptionExpiresAtMs = System.currentTimeMillis() + 30L * DialerPreferences.ONE_DAY_MILLIS

        val palettes = listOf(
            AppThemePalette.CYBER_NEON_3D,
            AppThemePalette.AURORA_3D_MIX,
            AppThemePalette.ROYAL_GOLD_VIP
        )
        palettes.forEach { palette ->
            prefs.selectedThemePaletteId = palette.id
            prefs.displayThemeMode = AppCompatDelegate.MODE_NIGHT_YES
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
            assertNotNull(bottomNav)

            listOf(
                R.id.nav_history,
                R.id.nav_favorites,
                R.id.nav_contacts,
                R.id.nav_settings,
                R.id.nav_account
            ).forEach { tabId ->
                bottomNav.selectedItemId = tabId
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }

            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `launch CallDetailActivity and AdminPanelActivity and click every admin tab without crashing`() {
        GoogleAuthHelper.completeFallbackLogin(app, "Jitendrakumar888126@gmail.com", "Admin User")
        val detailIntent = CallDetailActivity.createIntent(app, "+919876543210", "Test Contact")
        val detailCtrl = Robolectric.buildActivity(CallDetailActivity::class.java, detailIntent).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        detailCtrl.pause().stop().destroy()

        val adminIntent = AdminPanelActivity.createIntent(app)
        val adminCtrl = Robolectric.buildActivity(AdminPanelActivity::class.java, adminIntent).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val adminActivity = adminCtrl.get()
        val adminTabs = adminActivity.findViewById<TabLayout>(R.id.admin_tab_layout)
        assertNotNull(adminTabs)
        for (i in 0 until adminTabs.tabCount) {
            adminTabs.getTabAt(i)?.select()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        adminCtrl.pause().stop().destroy()
    }

    @Test
    fun `proximity sensor toggles screen lock and speakerphone based on ear proximity during active call`() {
        val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val shadowSensorManager = Shadows.shadowOf(sensorManager)
        val proximitySensor = ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY)
        shadowSensorManager.addSensor(proximitySensor)

        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(powerManager).setIsWakeLockLevelSupported(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            true
        )

        val lockEvents = mutableListOf<Boolean>()
        val speakerEvents = mutableListOf<Boolean>()
        val controller = ProximityScreenLockController(
            context = app,
            onUiLockChanged = { isLocked -> lockEvents.add(isLocked) },
            onSpeakerChanged = { speakerOn -> speakerEvents.add(speakerOn) }
        )

        // Ringing call should NOT arm proximity listener yet
        controller.updateCallState(
            hasActiveCall = true,
            callState = Call.STATE_RINGING,
            audioRoute = CallAudioState.ROUTE_EARPIECE,
            isSpeakerOn = false
        )
        assertFalse(controller.isListening)
        assertFalse(controller.isNearEarLocked)
        assertTrue(speakerEvents.isEmpty())

        // Active call arms SensorManager listener
        controller.updateCallState(
            hasActiveCall = true,
            callState = Call.STATE_ACTIVE,
            audioRoute = CallAudioState.ROUTE_EARPIECE,
            isSpeakerOn = false
        )
        assertTrue(controller.isListening)
        assertFalse(controller.isNearEarLocked)
        assertFalse(controller.isWakeLockHeld)

        // Device held to ear (0cm) -> locks UI, acquires wake lock, ensures speaker OFF
        controller.handleProximityDistance(distanceCm = 0f, maxRangeCm = 5f)
        assertTrue(controller.isNearEarLocked)
        assertTrue(controller.isWakeLockHeld)
        assertEquals(listOf(true), lockEvents)
        assertEquals(listOf(false), speakerEvents)

        // Moved away from ear (5cm) -> unlocks UI, releases wake lock, toggles speaker ON
        controller.handleProximityDistance(distanceCm = 5f, maxRangeCm = 5f)
        assertFalse(controller.isNearEarLocked)
        assertFalse(controller.isWakeLockHeld)
        assertEquals(listOf(true, false), lockEvents)
        assertEquals(listOf(false, true), speakerEvents)

        // Duplicate FAR reading is ignored (no redundant speaker toggle)
        controller.handleProximityDistance(distanceCm = 5f, maxRangeCm = 5f)
        assertEquals(listOf(false, true), speakerEvents)

        // Listener remains active while speakerphone is ON so returning to ear switches back to earpiece & locks
        controller.updateCallState(
            hasActiveCall = true,
            callState = Call.STATE_ACTIVE,
            audioRoute = CallAudioState.ROUTE_SPEAKER,
            isSpeakerOn = true
        )
        assertTrue(controller.isListening)

        controller.handleProximityDistance(distanceCm = 0.5f, maxRangeCm = 5f)
        assertTrue(controller.isNearEarLocked)
        assertTrue(controller.isWakeLockHeld)
        assertEquals(listOf(false, true, false), speakerEvents)

        // Connecting Bluetooth headset disarms proximity, unlocks UI, and releases wake lock
        controller.updateCallState(
            hasActiveCall = true,
            callState = Call.STATE_ACTIVE,
            audioRoute = CallAudioState.ROUTE_BLUETOOTH,
            isSpeakerOn = false
        )
        assertFalse(controller.isListening)
        assertFalse(controller.isNearEarLocked)
        assertFalse(controller.isWakeLockHeld)

        controller.release()
    }

    @Test
    fun `InCallActivity toggles screen lock overlay and speakerphone automatically on ear proximity`() {
        val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        Shadows.shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY))
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(powerManager).setIsWakeLockLevelSupported(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            true
        )

        CallManager.publishStateForTesting(
            CallUiState(
                hasActiveCall = true,
                callState = Call.STATE_ACTIVE,
                callerName = "Aarav Sharma",
                callerNumber = "+919876543210",
                connectTimeMillis = System.currentTimeMillis() - 5_000L,
                audioRoute = CallAudioState.ROUTE_EARPIECE,
                isSpeakerOn = false
            )
        )

        val activityCtrl = Robolectric.buildActivity(InCallActivity::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val activity = activityCtrl.get()
        val overlay = activity.findViewById<View>(R.id.view_proximity_lock_overlay)
        assertNotNull(overlay)
        assertEquals(View.GONE, overlay.visibility)
        assertTrue(activity.proximityScreenLockController.isListening)

        // 1. Hold phone to ear -> screen lock ON, blackout overlay VISIBLE, touches blocked, speaker OFF
        activity.proximityScreenLockController.handleProximityDistance(distanceCm = 0f, maxRangeCm = 5f)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, overlay.visibility)
        assertTrue(activity.proximityScreenLockController.isNearEarLocked)
        assertTrue(activity.proximityScreenLockController.isWakeLockHeld)
        assertEquals(0.01f, activity.window.attributes.screenBrightness, 0.001f)
        assertFalse(CallManager.uiState.value.isSpeakerOn)
        assertEquals(CallAudioState.ROUTE_EARPIECE, CallManager.uiState.value.audioRoute)

        val now = SystemClock.uptimeMillis()
        val touchEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        assertTrue(activity.dispatchTouchEvent(touchEvent))
        touchEvent.recycle()

        // 2. Move phone away from ear -> screen unlocked, overlay GONE, speakerphone auto-toggled ON
        activity.proximityScreenLockController.handleProximityDistance(distanceCm = 5f, maxRangeCm = 5f)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.GONE, overlay.visibility)
        assertFalse(activity.proximityScreenLockController.isNearEarLocked)
        assertFalse(activity.proximityScreenLockController.isWakeLockHeld)
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            activity.window.attributes.screenBrightness,
            0.001f
        )
        assertTrue(CallManager.uiState.value.isSpeakerOn)
        assertEquals(CallAudioState.ROUTE_SPEAKER, CallManager.uiState.value.audioRoute)
        assertTrue(activity.proximityScreenLockController.isListening)

        // 3. Bring phone back to ear while speakerphone is ON -> speaker auto-toggled OFF & screen re-locked
        activity.proximityScreenLockController.handleProximityDistance(distanceCm = 0f, maxRangeCm = 5f)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, overlay.visibility)
        assertTrue(activity.proximityScreenLockController.isNearEarLocked)
        assertFalse(CallManager.uiState.value.isSpeakerOn)
        assertEquals(CallAudioState.ROUTE_EARPIECE, CallManager.uiState.value.audioRoute)

        activityCtrl.pause().stop().destroy()
        assertFalse(activity.proximityScreenLockController.isListening)
        assertFalse(activity.proximityScreenLockController.isWakeLockHeld)
    }

    @Test
    fun `AdminPanelActivity filters users by registration date, updates isPremium manually, and deletes user`() {
        GoogleAuthHelper.completeFallbackLogin(app, "Jitendrakumar888126@gmail.com", "Super Admin")
        val adminCtrl = Robolectric.buildActivity(AdminPanelActivity::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val activity = adminCtrl.get()

        val nowMs = System.currentTimeMillis()
        val oneDay = DialerPreferences.ONE_DAY_MILLIS

        // Verify parseUserSnapshot preserves registrationDate into createdAt & trialStartedAt
        val parsedFromRtdb = RealtimeDatabaseSync.parseUserSnapshot(
            uid = "u_today",
            map = mapOf(
                "displayName" to "Today User",
                "email" to "today@example.com",
                "registrationDate" to (nowMs - 2_000L),
                "isPremium" to false,
                "subscriptionStatus" to UserEntitlement.STATUS_TRIAL
            )
        )
        assertEquals(nowMs - 2_000L, parsedFromRtdb.createdAt?.toDate()?.time)
        assertEquals(nowMs - 2_000L, parsedFromRtdb.trialStartedAt?.toDate()?.time)

        val user5d = UserEntitlement(
            id = "u_5d",
            userId = "u_5d",
            displayName = "Five Days User",
            email = "five@example.com",
            trialStartedAt = Timestamp(Date(nowMs - 5L * oneDay)),
            createdAt = Timestamp(Date(nowMs - 5L * oneDay)),
            isPremiumUser = false,
            subscriptionStatus = UserEntitlement.STATUS_TRIAL
        )
        val user20d = UserEntitlement(
            id = "u_20d",
            userId = "u_20d",
            displayName = "Twenty Days User",
            email = "twenty@example.com",
            trialStartedAt = Timestamp(Date(nowMs - 20L * oneDay)),
            createdAt = Timestamp(Date(nowMs - 20L * oneDay)),
            isPremiumUser = false,
            subscriptionStatus = UserEntitlement.STATUS_EXPIRED
        )
        val superAdminUser = UserEntitlement(
            id = "u_super",
            userId = "u_super",
            displayName = "Super Admin",
            email = "Jitendrakumar888126@gmail.com",
            trialStartedAt = Timestamp(Date(nowMs - 45L * oneDay)),
            createdAt = Timestamp(Date(nowMs - 45L * oneDay)),
            isPremiumUser = true,
            isAdmin = true,
            subscriptionStatus = UserEntitlement.STATUS_ACTIVE
        )

        // Feed live `/users` snapshot into AdminPanelActivity
        activity.handleUsersRtdbSnapshot(listOf(parsedFromRtdb, user5d, user20d, superAdminUser))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4, activity.getDisplayedUsersForTesting().size)

        val summaryView = activity.findViewById<TextView>(R.id.tv_admin_user_date_summary)
        assertTrue(summaryView.text.toString().contains("Registration Date"))

        // Registration Date filter: TODAY
        activity.applyRegistrationDateFilterForTesting(AdminUserDateFilter.TODAY)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("u_today"), activity.getDisplayedUsersForTesting().map { it.uid })

        // Registration Date filter: LAST_7_DAYS
        activity.applyRegistrationDateFilterForTesting(AdminUserDateFilter.LAST_7_DAYS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("u_today", "u_5d"), activity.getDisplayedUsersForTesting().map { it.uid }.toSet())

        // Registration Date filter: LAST_30_DAYS
        activity.applyRegistrationDateFilterForTesting(AdminUserDateFilter.LAST_30_DAYS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("u_today", "u_5d", "u_20d"), activity.getDisplayedUsersForTesting().map { it.uid }.toSet())

        // Registration Date filter: CUSTOM_RANGE (10 to 25 days ago -> only u_20d)
        activity.applyRegistrationDateFilterForTesting(
            dateFilter = AdminUserDateFilter.CUSTOM_RANGE,
            customStartMs = nowMs - 25L * oneDay,
            customEndMs = nowMs - 10L * oneDay
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("u_20d"), activity.getDisplayedUsersForTesting().map { it.uid })

        // Reset to ALL_TIME
        activity.applyRegistrationDateFilterForTesting(AdminUserDateFilter.ALL_TIME)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4, activity.getDisplayedUsersForTesting().size)

        // Manual isPremium update ON -> reflects immediately in displayed users
        activity.setUserPremiumStatus(user5d, targetPremium = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val promoted = activity.getDisplayedUsersForTesting().first { it.uid == "u_5d" }
        assertTrue(promoted.isPremiumUser)
        assertEquals(UserEntitlement.STATUS_ACTIVE, promoted.subscriptionStatus)
        assertNotNull(promoted.subscriptionExpiresAt)

        // Manual isPremium update OFF -> revokes immediately
        activity.setUserPremiumStatus(promoted, targetPremium = false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val revoked = activity.getDisplayedUsersForTesting().first { it.uid == "u_5d" }
        assertFalse(revoked.isPremiumUser)
        assertEquals(UserEntitlement.STATUS_EXPIRED, revoked.subscriptionStatus)

        // Delete regular user -> removed immediately
        assertTrue(activity.deleteUserAccount(user20d))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(activity.getDisplayedUsersForTesting().any { it.uid == "u_20d" })
        assertEquals(3, activity.getDisplayedUsersForTesting().size)

        // Super Admin cannot be deleted
        assertFalse(activity.deleteUserAccount(superAdminUser))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.getDisplayedUsersForTesting().any { it.uid == "u_super" })

        // Reconciling a subsequent RTDB snapshot where u_today was deleted removes u_today immediately
        activity.handleUsersRtdbSnapshot(listOf(revoked, superAdminUser))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("u_5d", "u_super"), activity.getDisplayedUsersForTesting().map { it.uid }.toSet())

        adminCtrl.pause().stop().destroy()
    }

    @Test
    fun `SubscriptionEnforcementService revokes premium immediately when user node is deleted by Admin`() = runBlocking {
        SubscriptionEnforcementService.stopMonitoring()
        val nowMs = System.currentTimeMillis()
        val activeState = SubscriptionEnforcementService.applyNodeUpdate(
            context = app,
            subscriptionStatus = UserEntitlement.STATUS_ACTIVE,
            isPremium = true,
            isBlocked = false,
            installDateMs = nowMs - 2L * DialerPreferences.ONE_DAY_MILLIS,
            subscriptionExpiresAtMs = nowMs + 30L * DialerPreferences.ONE_DAY_MILLIS,
            nowMs = nowMs
        )
        assertTrue(activeState.isPremium)
        assertTrue(activeState.isRecordingAllowed)
        assertTrue(prefs.isPremiumUser)

        var emittedEvent: SubscriptionRestrictionEvent? = null
        val collectorJob = launch {
            emittedEvent = SubscriptionEnforcementService.restrictionEvents.first {
                it is SubscriptionRestrictionEvent.UserDeletedByAdmin
            }
        }

        val afterDelete = SubscriptionEnforcementService.handleUserDeletedByAdmin(
            context = app,
            nowMs = nowMs + 500L
        )
        collectorJob.join()

        assertFalse(afterDelete.isPremium)
        assertFalse(afterDelete.isRecordingAllowed)
        assertEquals(UserEntitlement.STATUS_EXPIRED, afterDelete.subscriptionStatus)
        assertFalse(prefs.isPremiumUser)
        assertEquals(0L, prefs.subscriptionExpiresAtMs)
        assertEquals(SubscriptionRestrictionEvent.UserDeletedByAdmin, emittedEvent)

        SubscriptionEnforcementService.stopMonitoring()
    }

    @Test
    fun `CallerIdResolver eliminates random guessing and resolves accurate Location State ZIP Country and Social profiles`() {
        // 1. Exact Indian 4-digit mobile series (TRAI/DoT)
        val mumbai = com.example.telecom.CallerIdResolver.resolveInstant("+91 98201 23456")
        assertEquals("Mumbai", mumbai.city)
        assertEquals("Maharashtra", mumbai.state)
        assertEquals("400001", mumbai.postalCode)
        assertEquals("India", mumbai.country)
        assertEquals("+91", mumbai.countryCode)
        assertTrue(mumbai.geo.isExactPrefixMatch)

        val delhi = com.example.telecom.CallerIdResolver.resolveInstant("+91 98101 00000")
        assertEquals("New Delhi", delhi.city)
        assertEquals("Delhi NCR", delhi.state)
        assertEquals("110001", delhi.postalCode)
        assertEquals("India", delhi.country)

        val lucknow = com.example.telecom.CallerIdResolver.resolveInstant("8881260000")
        assertEquals("Lucknow", lucknow.city)
        assertEquals("Uttar Pradesh (East)", lucknow.state)
        assertEquals("226001", lucknow.postalCode)
        assertEquals("India", lucknow.country)

        // 2. Unlisted Indian mobile prefix: MUST NOT pick a random city/ZIP
        val unlistedA = com.example.telecom.CallerIdResolver.resolveInstant("+91 69991 12345")
        val unlistedB = com.example.telecom.CallerIdResolver.resolveInstant("+91 69992 98765")
        assertFalse(unlistedA.geo.isExactPrefixMatch)
        assertEquals("Pan-India Mobile", unlistedA.city)
        assertEquals("N/A", unlistedA.postalCode)
        assertEquals("India", unlistedA.country)
        assertEquals(unlistedA.city, unlistedB.city)
        assertEquals(unlistedA.state, unlistedB.state)

        // 3. International NANPA (USA & Canada), UK, UAE exact City/State/ZIP/Country
        val sf = com.example.telecom.CallerIdResolver.resolveInstant("+1 415 555 2671")
        assertEquals("San Francisco", sf.city)
        assertEquals("California", sf.state)
        assertEquals("94102", sf.postalCode)
        assertEquals("United States", sf.country)

        val nyc = com.example.telecom.CallerIdResolver.resolveInstant("+1 212 555 0199")
        assertEquals("New York", nyc.city)
        assertEquals("New York", nyc.state)
        assertEquals("10001", nyc.postalCode)
        assertEquals("United States", nyc.country)

        val toronto = com.example.telecom.CallerIdResolver.resolveInstant("+1 416 555 0144")
        assertEquals("Toronto", toronto.city)
        assertEquals("Ontario", toronto.state)
        assertEquals("Canada", toronto.country)

        val london = com.example.telecom.CallerIdResolver.resolveInstant("+44 20 7946 0958")
        assertEquals("London", london.city)
        assertEquals("Greater London", london.state)
        assertEquals("SW1A 1AA", london.postalCode)
        assertEquals("United Kingdom", london.country)

        val dubai = com.example.telecom.CallerIdResolver.resolveInstant("+971 4 555 0100")
        assertEquals("Dubai", dubai.city)
        assertEquals("Dubai Emirate", dubai.state)
        assertEquals("United Arab Emirates", dubai.country)

        // 4. Synced Social & Cloud Directory Real Name Resolution Priority + Postal Override
        val socialResolved = com.example.telecom.CallerIdResolver.resolveInstant(
            rawNumber = "+91 98201 23456",
            socialProfiles = listOf(
                com.example.telecom.SocialPlatformMatch(
                    platform = "WhatsApp",
                    profileName = "Rohan Sharma"
                ),
                com.example.telecom.SocialPlatformMatch(
                    platform = "Telegram",
                    profileName = "Rohan S."
                )
            ),
            postalOverride = com.example.telecom.CallerGeoInfo(
                city = "Bandra West, Mumbai",
                state = "Maharashtra",
                postalCode = "400050",
                country = "India"
            )
        )
        assertFalse(socialResolved.isUnknownNumber)
        assertEquals("Rohan Sharma", socialResolved.displayName)
        assertEquals("WhatsApp Profile", socialResolved.sourceLabel)
        assertEquals("Bandra West, Mumbai", socialResolved.city)
        assertEquals("400050", socialResolved.postalCode)
        assertEquals("WhatsApp • Telegram", socialResolved.socialBadgesSummary())

        // 5. Verified Social Lookup URLs use normalized E.164 digits
        assertEquals(
            "https://wa.me/919820123456",
            com.example.telecom.CallerIdResolver.buildSocialLookupUrl(
                com.example.telecom.CallerIdResolver.PLATFORM_WHATSAPP,
                "98201 23456"
            )
        )
        assertEquals(
            "https://t.me/+919820123456",
            com.example.telecom.CallerIdResolver.buildSocialLookupUrl(
                com.example.telecom.CallerIdResolver.PLATFORM_TELEGRAM,
                "+91 98201 23456"
            )
        )
    }

    @Test
    fun `CallDetailActivity renders structured Location State ZIP Country and social lookup controls`() {
        val intent = CallDetailActivity.createIntent(app, "+91 98201 23456", null)
        val ctrl = Robolectric.buildActivity(CallDetailActivity::class.java, intent).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val activity = ctrl.get()

        assertEquals("Mumbai", activity.findViewById<TextView>(R.id.tv_caller_id_city).text.toString())
        assertEquals("Maharashtra", activity.findViewById<TextView>(R.id.tv_caller_id_state).text.toString())
        assertEquals("400001", activity.findViewById<TextView>(R.id.tv_caller_id_zip).text.toString())
        assertTrue(activity.findViewById<TextView>(R.id.tv_caller_id_country).text.toString().contains("India"))
        assertNotNull(activity.findViewById<View>(R.id.chip_lookup_whatsapp))
        assertNotNull(activity.findViewById<View>(R.id.chip_lookup_telegram))
        assertNotNull(activity.findViewById<View>(R.id.chip_lookup_facebook))
        assertNotNull(activity.findViewById<View>(R.id.chip_lookup_instagram))
        assertNotNull(activity.findViewById<View>(R.id.chip_lookup_web))
        assertNotNull(activity.findViewById<View>(R.id.btn_verify_caller_identity))

        ctrl.pause().stop().destroy()
    }

    @Test
    fun `all 4 themes meet WCAG high-contrast legibility and render razor-sharp anti-aliased typography without blur`() {
        val prefs = DialerPreferences(app)
        prefs.adminBlockedUser = false
        prefs.isPremiumUser = true
        prefs.subscriptionExpiresAtMs = System.currentTimeMillis() + DialerPreferences.ONE_MONTH_MILLIS

        // 1. Verify WCAG AAA/AA contrast ratios across every theme palette (eliminating blurry neon-on-dark bloom)
        AppThemePalette.entries.forEach { palette ->
            val primaryContrast = androidx.core.graphics.ColorUtils.calculateContrast(
                palette.textPrimaryColorInt(),
                palette.cardBgColorInt()
            )
            val secondaryContrast = androidx.core.graphics.ColorUtils.calculateContrast(
                palette.textSecondaryColorInt(),
                palette.cardBgColorInt()
            )
            assertTrue(
                "${palette.id} primary contrast too low: $primaryContrast",
                primaryContrast >= 12.0
            )
            assertTrue(
                "${palette.id} secondary contrast too low: $secondaryContrast",
                secondaryContrast >= 7.0
            )
            assertNotNull(palette.create3DGradientDrawable(16f))
            assertNotNull(palette.createCrispBadgePillDrawable(16f))
            assertNotNull(palette.create3DThemeShowcaseBannerDrawable(18f))
            assertNotNull(palette.createFullApp3DBackgroundDrawable())
            assertNotNull(palette.create3DGlassCardDrawable(20f))
            assertNotNull(palette.createDarkGlassIconTileDrawable(14f))
        }

        // 2. Inflate fragment_settings and apply each 3D theme to verify switches, chips, cards, and crisp typography
        val themedCtx = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val inflater = android.view.LayoutInflater.from(themedCtx)
        AppThemePalette.premium3DMixThemes().forEach { palette ->
            prefs.selectedThemePaletteId = palette.id
            val settingsRoot = inflater.inflate(R.layout.fragment_settings, null)
            com.example.ui.theme.ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(settingsRoot, prefs)

            val simSummary = settingsRoot.findViewById<TextView>(R.id.tv_default_sim_summary)
            assertEquals(palette.textSecondaryColorInt(), simSummary.currentTextColor)
            assertEquals(0f, simSummary.shadowRadius, 0.001f)
            assertTrue((simSummary.paint.flags and android.graphics.Paint.ANTI_ALIAS_FLAG) != 0)
            assertTrue((simSummary.paint.flags and android.graphics.Paint.SUBPIXEL_TEXT_FLAG) != 0)

            val dynamicSwitch = settingsRoot.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switch_dynamic_colors
            )
            assertEquals(palette.textPrimaryColorInt(), dynamicSwitch.currentTextColor)
            assertNotNull(dynamicSwitch.trackTintList)
            assertNotNull(dynamicSwitch.thumbTintList)

            val themeCard = settingsRoot.findViewById<com.google.android.material.card.MaterialCardView>(
                R.id.card_theme_studio
            )
            assertEquals(palette.cardBgColorInt(), themeCard.cardBackgroundColor.defaultColor)
        }
    }

    @Test
    fun `CallDetail and NumberDetail dark glass styling, contact dedup, keypad icons, and foreground call popup tracking work cleanly`() {
        val prefs = DialerPreferences(app)
        prefs.adminBlockedUser = false
        prefs.isPremiumUser = true
        prefs.subscriptionExpiresAtMs = System.currentTimeMillis() + DialerPreferences.ONE_MONTH_MILLIS
        prefs.selectedThemePaletteId = AppThemePalette.AURORA_3D_MIX.id

        // 1. Verify CallDetailActivity restyles avatars, geo cards, and source badge on 3D themes (no white-on-white)
        val detailIntent = CallDetailActivity.createIntent(app, "+91 98201 23456", null)
        val detailCtrl = Robolectric.buildActivity(CallDetailActivity::class.java, detailIntent).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val detailActivity = detailCtrl.get()
        val detailAvatar = detailActivity.findViewById<TextView>(R.id.tv_detail_avatar)
        val sourceBadge = detailActivity.findViewById<TextView>(R.id.tv_caller_id_source_badge)
        val cityText = detailActivity.findViewById<TextView>(R.id.tv_caller_id_city)
        val cityBox = cityText.parent as View
        assertEquals(android.graphics.Color.WHITE, detailAvatar.currentTextColor)
        assertEquals(android.graphics.Color.WHITE, sourceBadge.currentTextColor)
        assertTrue(cityBox.background is android.graphics.drawable.LayerDrawable)
        assertEquals(View.VISIBLE, detailActivity.findViewById<View>(R.id.btn_save_contact).visibility)
        detailCtrl.pause().stop().destroy()

        // 2. Verify bottom_sheet_number_detail & item_contact restyle cleanly on 3D themes
        val themedCtx = android.view.ContextThemeWrapper(app, R.style.Theme_PulseDial)
        val inflater = android.view.LayoutInflater.from(themedCtx)
        val sheetRoot = inflater.inflate(R.layout.bottom_sheet_number_detail, null)
        com.example.ui.theme.ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(sheetRoot, prefs)
        val sheetGeoBox = sheetRoot.findViewById<View>(R.id.layout_sheet_caller_geo)
        val sheetAvatar = sheetRoot.findViewById<TextView>(R.id.tv_detail_avatar)
        assertTrue(sheetGeoBox.background is android.graphics.drawable.LayerDrawable)
        assertEquals(android.graphics.Color.WHITE, sheetAvatar.currentTextColor)
        assertNotNull(sheetRoot.findViewById<TextView>(R.id.tv_detail_save_label))

        val contactRow = inflater.inflate(R.layout.item_contact, null)
        com.example.ui.theme.ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(contactRow, prefs)
        val contactAvatar = contactRow.findViewById<TextView>(R.id.tv_contact_initial)
        assertEquals(android.graphics.Color.WHITE, contactAvatar.currentTextColor)

        // 3. Verify ContactsRepository normalization & phone-as-name detection
        val repo = com.example.data.ContactsRepository(app)
        assertEquals("9820123456", repo.normalizePhoneDedupKey("+91 98201-23456"))
        assertEquals("9820123456", repo.normalizePhoneDedupKey("09820123456"))
        assertTrue(com.example.data.ContactsRepository.isNameJustPhoneNumber("+91 98201 23456", "+919820123456"))
        assertFalse(com.example.data.ContactsRepository.isNameJustPhoneNumber("Aarav Sharma", "+919820123456"))

        // 4. Verify keypad icons & 3D call notification popup layouts inflate cleanly
        assertNotNull(androidx.core.content.ContextCompat.getDrawable(themedCtx, R.drawable.ic_dialpad))
        assertNotNull(androidx.core.content.ContextCompat.getDrawable(themedCtx, R.drawable.ic_backspace))
        assertNotNull(androidx.core.content.ContextCompat.getDrawable(themedCtx, R.drawable.ic_add_call))
        assertNotNull(inflater.inflate(R.layout.notification_call_3d_compact, null))
        assertNotNull(inflater.inflate(R.layout.notification_call_3d_popup, null))

        // 5. Verify InCallActivity foreground visibility toggles CallManager.inCallUiVisible
        CallManager.publishStateForTesting(
            CallUiState(
                hasActiveCall = true,
                callState = Call.STATE_RINGING,
                callerName = "Rahul",
                callerNumber = "+919876543210"
            )
        )
        val incallCtrl = Robolectric.buildActivity(InCallActivity::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(CallManager.isInCallUiVisible())
        incallCtrl.pause()
        assertFalse(CallManager.isInCallUiVisible())
        incallCtrl.stop().destroy()

        // 6. Verify call notification clears immediately on disconnect / decline (never stays stuck)
        assertFalse(
            com.example.telecom.CustomInCallService.shouldShowCallNotification(
                hasActiveCall = true,
                callState = Call.STATE_DISCONNECTED,
                liveCallCount = 0
            )
        )
        assertFalse(
            com.example.telecom.CustomInCallService.shouldShowCallNotification(
                hasActiveCall = true,
                callState = Call.STATE_DISCONNECTING,
                liveCallCount = 1
            )
        )
        assertTrue(
            com.example.telecom.CustomInCallService.shouldShowCallNotification(
                hasActiveCall = true,
                callState = Call.STATE_RINGING,
                liveCallCount = 1
            )
        )
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val dummyNotif = android.app.Notification.Builder(app, com.example.PulseDialApp.CHANNEL_INCOMING_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Stale Call")
            .build()
        nm.notify(com.example.telecom.CustomInCallService.CALL_NOTIFICATION_ID, dummyNotif)
        assertEquals(1, Shadows.shadowOf(nm).size())
        com.example.telecom.CallNotificationActionReceiver().onReceive(
            app,
            android.content.Intent(com.example.telecom.CallNotificationActionReceiver.ACTION_DECLINE_CALL)
        )
        assertEquals(0, Shadows.shadowOf(nm).size())
    }
}
