package com.whereq.dosbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the emulator process alive while the player is away.
 *
 * When a game is running and the user switches to another app, Android is free
 * to reclaim this process under memory pressure — which would lose the running
 * game (DOSBox r4305 has no save-state; the whole machine lives in RAM). While
 * this foreground service is running the OS keeps the process, so SDL's paused
 * emulator survives and resumes exactly where the player left it.
 *
 * The service carries no logic; it only shows an ongoing notification (tap to
 * return to the game) and holds the process. It is started by
 * [WhereqDosboxActivity] when a game is launched and stopped when the player
 * exits to the prompt or the activity is destroyed.
 */
class DosboxForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val game = intent?.getStringExtra(EXTRA_GAME) ?: "a game"
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(game))
        // Not sticky: a game launch restarts the whole process (see
        // WhereqDosboxActivity.relaunch); the OS must NOT resurrect this service
        // on its own after that process dies.
        return START_NOT_STICKY
    }

    private fun buildNotification(game: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhereQ-DOSBox")
            .setContentText("Playing $game — tap to return")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Running game", NotificationManager.IMPORTANCE_LOW
                )
                channel.description =
                    "Shown while a DOS game is kept running so you can switch away and return."
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "dosbox_running"
        private const val NOTIF_ID = 1001
        private const val EXTRA_GAME = "game"

        /** Starts the keep-alive service, naming the game in the notification. */
        fun start(context: Context, gameName: String) {
            val intent = Intent(context, DosboxForegroundService::class.java)
                .putExtra(EXTRA_GAME, gameName)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the keep-alive service (the player left the game). */
        fun stop(context: Context) {
            context.stopService(Intent(context, DosboxForegroundService::class.java))
        }
    }
}
