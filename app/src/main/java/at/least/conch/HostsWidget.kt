package at.least.conch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget listing hosts; each row deep-links into a terminal.
 * Implemented with classic RemoteViews + explicit PendingIntents (one per
 * host, each carrying its hostId extra — Glance's actionStartActivity cannot
 * attach extras).
 */
object HostsWidget {

    private const val ACTION_CONNECT = "at.least.conch.action.WIDGET_CONNECT"

    fun displayName(host: Host): String =
        if (host.alias.isNotBlank()) host.alias else "${host.username}@${host.hostname}"

    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, HostsWidgetReceiver::class.java))
        if (ids.isEmpty()) return

        val hosts = HostStore(context).load()
        val rv = RemoteViews(context.packageName, R.layout.widget_hosts)
        rv.removeAllViews(R.id.widget_hosts_list)

        if (hosts.isEmpty()) {
            val empty = RemoteViews(context.packageName, R.layout.widget_host_row)
            empty.setTextViewText(R.id.widget_host_name, "No hosts yet")
            rv.addView(R.id.widget_hosts_list, empty)
        } else {
            for (host in hosts.take(4)) {
                val row = RemoteViews(context.packageName, R.layout.widget_host_row)
                row.setTextViewText(R.id.widget_host_name, displayName(host))
                row.setTextViewText(
                    R.id.widget_host_detail,
                    "${host.username}@${host.hostname}:${host.port}" +
                        if (host.authType == Host.AUTH_KEY) " 🔑" else ""
                )
                val intent = Intent(context, HostsWidgetReceiver::class.java).apply {
                    action = ACTION_CONNECT
                    putExtra("hostId", host.id)
                }
                row.setOnClickPendingIntent(
                    R.id.widget_host_row,
                    PendingIntent.getBroadcast(
                        context,
                        host.id.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                rv.addView(R.id.widget_hosts_list, row)
            }
        }
        manager.updateAppWidget(ids, rv)
    }

    /** Opens the terminal for the host tapped on the widget. */
    fun handleConnect(context: Context, hostId: String?) {
        if (hostId == null) return
        val intent = Intent().apply {
            setClassName(context, "at.least.conch.TerminalActivity")
            putExtra("hostId", hostId)
            // Own task per session (LiveSessions design), like every other
            // terminal launch site.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        context.startActivity(intent)
    }
}

class HostsWidgetReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "at.least.conch.action.WIDGET_CONNECT" ->
                HostsWidget.handleConnect(context, intent.getStringExtra("hostId"))
            android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                HostsWidget.update(context)
            }
        }
    }
}
