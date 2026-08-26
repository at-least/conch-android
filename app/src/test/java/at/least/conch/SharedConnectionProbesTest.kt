package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * H2: the Monitor probe and Docker list command work unchanged when run as
 * exec channels on the connection shared with a live PTY shell — the only
 * difference from the old dedicated-connection Activities is WHERE the
 * SSHClient comes from, not the command strings or the parsers.
 */
class SharedConnectionProbesTest {

    private lateinit var dir: File
    private lateinit var server: TestSshd

    /** Realistic /proc+free+df+loadavg+uptime probe reply (MonitorParser doc shape). */
    private val probeOutput = """
        ---CPU
        cpu  100 200 300 400 500 600 700 800 900 100
        cpu  160 260 360 420 560 660 760 860 960 110
        ---MEM
        Mem:        8000000000 4000000000      200000000       500000000  2000000000  3500000000
        Swap:       2000000000  500000000  1500000000
        ---DISK
        /dev/root  60000000000  25000000000  35000000000  42% /
        ---LOAD
        0.20 0.18 0.12 1/400 1234
        ---UP
        12345.67 98765.43
    """.trimIndent()

    /** What `docker ps -a --format '{{json .}}'` emits, one NDJSON line per container. */
    private val dockerOutput = listOf(
        """{"ID":"a1b2c3d4e5f6","Names":"nginx-svc","Image":"nginx:1.25","State":"running","Status":"Up 2 hours"}""",
        """{"ID":"f6e5d4c3b2a1","Names":"cache","Image":"redis:7-alpine","State":"exited","Status":"Exited (0) 4 days ago"}""",
    ).joinToString("\n") + "\n"

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-h2").toFile()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
        dir.deleteRecursively()
    }

    @Test(timeout = 60_000)
    fun `monitor and docker probes parse identically over the shared connection`() {
        server = TestSshd(
            execHandler = { cmd ->
                when (cmd) {
                    MonitorParser.PROBE -> ExecResult(probeOutput.toByteArray())
                    DockerParser.LIST_COMMAND -> ExecResult(dockerOutput.toByteArray())
                    else -> ExecResult("$cmd\n".toByteArray())
                }
            },
        ).start()
        val ssh = connectTrusted(server, KnownHostsStore(dir))

        try {
            // a live PTY shell rides the same client, as in the terminal tab
            val shellSession = ssh.startSession()
            shellSession.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
            val sh = shellSession.startShell()
            val marker = "H2-SHELL-UP"
            synchronized(sh.outputStream) {
                sh.outputStream.write("$marker\r".toByteArray())
                sh.outputStream.flush()
            }
            assertTrue("shell is up before probes", readUntil(sh.inputStream, marker).contains(marker))

            // monitor probe: same constant, same parser — only the connection differs
            val monitorSession = ssh.startSession()
            val monitorCmd = monitorSession.exec(MonitorParser.PROBE)
            val monitorOut = monitorCmd.inputStream.readBytes().decodeToString()
            monitorCmd.close()
            monitorSession.close()
            assertEquals(probeOutput, monitorOut)

            val snap = MonitorParser.parse(monitorOut)
            assertNotNull("probe output parses to a snapshot", snap)
            snap!!
            // busy = (510-20)/510: deltas between the two cpu samples above
            assertEquals(96.08, snap.cpuPercent, 0.01)
            assertEquals(8_000_000_000L, snap.memTotalBytes)
            assertEquals(4_000_000_000L, snap.memUsedBytes)
            assertEquals(2_000_000_000L, snap.swapTotalBytes)
            assertEquals(500_000_000L, snap.swapUsedBytes)
            assertEquals(60_000_000_000L, snap.diskTotalBytes)
            assertEquals(25_000_000_000L, snap.diskUsedBytes)
            assertEquals(0.20, snap.load1, 1e-9)
            assertEquals(0.18, snap.load5, 1e-9)
            assertEquals(0.12, snap.load15, 1e-9)
            assertEquals(12345L, snap.uptimeSeconds)

            // docker probe: same constant, same parser
            val dockerSession = ssh.startSession()
            val dockerCmd = dockerSession.exec(DockerParser.LIST_COMMAND)
            val dockerOut = dockerCmd.inputStream.readBytes().decodeToString()
            dockerCmd.close()
            dockerSession.close()
            assertEquals(dockerOutput, dockerOut)

            val containers = DockerParser.parse(dockerOut)
            assertEquals(2, containers.size)
            assertEquals(
                DockerParser.Container("a1b2c3d4e5f6", "nginx-svc", "nginx:1.25", "running", "Up 2 hours"),
                containers[0]
            )
            assertEquals(
                DockerParser.Container("f6e5d4c3b2a1", "cache", "redis:7-alpine", "exited", "Exited (0) 4 days ago"),
                containers[1],
            )

            // the exact production wire strings reached the server
            assertEquals(
                listOf(MonitorParser.PROBE, DockerParser.LIST_COMMAND),
                server.recordedCommands.toList(),
            )

            // the shell survived both probes (H1's invariant, re-checked)
            val second = "H2-ALIVE"
            synchronized(sh.outputStream) {
                sh.outputStream.write("$second\r".toByteArray())
                sh.outputStream.flush()
            }
            assertTrue("shell still echoes after both probes", readUntil(sh.inputStream, second).contains(second))

            sh.close()
            shellSession.close()
        } finally {
            ssh.disconnect()
        }
    }
}
