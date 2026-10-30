package com.example.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

/**
 * Foreground service that keeps NC Studio's playback alive while the app is in
 * the background, and shows the matching notification.
 *
 * Transport state, audio focus and headset-disconnect handling live in the
 * ViewModel, which owns the transport and calls [startPlayback] / [stopPlayback]
 * as playback starts and ends. The service never runs while nothing is playing,
 * so no stale "paused" notification is ever left behind.
 */
class AudioEngineService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projectName = intent?.getStringExtra(EXTRA_PROJECT_NAME) ?: DEFAULT_PROJECT_NAME

        try {
            val notification = buildNotification(projectName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Foreground-service restrictions can refuse the promotion; audio
            // playback itself continues, so this is not fatal.
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(projectName: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(projectName)
            .setContentText(getString(R.string.notification_playback_playing))
            .setSmallIcon(R.drawable.ic_stat_playback)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nc_studio_playback_channel"
        private const val EXTRA_PROJECT_NAME = "PROJECT_NAME"
        private const val DEFAULT_PROJECT_NAME = "NC Studio"

        /** Promotes the service to the foreground and shows the playback notification. */
        fun startPlayback(context: Context, projectName: String) {
            val intent = Intent(context, AudioEngineService::class.java)
                .putExtra(EXTRA_PROJECT_NAME, projectName)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Background-start restrictions: playback still works in the foreground.
            }
        }

        /** Removes the notification and tears the service down. */
        fun stopPlayback(context: Context) {
            try {
                context.stopService(Intent(context, AudioEngineService::class.java))
            } catch (_: Exception) {
            /* best-effort JNI call: a native failure must not crash the audio thread */ }
        }
    }
}
