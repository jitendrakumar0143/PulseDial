package com.example.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles Call Notification action buttons (Answer, Decline, Hang Up, Mute, Speaker)
 * from the 3D Pull-Down Call Notification Popup & Notification.CallStyle without unlocking.
 */
class CallNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER_CALL -> CallManager.answerCall()
            ACTION_DECLINE_CALL,
            ACTION_HANG_UP_CALL -> CallManager.declineOrEndCall()
            ACTION_TOGGLE_MUTE -> CallManager.toggleMute()
            ACTION_TOGGLE_SPEAKER -> CallManager.toggleSpeaker()
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
