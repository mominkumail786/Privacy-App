package com.example.privacyguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.privacyguard.data.AppSettings

/**
 * Runs after the phone restarts.
 * Android does not allow an app to start a camera service from here, so we ONLY show a notification.
 * The user taps it, the app opens (visible), and then the app is allowed to start the service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (AppSettings(context).protectionWanted) {
            Notifier.showRestartPrompt(context)
        }
    }
}
