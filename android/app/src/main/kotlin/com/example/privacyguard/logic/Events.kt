package com.example.privacyguard.logic

/**
 * Counts analysis failures in a row. If recognition keeps failing, the UI must say so
 * ("Protection degraded") instead of claiming "Active".
 */
class FailureMonitor(private val limit: Int = 5) {
    private var inARow = 0

    var isDegraded = false
        private set

    /** Returns true when this success ends a degraded period. */
    fun success(): Boolean {
        inARow = 0
        val wasDegraded = isDegraded
        isDegraded = false
        return wasDegraded
    }

    /** Returns true when this failure makes the state degraded. */
    fun failure(): Boolean {
        inARow++
        if (!isDegraded && inARow >= limit) {
            isDegraded = true
            return true
        }
        return false
    }
}

enum class EventType {
    PROTECTION_STARTED,
    PROTECTION_STOPPED,
    OTHER_PERSON_ALERT,
    UNCERTAIN_ALERT,
    CAMERA_BUSY,
    CAMERA_RESUMED,
    ANALYSIS_FAILING,
    ANALYSIS_RECOVERED,
    PROFILE_INVALID,
    /** Enrollment finished; the stored number is the time it took, in seconds. */
    ENROLLMENT_DONE,
}

/** One line of the event log. Contains no pictures and no face data: only time, kind and a similarity number. */
data class GuardEvent(val timeMillis: Long, val type: EventType, val score: Float?)

object EventLogFormat {
    fun encode(e: GuardEvent): String = "${e.timeMillis}|${e.type.name}|${e.score ?: ""}"

    fun decode(line: String): GuardEvent? {
        val parts = line.split('|')
        if (parts.size != 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        val type = EventType.entries.firstOrNull { it.name == parts[1] } ?: return null
        val score = if (parts[2].isEmpty()) null else parts[2].toFloatOrNull() ?: return null
        return GuardEvent(time, type, score)
    }

    /** Keeps only the newest [max] lines. */
    fun trim(lines: List<String>, max: Int): List<String> = if (lines.size <= max) lines else lines.takeLast(max)
}
