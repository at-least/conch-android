package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionService.Registry — the multi-session invariants: per-session
 * notification ids are unique and stable, ending one session never ends the
 * others, and the service may only stop when the LAST session goes away.
 */
class SessionServiceRegistryTest {

    @After
    fun cleanUp() {
        SessionService.Registry.clear()
    }

    @Test
    fun `notification ids are unique per session`() {
        val a = SessionService.Registry.notifIdFor("sess-a")
        val b = SessionService.Registry.notifIdFor("sess-b")
        assertTrue(a != b)
        assertTrue(a > 0 && b > 0)
    }

    @Test
    fun `notification id is stable for the same session`() {
        assertEquals(
            SessionService.Registry.notifIdFor("sess-a"),
            SessionService.Registry.notifIdFor("sess-a"),
        )
    }

    @Test
    fun `stopping one session keeps the others live`() {
        SessionService.Registry.add("a", "host-a")
        SessionService.Registry.add("b", "host-b")

        SessionService.Registry.remove("a")
        assertFalse(SessionService.Registry.isEmpty())
        assertEquals(1, SessionService.Registry.entries().size)

        SessionService.Registry.remove("b")
        assertTrue(SessionService.Registry.isEmpty())
    }

    @Test
    fun `taking an unknown notification id yields null`() {
        assertEquals(null, SessionService.Registry.takeNotifId("never-started"))
    }

    @Test
    fun `taken notification id is retired`() {
        val id = SessionService.Registry.notifIdFor("a")
        assertEquals(id, SessionService.Registry.takeNotifId("a"))
        assertEquals(null, SessionService.Registry.takeNotifId("a"))
        // A re-registered session must not reuse the retired id.
        assertTrue(SessionService.Registry.notifIdFor("a") != id)
    }
}
