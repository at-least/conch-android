package at.least.conch

/**
 * Fixed-capacity ring of recent monitor samples for the Monitor sparklines
 * (improvement-plan 3.4: CPU/RAM history without extra server load — the
 * probe is unchanged, samples ride on the existing 5s poll). One sample per
 * SUCCESSFUL poll; failed parses push nothing — a flat line across an
 * outage would lie, so gaps simply shorten the window.
 */
class MetricHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Sample(val atMs: Long, val cpuPercent: Double, val memUsedFraction: Double)

    private val samples = ArrayDeque<Sample>(capacity.coerceAtLeast(1))

    val size: Int
        get() = samples.size

    fun push(atMs: Long, snapshot: MonitorParser.Snapshot) {
        if (samples.size >= capacity) samples.removeFirst()
        samples.addLast(
            Sample(
                atMs = atMs,
                cpuPercent = snapshot.cpuPercent,
                memUsedFraction = if (snapshot.memTotalBytes > 0) {
                    snapshot.memUsedBytes.toDouble() / snapshot.memTotalBytes
                } else {
                    0.0
                },
            ),
        )
    }

    /** Oldest→newest CPU percentages. */
    fun cpuSeries(): DoubleArray = DoubleArray(samples.size) { samples[it].cpuPercent }

    /** Oldest→newest memory-used fractions (0..1). */
    fun memSeries(): DoubleArray = DoubleArray(samples.size) { samples[it].memUsedFraction }

    /** Wall-clock seconds covered by the current window (0 below two samples). */
    fun spanSeconds(): Long =
        if (samples.size < 2) 0 else (samples.last().atMs - samples.first().atMs) / 1000

    companion object {
        /** 60 samples × 5s poll = 5 minutes of history. */
        const val DEFAULT_CAPACITY = 60
    }
}

/**
 * Pure geometry for the sparkline Canvas: maps values (oldest→newest) into
 * [0,width]×[0,height] with y inverted (higher value = higher on screen) and
 * clamped to [0,max]. Compose-free on purpose so the mapping is unit-testable.
 */
object SparklineGeometry {

    fun points(values: DoubleArray, width: Float, height: Float, max: Double): List<Pair<Float, Float>> {
        if (values.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val stepX = if (values.size == 1) 0f else width / (values.size - 1)
        return values.indices.map { i ->
            val fraction = if (max > 0) (values[i] / max).coerceIn(0.0, 1.0) else 0.0
            (i * stepX) to (height * (1.0 - fraction)).toFloat()
        }
    }
}

/**
 * Process-wide history per host (iOS parity: `MetricHistoryStore`), so
 * leaving and re-entering the Monitor tab — or a second Activity on the
 * same host — keeps the last five minutes instead of starting a fresh,
 * empty sparkline. Memory is bounded by hosts × 60 samples.
 */
object MetricHistoryStore {
    private val histories = HashMap<String, MetricHistory>()

    fun forHost(hostId: String): MetricHistory = synchronized(histories) {
        histories.getOrPut(hostId) { MetricHistory() }
    }

    fun remove(hostId: String) {
        synchronized(histories) { histories.remove(hostId) }
    }
}
