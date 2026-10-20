package com.example.privacyguard.logic

import kotlin.math.max
import kotlin.math.min

/**
 * Checks the 5 enrollment pictures before the profile is saved.
 * Every picture must look like the average of all pictures. If one does not, the pictures were
 * probably not all of the same person, or one was bad (turned away, dark, blocked).
 */
object EnrollmentCheck {
    const val REQUIRED_SAMPLES = 5

    // Measured with the real model: sets of photos of one person had every picture at 0.56-0.60 or
    // more from the average, while sets that contained a different person often fell lower
    // (median 0.51). Only 2 people in the test data had 3+ photos, so this is a sanity check
    // that catches obvious mistakes, not a security feature.
    const val MIN_SAMPLE_SIMILARITY = 0.50f

    class Result(val ok: Boolean, val lowestSimilarity: Float, val profile: FloatArray?)

    fun verify(samples: List<FloatArray>): Result {
        if (samples.size < REQUIRED_SAMPLES) return Result(false, 0f, null)
        val profile = FaceMath.averageProfile(samples)
        val lowest = samples.minOf { FaceMath.cosineSimilarity(it, profile) }
        return Result(lowest >= MIN_SAMPLE_SIMILARITY, lowest, profile)
    }
}

enum class CalibrationQuality {
    /** Owner and stranger scores are clearly apart. */
    GOOD,
    /** They overlap or are very close: the model cannot separate you well on this phone. */
    OVERLAP,
    /** Only the owner was measured. The stranger side is an assumption. */
    OWNER_ONLY,
    NOT_ENOUGH_DATA,
}

class CalibrationResult(
    val quality: CalibrationQuality,
    val matchThreshold: Float?,
    val uncertainMargin: Float?,
    /** Low end (5th percentile) of the owner's scores. */
    val ownerLow: Float?,
    /** High end (95th percentile) of the other person's scores. */
    val otherHigh: Float?,
)

/**
 * Turns measured scores into thresholds, so the numbers come from data on THIS phone and not from a guess.
 *
 * ownerScores: similarity of the owner's live face to the stored profile.
 * otherScores: similarity of another person's face to the same profile (can be empty).
 */
object ThresholdCalibrator {
    const val MIN_SAMPLES = 10
    private const val MIN_GOOD_GAP = 0.10f
    private const val MAX_MARGIN = 0.10f
    private const val MIN_THRESHOLD = 0.25f
    private const val MAX_THRESHOLD = 0.70f

    // With only the owner measured, we stay a little under the owner's low end, but never below 0.30:
    // in the measurements, different people on clean photos reached about 0.30 (95th percentile).
    private const val OWNER_ONLY_FLOOR = 0.30f
    private const val OWNER_ONLY_SLACK = 0.05f

    /** Value at percentile [p] (0..100), linear between neighbours. */
    fun percentile(values: List<Float>, p: Float): Float {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val position = (p / 100f) * (sorted.size - 1)
        val low = position.toInt()
        val high = min(low + 1, sorted.size - 1)
        val fraction = position - low
        return sorted[low] + (sorted[high] - sorted[low]) * fraction
    }

    fun calibrate(ownerScores: List<Float>, otherScores: List<Float>): CalibrationResult {
        if (ownerScores.size < MIN_SAMPLES) {
            return CalibrationResult(CalibrationQuality.NOT_ENOUGH_DATA, null, null, null, null)
        }
        val ownerLow = percentile(ownerScores, 5f)

        if (otherScores.size < MIN_SAMPLES) {
            val match = max(OWNER_ONLY_FLOOR, min(MAX_THRESHOLD, ownerLow - OWNER_ONLY_SLACK))
            return CalibrationResult(CalibrationQuality.OWNER_ONLY, match, MAX_MARGIN, ownerLow, null)
        }

        val otherHigh = percentile(otherScores, 95f)
        val gap = ownerLow - otherHigh
        val middle = (ownerLow + otherHigh) / 2f
        val match = max(MIN_THRESHOLD, min(MAX_THRESHOLD, middle))
        return if (gap >= MIN_GOOD_GAP) {
            // The "not sure" zone is exactly the gap between the two groups (at most 0.10 wide).
            CalibrationResult(CalibrationQuality.GOOD, match, min(MAX_MARGIN, gap / 2f), ownerLow, otherHigh)
        } else {
            // Not separable: keep a wide "not sure" zone so the app does not make confident wrong claims.
            CalibrationResult(CalibrationQuality.OVERLAP, match, MAX_MARGIN, ownerLow, otherHigh)
        }
    }
}

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
