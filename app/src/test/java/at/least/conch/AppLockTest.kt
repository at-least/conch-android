package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppLock grace window + defaults (iOS AppLockTests parity). The pure
 * kernel — withinGrace() — is JVM-tested; the BiometricPrompt itself and
 * the SharedPreferences toggle wiring are instrumented-QA (no Robolectric
 * in this repo).
 */
class AppLockTest {

    @Test
    fun `grace window returns true within 30 seconds of unlock`() {
        val unlockedAt = 1_000_000L
        assertTrue(AppLock.withinGrace(unlockedAt, unlockedAt))
        assertTrue(AppLock.withinGrace(unlockedAt, unlockedAt + 29_999))
    }

    @Test
    fun `grace window returns false beyond 30 seconds`() {
        val unlockedAt = 1_000_000L
        assertFalse(AppLock.withinGrace(unlockedAt, unlockedAt + 30_000))
        assertFalse(AppLock.withinGrace(unlockedAt, unlockedAt + 60_000))
    }

    @Test
    fun `relock state (0) is never within grace`() {
        // onWentToBackground() resets to 0 — the next gate must prompt
        assertFalse(AppLock.withinGrace(0L, 10_000L))
        assertFalse(AppLock.withinGrace(0L, System.currentTimeMillis()))
    }

    @Test
    fun `defaults match iOS - lock off, grace 30 seconds`() {
        assertEquals(false, AppLock.DEFAULT_ENABLED)
        assertEquals(30_000L, AppLock.GRACE_MS)
    }
}
