package com.example.privacyguard.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.privacyguard.R
import com.example.privacyguard.util.Permissions

/** Four short pages. Permissions are only asked on the last page, each with its reason. */
@Composable
fun OnboardingScreen(
    activity: Activity,
    refreshTick: Int,
    onRefresh: () -> Unit,
    onFinish: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val lastPage = 3

    val titles = listOf(R.string.onb_title_1, R.string.onb_title_2, R.string.onb_title_3, R.string.onb_title_4)
    val bodies = listOf(R.string.onb_body_1, R.string.onb_body_2, R.string.onb_body_3, R.string.onb_body_4)

    ScreenScaffold(title = stringResource(titles[page])) {
        Text(stringResource(bodies[page]))

        if (page == lastPage) {
            // refreshTick makes these re-read the permission state after the user comes back from Settings.
            PermissionCard(
                activity = activity,
                permission = Manifest.permission.CAMERA,
                title = stringResource(R.string.perm_camera_title),
                reason = stringResource(R.string.perm_camera_reason),
                granted = refreshTick >= 0 && Permissions.hasCamera(activity),
                onChanged = onRefresh,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    activity = activity,
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    title = stringResource(R.string.perm_notif_title),
                    reason = stringResource(R.string.perm_notif_reason),
                    granted = refreshTick >= 0 && Permissions.hasNotifications(activity),
                    onChanged = onRefresh,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page > 0) {
                OutlinedButton(onClick = { page-- }) { Text(stringResource(R.string.back)) }
            }
            if (page < lastPage) {
                Button(onClick = { page++ }) { Text(stringResource(R.string.onb_next)) }
            } else {
                Button(onClick = onFinish) { Text(stringResource(R.string.onb_finish)) }
            }
        }
    }
}
