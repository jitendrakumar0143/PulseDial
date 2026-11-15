package com.example.recording

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight AccessibilityService paired with CustomInCallService so Android 10–16+
 * permits background VOICE_RECOGNITION / VOICE_COMMUNICATION call audio capture cleanly
 * without reading or transmitting any screen content.
 */
class CallRecordingAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isRunning.value = true
        Log.i(TAG, "CallRecordingAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately no-op: active service binding enables OS audio capture during calls.
    }

    override fun onInterrupt() {
        Log.w(TAG, "CallRecordingAccessibilityService interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _isRunning.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _isRunning.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallRecAccessibility"
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun isServiceEnabled(context: Context): Boolean {
            if (_isRunning.value) return true
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val expectedComponent = ComponentName(
                context.packageName,
                CallRecordingAccessibilityService::class.java.name
            )
            if (am != null) {
                val enabledServices = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                val foundInManager = enabledServices.any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == expectedComponent.packageName &&
                        serviceInfo.name == expectedComponent.className
                }
                if (foundInManager) return true
            }
            val enabledSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabledSetting.contains(expectedComponent.flattenToString()) ||
                enabledSetting.contains(expectedComponent.flattenToShortString())
        }
    }
}
