package com.example

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.data.DialerPreferences
import com.example.data.firebase.LiveBroadcast
import com.example.data.firebase.RealtimeDatabaseSync
import com.example.data.firebase.RealtimeSyncEvent
import com.example.data.firebase.RealtimeSyncManager
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdminDeletionAndBroadcastPopupTest {

    private lateinit var context: Context
    private lateinit var prefs: DialerPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pulsedial_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        RealtimeSyncManager.stop()
        prefs = DialerPreferences(context)
    }

    @Test
    fun oneTimeDelivery_showsOncePerPopupId_andReTriggersOnNewPopupId() {
        val adPopup1 = LiveBroadcast(
            showPopup = true,
            popupId = "popup_1700000000001",
            popupType = LiveBroadcast.TYPE_AD,
            title = "Festive Offer",
            message = "Get 50% off today!",
            imageUrl = "https://example.com/ad.png",
            buttonText = "Check Now",
            actionUrl = "https://example.com/offer",
            showOnce = true,
            isCancelable = true
        )

        // First push -> should display
        assertTrue(adPopup1.shouldShowToUser(prefs.getSeenPopupIds()))
        assertTrue(adPopup1.isAdPopup())
        assertFalse(adPopup1.isForceUpdateActive())

        // User closes or clicks CTA -> saved into seenPopupIds
        prefs.markPopupIdSeen(adPopup1.effectivePopupId())
        assertTrue(prefs.hasSeenPopupId("popup_1700000000001"))

        // Same popupId must NEVER show again
        assertFalse(adPopup1.shouldShowToUser(prefs.getSeenPopupIds()))

        // Admin pushes a brand-new popup -> new popupId generated -> shows once again
        val adPopup2 = adPopup1.copy(popupId = "popup_1700000000002", title = "Second Promo")
        assertTrue(adPopup2.shouldShowToUser(prefs.getSeenPopupIds()))
    }

    @Test
    fun forceUpdatePopup_keepsShowingUntilDisabledByAdmin_whileOptionalUpdateShowsOnce() {
        val optionalUpdate = LiveBroadcast(
            showPopup = true,
            popupId = "popup_update_v20",
            popupType = LiveBroadcast.TYPE_UPDATE,
            version = "v2.0",
            forceUpdate = false,
            showOnce = true,
            title = "New Update Available",
            message = "Bug fixes and faster dialer.",
            buttonText = "Update Now",
            actionUrl = "https://example.com/v2",
            isCancelable = true
        )
        assertTrue(optionalUpdate.isUpdatePopup())
        assertFalse(optionalUpdate.isForceUpdateActive())
        assertTrue(optionalUpdate.shouldShowToUser(prefs.getSeenPopupIds()))

        prefs.markPopupIdSeen(optionalUpdate.effectivePopupId())
        assertFalse(optionalUpdate.shouldShowToUser(prefs.getSeenPopupIds()))

        // Now Admin turns Force Update ON -> must keep showing even if previously seen
        val forceUpdate = optionalUpdate.copy(forceUpdate = true, isCancelable = false)
        assertTrue(forceUpdate.isForceUpdateActive())
        assertTrue(forceUpdate.shouldShowToUser(prefs.getSeenPopupIds()))

        // When Admin turns popup OFF -> stops showing
        val disabled = forceUpdate.copy(showPopup = false)
        assertFalse(disabled.shouldShowToUser(prefs.getSeenPopupIds()))
    }

    @Test
    fun parseLiveBroadcastSnapshot_roundTripsUpdateAndAdFields() {
        val parsedUpdate = RealtimeDatabaseSync.parseLiveBroadcastSnapshot(
            mapOf(
                "showPopup" to true,
                "popupId" to "popup_9001",
                "popupType" to "update",
                "version" to "v2.5",
                "forceUpdate" to true,
                "showOnce" to true,
                "title" to "Critical Update",
                "message" to "Please update now.",
                "buttonText" to "Update Now",
                "actionUrl" to "https://example.com/download",
                "isCancelable" to false
            )
        )
        assertEquals(LiveBroadcast.TYPE_UPDATE, parsedUpdate.popupType)
        assertEquals("v2.5", parsedUpdate.version)
        assertTrue(parsedUpdate.forceUpdate)
        assertTrue(parsedUpdate.isForceUpdateActive())
        assertTrue(parsedUpdate.showOnce)

        val parsedAd = RealtimeDatabaseSync.parseLiveBroadcastSnapshot(
            mapOf(
                "showPopup" to true,
                "popupId" to "popup_9002",
                "popupType" to "ad",
                "forceUpdate" to true, // Ads must NEVER lock out close button
                "showOnce" to true,
                "title" to "Partner Deal",
                "message" to "Exclusive offer",
                "imageUrl" to "https://example.com/ad.jpg",
                "buttonText" to "Check Now",
                "actionUrl" to "https://example.com/deal"
            )
        )
        assertEquals(LiveBroadcast.TYPE_AD, parsedAd.popupType)
        assertFalse(parsedAd.forceUpdate)
        assertFalse(parsedAd.isForceUpdateActive())
        assertTrue(parsedAd.isCancelable)
    }

    @Test
    fun adminUserDeletion_clearsLocalSessionAndEmitsAccountDeletedEvent() = runBlocking {
        prefs.isLoggedIn = true
        prefs.loggedInUserId = "uid_victim_1"
        prefs.userEmail = "victim@example.com"
        prefs.userName = "Victim User"
        prefs.isPremiumUser = true

        val deferredEvent = async { RealtimeSyncManager.events.first() }
        RealtimeSyncManager.handleUserDeletedFromServer(
            uid = "uid_victim_1",
            ctx = context,
            prefs = prefs,
            source = "Test"
        )

        val emitted = deferredEvent.await()
        assertEquals(RealtimeSyncEvent.AccountDeletedByAdmin, emitted)
        assertFalse(prefs.isLoggedIn)
        assertEquals("", prefs.loggedInUserId)
        assertEquals("", prefs.userEmail)
        assertFalse(prefs.isPremiumUser)
        assertEquals(
            "Your account has been removed by the admin.",
            context.getString(R.string.realtime_account_deleted_toast)
        )
    }

    @Test
    fun liveBroadcastLayout_supportsForceUpdateAndAdCloseVisibility() {
        val inflater = android.view.LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_live_broadcast, null)
        assertTrue(view.findViewById<TextView>(R.id.tv_popup_type_badge) != null)
        assertTrue(view.findViewById<TextView>(R.id.tv_popup_version_pill) != null)
        assertTrue(view.findViewById<TextView>(R.id.btn_popup_close_icon) != null)
        assertTrue(view.findViewById<TextView>(R.id.tv_popup_force_notice) != null)
        assertTrue(view.findViewById<View>(R.id.card_popup_glass) != null)
    }
}
