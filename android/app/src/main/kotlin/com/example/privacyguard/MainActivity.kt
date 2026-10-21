package com.example.privacyguard

import android.content.Intent
import android.os.Bundle
import com.example.privacyguard.bridge.PrivacyGuardPlugin
import com.example.privacyguard.service.Notifier
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * The only activity. Flutter draws the whole UI. This class only
 *  - registers the native bridge ([PrivacyGuardPlugin]),
 *  - remembers that the user tapped "Tap to restart Privacy Guard" (after a phone restart), and
 *  - removes that notification when the app comes to the front.
 */
class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine) // registers the plugins of pubspec.yaml (permission_handler)
        flutterEngine.plugins.add(PrivacyGuardPlugin())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rememberAutoStart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        rememberAutoStart(intent)
    }

    override fun onResume() {
        super.onResume()
        Notifier.cancelRestartPrompt(this)
    }

    private fun rememberAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) autoStartRequested = true
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"

        /** True when the user tapped "Tap to restart". Read once by the Flutter side, then cleared. */
        @Volatile
        private var autoStartRequested = false

        fun consumeAutoStart(): Boolean {
            val value = autoStartRequested
            autoStartRequested = false
            return value
        }
    }
}
