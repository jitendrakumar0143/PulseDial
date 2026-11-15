package com.example.telecom

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.example.PulseDialApp
import com.example.R
import com.example.billing.SubscriptionManager
import com.example.data.ContactsRepository
import com.example.data.DialerPreferences
import com.example.data.firebase.RealtimeSyncManager
import com.example.ui.incall.InCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Default Dialer InCallService paired with CallRecordingAccessibilityService and CallRecorder.
 * Starts silent call recording immediately on the first bell (STATE_DIALING, STATE_RINGING,
 * or STATE_CONNECTING) and stops/finalizes only when the call enters STATE_DISCONNECTED.
 */
class CustomInCallService : InCallService() {

    private lateinit var prefs: DialerPreferences
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var contactsRepo: ContactsRepository
    private var currentCall: Call? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            CallManager.updateCallState(call, state)

            if (isEarlyOrActiveRecordingState(state)) {
                triggerAutoRecordingIfPermitted()
            } else if (state == Call.STATE_DISCONNECTED) {
                CallManager.stopRecordingIfNeeded()
                cancelCallNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = DialerPreferences(this)
        subscriptionManager = SubscriptionManager(this)
        contactsRepo = ContactsRepository(this)
        CallManager.attachService(this)
        RealtimeSyncManager.init(applicationContext)
        serviceScope.launch {
            RealtimeSyncManager.syncState.collect {
                val activeCall = currentCall ?: return@collect
                val callState = getCompatState(activeCall)
                if (!subscriptionManager.isSubscriptionActive() ||
                    !prefs.allowCallRecordingConfig ||
                    prefs.adminBlockedUser
                ) {
                    CallManager.stopRecordingIfNeeded()
                } else if (isEarlyOrActiveRecordingState(callState)) {
                    triggerAutoRecordingIfPermitted()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        CallManager.stopRecordingIfNeeded()
        cancelCallNotification()
        CallManager.detachService()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val rawNumber = call.details.handle?.schemeSpecificPart.orEmpty()
        val initialState = getCompatState(call)

        // Automatically reject blocked incoming numbers before any recording or UI starts
        if (initialState == Call.STATE_RINGING && prefs.isNumberBlocked(rawNumber)) {
            call.reject(false, null)
            return
        }

        currentCall = call
        call.registerCallback(callCallback)

        val resolvedName = call.details.callerDisplayName?.takeIf { it.isNotBlank() }
            ?: contactsRepo.lookupCallerName(rawNumber)
            ?: getString(R.string.incall_unknown_caller)

        CallManager.setCurrentCall(call, resolvedName, rawNumber)
        postCallNotification(resolvedName, rawNumber, initialState == Call.STATE_RINGING)

        // Start silent recording from the very first bell (Dialing / Ringing / Connecting)
        if (isEarlyOrActiveRecordingState(initialState)) {
            triggerAutoRecordingIfPermitted()
        }

        val launchIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(launchIntent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (call == currentCall) {
            CallManager.stopRecordingIfNeeded()
            currentCall = null
            CallManager.setCurrentCall(null, "", "")
            cancelCallNotification()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState ?: return
        val isSpeaker = (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0
        CallManager.updateAudioState(audioState.isMuted, isSpeaker)
    }

    private fun isEarlyOrActiveRecordingState(state: Int): Boolean {
        return state == Call.STATE_DIALING ||
            state == Call.STATE_RINGING ||
            state == Call.STATE_CONNECTING ||
            state == Call.STATE_ACTIVE
    }

    private fun triggerAutoRecordingIfPermitted() {
        if (prefs.autoCallRecording &&
            prefs.allowCallRecordingConfig &&
            subscriptionManager.isSubscriptionActive()
        ) {
            CallManager.startAutoRecordingIfEnabled(applicationContext)
        }
    }

    private fun postCallNotification(name: String, number: String, isRinging: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val fullScreenIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isRinging) {
            PulseDialApp.CHANNEL_INCOMING_CALLS
        } else {
            PulseDialApp.CHANNEL_ONGOING_CALLS
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setSmallIcon(R.drawable.ic_call)
            .setContentTitle(name)
            .setContentText(if (number.isNotBlank()) number else getString(R.string.incall_unknown_caller))
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        if (isRinging) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        runCatching {
            manager.notify(CALL_NOTIFICATION_ID, builder.build())
        }
    }

    private fun cancelCallNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(CALL_NOTIFICATION_ID)
    }

    @Suppress("DEPRECATION")
    private fun getCompatState(call: Call): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            call.state
        }
    }

    companion object {
        private const val CALL_NOTIFICATION_ID = 4401
    }
}
