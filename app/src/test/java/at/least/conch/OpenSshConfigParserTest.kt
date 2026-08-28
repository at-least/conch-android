package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSshConfigParserTest {

    @Test
    fun `tabs and key=value separators are the same as spaces`() {
        val config = "Host=tabbed\n\tHostName\ttab.example.com\n\tUser=carol\n\tPort = 2200\n"
        val hosts = OpenSshConfigParser.parse(config)
        assertEquals(1, hosts.size)
        assertEquals("tabbed", hosts[0].alias)
        assertEquals("tab.example.com", hosts[0].hostname)
        assertEquals("carol", hosts[0].user)
        assertEquals(2200, hosts[0].port)
    }

    @Test
    fun `only the first separator splits so option values keep their equals signs`() {
        val config = """
            Host jumpy
              ProxyJump bastion
              ProxyCommand ssh -o ProxyCommand=none -W %h:%p gw
              IdentityFile=~/.ssh/id_ed25519
        """.trimIndent()
        val h = OpenSshConfigParser.parse(config).single()
        assertEquals("bastion", h.proxyJump)
        assertEquals("~/.ssh/id_ed25519", h.identityFile)
    }

    @Test
    fun `a Match block does not bleed into the preceding Host`() {
        val config = """
            Host web
              HostName web.example.com
            Match host web user root
              HostName root-only.example.com
              Port 2222
            Host db
              HostName db.example.com
        """.trimIndent()
        val hosts = OpenSshConfigParser.parse(config)
        assertEquals(listOf("web", "db"), hosts.map { it.alias })
        assertEquals("web.example.com", hosts[0].hostname)
        assertEquals(22, hosts[0].port)
        assertEquals("db.example.com", hosts[1].hostname)
    }

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
    fun `parses forwardagent yes and ignores anything else`() {
        val config = """
            Host trusted
              HostName a.example.com
              ForwardAgent yes

            Host untrusted
              HostName b.example.com
              forwardagent no

            Host ask
              HostName c.example.com
              ForwardAgent ask
        """.trimIndent()
        val hosts = OpenSshConfigParser.parse(config)
        org.junit.Assert.assertTrue(hosts[0].forwardAgent)
        org.junit.Assert.assertFalse(hosts[1].forwardAgent)
        org.junit.Assert.assertFalse(hosts[2].forwardAgent)
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
    fun `parses proxyjump taking only the first hop`() {
        val config = """
            Host web
              ProxyJump bastion
            Host db
              proxyjump bastion.example.com:2222, second-hop
            Host plain
        """.trimIndent()
        val hosts = OpenSshConfigParser.parse(config)
        assertEquals("bastion", hosts[0].proxyJump)
        assertEquals("bastion.example.com:2222", hosts[1].proxyJump)
        assertEquals("", hosts[2].proxyJump)
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
