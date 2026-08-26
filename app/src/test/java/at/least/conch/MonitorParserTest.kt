package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MonitorParser pins: cpuUsage math edge cases and the parse() field /
 * null contract. (Moved out of OpenSshConfigParserTest.kt where the class
 * used to live next to unrelated fixtures.)
 *
 * /proc/stat cpu line: user nice system idle iowait irq softirq steal …
 * busy% = 100 * (1 - idleDelta / totalDelta), all non-idle ticks count busy.
 */
class MonitorParserTest {

    @Test
    fun `cpu usage from two samples`() {
        val a2 = "cpu  100 0 100 800 0 0 0 0 0 0"
        val b2 = "cpu  120 0 120 760 0 0 0 0 0 0"
        val u = MonitorParser.cpuUsage(a2, b2)
        // both sums equal (1000) -> totalDelta coerced to 1, idleDelta coerced to 0 -> 100%
        assertEquals(100.0, u, 0.001)

        val a3 = "cpu  100 0 100 800 0 0 0 0 0 0"
        val b3 = "cpu  200 0 300 1500 0 0 0 0 0 0"
        val u3 = MonitorParser.cpuUsage(a3, b3)
        // total delta = 2000-1000 = 1000, idle delta = 1500-800 = 700, busy = 300 -> 30%
        assertEquals(30.0, u3, 0.001)
    }

    @Test
    fun `full probe output parses`() {
        val out = """
            ---CPU
            cpu  367087 1290 82447 7558321 9243 0 4686 0 0 0
            cpu  367087 1290 82455 7558393 9243 0 4686 0 0 0
            ---MEM
            Mem:        8302436352   4167557120   113049600   104857600  4026531840   3753902080
            Swap:        2147483648           0  2147483648
            ---DISK
            /dev/nvme0n1p2  998242365440 314572800000 68366956544  83% /
            ---LOAD
            0.35 0.42 0.31 3/1200 56789
            ---UP
            86412.55 334456.00
        """.trimIndent()
        val s = MonitorParser.parse(out)!!
        // total delta = 80 (sys +8, idle +72) -> busy 8/80 = 10%
        assertEquals(10.0, s.cpuPercent, 0.001)
        assertEquals(8302436352L, s.memTotalBytes)
        assertEquals(4167557120L, s.memUsedBytes)
        assertEquals(2147483648L, s.swapTotalBytes)
        assertEquals(0L, s.swapUsedBytes)
        assertEquals(998242365440L, s.diskTotalBytes)
        assertEquals(314572800000L, s.diskUsedBytes)
        assertEquals(0.35, s.load1, 0.0001)
        assertEquals(0.42, s.load5, 0.0001)
        assertEquals(0.31, s.load15, 0.0001)
        assertEquals(86412L, s.uptimeSeconds)
    }

    @Test
    fun `missing sections return null`() {
        assertNull(MonitorParser.parse("garbage output\nnothing here"))
        assertNull(MonitorParser.parse("---CPU\ncpu 1 2 3 4"))
    }

    @Test
    fun `idle-only delta reads as zero percent busy`() {
        // all new ticks landed in idle -> busy share 0% (iOS parity edge)
        val busy = MonitorParser.cpuUsage(
            "cpu  100 0 0 900 0 0 0 0 0 0",
            "cpu  100 0 0 1000 0 0 0 0 0 0",
        )
        assertEquals(0.0, busy, 0.001)
    }

    @Test
    fun `busy-only delta reads as one hundred percent busy`() {
        // no idle growth at all -> fully saturated (iOS parity edge)
        val busy = MonitorParser.cpuUsage(
            "cpu  100 0 0 900 0 0 0 0 0 0",
            "cpu  200 0 0 900 0 0 0 0 0 0",
        )
        assertEquals(100.0, busy, 0.001)
    }

    @Test
    fun `idle counter moving backwards is clamped to fully busy`() {
        // pathological drift: idleB < idleA -> idleDelta coerced to 0
        val a = "cpu  100 0 100 800 0 0 0 0 0 0"
        val b = "cpu  190 0 110 790 0 0 0 0 0 0"
        assertEquals(100.0, MonitorParser.cpuUsage(a, b), 0.001)
    }

    @Test
    fun `malformed samples report zero instead of throwing`() {
        assertEquals(0.0, MonitorParser.cpuUsage("cpu  1 2 3", "cpu  4 5 6"), 0.001)
        assertEquals(0.0, MonitorParser.cpuUsage("", "cpu  4 5 6 7"), 0.001)
        assertEquals(0.0, MonitorParser.cpuUsage("cpu  4 5 6 7", "garbage"), 0.001)
    }

    @Test
    fun `tab-separated cpu lines are accepted`() {
        // parse() accepts "cpu\t…" as well as "cpu …" (MonitorParser.kt:63)
        val a = "cpu\t100 0 100 800"
        val b = "cpu  200 0 100 800"
        assertEquals(100.0, MonitorParser.cpuUsage(a, b), 0.001)
    }

    @Test
    fun `probe output without a Swap line yields zero swap not null`() {
        val noSwap = """
            ---CPU
            cpu  100 0 100 800 0 0 0 0 0 0
            cpu  200 0 100 800 0 0 0 0 0 0
            ---MEM
            Mem:        2000 1000 0 0 500 500
            ---DISK
            /dev/root 10000 4000 6000 40% /
            ---LOAD
            1.50 2.50 3.50 1/100 42
            ---UP
            86399.50 123456.00
        """.trimIndent()
        val s = MonitorParser.parse(noSwap)!!
        assertEquals(0L, s.swapTotalBytes)
        assertEquals(0L, s.swapUsedBytes)
        assertEquals(100.0, s.cpuPercent, 0.001)
    }

    @Test
    fun `probe command shape is the parser contract`() {
        // mirrors iOS testProbeCommandExact — every section the parser
        // requires must be produced by the probe, or parse() returns null
        assertTrue(MonitorParser.PROBE.startsWith("echo ---CPU; grep 'cpu ' /proc/stat"))
        assertTrue(MonitorParser.PROBE.contains("free -b"))
        assertTrue(MonitorParser.PROBE.contains("df -B1 /"))
        assertTrue(MonitorParser.PROBE.contains("cat /proc/loadavg"))
        assertTrue(MonitorParser.PROBE.contains("cat /proc/uptime"))
    }
}
