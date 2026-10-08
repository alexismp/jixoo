package io.github.glaforge.jixoo.controller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.glaforge.jixoo.controller.MainActivity

/**
 * Foreground Service holding:
 * 1. `PowerManager.PARTIAL_WAKE_LOCK` — prevents Android Doze & coroutine timer coalescing (`TimerSlack`) when screen locks.
 * 2. `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` — prevents Android Wi-Fi IEEE 802.11 Power Save Mode (`PSM`) latency spikes.
 */
class SlideshowForegroundService : Service() {

    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLowLatencyLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
            ?: "Keeping CPU & Low-Latency Wi-Fi active while screen is locked"

        acquireLocks()
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    @Suppress("WakelockTimeout")
    private fun acquireLocks() {
        if (cpuWakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Jixoo::SlideshowCpuWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (wifiLowLatencyLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLowLatencyLock = wm.createWifiLock(mode, "Jixoo::SlideshowWifiLowLatencyLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
            }
        }
        cpuWakeLock = null

        runCatching {
            if (wifiLowLatencyLock?.isHeld == true) {
                wifiLowLatencyLock?.release()
            }
        }
        wifiLowLatencyLock = null
    }

    private fun buildNotification(statusText: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pixoo Slideshow Active",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps CPU and low-latency Wi-Fi active during unattended Pixoo slideshow playback"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pixoo Slideshow Running")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pixoo_slideshow_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "io.github.glaforge.jixoo.controller.START_FOREGROUND"
        private const val ACTION_STOP = "io.github.glaforge.jixoo.controller.STOP_FOREGROUND"
        private const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun start(context: Context, statusText: String? = null) {
            val intent = Intent(context, SlideshowForegroundService::class.java).apply {
                action = ACTION_START
                statusText?.let { putExtra(EXTRA_STATUS_TEXT, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SlideshowForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
