package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LiveSessions registry semantics: ordering, per-host counts, replace /
 * remove, and disconnectAll firing every handle exactly once. The registry
 * is process-global, so every test cleans up after itself.
 */
class LiveSessionsTest {

    @After
    fun tearDown() {
        LiveSessions.disconnectAll()
    }

    private fun live(
        id: String,
        hostId: String = "h1",
        startedAt: Long = 0,
        onDisconnect: () -> Unit = {},
    ) = LiveSessions.Live(id, hostId, "session-$id", startedAt, onDisconnect)

    @Test
    fun `all returns sessions sorted by start time`() {
        LiveSessions.register(live("late", startedAt = 3_000))
        LiveSessions.register(live("early", startedAt = 1_000))
        LiveSessions.register(live("middle", startedAt = 2_000))

        assertEquals(listOf("early", "middle", "late"), LiveSessions.all().map { it.id })
    }

    @Test
    fun `re-registering the same id replaces the entry`() {
        LiveSessions.register(live("a", startedAt = 1_000))
        LiveSessions.register(live("a", startedAt = 9_000))

        val all = LiveSessions.all()
        assertEquals(1, all.size)
        assertEquals(9_000L, all.first().startedAt)
    }

    @Test
    fun `unregister removes only the target session`() {
        LiveSessions.register(live("a"))
        LiveSessions.register(live("b"))

        LiveSessions.unregister("a")

        assertEquals(listOf("b"), LiveSessions.all().map { it.id })
    }

    @Test
    fun `countForHost counts only that host's sessions`() {
        LiveSessions.register(live("a", hostId = "h1"))
        LiveSessions.register(live("b", hostId = "h1"))
        LiveSessions.register(live("c", hostId = "h2"))

        assertEquals(2, LiveSessions.countForHost("h1"))
        assertEquals(1, LiveSessions.countForHost("h2"))
        assertEquals(0, LiveSessions.countForHost("absent"))
    }

    @Test
    fun `disconnectAll invokes every handle once and clears the registry`() {
        val fired = mutableListOf<String>()
        LiveSessions.register(live("a") { fired.add("a") })
        LiveSessions.register(live("b") { fired.add("b") })

        LiveSessions.disconnectAll()

        assertEquals(listOf("a", "b"), fired.sorted())
        assertTrue("registry must be empty after disconnectAll", LiveSessions.isEmpty())
        assertEquals(0, LiveSessions.countForHost("h1"))
    }

    @Test
    fun `a throwing disconnect handle does not block the others`() {
        val fired = mutableListOf<String>()
        LiveSessions.register(live("boom") { error("disconnect crashed") })
        LiveSessions.register(live("ok") { fired.add("ok") })

        LiveSessions.disconnectAll()

        assertEquals(listOf("ok"), fired)
        assertTrue(LiveSessions.isEmpty())
    }
}
