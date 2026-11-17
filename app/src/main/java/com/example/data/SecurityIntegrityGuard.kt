package com.example.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import java.io.File

data class IntegrityReport(
    val isClean: Boolean,
    val reasons: List<String> = emptyList()
)

/**
 * Runtime anti-tamper & anti-hack verifier executed off the Main Thread.
 * Detects preference XML tampering, significant device clock rollback, debugger injection on
 * release builds, and common hooking frameworks (Frida / Xposed / LSPosed / Substrate).
 */
object SecurityIntegrityGuard {

    private const val CLOCK_ROLLBACK_TOLERANCE_MS = 10L * 60L * 1000L

    fun isDebuggerAttached(context: Context): Boolean {
        val isDebuggableApp = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggableApp) return false
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    fun isClockTampered(
        prefs: DialerPreferences,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val lastSeen = prefs.lastSeenTimeMs
        if (lastSeen <= 0L) return false
        val adjustedNow = nowMs + prefs.serverClockOffsetMs
        return adjustedNow < (lastSeen - CLOCK_ROLLBACK_TOLERANCE_MS)
    }

    fun detectHookFramework(): Boolean {
        val knownClasses = listOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "io.github.lsposed.lspd.core.Main",
            "com.saurik.substrate.MS"
        )
        knownClasses.forEach { className ->
            val found = runCatching { Class.forName(className, false, SecurityIntegrityGuard::class.java.classLoader) }.isSuccess
            if (found) return true
        }

        val suspiciousFiles = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server",
            "/sbin/.magisk",
            "/system/xposed.prop"
        )
        suspiciousFiles.forEach { path ->
            if (runCatching { File(path).exists() }.getOrDefault(false)) {
                return true
            }
        }

        val procMaps = runCatching { File("/proc/self/maps").takeIf { it.canRead() }?.readText() }.getOrNull()
        if (!procMaps.isNullOrBlank()) {
            val lower = procMaps.lowercase()
            if (lower.contains("frida") || lower.contains("xposed") || lower.contains("lsposed")) {
                return true
            }
        }
        return false
    }

    fun verifyRuntimeIntegrity(
        context: Context,
        prefs: DialerPreferences = DialerPreferences(context),
        nowMs: Long = System.currentTimeMillis()
    ): IntegrityReport {
        val reasons = mutableListOf<String>()

        if (!prefs.verifyIntegrityOrReset()) {
            reasons.add("pref_hmac_mismatch")
        }
        if (isClockTampered(prefs, nowMs)) {
            reasons.add("clock_rollback")
        }
        if (isDebuggerAttached(context)) {
            reasons.add("release_debugger_attached")
        }
        if (detectHookFramework()) {
            reasons.add("hook_framework_detected")
        }

        if (reasons.isNotEmpty()) {
            prefs.stripForgedPrivilegesOnTamper()
        }
        return IntegrityReport(
            isClean = reasons.isEmpty(),
            reasons = reasons
        )
    }
}
