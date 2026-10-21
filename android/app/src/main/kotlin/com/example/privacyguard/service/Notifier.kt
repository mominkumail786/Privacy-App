package com.example.privacyguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.example.privacyguard.MainActivity
import com.example.privacyguard.R

/** Creates all notifications of the app. */
object Notifier {
    private const val CHANNEL_STATUS = "guard_status"
    private const val CHANNEL_ALERT = "guard_alert"
    private const val CHANNEL_RESTART = "guard_restart"

    const val ID_STATUS = 1
    private const val ID_ALERT = 2
    private const val ID_RESTART = 3

    /** Channels are created once. Safe to call many times. */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
        )

        val alert = NotificationChannel(CHANNEL_ALERT, context.getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH)
        alert.enableVibration(false) // we vibrate ourselves, so the Settings switch works
        alert.setSound(null, null)   // silent: a beep in public would draw attention to the screen
        manager.createNotificationChannel(alert)

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESTART, context.getString(R.string.channel_restart), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openAppIntent(context: Context, autoStart: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_AUTO_START, autoStart)
        return PendingIntent.getActivity(
            context, if (autoStart) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** The permanent notification of the foreground service, with a Stop button. */
    fun statusNotification(context: Context, text: String): Notification {
        val stopIntent = Intent(context, PrivacyGuardService::class.java).setAction(PrivacyGuardService.ACTION_STOP)
        val stopPending = PendingIntent.getService(
            context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context, false))
            .addAction(0, context.getString(R.string.notif_stop), stopPending)
            .build()
    }

    fun updateStatus(context: Context, text: String) {
        context.getSystemService(NotificationManager::class.java)
            .notify(ID_STATUS, statusNotification(context, text))
    }

    /** The privacy alert: high priority notification plus vibration (if enabled in Settings). */
    fun showAlert(context: Context, vibrate: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.alert_title))
            .setContentText(context.getString(R.string.alert_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.alert_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(openAppIntent(context, false))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_ALERT, notification)

        if (vibrate) {
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
            }
        }
    }

    /** "Tap to restart Privacy Guard". Tapping opens the app, and the app starts the service. */
    fun showRestartPrompt(context: Context) {
        createChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESTART)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.restart_title))
            .setContentText(context.getString(R.string.restart_text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, true))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_RESTART, notification)
    }

    fun cancelRestartPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_RESTART)
    }
}
