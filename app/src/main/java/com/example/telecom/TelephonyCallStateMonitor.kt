package com.example.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat

/**
 * Monitors cellular call state transitions via Android's TelephonyManager using
 * [TelephonyCallback.CallStateListener] on API 31+ and [PhoneStateListener] on API < 31.
 *
 * Specifically detects the authoritative [TelephonyManager.CALL_STATE_OFFHOOK] ->
 * [TelephonyManager.CALL_STATE_IDLE] transition (as well as RINGING -> IDLE) so background
 * services can reliably clean up active call UI, notifications, and call recording even if
 * OEM Telecom stacks drop or delay `InCallService.onCallRemoved`.
 */
class TelephonyCallStateMonitor(
    private val context: Context,
    private val onIdleAfterOffhook: () -> Unit,
    private val onIdleAfterRinging: () -> Unit = {},
    private val onStateChanged: (Int) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val telephonyManager: TelephonyManager? =
        appContext.getSystemService(TelephonyManager::class.java)

    @Volatile
    private var isStarted: Boolean = false

    @Volatile
    private var lastCallState: Int = TelephonyManager.CALL_STATE_IDLE

    @Volatile
    private var hasSeenOffhook: Boolean = false

    @Volatile
    private var hasSeenRinging: Boolean = false

    private var api31Callback: TelephonyCallback? = null

    @Suppress("DEPRECATION")
    private var legacyPhoneStateListener: PhoneStateListener? = null

    fun start(): Boolean {
        if (isStarted) return true
        val tm = telephonyManager ?: return false
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE not granted; PhoneStateListener registration skipped")
            return false
        }

        // Seed state from current TelephonyManager call state without firing spurious IDLE cleanup
        runCatching {
            @Suppress("DEPRECATION")
            val currentState = tm.callState
            seedInitialState(currentState)
        }

        val registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerApi31Callback(tm) || registerLegacyPhoneStateListener(tm)
        } else {
            registerLegacyPhoneStateListener(tm)
        }

        isStarted = registered
        return registered
    }

    fun stop() {
        if (!isStarted && api31Callback == null && legacyPhoneStateListener == null) return
        val tm = telephonyManager
        if (tm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                api31Callback?.let { cb ->
                    runCatching { tm.unregisterTelephonyCallback(cb) }
                }
            }
            @Suppress("DEPRECATION")
            legacyPhoneStateListener?.let { listener ->
                runCatching { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
        }
        api31Callback = null
        legacyPhoneStateListener = null
        isStarted = false
        resetStateTracking()
    }

    /**
     * Synchronizes tracker flags when a call is already known to be ringing or offhook
     * (for instance, when the service starts mid-call).
     */
    fun seedInitialState(state: Int) {
        lastCallState = state
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                hasSeenOffhook = true
                hasSeenRinging = false
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                hasSeenRinging = true
                hasSeenOffhook = false
            }
            else -> {
                hasSeenOffhook = false
                hasSeenRinging = false
            }
        }
    }

    /**
     * Core deterministic state transition handler invoked by both [PhoneStateListener]
     * and [TelephonyCallback.CallStateListener].
     */
    @VisibleForTesting
    internal fun handleCallStateChanged(newState: Int) {
        val previousState = lastCallState
        val wasOffhook = previousState == TelephonyManager.CALL_STATE_OFFHOOK || hasSeenOffhook
        val wasRingingOnly = !wasOffhook &&
            (previousState == TelephonyManager.CALL_STATE_RINGING || hasSeenRinging)

        lastCallState = newState
        onStateChanged(newState)

        when (newState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                hasSeenRinging = true
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                hasSeenOffhook = true
                hasSeenRinging = false
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                hasSeenOffhook = false
                hasSeenRinging = false
                if (wasOffhook) {
                    onIdleAfterOffhook()
                } else if (wasRingingOnly) {
                    onIdleAfterRinging()
                }
            }
        }
    }

    fun getLastObservedState(): Int = lastCallState

    fun isMonitoring(): Boolean = isStarted

    private fun resetStateTracking() {
        lastCallState = TelephonyManager.CALL_STATE_IDLE
        hasSeenOffhook = false
        hasSeenRinging = false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31Callback(tm: TelephonyManager): Boolean {
        return runCatching {
            val callback = CallStateTelephonyCallback { state ->
                handleCallStateChanged(state)
            }
            tm.registerTelephonyCallback(ContextCompat.getMainExecutor(appContext), callback)
            api31Callback = callback
            true
        }.onFailure { e ->
            Log.w(TAG, "registerTelephonyCallback failed, falling back to PhoneStateListener: ${e.message}")
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyPhoneStateListener(tm: TelephonyManager): Boolean {
        return runCatching {
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    super.onCallStateChanged(state, phoneNumber)
                    handleCallStateChanged(state)
                }
            }
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            legacyPhoneStateListener = listener
            true
        }.onFailure { e ->
            Log.w(TAG, "PhoneStateListener.LISTEN_CALL_STATE failed: ${e.message}")
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class CallStateTelephonyCallback(
        private val onCallState: (Int) -> Unit
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            onCallState(state)
        }
    }

    companion object {
        private const val TAG = "TelephonyCallStateMon"
    }
}
