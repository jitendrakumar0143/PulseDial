package com.example.recording

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.PulseDialApp
import com.example.R
import com.example.billing.SubscriptionManager
import com.example.data.DialerPreferences
import com.example.data.firebase.RealtimeSyncManager
import com.example.telecom.CallManager
import com.example.ui.incall.InCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dedicated Android 14/15/16+ Foreground Service declaring microphone|phoneCall types.
 * Prevents Android 11–16 AppOps from silencing or killing the recorder when the screen
 * locks via proximity sensor or moves to the background during an active call.
 */
class CallRecorderForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        RealtimeSyncManager.init(applicationContext)
        serviceScope.launch {
            RealtimeSyncManager.syncState.collect { state ->
                if (state.uid != null) {
                    val prefs = DialerPreferences(this@CallRecorderForegroundService)
                    val subManager = SubscriptionManager(this@CallRecorderForegroundService)
                    if (!subManager.isSubscriptionActive() ||
                        !prefs.allowCallRecordingConfig ||
                        prefs.adminBlockedUser
                    ) {
                        CallManager.stopRecordingIfNeeded()
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildSilentNotification()
        startForegroundWithCompatTypes(notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun startForegroundWithCompatTypes(notification: Notification) {
        val primaryType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            else -> 0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, primaryType)
        } catch (se: SecurityException) {
            // Fallback to microphone-only type if phoneCall FGS prerequisite isn't met at runtime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                }.onFailure { stopSelf() }
            } else {
                stopSelf()
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun buildSilentNotification(): Notification {
        val tapIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            2001,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PulseDialApp.CHANNEL_ONGOING_CALLS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.incall_recording_active))
            .setOngoing(true)
            .setSilent(true)
            .setSound(null)
            .setVibrate(null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4402
        private const val ACTION_START = "com.example.recording.ACTION_START"
        private const val ACTION_STOP = "com.example.recording.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, CallRecorderForegroundService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, CallRecorderForegroundService::class.java))
            }
        }
    }
}
