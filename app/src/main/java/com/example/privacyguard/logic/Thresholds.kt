package com.example.privacyguard.logic

/** Three ready-made sensitivity levels. The Settings screen lets you pick one. */
enum class Sensitivity { LOW, MEDIUM, HIGH }

/**
 * All numbers that decide "is another person looking at my screen?".
 * You can tune them in Settings (Advanced), calibrate them on your own phone (Settings > Calibrate),
 * or change the presets below.
 *
 * A face gets one of three verdicts from its similarity to your profile:
 *   similarity >= matchThreshold                      -> OWNER
 *   strangerBelow <= similarity < matchThreshold      -> UNCERTAIN (never claimed as owner or stranger)
 *   similarity < strangerBelow                        -> STRANGER
 */
data class Thresholds(
    /** Cosine similarity (-1..1). A face at or above this is treated as "me". */
    val matchThreshold: Float,
    /** Width of the "not sure" zone below [matchThreshold]. A confident stranger is below matchThreshold - uncertainMargin. */
    val uncertainMargin: Float,
    /** Policy for faces in the "not sure" zone: false = no alert (just show "not sure"), true = alert as well. */
    val alertOnUncertain: Boolean,
    /** Head turn limit in degrees. If a face is turned (left/right or up/down) more than this, it is NOT facing the screen. */
    val maxHeadAngle: Float,
    /** Smallest face we care about: face width divided by picture width (0.10 = 10%). Smaller faces are far away. */
    val minFaceSize: Float,
    /** Temporal filter: look at the last [windowSize] analysed frames ... */
    val windowSize: Int,
    /** ... and alert only if at least [requiredCount] of them said "other person looking". */
    val requiredCount: Int,
) {
    /** Below this similarity a face is a confident stranger. */
    val strangerBelow: Float get() = matchThreshold - uncertainMargin

    companion object {
        // Where the numbers come from (measured with the real MobileFaceNet model on 25 public photos,
        // 38 same-person pairs and 262 different-person pairs, see README "How the thresholds were chosen"):
        //   clean photos : best single threshold 0.35, 5% of same-person pairs fall below 0.38
        //   poor photos  : same-person and different-person scores overlap a lot (0.5 and above is needed
        //                  to keep strangers out, but then 20% of owner pairs are lost)
        // So the presets keep an "uncertain" zone, and the calibration screen measures YOUR phone.
        // Higher matchThreshold / wider head angle / smaller window = more alerts.
        fun forSensitivity(s: Sensitivity): Thresholds = when (s) {
            Sensitivity.LOW -> Thresholds(0.35f, 0.10f, false, 20f, 0.20f, 5, 4)
            Sensitivity.MEDIUM -> Thresholds(0.40f, 0.10f, false, 30f, 0.12f, 5, 3)
            Sensitivity.HIGH -> Thresholds(0.50f, 0.10f, false, 40f, 0.08f, 4, 2)
        }
    }
}
