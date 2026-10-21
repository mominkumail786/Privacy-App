package com.example.privacyguard.logic

import kotlin.math.abs

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
