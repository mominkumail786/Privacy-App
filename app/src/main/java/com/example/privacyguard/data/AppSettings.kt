package com.example.privacyguard.data

import android.content.Context
import android.content.SharedPreferences
import com.example.privacyguard.logic.Sensitivity
import com.example.privacyguard.logic.Thresholds

/** Saves the user's settings in SharedPreferences (a small key/value file on the phone). */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(value) = prefs.edit().putBoolean("onboardingDone", value).apply()

    /** True after the user tapped Start. Used after a restart to show "Tap to restart". Cleared by Stop. */
    var protectionWanted: Boolean
        get() = prefs.getBoolean("protectionWanted", false)
        set(value) = prefs.edit().putBoolean("protectionWanted", value).apply()

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(value) = prefs.edit().putBoolean("vibration", value).apply()

    var cooldownSeconds: Int
        get() = prefs.getInt("cooldownSeconds", 30)
        set(value) = prefs.edit().putInt("cooldownSeconds", value).apply()

    var sensitivity: Sensitivity
        get() = runCatching { Sensitivity.valueOf(prefs.getString("sensitivity", null) ?: "") }
            .getOrDefault(Sensitivity.MEDIUM)
        set(value) = prefs.edit().putString("sensitivity", value.name).apply()

    /** The numbers actually used. Picking a sensitivity level overwrites all of them (see [applySensitivity]). */
    var thresholds: Thresholds
        get() {
            val d = Thresholds.forSensitivity(Sensitivity.MEDIUM)
            return Thresholds(
                matchThreshold = prefs.getFloat("matchThreshold", d.matchThreshold),
                maxHeadAngle = prefs.getFloat("maxHeadAngle", d.maxHeadAngle),
                minFaceSize = prefs.getFloat("minFaceSize", d.minFaceSize),
                windowSize = prefs.getInt("windowSize", d.windowSize),
                requiredCount = prefs.getInt("requiredCount", d.requiredCount),
            )
        }
        set(t) {
            prefs.edit()
                .putFloat("matchThreshold", t.matchThreshold)
                .putFloat("maxHeadAngle", t.maxHeadAngle)
                .putFloat("minFaceSize", t.minFaceSize)
                .putInt("windowSize", t.windowSize)
                .putInt("requiredCount", t.requiredCount)
                .apply()
        }

    fun applySensitivity(level: Sensitivity) {
        sensitivity = level
        thresholds = Thresholds.forSensitivity(level)
    }
}
