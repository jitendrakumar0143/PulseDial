package com.example.ui.admin

/**
 * Tracks consecutive taps on the Account bottom navigation tab to reveal the hidden Admin Panel
 * after [requiredTaps] taps (12 by default) within [tapTimeoutMs] per tap interval.
 */
class HiddenAdminTapDetector(
    private val requiredTaps: Int = DEFAULT_REQUIRED_TAPS,
    private val tapTimeoutMs: Long = DEFAULT_TAP_TIMEOUT_MS
) {
    private var tapCount: Int = 0
    private var lastTapMs: Long = 0L

    fun onAccountTabTapped(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastTapMs > 0L && (nowMs - lastTapMs) > tapTimeoutMs) {
            tapCount = 0
        }
        tapCount += 1
        lastTapMs = nowMs
        if (tapCount >= requiredTaps) {
            tapCount = 0
            lastTapMs = 0L
            return true
        }
        return false
    }

    fun onOtherTabSelected() {
        tapCount = 0
        lastTapMs = 0L
    }

    fun currentTapCount(): Int = tapCount

    companion object {
        const val DEFAULT_REQUIRED_TAPS = 12
        const val DEFAULT_TAP_TIMEOUT_MS = 4_000L
    }
}
