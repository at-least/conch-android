package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostCardStatus — pure badge derivation ported from iOS (C58). How many
 * live sessions a host has and what the card shows for it. Extracted from
 * the view so the derivation is unit-testable on the JVM.
 */
class HostCardStatusTest {

    private fun status(liveCount: Int) = HostCardStatus(liveCount)

    @Test
    fun `zero live sessions is not live and shows no badge`() {
        val s = status(0)
        assertFalse(s.isLive)
        assertNull(s.badgeText)
        assertFalse(s.showsDot)
    }

    @Test
    fun `one live session shows live badge and dot`() {
        val s = status(1)
        assertTrue(s.isLive)
        assertEquals("live", s.badgeText)
        assertTrue(s.showsDot)
    }

    @Test
    fun `multiple live sessions shows N live badge`() {
        val s = status(3)
        assertTrue(s.isLive)
        assertEquals("3 live", s.badgeText)
        assertTrue(s.showsDot)
    }
}
