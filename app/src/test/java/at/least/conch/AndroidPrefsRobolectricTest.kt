package at.least.conch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Robolectric coverage for the SharedPreferences / filesDir paths that
 * plain-JVM stubbing (isReturnDefaultValues) cannot reach: ExtraKeysConfig
 * persistence, AppLock's settings prefs, HostStore's on-disk hosts.json,
 * and KeyManager's keys.json decode shape (the encode side is exercised
 * once the serialization swap introduces a pure codec).
 */
@RunWith(AndroidJUnit4::class)
class AndroidPrefsRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        SettingsStore.reset()
        context = ApplicationProvider.getApplicationContext()
    }

    // ---------------------------------------------------------- extra keys

    @Test
    fun `extra keys save then load round-trips through real SharedPreferences`() {
        val ids = listOf("CTRL", "TAB", "PGUP", "DOLLAR")
        ExtraKeysConfig.save(context, ids)
        assertEquals(ids, ExtraKeysConfig.load(context))
    }

    @Test
    fun `extra keys default layout loads on a fresh install`() {
        assertEquals(ExtraKeysConfig.DEFAULT, ExtraKeysConfig.load(context))
    }

    @Test
    fun `extra keys corrupt persisted value falls back to default`() {
        SettingsStore.setExtraKeysJson(context, "{{not json")
        assertEquals(ExtraKeysConfig.DEFAULT, ExtraKeysConfig.load(context))
    }

    // ------------------------------------------------------------- datastore

    @Test
    fun `legacy conchapp_settings prefs migrate verbatim into the datastore`() {
        context.getSharedPreferences("conchapp_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("keepScreenOn", true)
            .putBoolean("commandHistory", false)
            .putBoolean("appLockEnabled", true)
            .putBoolean("crashReportsEnabled", true)
            .putString("terminalTheme", "Dracula")
            .putString("extraKeys", """["ESC","TAB"]""")
            .commit()

        assertEquals(true, SettingsStore.keepScreenOn(context))
        assertEquals(false, SettingsStore.commandHistory(context))
        assertEquals(true, SettingsStore.appLockEnabled(context))
        assertEquals(true, SettingsStore.crashReportsEnabled(context))
        assertEquals("Dracula", SettingsStore.terminalTheme(context))
        assertEquals("""["ESC","TAB"]""", SettingsStore.extraKeysJson(context))
        // migrated keys are re-read consistently after the prefs file is gone
        assertEquals(true, SettingsStore.keepScreenOn(context))
    }

    @Test
    fun `settings writes persist and defaults hold on a fresh install`() {
        assertEquals(false, SettingsStore.keepScreenOn(context))
        assertEquals(true, SettingsStore.commandHistory(context))
        assertEquals(false, SettingsStore.appLockEnabled(context))
        assertEquals(null, SettingsStore.terminalTheme(context))
        SettingsStore.setKeepScreenOn(context, true)
        SettingsStore.setTerminalTheme(context, "Nord")
        assertEquals(true, SettingsStore.keepScreenOn(context))
        assertEquals("Nord", SettingsStore.terminalTheme(context))
    }

    // --------------------------------------------------------------- applock

    @Test
    fun `app lock is off by default and toggles through real prefs`() {
        assertFalse(AppLock.isEnabled(context))
        AppLock.setEnabled(context, true)
        assertTrue(AppLock.isEnabled(context))
        AppLock.setEnabled(context, false)
        assertFalse(AppLock.isEnabled(context))
    }

    // ------------------------------------------------------------ host store

    @Test
    fun `host store save then load round-trips through real filesDir`() {
        val host = Host(
            id = "rt-1", alias = "prod", hostname = "prod.example.com", port = 2222,
            username = "alice", authType = Host.AUTH_KEY, keyId = "k1",
            fontSizeSp = 18f, keepAlive = false, tmuxAutoAttach = true, socksPort = 1080,
        )
        host.tunnels.add(Tunnel(8080, "db.internal", 5432))
        HostStore(context).save(listOf(host))

        val loaded = HostStore(context).load()
        assertEquals(1, loaded.size)
        assertEquals(host, loaded[0])
    }

    @Test
    fun `host store pre-written hostsjson decodes with the golden field names`() {
        File(context.filesDir, "hosts.json").writeText(
            """
            [{"id":"h1","alias":"a","hostname":"example.com","port":2222,"username":"u",
              "authType":"KEY","keyId":"k1","fontSizeSp":18,"keepAlive":false,
              "tmuxAutoAttach":true,"socksPort":0,
              "tunnels":[{"localPort":8080,"host":"db.internal","port":5432}]}]
            """.trimIndent()
        )
        val hosts = HostStore(context).load()
        assertEquals(1, hosts.size)
        val h = hosts[0]
        assertEquals("h1", h.id)
        assertEquals(Host.AUTH_KEY, h.authType)
        assertEquals("k1", h.keyId)
        assertEquals(18f, h.fontSizeSp)
        assertFalse(h.keepAlive)
        assertTrue(h.tmuxAutoAttach)
        assertEquals(listOf(Tunnel(8080, "db.internal", 5432)), h.tunnels)
    }

    @Test
    fun `host store corrupt file degrades to empty list`() {
        File(context.filesDir, "hosts.json").writeText("]]not json[[")
        assertTrue(HostStore(context).load().isEmpty())
    }

    // ------------------------------------------------------------ key manager

    @Test
    fun `key manager list reads pre-written keysjson`() {
        val keysFile = File(File(context.filesDir, "keys"), "keys.json")
        keysFile.parentFile!!.mkdirs()
        keysFile.writeText(
            """
            [{"id":"k1","name":"my-phone","algorithm":"ssh-ed25519","createdAt":1735689600123,
              "publicLine":"ssh-ed25519 AAAA my-phone","fingerprint":"SHA256:xxx"}]
            """.trimIndent()
        )
        val keys = KeyManager(context).list()
        assertEquals(
            listOf(
                SshKeyInfo(
                    id = "k1",
                    name = "my-phone",
                    algorithm = "ssh-ed25519",
                    createdAt = 1735689600123L,
                    publicLine = "ssh-ed25519 AAAA my-phone",
                    fingerprint = "SHA256:xxx",
                )
            ),
            keys,
        )
    }

    @Test
    fun `key manager entry missing a required field degrades the whole list to empty`() {
        // getString() throws inside list()'s try/catch — pinned degrade-to-empty
        val keysFile = File(File(context.filesDir, "keys"), "keys.json")
        keysFile.parentFile!!.mkdirs()
        keysFile.writeText("""[{"id":"k2"}]""")
        assertTrue(KeyManager(context).list().isEmpty())
    }

    @Test
    fun `key manager empty store lists nothing`() {
        assertTrue(KeyManager(context).list().isEmpty())
    }
}
