package com.example.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles Call Notification action buttons (Answer, Decline, Hang Up, Mute, Speaker)
 * from the 3D Call Notification Popup without unlocking, and guarantees stale notifications
 * are dismissed immediately when a call is declined, ended, or no longer tracked.
 */
class CallNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_ANSWER_CALL -> {
                if (CallManager.getTrackedCallsCount() == 0) {
                    CustomInCallService.cancelCallNotification(context)
                } else {
                    CallManager.answerCall()
                }
            }
            ACTION_DECLINE_CALL,
            ACTION_HANG_UP_CALL -> {
                val trackedBefore = CallManager.getTrackedCallsCount()
                CallManager.declineOrEndCall()
                if (trackedBefore <= 1) {
                    CustomInCallService.cancelCallNotification(context)
                }
            }
            ACTION_TOGGLE_MUTE -> {
                if (CallManager.getTrackedCallsCount() == 0) {
                    CustomInCallService.cancelCallNotification(context)
                } else {
                    CallManager.toggleMute()
                }
            }
            ACTION_TOGGLE_SPEAKER -> {
                if (CallManager.getTrackedCallsCount() == 0) {
                    CustomInCallService.cancelCallNotification(context)
                } else {
                    CallManager.toggleSpeaker()
                }
            }
        }
    }

    companion object {
        const val ACTION_ANSWER_CALL = "com.example.telecom.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "com.example.telecom.ACTION_DECLINE_CALL"
        const val ACTION_HANG_UP_CALL = "com.example.telecom.ACTION_HANG_UP_CALL"
        const val ACTION_TOGGLE_MUTE = "com.example.telecom.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.example.telecom.ACTION_TOGGLE_SPEAKER"
    }
}
