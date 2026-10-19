package com.example.privacyguard.logic

/** Three ready-made sensitivity levels. The Settings screen lets you pick one. */
enum class Sensitivity { LOW, MEDIUM, HIGH }

/**
 * All numbers that decide "is another person looking at my screen?".
 * You can tune them in Settings (Advanced) or change the presets below.
 */
data class Thresholds(
    /** Cosine similarity (-1..1). A face at or above this is treated as "me". */
    val matchThreshold: Float,
    /** Head turn limit in degrees. If a face is turned (left/right or up/down) more than this, it is NOT facing the screen. */
    val maxHeadAngle: Float,
    /** Smallest face we care about: face width divided by picture width (0.10 = 10%). Smaller faces are far away. */
    val minFaceSize: Float,
    /** Temporal filter: look at the last [windowSize] analysed frames ... */
    val windowSize: Int,
    /** ... and alert only if at least [requiredCount] of them said "other person looking". */
    val requiredCount: Int,
) {
    companion object {
        // Higher matchThreshold = stricter about who counts as "me" = more alerts.
        // Wider head angle / bigger window = more alerts. These are starting values: tune them on your phone.
        fun forSensitivity(s: Sensitivity): Thresholds = when (s) {
            Sensitivity.LOW -> Thresholds(0.35f, 20f, 0.20f, 5, 4)
            Sensitivity.MEDIUM -> Thresholds(0.45f, 30f, 0.12f, 5, 3)
            Sensitivity.HIGH -> Thresholds(0.55f, 40f, 0.08f, 4, 2)
        }
    }
}
