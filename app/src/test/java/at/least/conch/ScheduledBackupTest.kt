package at.least.conch

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Account-free sync (improvement-plan 3.3). The scheduling policy is a pure
 * function on purpose — every branch is pinned here against the REAL
 * instance (Robolectric supplies the context; actionFor itself reads no
 * state). The fingerprint is the "did anything change" signal, so it must
 * be stable across calls, sensitive to actual data changes, and —
 * deliberately — insensitive to the encryption's fresh salt/IV, which is
 * why it hashes the plaintext.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledBackupTest {

    private val policy = ScheduledBackup(ApplicationProvider.getApplicationContext())

    @Test
    fun `unconfigured folder never exports`() {
        assertEquals(
            ScheduledBackup.Action.NOT_CONFIGURED,
            policy.actionFor(configured = false, changed = true, sinceLastMs = 365L * 24 * 3600 * 1000),
        )
    }

    @Test
    fun `unchanged data never rewrites the file`() {
        assertEquals(
            ScheduledBackup.Action.SKIP_UNCHANGED,
            policy.actionFor(configured = true, changed = false, sinceLastMs = 365L * 24 * 3600 * 1000),
        )
    }

    @Test
    fun `changed data exports only after the hourly interval`() {
        assertEquals(
            ScheduledBackup.Action.SKIP_TOO_SOON,
            policy.actionFor(configured = true, changed = true, sinceLastMs = 59 * 60 * 1000L),
        )
        assertEquals(
            ScheduledBackup.Action.EXPORT,
            policy.actionFor(configured = true, changed = true, sinceLastMs = 60 * 60 * 1000L),
        )
    }

    @Test
    fun `fingerprint is stable for identical payloads and moves when data changes`() {
        val a = BackupPayload(snippets = listOf(BackupSnippet("s", "a", "b")))
        val sameData = BackupPayload(exportedAt = "2030-01-01T00:00:00Z", snippets = listOf(BackupSnippet("s", "a", "b")))
        val edited = a.copy(hosts = listOf(BackupHost(id = "h1", hostname = "x", username = "u")))
        assertEquals(BackupCodec.fingerprint(a), BackupCodec.fingerprint(sameData))
        assertNotEquals(BackupCodec.fingerprint(a), BackupCodec.fingerprint(edited))
    }

    @Test
    fun `contract constants hourly cap and fixed synced file name`() {
        // sync engines (Syncthing especially) collide badly with rapid
        // rewrites of one file; the fixed name is what makes two devices
        // converge on a single document instead of piling files
        assertEquals(60L * 60 * 1000, ScheduledBackup.MIN_INTERVAL_MS)
        assertEquals("conch-backup.conchbak", ScheduledBackup.FILE_NAME)
    }
}
