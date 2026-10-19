package com.example.privacyguard.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.privacyguard.R

/** The real state of the protection. The screen shows exactly this, nothing more optimistic. */
enum class GuardStatus(val textRes: Int) {
    ACTIVE(R.string.status_active),
    PAUSED_CAMERA_BUSY(R.string.status_paused_camera),
    PAUSED_SCREEN_OFF(R.string.status_paused_screen),
    PAUSED_LOW_BATTERY(R.string.status_paused_battery),
    STOPPED(R.string.status_stopped),
    PERMISSION_MISSING(R.string.status_permission),
    SETUP_NEEDED(R.string.status_setup),
}

/**
 * Shared between the service and the screens (both run in the same process).
 * If Android kills the process, this resets to STOPPED, which is the truth.
 */
object GuardState {
    var status by mutableStateOf(GuardStatus.STOPPED)

    /** Short numbers-only text about the last analysed frame, for tuning. Never contains pictures. */
    var debugLine by mutableStateOf("")
}
