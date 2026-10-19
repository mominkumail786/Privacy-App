package com.example.privacyguard.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.privacyguard.R
import com.example.privacyguard.data.AppSettings
import com.example.privacyguard.data.ProfileStore
import com.example.privacyguard.logic.Sensitivity
import com.example.privacyguard.service.PrivacyGuardService
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(context: Context, onOpenBatteryHelp: () -> Unit, onBack: () -> Unit) {
    val settings = remember { AppSettings(context) }
    var sensitivity by remember { mutableStateOf(settings.sensitivity) }
    var thresholds by remember { mutableStateOf(settings.thresholds) }
    var vibration by remember { mutableStateOf(settings.vibration) }
    var cooldown by remember { mutableIntStateOf(settings.cooldownSeconds) }
    var confirmDelete by remember { mutableStateOf(false) }

    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {

        // ----- Sensitivity -----
        Text(stringResource(R.string.settings_sensitivity), style = MaterialTheme.typography.titleLarge)
        val levels = listOf(
            Sensitivity.LOW to R.string.sens_low,
            Sensitivity.MEDIUM to R.string.sens_medium,
            Sensitivity.HIGH to R.string.sens_high,
        )
        for ((level, labelRes) in levels) {
            val choose = {
                sensitivity = level
                settings.applySensitivity(level) // also resets the advanced numbers
                thresholds = settings.thresholds
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = sensitivity == level, role = Role.RadioButton, onClick = choose),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = sensitivity == level, onClick = null)
                Text(stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
            }
        }

        HorizontalDivider()

        // ----- Advanced thresholds -----
        Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.settings_advanced_note), style = MaterialTheme.typography.bodySmall)

        Text(stringResource(R.string.adv_match, "%.2f".format(thresholds.matchThreshold)))
        Slider(
            value = thresholds.matchThreshold,
            onValueChange = { thresholds = thresholds.copy(matchThreshold = (it * 100).roundToInt() / 100f) },
            onValueChangeFinished = { settings.thresholds = thresholds },
            valueRange = 0.2f..0.8f,
        )

        Text(stringResource(R.string.adv_angle, thresholds.maxHeadAngle.roundToInt()))
        Slider(
            value = thresholds.maxHeadAngle,
            onValueChange = { thresholds = thresholds.copy(maxHeadAngle = it.roundToInt().toFloat()) },
            onValueChangeFinished = { settings.thresholds = thresholds },
            valueRange = 10f..60f,
        )

        Text(stringResource(R.string.adv_face, (thresholds.minFaceSize * 100).roundToInt()))
        Slider(
            value = thresholds.minFaceSize,
            onValueChange = { thresholds = thresholds.copy(minFaceSize = (it * 100).roundToInt() / 100f) },
            onValueChangeFinished = { settings.thresholds = thresholds },
            valueRange = 0.05f..0.4f,
        )

        Text(stringResource(R.string.adv_frames, thresholds.requiredCount, thresholds.windowSize))

        HorizontalDivider()

        // ----- Alerts -----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = vibration,
                    role = Role.Switch,
                    onValueChange = {
                        vibration = it
                        settings.vibration = it
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.settings_vibration))
            Switch(checked = vibration, onCheckedChange = null)
        }

        Text(stringResource(R.string.settings_cooldown, cooldown))
        Slider(
            value = cooldown.toFloat(),
            onValueChange = { cooldown = (it / 5f).roundToInt() * 5 },
            onValueChangeFinished = { settings.cooldownSeconds = cooldown },
            valueRange = 5f..300f,
            steps = 58, // steps of 5 seconds
        )

        HorizontalDivider()

        // ----- Battery help and delete -----
        OutlinedButton(onClick = onOpenBatteryHelp) { Text(stringResource(R.string.settings_battery)) }
        Button(onClick = { confirmDelete = true }) { Text(stringResource(R.string.settings_delete_profile)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    PrivacyGuardService.stop(context)   // protection cannot work without the profile
                    ProfileStore(context).delete()      // overwrites the file, deletes it, deletes the Keystore key
                    Toast.makeText(context, R.string.delete_done, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
