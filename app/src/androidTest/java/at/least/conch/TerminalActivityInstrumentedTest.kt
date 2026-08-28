package at.least.conch

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end: opening [TerminalActivity] for a saved host drives the real
 * [SshSession] against the Docker matrix (10.0.2.2) and a live session shows
 * up in [LiveSessions] — the whole Activity + Compose + SSH stack on a real
 * device, the layer neither the JVM nor Robolectric can reach. Same opt-in /
 * skip semantics as the other on-device matrix tests (see [MatrixDevice]).
 */
@RunWith(AndroidJUnit4::class)
class TerminalActivityInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "e2e-term-${System.currentTimeMillis()}"

    @After
    fun tearDown() {
        val store = HostStore(context)
        store.load().filter { it.alias == alias }.forEach { SecretsStore.delete("host-pw:${it.id}") }
        store.save(store.load().filterNot { it.alias == alias })
    }

    @Test
    fun `opening_the_terminal_for_a_saved_host_produces_a_live_session_against_real_openssh`() {
        MatrixDevice.requireMatrix()
        // seed a host the way the form would, plus its password
        val host = MatrixDevice.passwordHost(alias = alias)
        val store = HostStore(context)
        store.save(store.load() + host)
        SecretsStore.put("host-pw:${host.id}", "conch-pw-1")
        // pin the host key once so the Activity's background connect is promptless
        SshConnectionFactory.connect(context, host, MatrixDevice.acceptPrompt).disconnect()

        val intent = Intent(context, TerminalActivity::class.java).putExtra("hostId", host.id)
        ActivityScenario.launch<TerminalActivity>(intent).use {
            // the Activity built a real SshSession; on connect it registers in
            // LiveSessions — the end-to-end proof the screen is wired to SSH
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && LiveSessions.countForHost(host.id) == 0) {
                Thread.sleep(200)
            }
            assertTrue(
                "the terminal screen never produced a live session for the host",
                LiveSessions.countForHost(host.id) > 0,
            )
            val live = LiveSessions.all().firstOrNull { it.hostId == host.id }
            assertNotNull("no LiveSessions entry for the connected host", live)
            // tearing it down through the same registry the UI uses works too
            live!!.disconnect()
        }
    }
}
