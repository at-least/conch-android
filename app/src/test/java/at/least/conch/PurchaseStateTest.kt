package at.least.conch

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PurchaseState against a mocked SecretsStore (same mockkObject pattern as
 * HostStoreLegacyMigrationTest — the real store needs the Android Keystore).
 * Pins: the unlock flag lives under "iap:noads" so it cannot be flipped by
 * editing plain files, and reads hit the store at most once per cache fill.
 */
class PurchaseStateTest {

    private val secrets = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        PurchaseState.resetCache()
        secrets.clear()
        mockkObject(SecretsStore)
        every { SecretsStore.get(any()) } answers { secrets[firstArg()] }
        every { SecretsStore.put(any(), any()) } answers { secrets[firstArg()] = secondArg() }
    }

    @After
    fun tearDown() {
        unmockkObject(SecretsStore)
        PurchaseState.resetCache()
    }

    @Test
    fun `default state is not ad-free`() {
        assertFalse(PurchaseState.isAdFree())
    }

    @Test
    fun `repeated reads hit the store once`() {
        PurchaseState.isAdFree()
        PurchaseState.isAdFree()
        PurchaseState.isAdFree()
        verify(exactly = 1) { SecretsStore.get(any()) }
    }

    @Test
    fun `setAdFree persists the flag under the pinned key and caches true`() {
        PurchaseState.setAdFree()

        assertEquals("1", secrets["iap:noads"])
        assertTrue(PurchaseState.isAdFree())
        verify(exactly = 0) { SecretsStore.get(any()) }
    }

    @Test
    fun `a previously persisted flag survives a process restart`() {
        secrets["iap:noads"] = "1"
        // cache already reset in setUp → this read must come from the store
        assertTrue(PurchaseState.isAdFree())
    }
}
