package com.example.privacyguard.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.privacyguard.R
import com.example.privacyguard.data.ProfileStore
import com.example.privacyguard.face.FaceEmbedder
import com.example.privacyguard.service.GuardState
import com.example.privacyguard.service.GuardStatus
import com.example.privacyguard.service.PrivacyGuardService
import com.example.privacyguard.util.Permissions

enum class Screen { ONBOARDING, HOME, ENROLL, SETTINGS, ABOUT, BATTERY }

@Composable
fun HomeScreen(
    activity: Activity,
    refreshTick: Int,
    onRefresh: () -> Unit,
    autoStartRequested: Boolean,
    onAutoStartHandled: () -> Unit,
    navigate: (Screen) -> Unit,
) {
    // These are re-read whenever refreshTick changes (the app came back to the front, or a permission changed).
    val cameraOk = remember(refreshTick) { Permissions.hasCamera(activity) }
    val notificationsOk = remember(refreshTick) { Permissions.hasNotifications(activity) }
    val hasProfile = remember(refreshTick) { ProfileStore(activity).hasProfile() }
    val modelOk = remember { FaceEmbedder.isModelAvailable(activity) }
    val batteryOptimizationOn = remember(refreshTick) {
        !activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)
    }

    val serviceStatus = GuardState.status
    val running = serviceStatus == GuardStatus.ACTIVE ||
        serviceStatus == GuardStatus.PAUSED_CAMERA_BUSY ||
        serviceStatus == GuardStatus.PAUSED_SCREEN_OFF ||
        serviceStatus == GuardStatus.PAUSED_LOW_BATTERY
    // The camera permission can be taken away in system settings: then the truth is "Permission missing".
    val shownStatus = if (!cameraOk && !running) GuardStatus.PERMISSION_MISSING else serviceStatus

    val canStart = cameraOk && notificationsOk && hasProfile && modelOk && !running

    // After a restart, the user taps the "Tap to restart" notification. The app is now visible, so starting is allowed.
    LaunchedEffect(autoStartRequested, canStart) {
        if (autoStartRequested) {
            if (canStart) PrivacyGuardService.start(activity)
            onAutoStartHandled()
        }
    }

    ScreenScaffold(title = stringResource(R.string.app_name)) {
        StatusCard(shownStatus, GuardState.debugLine)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { PrivacyGuardService.start(activity) }, enabled = canStart) {
                Text(stringResource(R.string.btn_start))
            }
            OutlinedButton(onClick = { PrivacyGuardService.stop(activity) }, enabled = running) {
                Text(stringResource(R.string.btn_stop))
            }
        }
        if (!canStart && !running) {
            Text(stringResource(R.string.start_blocked), style = MaterialTheme.typography.bodyMedium)
        }

        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)

        if (!cameraOk) {
            PermissionCard(
                activity = activity,
                permission = Manifest.permission.CAMERA,
                title = stringResource(R.string.perm_camera_title),
                reason = stringResource(R.string.perm_camera_reason),
                granted = false,
                onChanged = onRefresh,
            )
        }
        if (!notificationsOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                activity = activity,
                permission = Manifest.permission.POST_NOTIFICATIONS,
                title = stringResource(R.string.perm_notif_title),
                reason = stringResource(R.string.perm_notif_reason),
                granted = false,
                onChanged = onRefresh,
            )
        }

        InfoCard {
            if (!modelOk) {
                Text(stringResource(R.string.setup_model_missing), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(if (hasProfile) R.string.setup_profile_ok else R.string.setup_profile_missing))
            if (running) {
                Text(stringResource(R.string.setup_stop_first))
            }
            Button(
                onClick = { navigate(Screen.ENROLL) },
                enabled = cameraOk && modelOk && !running,
            ) {
                Text(stringResource(if (hasProfile) R.string.btn_reenroll else R.string.btn_enroll))
            }
        }

        InfoCard {
            Text(stringResource(if (batteryOptimizationOn) R.string.setup_battery_on else R.string.setup_battery_off))
            OutlinedButton(onClick = { navigate(Screen.BATTERY) }) { Text(stringResource(R.string.btn_battery)) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { navigate(Screen.SETTINGS) }) { Text(stringResource(R.string.btn_settings)) }
            OutlinedButton(onClick = { navigate(Screen.ABOUT) }) { Text(stringResource(R.string.btn_about)) }
        }
    }
}

@Composable
private fun StatusCard(status: GuardStatus, debugLine: String) {
    val colors = MaterialTheme.colorScheme
    val background = when (status) {
        GuardStatus.ACTIVE -> colors.primaryContainer
        GuardStatus.PAUSED_CAMERA_BUSY, GuardStatus.PAUSED_SCREEN_OFF, GuardStatus.PAUSED_LOW_BATTERY -> colors.tertiaryContainer
        GuardStatus.STOPPED -> colors.surfaceVariant
        GuardStatus.PERMISSION_MISSING, GuardStatus.SETUP_NEEDED -> colors.errorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.status_label), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(status.textRes), style = MaterialTheme.typography.titleLarge)
            if (status == GuardStatus.ACTIVE && debugLine.isNotEmpty()) {
                Text(stringResource(R.string.debug_title) + ": " + debugLine, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.status_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}
