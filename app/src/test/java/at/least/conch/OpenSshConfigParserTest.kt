package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSshConfigParserTest {

    @Test
    fun `parses basic host blocks`() {
        val config = """
            # my servers
            Host web
              HostName web.example.com
              User alice
              Port 2222

            Host db
              hostname db.internal
              user bob
        """.trimIndent()
        val hosts = OpenSshConfigParser.parse(config)
        assertEquals(2, hosts.size)
        val web = hosts[0]
        assertEquals("web", web.alias)
        assertEquals("web.example.com", web.hostname)
        assertEquals("alice", web.user)
        assertEquals(2222, web.port)
        val db = hosts[1]
        assertEquals("db.internal", db.hostname)
        assertEquals("bob", db.user)
        assertEquals(22, db.port)
    }

    @Test
    fun `skips wildcard-only and default blocks`() {
        val config = """
            Host *
              ServerAliveInterval 60
              User fallback

            Host *
              Port 22
        """.trimIndent()
        assertEquals(0, OpenSshConfigParser.parse(config).size)
    }

    @Test
    fun `comments and blank lines ignored`() {
        val config = """
            Host alpha # trailing comment
              HostName 10.0.0.1
              # User nobody
        """.trimIndent()
        val hosts = OpenSshConfigParser.parse(config)
        assertEquals(1, hosts.size)
        assertEquals("alpha", hosts[0].alias)
        assertEquals("10.0.0.1", hosts[0].hostname)
        assertEquals("", hosts[0].user)
    }

    @Test
    fun `invalid port falls back to 22`() {
        val config = """
            Host beta
              HostName h
              Port notanumber
        """.trimIndent()
        assertEquals(22, OpenSshConfigParser.parse(config)[0].port)
    }

    @Test
    fun `parses identityfile directives case-insensitively with quotes`() {
        val config = """
            Host web
              IdentityFile ~/.ssh/id_ed25519
            Host db
              identityfile "/path/with spaces/id_rsa"
            Host bare
        """.trimIndent()
        val hosts = OpenSshConfigParser.parse(config)
        assertEquals("~/.ssh/id_ed25519", hosts[0].identityFile)
        assertEquals("/path/with spaces/id_rsa", hosts[1].identityFile)
        assertEquals("", hosts[2].identityFile)
    }

    @Test
    fun `directives before any host block are ignored`() {
        val config = """
            GlobalOption yes
            Host gamma
              HostName g.example
        """.trimIndent()
        val hosts = OpenSshConfigParser.parse(config)
        assertEquals(1, hosts.size)
        assertEquals("gamma", hosts[0].alias)
    }
}

class KnownHostsCodecTest {

    @Test
    fun `host field formatting`() {
        assertEquals("example.com", KnownHostsStore.hostField("example.com", 22))
        assertEquals("[example.com]:2222", KnownHostsStore.hostField("example.com", 2222))
    }

    @Test
    fun `parse entry roundtrip`() {
        val line = "[h.example]:2200 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAdX7FvzO9Hc6cOgND5cRcyArlGYzMC0TPXxhXHzh3Pn"
        val e = KnownHostsStore.parseEntry(line)
        assertNotNull(e)
        assertEquals("[h.example]:2200", e!!.host)
        assertEquals("ssh-ed25519", e.algorithm)
        assertEquals(51, e.blob.size)
    }

    @Test
    fun `rejects malformed lines`() {
        assertNull(KnownHostsStore.parseEntry("only-two-fields"))
        assertNull(KnownHostsStore.parseEntry("host algo !!!notbase64!!!"))
        assertNull(KnownHostsStore.parseEntry(""))
    }
}
