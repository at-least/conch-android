package at.least.conch

/**
 * Parses the combined output of the monitoring probe command into a snapshot.
 * Pure Kotlin — unit tested without Android.
 *
 * Probe shape:
 * ```
 * ---CPU
 * cpu  u1 n1 s1 i1 w1 irq1 sirq1 st1 guest1 gn1      (sample A)
 * cpu  u2 n2 s2 i2 w2 irq2 sirq2 st2 guest2 gn2      (sample B, 1s later)
 * ---MEM
 * Mem:   total used shared buff cache available
 * Swap:  total used free ...
 * ---DISK
 * /dev/root  total used avail use% /                 (df -B1 /)
 * ---LOAD
 * 0.20 0.18 0.12 1/400 1234
 * ---UP
 * 12345.67 98765.43
 * ```
 */
object MonitorParser {

    /**
     * Wire contract: the probe command whose output [parse] consumes. The
     * shape (sections, `sleep 1` dual cpu samples, `df -B1 /`) is shared
     * with the iOS parser — drift here breaks the parser, not the build.
     * Pinned by InteractionStringContractTest.
     */
    const val PROBE = "echo ---CPU; grep 'cpu ' /proc/stat; sleep 1; grep 'cpu ' /proc/stat; " +
        "echo ---MEM; free -b | grep -E '^Mem:|^Swap:'; " +
        "echo ---DISK; df -B1 / | tail -1; " +
        "echo ---LOAD; cat /proc/loadavg; " +
        "echo ---UP; cat /proc/uptime"

    data class Snapshot(
        val cpuPercent: Double,
        val memTotalBytes: Long,
        val memUsedBytes: Long,
        val swapTotalBytes: Long,
        val swapUsedBytes: Long,
        val diskTotalBytes: Long,
        val diskUsedBytes: Long,
        val load1: Double,
        val load5: Double,
        val load15: Double,
        val uptimeSeconds: Long,
    )

    fun parse(output: String): Snapshot? {
        val sections = mutableMapOf<String, List<String>>()
        var current: MutableList<String>? = null
        for (line in output.lines()) {
            if (line.startsWith("---") && line.length > 3) {
                current = mutableListOf()
                sections[line.substring(3).trim()] = current
            } else {
                current?.add(line)
            }
        }

        val cpu = sections["CPU"]?.filter { it.startsWith("cpu ") || it.startsWith("cpu\t") }
            ?.takeIf { it.size >= 2 }
            ?.let { cpuUsage(it[0], it[1]) }

        val memLines = sections["MEM"].orEmpty()
        val mem = memLines.firstOrNull { it.startsWith("Mem:") }?.let { fields(it) }
        val swap = memLines.firstOrNull { it.startsWith("Swap:") }?.let { fields(it) }

        val disk = sections["DISK"]?.firstOrNull { it.isNotBlank() }?.let { fields(it) }

        val load = sections["LOAD"]?.firstOrNull { it.isNotBlank() }?.split(Regex("\\s+"))
        val up = sections["UP"]?.firstOrNull { it.isNotBlank() }?.split(Regex("\\s+"))

        if (cpu == null) return null
        if (mem == null) return null
        if (disk == null) return null
        if (load == null) return null
        if (up == null) return null

        return Snapshot(
            cpuPercent = cpu,
            memTotalBytes = mem.getOrNull(1)?.toLongOrNull() ?: 0,
            memUsedBytes = mem.getOrNull(2)?.toLongOrNull() ?: 0,
            swapTotalBytes = swap?.getOrNull(1)?.toLongOrNull() ?: 0,
            swapUsedBytes = swap?.getOrNull(2)?.toLongOrNull() ?: 0,
            diskTotalBytes = disk.getOrNull(1)?.toLongOrNull() ?: 0,
            diskUsedBytes = disk.getOrNull(2)?.toLongOrNull() ?: 0,
            load1 = load.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
            load5 = load.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
            load15 = load.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
            uptimeSeconds = (up.getOrNull(0)?.toDoubleOrNull() ?: 0.0).toLong(),
        )
    }

    private fun fields(line: String): List<String> = line.trim().split(Regex("\\s+"))

    /** busy = 100 * (1 - idleDelta / totalDelta), counting softirq etc. as busy. */
    fun cpuUsage(sampleA: String, sampleB: String): Double {
        val a = cpuFields(sampleA) ?: return 0.0
        val b = cpuFields(sampleB) ?: return 0.0
        val totalDelta = (b.sum() - a.sum()).coerceAtLeast(1)
        val idleA = a.getOrNull(3) ?: 0
        val idleB = b.getOrNull(3) ?: 0
        val idleDelta = (idleB - idleA).coerceAtLeast(0)
        return (100.0 * (totalDelta - idleDelta) / totalDelta).coerceIn(0.0, 100.0)
    }

    /** /proc/stat cpu line values (user nice system idle iowait irq softirq steal …). */
    private fun cpuFields(line: String): List<Long>? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5) return null
        return parts.drop(1).mapNotNull { it.toLongOrNull() }
    }
}
