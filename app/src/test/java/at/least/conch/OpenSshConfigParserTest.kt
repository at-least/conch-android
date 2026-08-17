package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

class MonitorParserTest {

    @Test
    fun `cpu usage from two samples`() {
        val a2 = "cpu  100 0 100 800 0 0 0 0 0 0"
        val b2 = "cpu  120 0 120 760 0 0 0 0 0 0"
        val u = MonitorParser.cpuUsage(a2, b2)
        // both sums equal (1000) -> totalDelta coerced to 1, idleDelta coerced to 0 -> 100%
        assertEquals(100.0, u, 0.001)

        val a3 = "cpu  100 0 100 800 0 0 0 0 0 0"
        val b3 = "cpu  200 0 300 1500 0 0 0 0 0 0"
        val u3 = MonitorParser.cpuUsage(a3, b3)
        // total delta = 2000-1000 = 1000, idle delta = 1500-800 = 700, busy = 300 -> 30%
        assertEquals(30.0, u3, 0.001)
    }

    @Test
    fun `full probe output parses`() {
        val out = """
            ---CPU
            cpu  367087 1290 82447 7558321 9243 0 4686 0 0 0
            cpu  367087 1290 82455 7558393 9243 0 4686 0 0 0
            ---MEM
            Mem:        8302436352   4167557120   113049600   104857600  4026531840   3753902080
            Swap:        2147483648           0  2147483648
            ---DISK
            /dev/nvme0n1p2  998242365440 314572800000 68366956544  83% /
            ---LOAD
            0.35 0.42 0.31 3/1200 56789
            ---UP
            86412.55 334456.00
        """.trimIndent()
        val s = MonitorParser.parse(out)!!
        // total delta = 80 (sys +8, idle +72) -> busy 8/80 = 10%
        assertEquals(10.0, s.cpuPercent, 0.001)
        assertEquals(8302436352L, s.memTotalBytes)
        assertEquals(4167557120L, s.memUsedBytes)
        assertEquals(2147483648L, s.swapTotalBytes)
        assertEquals(0L, s.swapUsedBytes)
        assertEquals(998242365440L, s.diskTotalBytes)
        assertEquals(314572800000L, s.diskUsedBytes)
        assertEquals(0.35, s.load1, 0.0001)
        assertEquals(0.42, s.load5, 0.0001)
        assertEquals(0.31, s.load15, 0.0001)
        assertEquals(86412L, s.uptimeSeconds)
    }

    @Test
    fun `missing sections return null`() {
        assertNull(MonitorParser.parse("garbage output\nnothing here"))
        assertNull(MonitorParser.parse("---CPU\ncpu 1 2 3 4"))
    }
}
