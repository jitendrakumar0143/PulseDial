package com.example.ui.incall

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.util.Log

/**
 * In-Call UI [SensorManager] proximity listener (`Sensor.TYPE_PROXIMITY`) that detects
 * ear proximity during active calls to automatically toggle BOTH:
 * 1. **Screen & UI Lock**: Acquires [PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK] to turn off
 *    the display and notifies [onUiLockChanged] to lock the In-Call UI against accidental touches
 *    when held near the ear; releases and unlocks immediately when moved away.
 * 2. **Speakerphone Status**: Automatically switches speakerphone OFF (earpiece) when held near
 *    the ear and toggles speakerphone ON when moved away from the ear during active calls,
 *    while staying hands-off whenever Bluetooth or a wired headset is active.
 */
class ProximityScreenLockController(
    context: Context,
    private val onUiLockChanged: (isLocked: Boolean) -> Unit,
    private val onSpeakerChanged: ((speakerOn: Boolean) -> Unit)? = null
) : SensorEventListener {

    private val appContext: Context = context.applicationContext
    private val sensorManager: SensorManager? =
        appContext.getSystemService(SensorManager::class.java)
    private val proximitySensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val powerManager: PowerManager? =
        appContext.getSystemService(PowerManager::class.java)

    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var currentCallState: Int = Call.STATE_NEW
    private var isSpeakerCurrentlyOn: Boolean = false
    private var isExternalHeadsetActive: Boolean = false
    private var lastIsNearEar: Boolean? = null

    var autoToggleSpeakerEnabled: Boolean = true

    var isListening: Boolean = false
        private set

    var isNearEarLocked: Boolean = false
        private set

    val isWakeLockHeld: Boolean
        get() = runCatching { proximityWakeLock?.isHeld == true }.getOrDefault(false)

    val hasProximitySensor: Boolean
        get() = proximitySensor != null

    /**
     * Evaluates whether proximity ear-detection should be armed based on the current
     * call lifecycle state and active audio route.
     *
     * Proximity detection is active during ongoing calls (active, dialing, connecting, holding —
     * NOT ringing or disconnected) whenever no external headset (Bluetooth or wired) is active.
     * It remains armed even while speakerphone is ON so bringing the phone back to the ear
     * automatically switches speakerphone OFF and locks the screen.
     */
    @Suppress("DEPRECATION")
    fun updateCallState(
        hasActiveCall: Boolean,
        callState: Int,
        audioRoute: Int,
        isSpeakerOn: Boolean
    ) {
        currentCallState = callState
        isSpeakerCurrentlyOn = isSpeakerOn || (audioRoute and CallAudioState.ROUTE_SPEAKER) != 0
        isExternalHeadsetActive =
            (audioRoute and (CallAudioState.ROUTE_BLUETOOTH or CallAudioState.ROUTE_WIRED_HEADSET)) != 0

        val isOngoingCall = hasActiveCall &&
            callState != Call.STATE_RINGING &&
            callState != Call.STATE_DISCONNECTED &&
            callState != Call.STATE_NEW

        if (isOngoingCall && !isExternalHeadsetActive) {
            startListening()
        } else {
            stopListening(waitForNoProximity = false)
        }
    }

    /**
     * Records a manual audio-route selection from the In-Call UI (e.g. Audio Output sheet)
     * so proximity does not immediately override the user's choice until ear proximity changes.
     */
    fun notifyManualAudioRouteSelection(isSpeakerOn: Boolean, isExternalHeadset: Boolean = false) {
        isSpeakerCurrentlyOn = isSpeakerOn
        isExternalHeadsetActive = isExternalHeadset
        if (isExternalHeadset) {
            stopListening(waitForNoProximity = false)
        }
    }

    /**
     * Registers the [SensorEventListener] on [Sensor.TYPE_PROXIMITY].
     */
    fun startListening() {
        if (isListening) return
        val manager = sensorManager ?: return
        val sensor = proximitySensor ?: return

        val registered = runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)

        if (registered) {
            isListening = true
            lastIsNearEar = null
            ensureWakeLockCreated()
        }
    }

    /**
     * Unregisters the proximity sensor listener, unlocks the UI, and releases the
     * [PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK].
     */
    fun stopListening(waitForNoProximity: Boolean = false) {
        if (isListening) {
            runCatching { sensorManager?.unregisterListener(this) }
            isListening = false
        }
        lastIsNearEar = null
        releaseProximityWakeLock(waitForNoProximity = waitForNoProximity)
        setUiLocked(false)
    }

    /**
     * Releases all sensor registrations and wake locks (call in Activity `onDestroy()`).
     */
    fun release() {
        stopListening(waitForNoProximity = false)
        proximityWakeLock = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_PROXIMITY) return
        val distanceCm = event.values.firstOrNull() ?: return
        val maxRangeCm = event.sensor.maximumRange.takeIf { it > 0f }
            ?: proximitySensor?.maximumRange?.takeIf { it > 0f }
            ?: DEFAULT_MAX_RANGE_CM
        handleProximityDistance(distanceCm, maxRangeCm)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Processes a proximity reading in centimeters. Exposed internally for deterministic
     * unit/Robolectric verification alongside live [SensorEvent] callbacks.
     */
    internal fun handleProximityDistance(
        distanceCm: Float,
        maxRangeCm: Float = proximitySensor?.maximumRange?.takeIf { it > 0f } ?: DEFAULT_MAX_RANGE_CM
    ) {
        if (!isListening || isExternalHeadsetActive) return
        val isNearEar = isNearEar(distanceCm, maxRangeCm)
        val proximityChanged = (lastIsNearEar != isNearEar)
        lastIsNearEar = isNearEar

        if (isNearEar) {
            acquireProximityWakeLock()
            setUiLocked(true)
            if (autoToggleSpeakerEnabled &&
                (proximityChanged || isSpeakerCurrentlyOn) &&
                (currentCallState == Call.STATE_ACTIVE || isSpeakerCurrentlyOn)
            ) {
                isSpeakerCurrentlyOn = false
                onSpeakerChanged?.invoke(false)
            }
        } else {
            releaseProximityWakeLock(waitForNoProximity = false)
            setUiLocked(false)
            if (autoToggleSpeakerEnabled &&
                proximityChanged &&
                currentCallState == Call.STATE_ACTIVE
            ) {
                isSpeakerCurrentlyOn = true
                onSpeakerChanged?.invoke(true)
            }
        }
    }

    private fun setUiLocked(locked: Boolean) {
        if (isNearEarLocked == locked) return
        isNearEarLocked = locked
        onUiLockChanged(locked)
    }

    @SuppressLint("InvalidWakeLockTag")
    private fun ensureWakeLockCreated() {
        if (proximityWakeLock != null) return
        val pm = powerManager ?: return
        val supported = runCatching {
            pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
        }.getOrDefault(false)
        if (!supported) return

        proximityWakeLock = runCatching {
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
            }
        }.onFailure { e ->
            Log.w(TAG, "Unable to create PROXIMITY_SCREEN_OFF_WAKE_LOCK: ${e.message}")
        }.getOrNull()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireProximityWakeLock() {
        ensureWakeLockCreated()
        val wl = proximityWakeLock ?: return
        runCatching {
            if (!wl.isHeld) {
                wl.acquire(MAX_WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to acquire proximity wake lock: ${e.message}")
        }
    }

    private fun releaseProximityWakeLock(waitForNoProximity: Boolean) {
        val wl = proximityWakeLock ?: return
        runCatching {
            if (wl.isHeld) {
                if (waitForNoProximity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    wl.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                } else {
                    wl.release()
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to release proximity wake lock: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ProximityScreenLock"
        private const val WAKE_LOCK_TAG = "PulseDial:ProximityScreenOffLock"
        internal const val NEAR_THRESHOLD_CM = 5.0f
        private const val DEFAULT_MAX_RANGE_CM = 5.0f
        private const val MAX_WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 60L * 1000L // 2 hours safety cap

        /**
         * Determines whether a proximity sensor reading indicates the handset is held against
         * the user's ear. Uses `minOf(maxRangeCm, 5.0f)` so both continuous (cm) sensors and
         * binary (0 / maxRange) sensors evaluate accurately.
         */
        fun isNearEar(distanceCm: Float, maxRangeCm: Float): Boolean {
            if (distanceCm < 0f || distanceCm.isNaN()) return false
            val threshold = if (maxRangeCm > 0f) {
                minOf(maxRangeCm, NEAR_THRESHOLD_CM)
            } else {
                NEAR_THRESHOLD_CM
            }
            return distanceCm < threshold
        }
    }
}
