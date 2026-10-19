package com.ssjrocks.ow18blcd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app (and its Bluetooth connection, held by MainViewModel) alive while a recording runs
 * with the screen off or another app in front. It does nothing else: the notification shows the count.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching { ServiceCompat.startForeground(this, ID, notification(this, intent?.getIntExtra(COUNT, 0) ?: 0), type) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val ID = 3
        private const val COUNT = "count"
        private var lastUpdate = 0L

        private fun notification(ctx: Context, count: Int): android.app.Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_meter)
                .setContentTitle("Recording readings")
                .setContentText(if (count == 0) "Starting…" else "$count readings so far")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java)) }
        }

        /** Updates the count in the notification, at most once every 2 s. */
        fun update(ctx: Context, count: Int) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate < 2000) return
            lastUpdate = now
            runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(ID, notification(ctx, count)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RecordingService::class.java))
        }
    }
}
