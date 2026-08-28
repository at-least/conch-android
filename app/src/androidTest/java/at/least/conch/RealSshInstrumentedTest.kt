package at.least.conch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The full Android connect path — [SshConnectionFactory.connect] with a
 * Context — on a real device/emulator: the password comes out of the
 * Android-Keystore-backed [SecretsStore], the host record out of
 * [HostStore] on the app's filesDir, the host key is TOFU'd into the app's
 * known_hosts, and the session runs against real OpenSSH in the Docker
 * matrix. Robolectric cannot back the Keystore; the JVM tests inject
 * secrets. This is the only place all three meet.
 */
@RunWith(AndroidJUnit4::class)
class RealSshInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var host: Host

    @Before
    fun setUp() {
        SecretsStore.init(context)
        host = MatrixDevice.passwordHost()
        HostStore(context).save(HostStore(context).load().filterNot { it.alias == host.alias } + host)
        SecretsStore.put("host-pw:${host.id}", "conch-pw-1")
    }

    @After
    fun tearDown() {
        HostStore(context).save(HostStore(context).load().filterNot { it.id == host.id })
        SecretsStore.delete("host-pw:${host.id}")
    }

    @Test
    fun `keystore password and tofu known_hosts connect to real openssh`() {
        MatrixDevice.requireMatrix()
        val store = KnownHostsStore(context.filesDir)
        val before = store.file.takeIf { it.exists() }?.readLines().orEmpty().size

        val ssh = SshConnectionFactory.connect(context, host, MatrixDevice.acceptPrompt)
        try {
            val out = SshSession.execChannelOutput(ssh, "echo DEVICE_OK; whoami")
            assertEquals("DEVICE_OK\npwuser", out.trim())
        } finally {
            ssh.disconnect()
        }
        // TOFU wrote the matrix host key into the app's own known_hosts
        val after = store.file.readLines().filter { it.isNotBlank() }
        assertTrue("known_hosts did not grow: $after", after.size > before)
        assertTrue(after.any { it.startsWith("[${MatrixDevice.host}]:${MatrixDevice.PW_AND_KEY_PORT} ") })

        // promptless reconnect (background session shape) now succeeds
        SshConnectionFactory.connect(context, host, prompt = null).disconnect()
    }

    @Test
    fun `sshsession on the main looper delivers connected data and disconnect callbacks`() {
        MatrixDevice.requireMatrix()
        KnownHostsStore(context.filesDir).let { store ->
            SshConnectionFactory.connect(context, host, MatrixDevice.acceptPrompt).disconnect()
            assertTrue(store.file.exists())
        }
        val connected = CountDownLatch(1)
        val gotEcho = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val reason = AtomicReference<String>()
        val text = StringBuilder()
        val session = SshSession(
            context = context,
            host = host,
            initialCols = 80,
            initialRows = 24,
            callbacks = object : SshSession.Callbacks {
                override fun onConnected() = connected.countDown()
                override fun onData(data: ByteArray) {
                    synchronized(text) { text.append(String(data)) }
                    if (synchronized(text) { text.contains("ECHO_OK") }) gotEcho.countDown()
                }
                override fun onDisconnected(r: String) {
                    reason.set(r)
                    disconnected.countDown()
                }
            },
        )
        session.connect()
        assertTrue("never connected", connected.await(30, TimeUnit.SECONDS))
        session.write("echo ECHO_'OK'\r".toByteArray())
        assertTrue("shell never echoed", gotEcho.await(20, TimeUnit.SECONDS))
        assertNotNull(session.exec("echo EXEC_OK"))
        session.disconnect("user closed")
        assertTrue("no disconnect callback", disconnected.await(20, TimeUnit.SECONDS))
        assertEquals("user closed", reason.get())
    }
}
