package at.least.conch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStoreJsonTest {

    @Test
    fun `host json roundtrip preserves all fields`() {
        val host = Host(
            alias = "prod",
            hostname = "example.com",
            port = 2222,
            username = "alice",
            authType = Host.AUTH_KEY,
            keyId = "kid-1",
            fontSizeSp = 18f,
            keepAlive = false,
            tmuxAutoAttach = true,
        )
        host.tunnels.add(Tunnel(8080, "db.internal", 5432))

        val json = HostStore.hostToJson(host)
        val back = HostStore.hostFromJson(json)

        assertEquals(host.id, back.id)
        assertEquals("prod", back.alias)
        assertEquals("example.com", back.hostname)
        assertEquals(2222, back.port)
        assertEquals("alice", back.username)
        assertEquals(Host.AUTH_KEY, back.authType)
        assertEquals("kid-1", back.keyId)
        assertEquals(18f, back.fontSizeSp)
        assertFalse(back.keepAlive)
        assertTrue(back.tmuxAutoAttach)
        assertEquals(1, back.tunnels.size)
        assertEquals(Tunnel(8080, "db.internal", 5432), back.tunnels[0])
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val o = JSONObject().put("id", "x").put("hostname", "h").put("username", "u")
        val host = HostStore.hostFromJson(o)
        assertEquals(22, host.port)
        assertEquals(Host.AUTH_PASSWORD, host.authType)
        assertEquals(null, host.keyId)
        assertEquals(0f, host.fontSizeSp)
        assertTrue(host.keepAlive)
        assertFalse(host.tmuxAutoAttach)
        assertTrue(host.tunnels.isEmpty())
    }

    @Test
    fun `legacy entry with plaintext password still parses`() {
        val arr = JSONArray()
            .put(JSONObject()
                .put("id", "old")
                .put("hostname", "h")
                .put("username", "u")
                .put("password", "secret"))
        val host = HostStore.hostFromJson(arr.getJSONObject(0))
        assertEquals("old", host.id)
        // parsing itself must not throw and must not carry the password on the Host
        assertEquals(Host.AUTH_PASSWORD, host.authType)
    }
}
