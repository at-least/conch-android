package at.least.conch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

/**
 * Server-stats home-screen widget (iOS parity: `StatsWidget`): last-known
 * CPU / memory per host plus the sample's age, written by the Monitor tab
 * through [SharedStats]. Tap opens that host's Monitor tab. Same
 * RemoteViews + explicit-PendingIntent pattern as [HostsWidget].
 */
object StatsWidget {

    private const val ACTION_MONITOR = "at.least.conch.action.WIDGET_MONITOR"
    private const val MAX_ROWS = 4

    fun update(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, StatsWidgetReceiver::class.java))
        if (ids.isEmpty()) return

        val hosts = HostStore(context).load()
        val stats = SharedStats(context).read()
        val rv = RemoteViews(context.packageName, R.layout.widget_stats)
        rv.removeAllViews(R.id.widget_stats_list)

        if (hosts.isEmpty()) {
            val empty = RemoteViews(context.packageName, R.layout.widget_stats_row)
            empty.setTextViewText(R.id.widget_stats_name, context.getString(R.string.widget_stats_empty))
            empty.setTextViewText(R.id.widget_stats_values, "")
            empty.setTextViewText(R.id.widget_stats_age, "")
            rv.addView(R.id.widget_stats_list, empty)
        } else {
            for (host in hosts.take(MAX_ROWS)) {
                rv.addView(R.id.widget_stats_list, row(context, host, stats[host.id], nowMs))
            }
        }
        manager.updateAppWidget(ids, rv)
    }

    private fun row(context: Context, host: Host, s: StatsSnapshot?, nowMs: Long): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_stats_row)
        row.setTextViewText(R.id.widget_stats_name, HostsWidget.displayName(host))
        if (s == null) {
            row.setTextViewText(R.id.widget_stats_values, "")
            row.setTextViewText(R.id.widget_stats_age, context.getString(R.string.widget_stats_no_data))
        } else {
            row.setTextViewText(R.id.widget_stats_values, valuesLine(s))
            row.setTextViewText(R.id.widget_stats_age, SharedStats.ageLabel(nowMs, s.timestampMs))
            // stale = history, not a reading: dim the whole row
            val alpha = if (SharedStats.isStale(nowMs, s.timestampMs)) 0.5f else 1f
            row.setFloat(R.id.widget_stats_row, "setAlpha", alpha)
        }
        val intent = Intent(context, StatsWidgetReceiver::class.java).apply {
            action = ACTION_MONITOR
            putExtra("hostId", host.id)
        }
        row.setOnClickPendingIntent(
            R.id.widget_stats_row,
            PendingIntent.getBroadcast(
                context,
                host.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return row
    }

    /** "CPU 23% · 4.0 / 16 GB" */
    fun valuesLine(s: StatsSnapshot): String =
        String.format(Locale.US, "CPU %.0f%%", s.cpuPercent) +
            " · " + SharedStats.memoryLabel(s.memUsedBytes, s.memTotalBytes)

    /** Opens the host's terminal on the Monitor tab. */
    fun handleMonitor(context: Context, hostId: String?) {
        if (hostId == null) return
        val intent = Intent().apply {
            setClassName(context, "at.least.conch.TerminalActivity")
            putExtra("hostId", hostId)
            putExtra(TerminalActivity.EXTRA_TAB, TerminalActivity.TAB_MONITOR)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        context.startActivity(intent)
    }
}

class StatsWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "at.least.conch.action.WIDGET_MONITOR" ->
                StatsWidget.handleMonitor(context, intent.getStringExtra("hostId"))
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> StatsWidget.update(context)
        }
    }
}
