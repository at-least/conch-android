package at.least.conch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process alive while a terminal session is connected. Started by
 * TerminalActivity on connect; stops on disconnect. The persistent
 * notification tells Android's resource killers we're actively working
 * (the lesson every terminal app learned from Termux's "signal 9" reports).
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val notifId = intent.getIntExtra("notifId", NOTIF_ID_BASE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId)
            stopSelf()
            return START_NOT_STICKY
        }
        val hostName = intent?.getStringExtra("hostName") ?: "session"
        // unique id per host so several concurrent sessions each keep
        // their own persistent notification
        val notifId = (intent?.getStringExtra("hostId") ?: hostName).hashCode()
        try {
            startForeground(notifId, buildNotification(hostName))
        } catch (_: Exception) {
            // Android 14+ denies some FGS starts (background timing); the
            // session still works without the persistent notification.
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(hostName: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active sessions",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps SSH sessions alive in the background"
                setShowBadge(false)
            }
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Connected to $hostName")
            .setContentText("SSH session running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "conchapp_sessions"
        private const val NOTIF_ID_BASE = 1
        private const val ACTION_STOP = "at.least.conch.action.STOP_SESSION"

        fun start(context: Context, hostId: String, hostName: String) {
            val intent = Intent(context, SessionService::class.java)
                .putExtra("hostName", hostName)
                .putExtra("hostId", hostId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionService::class.java))
        }
    }
}
