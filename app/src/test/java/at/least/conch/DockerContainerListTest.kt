package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Docker tab against a real Docker daemon: run.sh mounts the host's
 * docker socket into the matrix container, so the exact command strings
 * the app runs over SSH ([DockerParser.LIST_COMMAND], `docker logs --tail
 * 200`, `docker stop/start`) hit the real CLI + daemon, and the parser
 * eats real NDJSON — including a Swarm-style 200-character container name
 * (the ServerBox RangeError class of bug) and non-JSON error lines.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]); skips when
 * the socket was not mounted (run.sh reports that at start-up).
 */
class DockerContainerListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val longName = "conch-longname-" + "swarm_stack_service_replica-".repeat(6) + "1"
    private var spawned = false

    @After
    fun tearDown() {
        if (spawned) DockerMatrix.docker("rm", "-f", longName, allowFailure = true)
    }

    private fun connect() =
        DockerMatrix.connect(
            KnownHostsStore(tmp.newFolder()),
            DockerMatrix.PW_AND_KEY_PORT,
            "pwuser",
            password = "conch-pw-1"
        )

    private fun requireSocket(ssh: net.schmizz.sshj.SSHClient) {
        val probe = DockerMatrix.exec(ssh, "test -S /var/run/docker.sock && echo SOCK || echo NO_SOCK").trim()
        assumeTrue("docker socket not mounted into the matrix container", probe == "SOCK")
    }

    private fun list(ssh: net.schmizz.sshj.SSHClient): List<DockerParser.Container> {
        val raw = DockerMatrix.exec(ssh, "${DockerParser.LIST_COMMAND} 2>&1", 30_000)
        assertTrue("docker ps failed over ssh: $raw", !raw.contains("permission denied", true))
        return DockerParser.parse(raw)
    }

    @Test(timeout = 90_000)
    fun `list command shows the matrix container itself as running`() {
        DockerMatrix.requireMatrix()
        connect().use { ssh ->
            requireSocket(ssh)
            val self = list(ssh).firstOrNull { it.names == DockerMatrix.CONTAINER_NAME }
            assertTrue("matrix container missing from its own docker ps", self != null)
            assertEquals("running", self!!.state)
            assertTrue("status should read Up…: ${self.status}", self.status.startsWith("Up"))
            assertTrue("image should be the matrix image: ${self.image}", self.image.startsWith("conch-android-sshd"))
            assertEquals(12, self.id.length)
        }
    }

    @Test(timeout = 120_000)
    fun `long container names, logs and stop-start round-trip through the app's commands`() {
        DockerMatrix.requireMatrix()
        connect().use { ssh ->
            requireSocket(ssh)
            assertTrue(longName.length > 150)
            DockerMatrix.docker(
                "run", "-d", "--name", longName, "--entrypoint", "sh", "conch-android-sshd:latest",
                "-c", "echo LOG_LINE_FROM_CONTAINER; sleep 300",
            )
            spawned = true

            val c = list(ssh).firstOrNull { it.names == longName }
            assertTrue("long-named container not parsed", c != null)
            c!!
            assertEquals("running", c.state)

            // exact shapes SessionTabs runs
            val logs = DockerMatrix.exec(ssh, "docker logs --tail 200 ${c.id} 2>&1", 30_000)
            assertTrue("logs missing: $logs", logs.contains("LOG_LINE_FROM_CONTAINER"))

            val stop = DockerMatrix.exec(ssh, "docker stop ${c.id} 2>&1", 60_000)
            assertTrue("stop failed: $stop", stop.trim().startsWith(c.id))
            val stopped = list(ssh).first { it.id == c.id }
            assertEquals("exited", stopped.state)
            assertTrue("status should read Exited…: ${stopped.status}", stopped.status.startsWith("Exited"))

            val start = DockerMatrix.exec(ssh, "docker start ${c.id} 2>&1", 60_000)
            assertTrue("start failed: $start", start.trim().startsWith(c.id))
            assertEquals("running", list(ssh).first { it.id == c.id }.state)
        }
    }

    @Test(timeout = 60_000)
    fun `daemon errors mixed with json lines never crash the parser`() {
        DockerMatrix.requireMatrix()
        connect().use { ssh ->
            requireSocket(ssh)
            // a bad flag: the CLI prints usage to stderr, which the tab merges
            val raw = DockerMatrix.exec(
                ssh,
                "docker ps --format '{{json .}}' --no-such-flag 2>&1; ${DockerParser.LIST_COMMAND}",
                30_000
            )
            assertTrue(raw.contains("unknown flag", true))
            val parsed = DockerParser.parse(raw)
            assertTrue(
                "real rows must survive the error preamble",
                parsed.any { it.names == DockerMatrix.CONTAINER_NAME }
            )
        }
    }
}
