package at.least.conch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Monitor history ring + sparkline geometry (improvement-plan 3.4). The
 * server probe is untouched — history rides on the existing 5s poll, so the
 * contracts worth pinning are: eviction order, no fabrication of samples
 * across failed polls, and the exact point mapping the Canvas draws.
 */
class MetricHistoryTest {

    private fun snap(cpu: Double, memUsed: Long = 50, memTotal: Long = 100) = MonitorParser.Snapshot(
        cpuPercent = cpu,
        memTotalBytes = memTotal,
        memUsedBytes = memUsed,
        swapTotalBytes = 0,
        swapUsedBytes = 0,
        diskTotalBytes = 0,
        diskUsedBytes = 0,
        load1 = 0.0,
        load5 = 0.0,
        load15 = 0.0,
        uptimeSeconds = 0,
    )

    @Test
    fun `ring keeps newest capacity samples in arrival order`() {
        val h = MetricHistory(capacity = 3)
        for (i in 1..5) h.push(i * 1000L, snap(cpu = i * 10.0))
        assertEquals(3, h.size)
        // 1 and 2 evicted; 3,4,5 remain oldest→newest
        assertArrayEquals(doubleArrayOf(30.0, 40.0, 50.0), h.cpuSeries(), 0.0)
    }

    @Test
    fun `memory series is the used fraction`() {
        val h = MetricHistory()
        h.push(0, snap(cpu = 0.0, memUsed = 25, memTotal = 200))
        h.push(5_000, snap(cpu = 0.0, memUsed = 100, memTotal = 200))
        h.push(10_000, snap(cpu = 0.0, memUsed = 1, memTotal = 0)) // host without /proc? honest zero
        assertArrayEquals(doubleArrayOf(0.125, 0.5, 0.0), h.memSeries(), 1e-9)
    }

    @Test
    fun `span covers wall time between oldest and newest sample`() {
        val h = MetricHistory()
        assertEquals(0, h.spanSeconds())
        h.push(0, snap(0.0))
        assertEquals(0, h.spanSeconds()) // single sample: no window yet
        h.push(65_000, snap(0.0))
        assertEquals(65, h.spanSeconds())
    }

    @Test
    fun `failed polls push nothing — no fabricated flat line`() {
        val h = MetricHistory()
        h.push(0, snap(cpu = 11.0))
        // (a parse failure in MonitorTab simply doesn't call push)
        h.push(15_000, snap(cpu = 22.0))
        assertEquals(2, h.size)
        assertArrayEquals(doubleArrayOf(11.0, 22.0), h.cpuSeries(), 0.0)
    }

    @Test
    fun `geometry spreads x across width and inverts y against max`() {
        val pts = SparklineGeometry.points(doubleArrayOf(0.0, 50.0, 100.0), 100f, 40f, 100.0)
        assertEquals(3, pts.size)
        assertEquals(0f, pts.first().first, 0f) // oldest at left edge
        assertEquals(100f, pts.last().first, 0f) // newest at right edge
        // screen y grows downward: 0% sits at the bottom, 100% at y=0
        assertEquals(40f, pts[0].second, 1e-4f)
        assertEquals(0f, pts[2].second, 1e-4f)
        assertEquals(20f, pts[1].second, 1e-4f) // 50% in the middle
    }

    @Test
    fun `geometry clamps out-of-range values instead of leaving the canvas`() {
        val pts = SparklineGeometry.points(doubleArrayOf(-5.0, 150.0), 10f, 10f, 100.0)
        assertTrue(pts[0].second >= 0f && pts[0].second <= 10f)
        assertTrue(pts[1].second >= 0f && pts[1].second <= 10f)
    }

    @Test
    fun `geometry degenerate cases return empty`() {
        assertTrue(SparklineGeometry.points(doubleArrayOf(), 10f, 10f, 1.0).isEmpty())
        assertTrue(SparklineGeometry.points(doubleArrayOf(1.0), 0f, 10f, 1.0).isEmpty())
        assertTrue(SparklineGeometry.points(doubleArrayOf(1.0), 10f, 0f, 1.0).isEmpty())
        // a single point has no line to draw; callers skip < 2 points anyway
        assertEquals(1, SparklineGeometry.points(doubleArrayOf(1.0), 10f, 10f, 1.0).size)
    }

    @Test
    fun `store hands out one history per host and keeps it across lookups`() {
        MetricHistoryStore.remove("a")
        MetricHistoryStore.remove("b")
        val a = MetricHistoryStore.forHost("a")
        a.push(0, snap(cpu = 42.0))
        // a second lookup (re-entering the tab) sees the same samples
        assertEquals(1, MetricHistoryStore.forHost("a").size)
        assertTrue(a === MetricHistoryStore.forHost("a"))
        // other hosts are isolated
        assertEquals(0, MetricHistoryStore.forHost("b").size)
        MetricHistoryStore.remove("a")
        assertEquals(0, MetricHistoryStore.forHost("a").size)
    }
}
