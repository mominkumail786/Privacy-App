package com.example.privacyguard.data

import android.content.Context
import android.content.SharedPreferences
import com.example.privacyguard.logic.Sensitivity
import com.example.privacyguard.logic.Thresholds

/** Saves the user's settings in SharedPreferences (a small key/value file on the phone). */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // Version 2 introduced the "uncertain zone" and new, measured threshold presets.
        // Older installs still hold the old numbers (match 0.45 was too strict), so apply the current preset once.
        if (prefs.getInt("settingsVersion", 1) < SETTINGS_VERSION) {
            applySensitivity(sensitivity)
            prefs.edit().putInt("settingsVersion", SETTINGS_VERSION).apply()
        }
    }

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
                uncertainMargin = prefs.getFloat("uncertainMargin", d.uncertainMargin),
                alertOnUncertain = prefs.getBoolean("alertOnUncertain", d.alertOnUncertain),
                maxHeadAngle = prefs.getFloat("maxHeadAngle", d.maxHeadAngle),
                minFaceSize = prefs.getFloat("minFaceSize", d.minFaceSize),
                windowSize = prefs.getInt("windowSize", d.windowSize),
                requiredCount = prefs.getInt("requiredCount", d.requiredCount),
            )
        }
        set(t) {
            prefs.edit()
                .putFloat("matchThreshold", t.matchThreshold)
                .putFloat("uncertainMargin", t.uncertainMargin)
                .putBoolean("alertOnUncertain", t.alertOnUncertain)
                .putFloat("maxHeadAngle", t.maxHeadAngle)
                .putFloat("minFaceSize", t.minFaceSize)
                .putInt("windowSize", t.windowSize)
                .putInt("requiredCount", t.requiredCount)
                .apply()
        }

    /** Picks a preset. Keeps the user's "alert on uncertain faces" choice. */
    fun applySensitivity(level: Sensitivity) {
        val keepPolicy = prefs.getBoolean("alertOnUncertain", false)
        sensitivity = level
        thresholds = Thresholds.forSensitivity(level).copy(alertOnUncertain = keepPolicy)
    }

    companion object {
        private const val SETTINGS_VERSION = 2
    }
}
