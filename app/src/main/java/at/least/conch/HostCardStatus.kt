package at.least.conch

/**
 * C58 host-card badge derivation — Android port of iOS's `HostCardStatus`.
 * Pure: how many live sessions this host has and what the card should show.
 * Extracted from the view for unit tests (same pattern as TunnelStatus /
 * ConnectionHealth on iOS).
 */
data class HostCardStatus(val liveSessionCount: Int = 0) {

    val isLive: Boolean get() = liveSessionCount > 0

    /** Badge text on the card; null hides it. "live" / "N live". */
    val badgeText: String?
        get() = when {
            liveSessionCount <= 0 -> null
            liveSessionCount == 1 -> "live"
            else -> "$liveSessionCount live"
        }

    /** Corner dot color semantic — matches the live connection-health dot. */
    val showsDot: Boolean get() = isLive
}
