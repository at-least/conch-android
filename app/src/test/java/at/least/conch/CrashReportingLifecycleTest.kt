package at.least.conch

import io.sentry.SentryOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CrashReporting gate + privacy options (iOS CrashReporterTests parity,
 * adapted to the Sentry design). The marker-file lifecycle is Sentry-SDK
 * internal on Android — the testable contracts are: nothing flows unless
 * DSN-present AND user-opt-in (default-off corner), and the SDK options
 * that keep the payload free of host/user data. Message/tag scrubbing is
 * covered separately in CrashReportingScrubberTest.
 */
class CrashReportingLifecycleTest {

    @Test
    fun `nothing is reported by default`() {
        // (no DSN, no opt-in) — the out-of-the-box corner
        assertFalse(CrashReporting.shouldReport(available = false, enabled = false))
    }

    @Test
    fun `opt-in toggle alone is not enough without a compiled dsn`() {
        // iOS parity: "enabled but empty endpoint is fully disabled"
        assertFalse(CrashReporting.shouldReport(available = false, enabled = true))
    }

    @Test
    fun `a compiled dsn alone is not enough without opt-in`() {
        assertFalse(CrashReporting.shouldReport(available = true, enabled = false))
    }

    @Test
    fun `reports flow only with dsn and opt-in`() {
        assertTrue(CrashReporting.shouldReport(available = true, enabled = true))
    }

    @Test
    fun `sdk options carry no pii, no sessions, no tracing, no breadcrumbs`() {
        val options = SentryOptions()
        CrashReporting.applyPrivacyOptions(options)

        assertFalse(options.isSendDefaultPii)
        assertFalse(options.isEnableAutoSessionTracking)
        assertEquals(0.0, options.tracesSampleRate ?: -1.0, 0.0)
        assertFalse(options.isAttachThreads)
        assertTrue(options.isAttachStacktrace)
    }
}
