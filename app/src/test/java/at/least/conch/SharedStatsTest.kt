package at.least.conch

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Server-stats widget bridge (iOS SharedStatsTests parity): file
 * round-trip with the on-disk shape pinned, per-host merge/remove, the
 * once-a-minute throttle, and the age/stale labels the widget renders.
 */
class SharedStatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var writes = 0
    private lateinit var store: SharedStats

    @Before
    fun setUp() {
        SharedStats.resetThrottleForTests()
        store = SharedStats(tmp.newFile("widget_stats.json"), onWrite = { writes++ })
    }

    private fun snap(cpu: Double, ts: Long = 1_787_000_000_000L) = StatsSnapshot(
        cpuPercent = cpu,
        memUsedBytes = 8,
        memTotalBytes = 16,
        diskUsedBytes = 100,
        diskTotalBytes = 200,
        timestampMs = ts,
    )

    private val mapSerializer = MapSerializer(String.serializer(), StatsSnapshot.serializer())

    @Test
    fun `round trip pins the on-disk shape`() {
        store.write(mapOf("h1" to snap(42.5)))
        assertEquals(
            """{"h1":{"cpuPercent":42.5,"memUsedBytes":8,"memTotalBytes":16,""" +
                """"diskUsedBytes":100,"diskTotalBytes":200,"timestampMs":1787000000000}}""",
            ConchJson.encodeToString(mapSerializer, store.read()),
        )
        assertEquals(snap(42.5), store.read()["h1"])
        assertEquals(1, writes)
        store.write(emptyMap())
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `set merges and remove deletes, refreshing the widget each time`() {
        store.set("a", snap(1.0), nowMs = 0)
        store.set("b", snap(2.0), nowMs = 0)
        assertEquals(setOf("a", "b"), store.read().keys)
        store.remove("a")
        assertNull(store.read()["a"])
        assertEquals(2.0, store.read().getValue("b").cpuPercent, 0.0)
        assertEquals(3, writes)
        store.remove("missing")
        assertEquals("removing an unknown host must not rewrite", 3, writes)
    }

    @Test
    fun `set is throttled to once per minute per host`() {
        val t0 = 1_787_000_000_000L
        assertTrue(store.set("x", snap(5.0), nowMs = t0))
        assertFalse("inside the window must be dropped", store.set("x", snap(9.0), nowMs = t0 + 10_000))
        assertEquals(5.0, store.read().getValue("x").cpuPercent, 0.0)
        assertTrue("another host has its own stamp", store.set("y", snap(7.0), nowMs = t0 + 10_000))
        assertTrue(store.set("x", snap(9.0), nowMs = t0 + 61_000))
        assertEquals(9.0, store.read().getValue("x").cpuPercent, 0.0)
        assertTrue(SharedStats.shouldWrite(null, 0))
        assertFalse(SharedStats.shouldWrite(0, 59_999))
        assertTrue(SharedStats.shouldWrite(0, 60_000))
    }

    @Test
    fun `from parser snapshot keeps the fields the widget renders`() {
        val p = MonitorParser.Snapshot(
            cpuPercent = 12.5, memTotalBytes = 10, memUsedBytes = 4, swapTotalBytes = 0, swapUsedBytes = 0,
            diskTotalBytes = 100, diskUsedBytes = 25, load1 = 0.0, load5 = 0.0, load15 = 0.0, uptimeSeconds = 5,
        )
        assertEquals(StatsSnapshot(12.5, 4, 10, 25, 100, 777), StatsSnapshot.from(p, nowMs = 777))
    }

    @Test
    fun `age and stale labels`() {
        val now = 1_000_000_000L
        assertEquals("just now", SharedStats.ageLabel(now, now - 59_000))
        assertEquals("1 min ago", SharedStats.ageLabel(now, now - 60_000))
        assertEquals("59 min ago", SharedStats.ageLabel(now, now - 59 * 60_000))
        assertEquals("1 h ago", SharedStats.ageLabel(now, now - 3_600_000))
        assertEquals("2 d ago", SharedStats.ageLabel(now, now - 2 * 86_400_000L))
        assertEquals("just now", SharedStats.ageLabel(now, now + 5_000)) // clock skew, never negative
        assertFalse(SharedStats.isStale(now, now - 10 * 60_000))
        assertTrue(SharedStats.isStale(now, now - 10 * 60_000 - 1))
    }

    @Test
    fun `widget value line`() {
        val gb = 1L shl 30
        val mb = 1L shl 20
        val big = snap(23.4).copy(memUsedBytes = 4 * gb, memTotalBytes = 16 * gb)
        assertEquals("CPU 23% · 4.0 / 16 GB", StatsWidget.valuesLine(big))
        val small = snap(99.6).copy(memUsedBytes = 512 * mb, memTotalBytes = 900 * mb)
        assertEquals("CPU 100% · 512 / 900 MB", StatsWidget.valuesLine(small))
    }

    @Test
    fun `terminal opens on the tab the widget asks for`() {
        assertEquals(SessionTab.MONITOR, TerminalActivity.initialTab(TerminalActivity.TAB_MONITOR))
        assertEquals(SessionTab.TERMINAL, TerminalActivity.initialTab(null))
        assertEquals(SessionTab.TERMINAL, TerminalActivity.initialTab("files"))
    }
}
