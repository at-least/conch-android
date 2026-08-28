package at.least.conch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process alive while terminal sessions are connected. Terminal
 * activities register on connect and unregister on disconnect. The persistent
 * notifications tell Android's resource killers we're actively working (the
 * lesson every terminal app learned from Termux's "signal 9" reports).
 *
 * Concurrent sessions each keep their own notification, keyed by the session
 * id; the service itself stops only when the LAST session goes away — ending
 * session A must not drop the foreground protection of still-live session B.
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val sessionId = intent.getStringExtra("sessionId")
            if (sessionId != null) stopSession(sessionId)
            return START_NOT_STICKY
        }
        val sessionId = intent?.getStringExtra("sessionId")
        val hostName = intent?.getStringExtra("hostName") ?: "session"
        if (sessionId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        Registry.add(sessionId, hostName)
        try {
            // Bind the foreground state to (re-affirm) this session's
            // notification. Extra notifications for other live sessions are
            // posted below — they inform, the bound one protects the process.
            startForeground(Registry.notifIdFor(sessionId), buildNotification(hostName))
            Registry.entries().forEach { (id, name) ->
                notify(id, buildNotification(name))
            }
        } catch (_: Exception) {
            // Android 14+ denies some FGS starts (background timing); the
            // session still works without the persistent notification.
            Registry.remove(sessionId)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ caps dataSync foreground services at 6 hours per 24-hour
     * window; when the cap hits, the system calls here and the service MUST
     * stop shortly — not stopping crashes with
     * ForegroundServiceDidNotStopInTimeException. Sessions survive server-
     * side (tmux auto-attach) and the app reconnects on next open, so this
     * degrades gracefully: stop, then tell the user what happened.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundTimeout()
    }

    private fun handleForegroundTimeout() {
        val sessionNames = Registry.entries().map { it.second }
        Registry.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (sessionNames.isEmpty()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(TIMEOUT_NOTIF_ID, buildTimeoutNotification(sessionNames.first()))
    }

    private fun buildTimeoutNotification(hostName: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active sessions",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps SSH sessions alive in the background"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Background protection ended — $hostName")
            .setContentText(
                "Android caps background sessions at 6 hours per day. Your tmux " +
                    "session is alive on the server — reopen Conch to reconnect.",
            )
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    private fun stopSession(sessionId: String) {
        Registry.remove(sessionId)
        val notifId = Registry.takeNotifId(sessionId)
        if (notifId != null) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }
        val remaining = Registry.entries()
        if (remaining.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            // Re-bind the foreground state to a still-live session (the
            // canceled notification may have been the bound one).
            val (id, name) = remaining.first()
            try {
                startForeground(id, buildNotification(name))
            } catch (_: Exception) {
            }
        }
    }

    private fun notify(notifId: Int, notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notification)
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
            this,
            0,
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

    /**
     * Process-wide bookkeeping shared between the service instance and the
     * static start/stop entry points. Pure data — JVM-testable.
     */
    object Registry {
        private val namesById = ConcurrentHashMap<String, String>()
        private val notifIds = ConcurrentHashMap<String, Int>()
        private val nextNotifId = AtomicInteger(NOTIF_ID_BASE + 1)

        fun add(sessionId: String, hostName: String) {
            namesById[sessionId] = hostName
        }

        fun remove(sessionId: String) {
            namesById.remove(sessionId)
        }

        fun notifIdFor(sessionId: String): Int =
            notifIds.getOrPut(sessionId) { nextNotifId.getAndIncrement() }

        /** Stop tracking a session's notification id — null if never assigned. */
        fun takeNotifId(sessionId: String): Int? = notifIds.remove(sessionId)

        fun entries(): List<Pair<Int, String>> =
            namesById.entries.map { notifIdFor(it.key) to it.value }

        fun isEmpty(): Boolean = namesById.isEmpty()

        fun clear() {
            namesById.clear()
            notifIds.clear()
        }
    }

    companion object {
        private const val CHANNEL_ID = "conchapp_sessions"
        private const val NOTIF_ID_BASE = 1
        private const val TIMEOUT_NOTIF_ID = 9001
        private const val ACTION_STOP = "at.least.conch.action.STOP_SESSION"

        fun start(context: Context, sessionId: String, hostName: String) {
            Registry.add(sessionId, hostName)
            val intent = Intent(context, SessionService::class.java)
                .putExtra("sessionId", sessionId)
                .putExtra("hostName", hostName)
            context.startForegroundService(intent)
        }

        fun stop(context: Context, sessionId: String) {
            val intent = Intent(context, SessionService::class.java)
                .setAction(ACTION_STOP)
                .putExtra("sessionId", sessionId)
            runCatching { context.startService(intent) }
        }
    }
}
