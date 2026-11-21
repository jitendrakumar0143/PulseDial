package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import android.telecom.Call
import android.telephony.TelephonyManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.recording.CallRecorderForegroundService
import com.example.telecom.CallManager
import com.example.telecom.CallUiState
import com.example.telecom.CustomInCallService
import com.example.telecom.TelephonyCallStateMonitor
import com.example.ui.incall.InCallActivity
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelephonyCallStateMonitorTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CallManager.publishStateForTesting(CallUiState())
        CustomInCallService.cancelCallNotification(app)
    }

    @After
    fun tearDown() {
        CallManager.publishStateForTesting(CallUiState())
        CustomInCallService.cancelCallNotification(app)
    }

    @Test
    fun `detects IDLE after OFFHOOK accurately and ignores spurious initial IDLE`() {
        var offhookIdleCount = 0
        var ringingIdleCount = 0

        val monitor = TelephonyCallStateMonitor(
            context = app,
            onIdleAfterOffhook = { offhookIdleCount++ },
            onIdleAfterRinging = { ringingIdleCount++ }
        )

        // Initial IDLE should never falsely terminate a call
        monitor.handleCallStateChanged(TelephonyManager.CALL_STATE_IDLE)
        assertEquals(0, offhookIdleCount)
        assertEquals(0, ringingIdleCount)

        // Incoming ring -> Answered (OFFHOOK) -> Call ended (IDLE)
        monitor.handleCallStateChanged(TelephonyManager.CALL_STATE_RINGING)
        monitor.handleCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK)
        assertEquals(0, offhookIdleCount)

        monitor.handleCallStateChanged(TelephonyManager.CALL_STATE_IDLE)
        assertEquals(1, offhookIdleCount)
        assertEquals(0, ringingIdleCount)

        // Duplicate IDLE after cleanup must not fire again
        monitor.handleCallStateChanged(TelephonyManager.CALL_STATE_IDLE)
        assertEquals(1, offhookIdleCount)
    }

    @Test
    fun `detects IDLE after RINGING without triggering OFFHOOK cleanup`() {
        var offhookIdleCount = 0
        var ringingIdleCount = 0

        val monitor = TelephonyCallStateMonitor(
            context = app,
            onIdleAfterOffhook = { offhookIdleCount++ },
            onIdleAfterRinging = { ringingIdleCount++ }
        )

        monitor.handleCallStateChanged(TelephonyManager.CALL_STATE_RINGING)
        monitor.handleCallStateChanged(TelephonyManager.CALL_STATE_IDLE)

        assertEquals(0, offhookIdleCount)
        assertEquals(1, ringingIdleCount)
    }

    @Test
    @Config(sdk = [30])
    fun `registers legacy PhoneStateListener on API 30 when READ_PHONE_STATE is granted`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        var offhookIdleCount = 0

        val monitor = TelephonyCallStateMonitor(
            context = app,
            onIdleAfterOffhook = { offhookIdleCount++ }
        )

        assertTrue(monitor.start())
        assertTrue(monitor.isMonitoring())

        val tm = app.getSystemService(TelephonyManager::class.java)
        val shadowTm = Shadows.shadowOf(tm)
        shadowTm.setCallState(TelephonyManager.CALL_STATE_OFFHOOK, "+919876543210")
        shadowTm.setCallState(TelephonyManager.CALL_STATE_IDLE, "")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, offhookIdleCount)

        monitor.stop()
        assertFalse(monitor.isMonitoring())
    }

    @Test
    fun `CustomInCallService OFFHOOK to IDLE cleans up notification, CallManager, and finishes InCallActivity`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        val serviceController = Robolectric.buildService(CustomInCallService::class.java).create()
        val service = serviceController.get()
        val monitor = service.telephonyCallStateMonitor
        assertNotNull(monitor)

        // Simulate active call state + ongoing notification + visible InCallActivity
        CallManager.publishStateForTesting(
            CallUiState(
                hasActiveCall = true,
                callerName = "Aarav Sharma",
                callerNumber = "+919876543210",
                callState = Call.STATE_ACTIVE,
                connectTimeMillis = System.currentTimeMillis() - 5_000L
            )
        )
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.notify(
            CustomInCallService.CALL_NOTIFICATION_ID,
            Notification.Builder(app, PulseDialApp.CHANNEL_ONGOING_CALLS)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle("Aarav Sharma")
                .setOngoing(true)
                .build()
        )
        assertEquals(1, Shadows.shadowOf(nm).allNotifications.size)

        val activityController = Robolectric.buildActivity(InCallActivity::class.java).setup()
        val activity = activityController.get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(activity.isFinishing)

        // Transition PhoneStateListener / TelephonyCallback: OFFHOOK -> IDLE
        monitor!!.handleCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK)
        monitor.handleCallStateChanged(TelephonyManager.CALL_STATE_IDLE)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // 1. CallManager state is cleared
        assertFalse(CallManager.uiState.value.hasActiveCall)
        assertEquals(Call.STATE_DISCONNECTED, CallManager.uiState.value.callState)

        // 2. Ongoing call notification is removed immediately
        assertEquals(0, Shadows.shadowOf(nm).allNotifications.size)

        // 3. InCallActivity enters Call Ended state and finishes after dismissal hold
        val statusText = activity.findViewById<TextView>(R.id.tv_incall_status).text.toString()
        assertEquals(app.getString(R.string.incall_call_ended), statusText)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200L))
        assertTrue(activity.isFinishing)

        serviceController.destroy()
        assertFalse(monitor.isMonitoring())
    }

    @Test
    fun `CallRecorderForegroundService cleans up active call UI on OFFHOOK to IDLE`() {
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO
        )
        CallManager.publishStateForTesting(
            CallUiState(
                hasActiveCall = true,
                callerName = "Priya Nair",
                callerNumber = "+919988776655",
                callState = Call.STATE_ACTIVE
            )
        )

        val controller = Robolectric.buildService(CallRecorderForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)

        val monitor = service.telephonyCallStateMonitor
        assertNotNull(monitor)

        monitor!!.handleCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK)
        monitor.handleCallStateChanged(TelephonyManager.CALL_STATE_IDLE)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(CallManager.uiState.value.hasActiveCall)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)

        controller.destroy()
        assertFalse(monitor.isMonitoring())
    }
}
