package at.least.conch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CrashReporting's DataStore wiring (the phase-5 swap from SharedPreferences).
 * CrashReportingLifecycleTest covers the pure gate matrix; this covers the
 * actual isEnabled/setEnabled plumbing through SettingsStore. Runs without a
 * compiled DSN (foss debug): the SDK must never initialize, and the toggle
 * must still persist — the user's opt-in survives into a DSN-carrying build.
 */
@RunWith(AndroidJUnit4::class)
class CrashReportingWiringRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        SettingsStore.reset()
        context = ApplicationProvider.getApplicationContext()
        CrashReporting.init(context)
    }

    @Test
    fun `toggle persists through the datastore even without a dsn`() {
        assertFalse(CrashReporting.isEnabled())
        CrashReporting.setEnabled(true)
        assertTrue(SettingsStore.crashReportsEnabled(context))
        CrashReporting.setEnabled(false)
        assertFalse(SettingsStore.crashReportsEnabled(context))
    }

    @Test
    fun `enabled toggle alone stays inert without a compiled dsn`() {
        CrashReporting.setEnabled(true)
        assertFalse(CrashReporting.isEnabled())   // isAvailable() gates the SDK
    }

    @Test
    fun `init on a fresh install reports nothing`() {
        assertFalse(CrashReporting.isEnabled())
    }
}
