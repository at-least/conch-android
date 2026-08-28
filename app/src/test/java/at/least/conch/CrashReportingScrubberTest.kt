package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashReportingScrubberTest {

    @Test
    fun `ip and port are masked`() {
        assertEquals(
            "Connecting to host:port …",
            CrashReporting.Scrubber.scrub("Connecting to 10.0.2.2:2223 …")
        )
        assertEquals(
            "Connection refused at host:port",
            CrashReporting.Scrubber.scrub("Connection refused at 192.168.1.100:22")
        )
    }

    @Test
    fun `ipv6 bracket host form is masked`() {
        assertEquals(
            "connect to [host]:port failed",
            CrashReporting.Scrubber.scrub("connect to [2001:db8::1]:2222 failed")
        )
    }

    @Test
    fun `domain names with port are masked`() {
        assertEquals(
            "Auth fail for host:port",
            CrashReporting.Scrubber.scrub("Auth fail for web.example.com:2222")
        )
    }

    @Test
    fun `plain error text is untouched`() {
        assertEquals(
            "Authentication failed: Exhausted available authentication methods",
            CrashReporting.Scrubber.scrub("Authentication failed: Exhausted available authentication methods")
        )
        assertNull(CrashReporting.Scrubber.scrub(null))
    }

    @Test
    fun `throwable messages are scrubbed including causes`() {
        val cause = IllegalStateException("timeout connecting 172.16.0.5:22")
        val err = RuntimeException("Failed to reach db.internal:5432", cause)
        val scrubbed = CrashReporting.Scrubber.scrubThrowable(err)
        assertEquals("Failed to reach host:port", scrubbed.message)
        assertEquals("timeout connecting host:port", scrubbed.cause?.message)
    }

    @Test
    fun `versions and numbers without dots are not mangled`() {
        assertEquals("TLS 1.2 handshake", CrashReporting.Scrubber.scrub("TLS 1.2 handshake"))
        assertEquals("error code 42", CrashReporting.Scrubber.scrub("error code 42"))
    }

    @Test
    fun `sentry exception values are scrubbed, not just the throwable`() {
        // Sentry converts the throwable into event.exceptions BEFORE
        // beforeSend runs — that list is what gets serialized
        val event = io.sentry.SentryEvent(RuntimeException("Connection refused: prod.example.com:22"))
        event.exceptions = listOf(
            io.sentry.protocol.SentryException().apply {
                type = "ConnectException"
                value = "Connection refused: prod.example.com:22"
            },
        )
        event.message = io.sentry.protocol.Message().apply {
            message = "reaching 10.1.2.3:2222"
            formatted = "reaching 10.1.2.3:2222"
        }
        val out = CrashReporting.scrub(event)!!
        assertEquals("Connection refused: host:port", out.exceptions!!.single().value)
        assertEquals("reaching host:port", out.message!!.message)
        assertEquals("reaching host:port", out.message!!.formatted)
    }
}
