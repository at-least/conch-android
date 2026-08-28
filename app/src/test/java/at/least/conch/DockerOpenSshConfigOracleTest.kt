package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [OpenSshConfigParser] checked against the reference implementation: the
 * same config file is handed to the real `ssh -G -F` in the matrix
 * container, and every field the importer surfaces must agree with what
 * OpenSSH resolves for that alias. Covers the syntax variants users
 * actually paste (tabs, `=`, quoted paths, comments, multi-alias Host
 * lines, Match blocks, multi-hop ProxyJump).
 *
 * Deliberately excluded: `Host *` defaults for imported fields — the
 * parser documents that wildcard blocks are skipped, so a `User` there is
 * a known divergence, not something to paper over here.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerOpenSshConfigOracleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val config = """
        # global defaults the importer ignores. NB: a global ProxyCommand
        # would change ssh -G's per-host proxyjump (a set ProxyCommand
        # suppresses a later ProxyJump), which the importer deliberately does
        # not model — that intentional divergence has its own test below, so
        # it is kept out of this oracle's Host * block.
        Host *
            ServerAliveInterval 30

        Host web
            HostName web.example.com
            User deploy
            Port 2200
            IdentityFile ~/.ssh/web_key
            ForwardAgent yes

        Host bastion
        	HostName=bastion.example.com
        	User = admin   # trailing comment

        Host app app-alias app-*
            HostName 10.0.0.5
            ProxyJump bastion
            IdentityFile "/keys/with space.key"
            ForwardAgent no

        Match user nobody
            User someone-else
            Port 9

        Host db
            HostName db.internal
            ProxyJump bastion,web
            ProxyCommand ssh -o Foo=bar -W %h:%p bastion
    """.trimIndent()

    private data class Resolved(val fields: Map<String, List<String>>) {
        fun one(key: String): String = fields[key]?.first() ?: error("ssh -G printed no '$key': $fields")
    }

    private fun resolve(ssh: net.schmizz.sshj.SSHClient, alias: String): Resolved {
        val out = DockerMatrix.exec(ssh, "ssh -G -F /tmp/conch-oracle.cfg $alias 2>&1")
        assertTrue("ssh -G failed for $alias: $out", !out.contains("Bad configuration", true))
        val map = mutableMapOf<String, MutableList<String>>()
        for (line in out.lines()) {
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            map.getOrPut(line.substring(0, sp)) { mutableListOf() }.add(line.substring(sp + 1).trim())
        }
        return Resolved(map)
    }

    @Test(timeout = 60_000)
    fun `parser agrees with ssh -G for every imported field`() {
        DockerMatrix.requireMatrix()
        val parsed = OpenSshConfigParser.parse(config)
        assertEquals(listOf("web", "bastion", "app", "db"), parsed.map { it.alias })

        DockerMatrix.connect(
            KnownHostsStore(tmp.newFolder()),
            DockerMatrix.PW_AND_KEY_PORT,
            "pwuser",
            password = "conch-pw-1"
        )
            .use { ssh ->
                ssh.newSFTPClient().use { sftp ->
                    val local = tmp.newFile("oracle.cfg").apply { writeText(config + "\n") }
                    sftp.getFileTransfer().upload(local.absolutePath, "/tmp/conch-oracle.cfg")
                }
                for (host in parsed) {
                    val ref = resolve(ssh, host.alias)
                    assertEquals("hostname for ${host.alias}", ref.one("hostname"), host.hostname)
                    assertEquals("port for ${host.alias}", ref.one("port").toInt(), host.port)
                    // unset in the file → parser leaves "" and OpenSSH falls back
                    // to the login user of the invoking account (pwuser here)
                    assertEquals("user for ${host.alias}", ref.one("user"), host.user.ifEmpty { "pwuser" })
                    if (host.identityFile.isNotEmpty()) {
                        // ssh -G expands ~ to the invoking user's home; the
                        // parser keeps the path verbatim, so compare on the
                        // portion after a leading ~/
                        val tail = host.identityFile.removePrefix("~/")
                        assertTrue(
                            "identityfile for ${host.alias}: parser='${host.identityFile}' ssh=${ref.fields["identityfile"]}",
                            ref.fields["identityfile"].orEmpty().any { it.endsWith(tail) },
                        )
                    }
                    // ssh -G prints proxyjump only when set (older builds omit
                    // it entirely); absent or "none" both mean no jump
                    val refJump = ref.fields["proxyjump"]?.firstOrNull() ?: "none"
                    val expectedJump = if (refJump == "none") "" else refJump.substringBefore(',')
                    assertEquals("proxyjump for ${host.alias}", expectedJump, host.proxyJump)
                    assertEquals(
                        "forwardagent for ${host.alias}",
                        ref.one("forwardagent") == "yes",
                        host.forwardAgent,
                    )
                }
                // the Match block did not bleed into "db" (its directives are
                // not applied by ssh either, because the user is not nobody)
                val db = resolve(ssh, "db")
                assertEquals("22", db.one("port"))
                DockerMatrix.exec(ssh, "rm -f /tmp/conch-oracle.cfg")
            }
    }

    /**
     * A documented, intentional divergence from `ssh -G`: OpenSSH is
     * first-match-wins, so a global `Host * ProxyCommand none` is obtained
     * before a per-host `ProxyJump` and suppresses it — `ssh -G host` then
     * prints no proxyjump. conch's importer does NOT model cross-block
     * ProxyCommand/ProxyJump precedence (it ignores `Host *` wildcard blocks
     * entirely, per this class's contract); it keeps the explicit per-host
     * `ProxyJump` the user wrote. This pins that intended behaviour with the
     * container's real `ssh -G` as the oracle for the divergence itself.
     */
    @Test(timeout = 60_000)
    fun `a global ProxyCommand suppresses proxyjump in ssh -G but the importer keeps the explicit hop`() {
        DockerMatrix.requireMatrix()
        val cfg = """
            Host *
                ProxyCommand none

            Host app
                HostName 10.0.0.5
                ProxyJump bastion
        """.trimIndent()
        // the importer keeps the explicit per-host hop
        val app = OpenSshConfigParser.parse(cfg).first { it.alias == "app" }
        assertEquals("bastion", app.proxyJump)

        // real ssh -G, however, drops it because the global ProxyCommand wins
        DockerMatrix.connect(
            KnownHostsStore(tmp.newFolder()),
            DockerMatrix.PW_AND_KEY_PORT,
            "pwuser",
            password = "conch-pw-1",
        ).use { ssh ->
            ssh.newSFTPClient().use { sftp ->
                val local = tmp.newFile("suppress.cfg").apply { writeText(cfg + "\n") }
                sftp.getFileTransfer().upload(local.absolutePath, "/tmp/conch-suppress.cfg")
            }
            val ref = DockerMatrix.exec(ssh, "ssh -G -F /tmp/conch-suppress.cfg app 2>&1")
            val proxyjump = ref.lineSequence()
                .firstOrNull { it.startsWith("proxyjump ") }
                ?.substringAfter(' ')?.trim()
            assertTrue(
                "ssh -G unexpectedly kept proxyjump ('$proxyjump') — the documented divergence no longer holds",
                proxyjump == null || proxyjump == "none",
            )
            DockerMatrix.exec(ssh, "rm -f /tmp/conch-suppress.cfg")
        }
    }
}
