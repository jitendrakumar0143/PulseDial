package com.example.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles Call Notification action buttons (Answer, Decline, Hang Up) from Notification.CallStyle
 * without requiring the user to unlock or open InCallActivity first.
 */
class CallNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER_CALL -> CallManager.answerCall()
            ACTION_DECLINE_CALL,
            ACTION_HANG_UP_CALL -> CallManager.declineOrEndCall()
        }
    }

    companion object {
        const val ACTION_ANSWER_CALL = "com.example.telecom.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "com.example.telecom.ACTION_DECLINE_CALL"
        const val ACTION_HANG_UP_CALL = "com.example.telecom.ACTION_HANG_UP_CALL"
    }
}
