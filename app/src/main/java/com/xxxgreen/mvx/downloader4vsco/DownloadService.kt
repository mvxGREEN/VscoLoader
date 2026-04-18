package com.xxxgreen.mvx.downloader4vsco

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class DownloadService : Service() {
    companion object {
        const val CHANNEL_ID = "vscoloader_channel"
        const val NOTIF_ID = 3899
        const val PROGRESS_UPDATE_ACTION = "com.xxxgreen.mvx.PROGRESS_UPDATE"
    }

    // Receiver to handle updates from DownloadReceiver
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val completed = intent?.getIntExtra("completed", 0) ?: 0
            val total = intent?.getIntExtra("total", 0) ?: 0
            val isFinished = intent?.getBooleanExtra("finished", false) ?: false

            if (isFinished) {
                // Done: Remove notification and stop service
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                // Update: Update notification bar
                updateNotification(completed, total)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Register local receiver
        val filter = IntentFilter(PROGRESS_UPDATE_ACTION)
        ContextCompat.registerReceiver(this, progressReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_SERVICE") {
            // Initialize counters
            VscoLoader.completedItems = 0
            VscoLoader.totalItems = VscoLoader.mMediaUrls.size

            // Initial Notification
            startForeground(NOTIF_ID, buildNotification(0, VscoLoader.totalItems))

            // Start first download
            if (VscoLoader.mMediaUrls.isNotEmpty()) {
                val url = VscoLoader.mMediaUrls.removeAt(0)
                VscoLoader.downloadFile(this, url)
            }
        } else if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(progress: Int, max: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIF_ID, buildNotification(progress, max))
    }

    private fun buildNotification(progress: Int, max: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val title = if (max > 0) "Downloading $progress / $max" else "Downloading..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            // Determinate Progress Bar
            .setProgress(max, progress, false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Prevents sound/vibration on every update
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(progressReceiver)
    }
}