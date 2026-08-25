package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionTab model + tunnel capsule labels (iOS TunnelStatusTests parity,
 * adapted): iOS's SessionCard has exactly [terminal, monitor, docker,
 * files] tabs with title+icon, and TunnelStatus derives count/label.
 * Android's tab model lives here; count semantics live on
 * SshSession.tunnelCount (interaction-tested); the user-facing strings are
 * pinned via TunnelCapsule.
 */
class SessionTabsTest {

    @Test
    fun `session has exactly four tabs in redesign order`() {
        assertEquals(
            listOf("TERMINAL", "MONITOR", "DOCKER", "FILES"),
            SessionTab.entries.map { it.name },
        )
    }

    @Test
    fun `every tab has a non-empty title`() {
        assertEquals(
            listOf("Terminal", "Monitor", "Docker", "Files"),
            SessionTab.entries.map { it.title },
        )
    }

    @Test
    fun `every tab has an icon`() {
        SessionTab.entries.forEach { t ->
            assertNotNull("icon for ${t.name}", t.icon)
        }
    }
}

class TunnelCapsuleTest {

    @Test
    fun `capsule is visible only with at least one tunnel`() {
        assertFalse(TunnelCapsule.visible(0))
        assertTrue(TunnelCapsule.visible(1))
        assertTrue(TunnelCapsule.visible(3))
    }

    @Test
    fun `chip text pins the count next to the arrows`() {
        assertEquals("⇅ 1", TunnelCapsule.chipText(1))
        assertEquals("⇅ 3", TunnelCapsule.chipText(3))
    }

    @Test
    fun `stop dialog title is pinned`() {
        assertEquals("Stop 1 tunnel(s)?", TunnelCapsule.stopDialogTitle(1))
        assertEquals("Stop 2 tunnel(s)?", TunnelCapsule.stopDialogTitle(2))
    }
}
