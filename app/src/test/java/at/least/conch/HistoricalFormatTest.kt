package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Historical on-disk format compatibility (Aegis VaultTest pattern):
 * files written by OLDER app versions are checked in as fixtures under
 * src/test/resources/format and must keep decoding with today's semantics.
 * Each fixture also proves the re-encode invariant (Aegis
 * "save to and load from the new format still passes"): decode -> HostWire
 * -> encode -> decode is semantically identical.
 */
class HistoricalFormatTest {

    private fun fixture(name: String): String = testResource("/format/$name").decodeToString()

    @Test
    fun `v0 hosts file with plaintext passwords decodes and keeps legacy fields out of the model`() {
        val hosts = ConchJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(HostWire.serializer()),
            fixture("hosts_v0_legacy_password.json"),
        ).map { it.toHost() }

        assertEquals(2, hosts.size)
        val h1 = hosts.first { it.id == "h1" }
        assertEquals("PASSWORD", h1.authType)
        assertEquals(null, h1.keyId)
        // fields that did not exist yet decode to their PRE-FEATURE fallbacks
        assertFalse(h1.tmuxAutoAttach)
        assertEquals(0, h1.socksPort)
        assertTrue(h1.tunnels.isEmpty())
        // the legacy password never rides on the Host object
        val h1wire = hosts.let { HostWire.from(h1) }
        assertEquals(null, h1wire.password)
    }

    @Test
    fun `pre-socksPort hosts file decodes tunnels and re-encode round-trips semantically`() {
        val wires = ConchJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(HostWire.serializer()),
            fixture("hosts_pre_socks.json"),
        )
        val hosts = wires.map { it.toHost() }

        val h = hosts.single()
        assertEquals("KEY", h.authType)
        assertEquals(false, h.tmuxAutoAttach) // explicit false survives
        assertEquals(listOf(Tunnel(8080, "db.internal", 5432)), h.tunnels)

        // Aegis invariant: encode today's model, decode again — same semantics
        val reDecoded = ConchJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(HostWire.serializer()),
            ConchJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(HostWire.serializer()), wires),
        ).map { it.toHost() }
        assertEquals(listOf(h), reDecoded)
    }

    @Test
    fun `real docker ps output fixture parses with unicode names and junk lines`() {
        val cs = DockerParser.parse(fixture("docker_ps_real.txt"))
        assertEquals(2, cs.size)
        assertEquals("web-生産", cs[0].names)
        assertEquals("running", cs[0].state)
        assertEquals("exited", cs[1].state)
    }
}
