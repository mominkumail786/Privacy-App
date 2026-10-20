package com.example.privacyguard.logic

import kotlin.math.abs

/** The five head positions we capture during enrollment. */
enum class EnrollStep { FRONT, LEFT, RIGHT, UP, DOWN }

/**
 * Keeps track of which enrollment step we are on and checks the head angle for it.
 *
 * Why the angle signs are not hard-coded: the sign of ML Kit's headEulerAngleY/X depends on the
 * camera mirroring, and I could not test it on a real phone. So LEFT accepts a turn to either side,
 * and RIGHT then requires the OPPOSITE side. Same for UP / DOWN. Both sides always get captured,
 * even if the on-screen words "left" and "right" are swapped on your phone.
 */
class EnrollmentSession {
    val steps: List<EnrollStep> = EnrollStep.entries
    var index = 0
        private set

    private var turnSign = 0 // sign of yaw seen at the LEFT step
    private var tiltSign = 0 // sign of pitch seen at the UP step

    /** Starts again from the first step (used when the 5 pictures turned out to be inconsistent). */
    fun reset() {
        index = 0
        turnSign = 0
        tiltSign = 0
    }

    val isFinished get() = index >= steps.size
    val currentStep get() = steps[index]

    /** Is the head in the right position for the current step? */
    fun poseMatches(yaw: Float, pitch: Float): Boolean = when (currentStep) {
        EnrollStep.FRONT -> abs(yaw) < FRONT_LIMIT && abs(pitch) < FRONT_LIMIT
        EnrollStep.LEFT -> abs(yaw) >= TURN_MIN && abs(pitch) < OTHER_AXIS_MAX
        EnrollStep.RIGHT -> turnSign != 0 && yaw * turnSign <= -TURN_MIN && abs(pitch) < OTHER_AXIS_MAX
        EnrollStep.UP -> abs(pitch) >= TILT_MIN && abs(yaw) < OTHER_AXIS_MAX
        EnrollStep.DOWN -> tiltSign != 0 && pitch * tiltSign <= -TILT_MIN && abs(yaw) < OTHER_AXIS_MAX
    }

    /** Call after a good picture was taken. Remembers the signs and moves to the next step. */
    fun onCaptured(yaw: Float, pitch: Float) {
        if (currentStep == EnrollStep.LEFT) turnSign = if (yaw > 0) 1 else -1
        if (currentStep == EnrollStep.UP) tiltSign = if (pitch > 0) 1 else -1
        index++
    }

    companion object {
        const val FRONT_LIMIT = 12f
        const val TURN_MIN = 18f
        const val TILT_MIN = 12f
        const val OTHER_AXIS_MAX = 20f
    }
}

/** Simple picture-quality checks on a small grey (0..255) face crop. */
object ImageQuality {
    const val MIN_BRIGHTNESS = 50f
    const val MAX_BRIGHTNESS = 210f
    // Checked on sample face crops (112x112 grey): sharp faces scored 4.6-10, blurred ones 1.1-3.0.
    const val MIN_SHARPNESS = 3.5f

    /** Average grey value, 0 (black) .. 255 (white). */
    fun brightness(gray: IntArray): Float {
        if (gray.isEmpty()) return 0f
        var sum = 0L
        for (g in gray) sum += g
        return sum.toFloat() / gray.size
    }

    /**
     * Blur check: average difference between neighbouring pixels.
     * A sharp face has many edges (high value). A blurry one is smooth (low value).
     */
    fun sharpness(gray: IntArray, width: Int, height: Int): Float {
        if (width < 2 || height < 2) return 0f
        var sum = 0L
        var count = 0
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val p = gray[y * width + x]
                sum += abs(p - gray[y * width + x + 1]) + abs(p - gray[(y + 1) * width + x])
                count += 2
            }
        }
        return sum.toFloat() / count
    }

    /** Returns null if the picture is fine, otherwise a short reason. */
    fun problem(gray: IntArray, width: Int, height: Int): QualityProblem? {
        val b = brightness(gray)
        if (b < MIN_BRIGHTNESS) return QualityProblem.TOO_DARK
        if (b > MAX_BRIGHTNESS) return QualityProblem.TOO_BRIGHT
        if (sharpness(gray, width, height) < MIN_SHARPNESS) return QualityProblem.BLURRY
        return null
    }
}

enum class QualityProblem { TOO_DARK, TOO_BRIGHT, BLURRY }
