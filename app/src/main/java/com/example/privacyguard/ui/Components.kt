package com.example.privacyguard.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.privacyguard.R
import com.example.privacyguard.util.Permissions

/** Common layout for every screen: title on top, scrollable content, optional Back button. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding() // Android 15 draws apps edge-to-edge; this keeps content clear of the bars
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
        if (onBack != null) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        // Very last thing on the screen (used by Settings for the About / developer credit).
        footer?.invoke(this)
    }
}

/**
 * Explains why a permission is needed, then asks for it.
 * Handles "denied" (can ask again) and "denied permanently" (must go to app settings).
 */
@Composable
fun PermissionCard(
    activity: Activity,
    permission: String,
    title: String,
    reason: String,
    granted: Boolean,
    onChanged: () -> Unit,
) {
    var deniedForever by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        // After a "no", if Android no longer wants to show the rationale, the user cannot be asked again.
        deniedForever = !isGranted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        onChanged()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            when {
                granted -> Text("✓ " + stringResource(R.string.perm_allowed))
                deniedForever -> {
                    Text(stringResource(R.string.perm_denied_forever))
                    Button(onClick = { Permissions.openAppSettings(activity) }) {
                        Text(stringResource(R.string.perm_open_settings))
                    }
                }
                else -> Button(onClick = { launcher.launch(permission) }) {
                    Text(stringResource(R.string.perm_allow))
                }
            }
        }
    }
}
