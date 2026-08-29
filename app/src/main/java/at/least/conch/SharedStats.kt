package at.least.conch

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Compact per-host monitor snapshot for the Server-stats widget (iOS
 * parity: `SharedStats` / `StatsSnapshot`). STALE-DATA MODEL: the app
 * writes while a Monitor tab is sampling (throttled); the widget only
 * renders last-known values plus their age — it never opens SSH.
 */
@Serializable
data class StatsSnapshot(
    val cpuPercent: Double,
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val diskUsedBytes: Long,
    val diskTotalBytes: Long,
    /** Epoch milliseconds of the sample. */
    val timestampMs: Long,
) {
    companion object {
        fun from(s: MonitorParser.Snapshot, nowMs: Long = System.currentTimeMillis()) = StatsSnapshot(
            cpuPercent = s.cpuPercent,
            memUsedBytes = s.memUsedBytes,
            memTotalBytes = s.memTotalBytes,
            diskUsedBytes = s.diskUsedBytes,
            diskTotalBytes = s.diskTotalBytes,
            timestampMs = nowMs,
        )
    }
}

/**
 * JSON file store of the last-known snapshot per host id. Primary
 * constructor takes the backing [file] so the store is JVM-testable; the
 * Android constructor resolves filesDir and refreshes the widget on write.
 */
class SharedStats(private val file: File, private val onWrite: () -> Unit = {}) {

    constructor(context: Context) : this(
        File(context.filesDir, FILE_NAME),
        onWrite = { StatsWidget.update(context) },
    )

    fun read(): Map<String, StatsSnapshot> = try {
        if (file.exists()) ConchJson.decodeFromString(SERIALIZER, file.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun write(all: Map<String, StatsSnapshot>) {
        AtomicFile.write(file, ConchJson.encodeToString(SERIALIZER, all))
        onWrite()
    }

    /**
     * Merges one host's snapshot, throttled per host (widget refreshes are
     * rate-limited and the monitor samples every 5 s). Returns whether it
     * was written.
     */
    fun set(hostId: String, snapshot: StatsSnapshot, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!shouldWrite(lastWriteMs[hostId], nowMs)) return false
        lastWriteMs[hostId] = nowMs
        write(read() + (hostId to snapshot))
        return true
    }

    fun remove(hostId: String) {
        val all = read()
        if (hostId !in all) return
        write(all - hostId)
    }

    companion object {
        const val FILE_NAME = "widget_stats.json"

        /** Per host: one global stamp would let only ONE host reach the widget per minute. */
        const val MIN_WRITE_INTERVAL_MS = 60_000L

        /** Older than this is drawn dimmed — the numbers are history, not a reading. */
        const val STALE_AFTER_MS = 10 * 60_000L

        private val SERIALIZER = MapSerializer(String.serializer(), StatsSnapshot.serializer())
        private val lastWriteMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** Test hook: forget the throttle stamps. */
        fun resetThrottleForTests() = lastWriteMs.clear()

        fun shouldWrite(lastWriteMs: Long?, nowMs: Long): Boolean =
            lastWriteMs == null || nowMs - lastWriteMs >= MIN_WRITE_INTERVAL_MS

        fun isStale(nowMs: Long, timestampMs: Long): Boolean = nowMs - timestampMs > STALE_AFTER_MS

        /** "just now" / "2 min ago" / "3 h ago" / "4 d ago". */
        fun ageLabel(nowMs: Long, timestampMs: Long): String {
            val s = ((nowMs - timestampMs) / 1000).coerceAtLeast(0)
            return when {
                s < 60 -> "just now"
                s < 3600 -> "${s / 60} min ago"
                s < 86_400 -> "${s / 3600} h ago"
                else -> "${s / 86_400} d ago"
            }
        }

        /** "4.0 / 16 GB" — used and total in the total's unit. */
        fun memoryLabel(usedBytes: Long, totalBytes: Long): String {
            val gb = 1024.0 * 1024 * 1024
            val mb = 1024.0 * 1024
            return if (totalBytes >= gb) {
                String.format(java.util.Locale.US, "%.1f / %.0f GB", usedBytes / gb, totalBytes / gb)
            } else {
                String.format(java.util.Locale.US, "%.0f / %.0f MB", usedBytes / mb, totalBytes / mb)
            }
        }
    }
}
