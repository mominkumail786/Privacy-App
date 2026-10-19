package com.example.privacyguard.logic

/**
 * Avoids false alarms: a single odd frame must not trigger an alert.
 * It remembers the last [windowSize] answers and says "yes" only if at least [requiredCount] were true.
 * Example: window 5, required 3 = "3 out of the last 5 frames".
 */
class TemporalFilter(private val windowSize: Int, private val requiredCount: Int) {
    private val recent = ArrayDeque<Boolean>()

    /** Adds the newest frame result. Returns true when the alarm condition is confirmed. */
    fun add(value: Boolean): Boolean {
        recent.addLast(value)
        while (recent.size > windowSize) recent.removeFirst()
        return recent.count { it } >= requiredCount
    }

    fun reset() = recent.clear()
}

/** Makes sure we do not alert again and again. Time is passed in, so it is easy to test. */
class AlertCooldown(private val cooldownMillis: () -> Long) {
    private var lastAlertAt: Long? = null

    /** Returns true (and starts the cooldown) if an alert may be shown now. */
    fun tryAlert(nowMillis: Long): Boolean {
        val last = lastAlertAt
        if (last != null && nowMillis - last < cooldownMillis()) return false
        lastAlertAt = nowMillis
        return true
    }
}
