package com.example.privacyguard.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.privacyguard.R
import com.example.privacyguard.logic.FrameState

/** The real state of the protection. The screen shows exactly this, nothing more optimistic. */
enum class GuardStatus(val textRes: Int) {
    ACTIVE(R.string.status_active),
    PAUSED_CAMERA_BUSY(R.string.status_paused_camera),
    PAUSED_SCREEN_OFF(R.string.status_paused_screen),
    PAUSED_LOW_BATTERY(R.string.status_paused_battery),

    /** The camera works but face analysis keeps failing. Not "Active": nothing is really being checked. */
    DEGRADED(R.string.status_degraded),
    STOPPED(R.string.status_stopped),
    PERMISSION_MISSING(R.string.status_permission),
    SETUP_NEEDED(R.string.status_setup);

    /** True while the service is running (even if paused or degraded). The camera is then in use by the service. */
    val isRunning: Boolean
        get() = this == ACTIVE || this == PAUSED_CAMERA_BUSY || this == PAUSED_SCREEN_OFF ||
            this == PAUSED_LOW_BATTERY || this == DEGRADED
}

/**
 * Shared between the service and the screens (both run in the same process).
 * If Android kills the process, this resets to STOPPED, which is the truth.
 */
object GuardState {
    var status by mutableStateOf(GuardStatus.STOPPED)

    /** What the last analysed picture showed (owner, other person, ...). null when not actively analysing. */
    var detection by mutableStateOf<FrameState?>(null)

    /** Best similarity to the owner's profile in the last analysed picture. Numbers only, never pictures. */
    var lastScore by mutableStateOf<Float?>(null)
}
