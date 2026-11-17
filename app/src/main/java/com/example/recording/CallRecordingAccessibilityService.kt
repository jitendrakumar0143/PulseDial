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
        VoipCallRecorderManager.init(applicationContext)
        Log.i(TAG, "CallRecordingAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if ((pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") && consumeValidWhatsappAutoSendWindow()) {
            val root = runCatching { rootInActiveWindow }.getOrNull()
            val sendNodes = root?.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            val sendButton = sendNodes?.firstOrNull { it.isClickable && it.isEnabled }
            if (sendButton != null) {
                sendButton.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                clearPendingWhatsappAutoSend()
            }
        }

        val voipSource = CallRecordingSource.fromPackageName(pkg) ?: return
        val eventType = event?.eventType ?: return
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        ) {
            val combinedText = buildString {
                event.contentDescription?.let { append(it).append(' ') }
                event.text?.forEach { append(it).append(' ') }
                event.className?.let { append(it) }
            }
            val lower = combinedText.lowercase()
            val isCallHint = eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ||
                lower.contains("call") ||
                lower.contains("ringing") ||
                lower.contains("calling") ||
                lower.contains("ongoing") ||
                lower.contains("meet") ||
                lower.contains("voip") ||
                lower.contains("video")
            VoipCallRecorderManager.onVoipAppEvent(
                context = applicationContext,
                source = voipSource,
                isCallHint = isCallHint,
                labelHint = voipSource.filePrefix
            )
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "CallRecordingAccessibilityService interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _isRunning.value = false
        VoipCallRecorderManager.stopRecordingIfNeeded()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _isRunning.value = false
        VoipCallRecorderManager.stopRecordingIfNeeded()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallRecAccessibility"
        private const val AUTO_SEND_WINDOW_MS = 12_000L
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        @Volatile
        private var pendingWhatsappAutoSendUntilMs: Long = 0L

        fun notifyPendingWhatsappAutoSend(nowMs: Long = System.currentTimeMillis()) {
            pendingWhatsappAutoSendUntilMs = nowMs + AUTO_SEND_WINDOW_MS
        }

        fun clearPendingWhatsappAutoSend() {
            pendingWhatsappAutoSendUntilMs = 0L
        }

        private fun consumeValidWhatsappAutoSendWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
            val until = pendingWhatsappAutoSendUntilMs
            if (until <= 0L) return false
            if (nowMs > until) {
                pendingWhatsappAutoSendUntilMs = 0L
                return false
            }
            return true
        }

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
