package at.least.conch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * DataStore file corruption (Now in Android UserPreferencesSerializerTest
 * pattern: corrupt input is a pinned, non-crashing path). A corrupt
 * preferences_pb on disk must degrade to defaults — a settings file a user
 * restored from a partial backup must never crash the app at startup.
 *
 * Seeding happens BEFORE the first SettingsStore access: each Robolectric
 * test method gets a fresh dataDir, so the DataStore created here is the
 * only instance for this file (re-creating after reset() mid-test would
 * trip DataStore's per-file singleton registry).
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreCorruptionRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        SettingsStore.reset()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `corrupt datastore file degrades to defaults instead of crashing`() {
        val dataStoreFile = File(File(context.filesDir, "datastore"), "conch_settings.preferences_pb")
        dataStoreFile.parentFile!!.mkdirs()
        dataStoreFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))

        assertEquals(false, SettingsStore.keepScreenOn(context))
        assertEquals(true, SettingsStore.commandHistory(context))
        // and the store stays usable: the corruption handler replaced the file
        SettingsStore.setKeepScreenOn(context, true)
        assertEquals(true, SettingsStore.keepScreenOn(context))
    }
}
