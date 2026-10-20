package com.example.privacyguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.privacyguard.data.AppSettings
import com.example.privacyguard.service.Notifier
import com.example.privacyguard.ui.AboutScreen
import com.example.privacyguard.ui.BatteryHelpScreen
import com.example.privacyguard.ui.CalibrationScreen
import com.example.privacyguard.ui.EnrollmentScreen
import com.example.privacyguard.ui.EventLogScreen
import com.example.privacyguard.ui.HomeScreen
import com.example.privacyguard.ui.OnboardingScreen
import com.example.privacyguard.ui.PrivacyGuardTheme
import com.example.privacyguard.ui.Screen
import com.example.privacyguard.ui.SettingsScreen

/** The only screen-holding activity. It switches between our simple Compose screens. */
class MainActivity : ComponentActivity() {

    // Changes every time the app comes to the front, so screens re-check permissions and battery state.
    private var refreshTick by mutableIntStateOf(0)

    // True when the user tapped "Tap to restart Privacy Guard" (after a phone restart).
    private var autoStartRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        autoStartRequested = intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true

        setContent {
            PrivacyGuardTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(
                        activity = this,
                        refreshTick = refreshTick,
                        onRefresh = { refreshTick++ },
                        autoStartRequested = autoStartRequested,
                        onAutoStartHandled = { autoStartRequested = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) autoStartRequested = true
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
        Notifier.cancelRestartPrompt(this)
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }
}

@Composable
private fun AppRoot(
    activity: MainActivity,
    refreshTick: Int,
    onRefresh: () -> Unit,
    autoStartRequested: Boolean,
    onAutoStartHandled: () -> Unit,
) {
    val settings = remember { AppSettings(activity) }
    var screen by rememberSaveable { mutableStateOf(if (settings.onboardingDone) Screen.HOME else Screen.ONBOARDING) }

    // The system Back button goes to the main screen from any other screen.
    BackHandler(enabled = screen != Screen.HOME && screen != Screen.ONBOARDING) { screen = Screen.HOME }

    when (screen) {
        Screen.ONBOARDING -> OnboardingScreen(
            activity = activity,
            refreshTick = refreshTick,
            onRefresh = onRefresh,
            onFinish = {
                settings.onboardingDone = true
                screen = Screen.HOME
            },
        )

        Screen.HOME -> HomeScreen(
            activity = activity,
            refreshTick = refreshTick,
            onRefresh = onRefresh,
            autoStartRequested = autoStartRequested,
            onAutoStartHandled = onAutoStartHandled,
            navigate = { screen = it },
        )

        Screen.ENROLL -> EnrollmentScreen(
            activity = activity,
            onFinished = { screen = Screen.HOME },
            onCancel = { screen = Screen.HOME },
        )

        Screen.SETTINGS -> SettingsScreen(
            context = activity,
            onOpenBatteryHelp = { screen = Screen.BATTERY },
            onCalibrate = { screen = Screen.CALIBRATE },
            onBack = { screen = Screen.HOME },
        )

        Screen.CALIBRATE -> CalibrationScreen(activity = activity, onDone = { screen = Screen.SETTINGS })
        Screen.EVENTS -> EventLogScreen(context = activity, onBack = { screen = Screen.HOME })
        Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.HOME })
        Screen.BATTERY -> BatteryHelpScreen(activity = activity, refreshTick = refreshTick, onBack = { screen = Screen.HOME })
    }
}
