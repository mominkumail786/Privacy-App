package com.example.privacyguard.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.example.privacyguard.R
import com.example.privacyguard.util.Permissions

/** Honest explanation of what the app can and cannot do. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        val paragraphs = listOf(
            R.string.about_p1, R.string.about_p2, R.string.about_p3, R.string.about_p4,
            R.string.about_p5, R.string.about_p6, R.string.about_p7,
        )
        for (p in paragraphs) Text(stringResource(p), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Explains battery optimization in simple words and opens the correct system screen. */
@Composable
fun BatteryHelpScreen(activity: Activity, refreshTick: Int, onBack: () -> Unit) {
    // Re-read when the user comes back from the system dialog (refreshTick changes).
    val optimized = remember(refreshTick) {
        !activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)
    }

    ScreenScaffold(title = stringResource(R.string.battery_title), onBack = onBack) {
        Text(stringResource(R.string.battery_why), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(if (optimized) R.string.battery_status_on else R.string.battery_status_off),
            style = MaterialTheme.typography.titleMedium,
        )

        if (optimized) {
            Button(onClick = {
                // One-tap system dialog "Let app always run in background?". If the phone does not have it,
                // fall back to the full list of apps.
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
                    )
                } catch (e: Exception) {
                    openBatteryList(activity)
                }
            }) { Text(stringResource(R.string.battery_btn_turn_off)) }
        }
        OutlinedButton(onClick = { openBatteryList(activity) }) { Text(stringResource(R.string.battery_btn_list)) }
        OutlinedButton(onClick = { Permissions.openAppSettings(activity) }) {
            Text(stringResource(R.string.battery_btn_app_settings))
        }

        Text(stringResource(R.string.battery_oem), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun openBatteryList(activity: Activity) {
    try {
        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (e: Exception) {
        Permissions.openAppSettings(activity) // last fallback
    }
}
