package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.example.data.DialerPreferences

class PulseDialApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.example.data.PulseDialCrashLogger.install(this)
        val prefs = DialerPreferences(this)
        prefs.ensureFirstLaunchRecorded()
        AppCompatDelegate.setDefaultNightMode(prefs.displayThemeMode)
        createNotificationChannels()
        com.example.data.firebase.RealtimeDatabaseSync.init(this)
        com.example.billing.SubscriptionEnforcementService.init(this)
        com.example.data.firebase.RealtimeSyncManager.init(this)
        com.example.recording.VoipCallRecorderManager.init(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            val incomingChannel = NotificationChannel(
                CHANNEL_INCOMING_CALLS,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen notifications for incoming phone calls"
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                setSound(
                    ringtoneUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
            }

            val ongoingChannel = NotificationChannel(
                CHANNEL_ONGOING_CALLS,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active call status and recording indicator"
                setSound(null, null)
                enableVibration(false)
            }

            val followUpChannel = NotificationChannel(
                CHANNEL_FOLLOW_UP,
                "Post-Call Follow-Up",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Quick actions to send WhatsApp or SMS follow-ups after calls"
            }

            manager.createNotificationChannel(incomingChannel)
            manager.createNotificationChannel(ongoingChannel)
            manager.createNotificationChannel(followUpChannel)
        }
    }

    companion object {
        const val CHANNEL_INCOMING_CALLS = "pulsedial_incoming_calls"
        const val CHANNEL_ONGOING_CALLS = "pulsedial_ongoing_calls"
        const val CHANNEL_FOLLOW_UP = "pulsedial_follow_up"
    }
}
