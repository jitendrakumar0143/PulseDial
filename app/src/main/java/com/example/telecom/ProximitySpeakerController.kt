package com.example.telecom

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.telecom.CallAudioState

/**
 * Listens to [Sensor.TYPE_PROXIMITY] during an active multi-party / conference call and
 * automatically routes audio to the earpiece (speaker OFF) when near the user's ear or to
 * the speakerphone (speaker ON) when moved away from the ear.
 */
class ProximitySpeakerController(
    context: Context,
    private val onSpeakerDesiredChanged: (Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)
    private val proximitySensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var isRegistered: Boolean = false
    private var isMultiPartyCallActive: Boolean = false
    private var lastIsNearEar: Boolean? = null
    private var isExternalHeadsetActive: Boolean = false

    val isListening: Boolean
        get() = isRegistered

    fun onCallUiStateChanged(state: CallUiState) {
        val shouldListen = CallManager.isActiveMultiPartyCall(state)
        isMultiPartyCallActive = shouldListen

        if (shouldListen && !isRegistered) {
            register()
        } else if (!shouldListen && isRegistered) {
            unregister()
        }
    }

    @Suppress("DEPRECATION")
    fun onAudioRouteChanged(audioRouteMask: Int) {
        isExternalHeadsetActive =
            (audioRouteMask and (CallAudioState.ROUTE_BLUETOOTH or CallAudioState.ROUTE_WIRED_HEADSET)) != 0
    }

    fun release() {
        isMultiPartyCallActive = false
        unregister()
    }

    private fun register() {
        val manager = sensorManager ?: return
        val sensor = proximitySensor ?: return
        lastIsNearEar = null
        val registered = runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
        isRegistered = registered
    }

    private fun unregister() {
        if (isRegistered) {
            runCatching {
                sensorManager?.unregisterListener(this)
            }
        }
        isRegistered = false
        lastIsNearEar = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor?.type != Sensor.TYPE_PROXIMITY) return
        if (event.values.isEmpty() || !isMultiPartyCallActive) return

        val maxRange = proximitySensor?.maximumRange ?: DEFAULT_PROXIMITY_THRESHOLD_CM
        handleProximityDistance(distanceCm = event.values[0], maxRangeCm = maxRange)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    internal fun handleProximityDistance(distanceCm: Float, maxRangeCm: Float) {
        if (!isMultiPartyCallActive) return
        val isNear = isNearEar(distanceCm, maxRangeCm)
        if (isNear == lastIsNearEar) return
        lastIsNearEar = isNear

        if (isExternalHeadsetActive) return
        onSpeakerDesiredChanged(!isNear)
    }

    companion object {
        private const val DEFAULT_PROXIMITY_THRESHOLD_CM = 5.0f

        /**
         * Determines whether a proximity sensor reading indicates the handset is held against
         * the user's ear. Uses `minOf(maxRangeCm, 5.0f)` so both continuous (cm) sensors and
         * binary (0 / maxRange) sensors evaluate accurately.
         */
        fun isNearEar(distanceCm: Float, maxRangeCm: Float): Boolean {
            if (distanceCm < 0f || distanceCm.isNaN()) return false
            val threshold = if (maxRangeCm > 0f) {
                minOf(maxRangeCm, DEFAULT_PROXIMITY_THRESHOLD_CM)
            } else {
                DEFAULT_PROXIMITY_THRESHOLD_CM
            }
            return distanceCm < threshold
        }
    }
}
