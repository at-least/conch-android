package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockerParserTest {

    @Test
    fun `parses json lines output`() {
        val out = """
            {"Command":"/docker-entrypoint.…","CreatedAt":"2026-08-01 10:00:00 +0000 UTC","ID":"a1b2c3d4e5f6","Image":"nginx:alpine","Labels":"","LocalVolumes":"0","Mounts":"","Names":"web","Networks":"bridge","Ports":"0.0.0.0:8080->80/tcp","RunningFor":"2 weeks ago","Size":"0B","State":"running","Status":"Up 2 weeks"}
            {"Command":"postgres","CreatedAt":"2026-08-10 08:00:00 +0000 UTC","ID":"f6e5d4c3b2a1","Image":"postgres:16","Names":"db","State":"exited","Status":"Exited (0) 3 days ago"}
        """.trimIndent()
        val cs = DockerParser.parse(out)
        assertEquals(2, cs.size)
        assertEquals("web", cs[0].names)
        assertEquals("nginx:alpine", cs[0].image)
        assertEquals("running", cs[0].state)
        assertTrue(cs[0].status.startsWith("Up"))
        assertEquals("exited", cs[1].state)
    }

    @Test
    fun `skips junk and empty lines`() {
        val out = "docker: permission denied\n\n{bad json}\n"
        assertEquals(0, DockerParser.parse(out).size)
    }

    @Test
    fun `docker unavailable error passes through as empty`() {
        assertEquals(0, DockerParser.parse("bash: docker: command not found").size)
    }
}
