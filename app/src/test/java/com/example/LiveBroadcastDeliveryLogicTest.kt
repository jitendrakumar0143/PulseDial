package com.example

import com.example.data.firebase.LiveBroadcast
import com.example.data.firebase.RealtimeDatabaseSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast pure-JVM unit tests verifying strict one-time popup delivery (`seenPopupIds`),
 * Force Update override behavior, Custom Ad close guarantees, and RTDB payload parsing.
 */
class LiveBroadcastDeliveryLogicTest {

    @Test
    fun oneTimeDelivery_blocksSamePopupIdAfterSeen_andAllowsNewPopupId() {
        val seenIds = mutableSetOf<String>()
        val popup1 = LiveBroadcast(
            showPopup = true,
            popupId = LiveBroadcast.generatePopupId(1_700_000_000_001L),
            popupType = LiveBroadcast.TYPE_AD,
            title = "Festive Sale",
            message = "Get 50% off now!",
            imageUrl = "https://example.com/ad.png",
            buttonText = "Check Now",
            actionUrl = "https://example.com/offer",
            showOnce = true,
            isCancelable = true
        )

        assertEquals("popup_1700000000001", popup1.popupId)
        assertTrue(popup1.shouldShowToUser(seenIds))

        // User closes or clicks CTA -> popupId saved into seenPopupIds
        seenIds.add(popup1.effectivePopupId())
        assertFalse(popup1.shouldShowToUser(seenIds))

        // Admin pushes a new popup -> new popupId generated -> delivered once again
        val popup2 = popup1.copy(popupId = LiveBroadcast.generatePopupId(1_700_000_000_999L))
        assertTrue(popup2.shouldShowToUser(seenIds))
    }

    @Test
    fun forceUpdate_keepsShowingUntilAdminDisables_whileOptionalUpdateShowsOnce() {
        val seenIds = mutableSetOf<String>()
        val optionalUpdate = LiveBroadcast(
            showPopup = true,
            popupId = "popup_update_v2",
            popupType = LiveBroadcast.TYPE_UPDATE,
            version = "v2.0",
            forceUpdate = false,
            showOnce = true,
            title = "New Update Available",
            message = "Major performance improvements.",
            buttonText = "Update Now",
            actionUrl = "https://example.com/v2",
            isCancelable = true
        )

        assertTrue(optionalUpdate.isUpdatePopup())
        assertFalse(optionalUpdate.isForceUpdateActive())
        assertTrue(optionalUpdate.shouldShowToUser(seenIds))

        seenIds.add(optionalUpdate.effectivePopupId())
        assertFalse(optionalUpdate.shouldShowToUser(seenIds))

        val forceUpdate = optionalUpdate.copy(forceUpdate = true, isCancelable = false)
        assertTrue(forceUpdate.isForceUpdateActive())
        assertTrue(forceUpdate.shouldShowToUser(seenIds))

        val turnedOff = forceUpdate.copy(showPopup = false)
        assertFalse(turnedOff.shouldShowToUser(seenIds))
    }

    @Test
    fun parseLiveBroadcastSnapshot_enforcesAdAlwaysDismissible_andPreservesUpdateFields() {
        val updateParsed = RealtimeDatabaseSync.parseLiveBroadcastSnapshot(
            mapOf(
                "showPopup" to true,
                "popupId" to "popup_100",
                "popupType" to "update",
                "version" to "v2.1",
                "forceUpdate" to true,
                "showOnce" to true,
                "title" to "Update v2.1",
                "message" to "Important fixes",
                "buttonText" to "Update Now",
                "actionUrl" to "https://example.com/v21"
            )
        )
        assertEquals(LiveBroadcast.TYPE_UPDATE, updateParsed.popupType)
        assertEquals("v2.1", updateParsed.version)
        assertTrue(updateParsed.isForceUpdateActive())
        assertFalse(updateParsed.isCancelable)

        val adParsed = RealtimeDatabaseSync.parseLiveBroadcastSnapshot(
            mapOf(
                "showPopup" to true,
                "popupId" to "popup_200",
                "popupType" to "ad",
                "forceUpdate" to true, // Custom ads must always keep close (x) visible
                "showOnce" to true,
                "title" to "Promo Ad",
                "message" to "Limited time deal",
                "imageUrl" to "https://example.com/promo.jpg",
                "buttonText" to "Check Now",
                "actionUrl" to "https://example.com/promo"
            )
        )
        assertEquals(LiveBroadcast.TYPE_AD, adParsed.popupType)
        assertFalse(adParsed.isForceUpdateActive())
        assertTrue(adParsed.isCancelable)
    }
}
