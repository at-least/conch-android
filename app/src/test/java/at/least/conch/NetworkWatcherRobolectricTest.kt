package at.least.conch

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The process-wide network-back fan-out ([NetworkWatcher]): one platform
 * callback shared by every live session, torn down with the last listener.
 * The retry policy it drives is pinned in ReconnectPolicyTest, and the
 * reconnector's own subscription in SessionReconnectorBackoffLoopTest; what
 * only this test pins is the shared registration surviving sessions coming
 * and going.
 */
@RunWith(AndroidJUnit4::class)
class NetworkWatcherRobolectricTest {

    /** Stands in for a session: records how often it heard the signal. */
    private class Session : NetworkSignal.Listener {
        var heard = 0
            private set

        override fun onNetworkAvailable(): Boolean {
            heard++
            return true
        }
    }

    @Before
    fun setUp() {
        NetworkWatcher.resetCache()
        NetworkWatcher.init(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = NetworkWatcher.resetCache()

    @Test
    fun `every live session hears the network coming back`() {
        val a = Session()
        val b = Session()
        NetworkWatcher.addListener(a)
        NetworkWatcher.addListener(b)

        NetworkWatcher.dispatch()
        assertEquals(1, a.heard)
        assertEquals(1, b.heard)

        // one session ends; the other keeps hearing events
        NetworkWatcher.removeListener(a)
        NetworkWatcher.dispatch()
        assertEquals(1, a.heard)
        assertEquals(2, b.heard)
    }

    @Test
    fun `the watcher re-arms after the last session goes away`() {
        val departed = Session()
        NetworkWatcher.addListener(departed)
        NetworkWatcher.removeListener(departed)
        // removing twice (stop() then onDestroy) must not throw
        NetworkWatcher.removeListener(departed)

        val fresh = Session()
        NetworkWatcher.addListener(fresh)
        NetworkWatcher.dispatch()

        assertEquals("a new session must re-arm the watcher", 1, fresh.heard)
        assertEquals("the departed session must hear nothing", 0, departed.heard)
    }

    @Test
    fun `a listener that throws does not silence the other sessions`() {
        val survivor = Session()
        NetworkWatcher.addListener(NetworkSignal.Listener { error("session already torn down") })
        NetworkWatcher.addListener(survivor)

        NetworkWatcher.dispatch()

        assertEquals(1, survivor.heard)
    }
}
