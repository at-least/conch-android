package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exec channel interaction — the exact pattern the Docker tab (SessionTabs) uses
 * MonitorTab probe: startSession -> exec -> read all output.
 */
class SshExecInteractionTest {

    private lateinit var dir: File
    private lateinit var server: TestSshd

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-exec").toFile()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
        dir.deleteRecursively()
    }

    private fun connect(execHandler: (String) -> ExecResult): net.schmizz.sshj.SSHClient {
        server = TestSshd(execHandler = execHandler).start()
        return connectTrusted(server, KnownHostsStore(dir))
    }

    @Test(timeout = 30_000)
    fun `exec command and stdout round-trip exactly`() {
        val ssh = connect { cmd -> ExecResult("ran [$cmd] ok\n".toByteArray()) }
        try {
            val s = ssh.startSession()
            val cmd = s.exec("uptime")
            val out = cmd.inputStream.readBytes().decodeToString()
            cmd.close()
            s.close()
            assertEquals("ran [uptime] ok\n", out)
            assertEquals(listOf("uptime"), server.recordedCommands.toList())
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 30_000)
    fun `exact docker command string with braces survives the wire`() {
        val dockerCmd = "docker ps -a --format '{{json .}}'"
        val ssh = connect { ExecResult("ignored\n".toByteArray()) }
        try {
            val s = ssh.startSession()
            val cmd = s.exec(dockerCmd)
            cmd.inputStream.readBytes()
            cmd.close()
            s.close()
            assertEquals(listOf(dockerCmd), server.recordedCommands.toList())
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 30_000)
    fun `docker json output flows into DockerParser`() {
        val line = """{"ID":"abc123def","Names":"web-1","Image":"nginx:1.27","State":"running","Status":"Up 3 hours"}"""
        val ssh = connect { ExecResult("$line\n".toByteArray()) }
        try {
            val s = ssh.startSession()
            val cmd = s.exec("docker ps -a --format '{{json .}}'")
            val out = cmd.inputStream.readBytes().decodeToString()
            cmd.close()
            s.close()
            val containers = DockerParser.parse(out)
            assertEquals(1, containers.size)
            assertEquals("abc123def", containers.first().id)
            assertEquals("web-1", containers.first().names)
            assertEquals("nginx:1.27", containers.first().image)
            assertEquals("running", containers.first().state)
            assertEquals("Up 3 hours", containers.first().status)
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 30_000)
    fun `exec exit status is propagated`() {
        val ssh = connect { ExecResult("".toByteArray(), exit = 42) }
        try {
            val s = ssh.startSession()
            val cmd = s.exec("exit 42")
            cmd.inputStream.readBytes()
            cmd.close()
            s.close()
            assertNotNull(cmd.exitStatus)
            assertEquals(42, cmd.exitStatus.toInt())
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 30_000)
    fun `stderr arrives on the error stream only`() {
        val ssh = connect { ExecResult("to-stdout\n".toByteArray(), "to-stderr\n".toByteArray()) }
        try {
            val s = ssh.startSession()
            val cmd = s.exec("make")
            val out = cmd.inputStream.readBytes().decodeToString()
            val err = cmd.errorStream.readBytes().decodeToString()
            cmd.close()
            s.close()
            assertEquals("to-stdout\n", out)
            assertEquals("to-stderr\n", err)
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 30_000)
    fun `sequential exec sessions reuse one connection`() {
        val ssh = connect { cmd -> ExecResult("[$cmd]\n".toByteArray()) }
        try {
            for (i in 1..3) {
                val s = ssh.startSession()
                val cmd = s.exec("seq$i")
                assertEquals("[seq$i]\n", cmd.inputStream.readBytes().decodeToString())
                cmd.close()
                s.close()
            }
            assertEquals(listOf("seq1", "seq2", "seq3"), server.recordedCommands.toList())
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 60_000)
    fun `large output survives chunked transfer`() {
        val payload = buildString {
            repeat(20_000) { append("payload line $it with some content to make it big\n") }
        }.toByteArray()
        val ssh = connect { ExecResult(payload) }
        try {
            val s = ssh.startSession()
            val cmd = s.exec("cat big")
            val out = cmd.inputStream.readBytes()
            cmd.close()
            s.close()
            org.junit.Assert.assertArrayEquals(payload, out)
        } finally {
            ssh.disconnect()
        }
    }
}
