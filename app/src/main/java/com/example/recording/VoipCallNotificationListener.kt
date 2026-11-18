package com.example.recording

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Play-Protect-safe VoIP Call Detector using standard Android [NotificationListenerService].
 *
 * Identifies active or incoming calls from:
 * - WhatsApp (`com.whatsapp`)
 * - WhatsApp Business (`com.whatsapp.w4b`)
 * - Google Meet (`com.google.android.apps.meetings` / `com.google.android.apps.tachyon`)
 * - Telegram (`org.telegram.messenger` / variants)
 * - Instagram (`com.instagram.android` / `com.instagram.lite`)
 *
 * Strictly ignores message notifications and triggers VoIP recording hints ONLY for
 * call-category / ongoing call notifications so each app saves into its own dedicated folder.
 */
class VoipCallNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusBarNotif = sbn ?: return
        val source = CallRecordingSource.fromPackageName(statusBarNotif.packageName) ?: return
        val notification = statusBarNotif.notification ?: return
        if (!isCallNotification(notification)) return

        val callerTitle = notification.extras
            ?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
            ?.takeIf { title ->
                title.isNotBlank() && !title.equals(source.displayLabel, ignoreCase = true)
            }

        VoipCallRecorderManager.onVoipAppEvent(
            context = applicationContext,
            source = source,
            isCallHint = true,
            labelHint = callerTitle
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val statusBarNotif = sbn ?: return
        val source = CallRecordingSource.fromPackageName(statusBarNotif.packageName) ?: return
        val notification = statusBarNotif.notification
        if (notification == null || isCallNotification(notification)) {
            VoipCallRecorderManager.onVoipNotificationCleared(source)
        }
    }

    companion object {
        private val CALL_KEYWORDS = listOf(
            "ongoing call",
            "incoming call",
            "voice call",
            "video call",
            "whatsapp call",
            "calling",
            "ringing",
            "tap to return to call",
            "on call",
            "in call",
            "meet",
            "video chat"
        )

        fun isCallNotification(notification: Notification): Boolean {
            if (notification.category == Notification.CATEGORY_CALL) return true
            val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            if (!isOngoing) return false

            val extras = notification.extras ?: return false
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            val combined = "$title $text $subText".lowercase()
            return CALL_KEYWORDS.any { keyword -> combined.contains(keyword) }
        }

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledPackages = runCatching {
                NotificationManagerCompat.getEnabledListenerPackages(context.applicationContext)
            }.getOrDefault(emptySet())
            if (enabledPackages.contains(context.packageName)) return true

            val flat = runCatching {
                Settings.Secure.getString(
                    context.applicationContext.contentResolver,
                    "enabled_notification_listeners"
                )
            }.getOrNull().orEmpty()
            return flat.contains(context.packageName)
        }

        fun createNotificationAccessSettingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
