package at.least.conch

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * App settings, backed by a single Preferences DataStore. The legacy
 * `conchapp_settings` SharedPreferences file is migrated verbatim on first
 * access (SharedPreferencesMigration maps keys by identical name) and then
 * removed — SecretsStore (`conchapp_secrets`, Keystore-wrapped) is NOT part
 * of this migration.
 *
 * Accessors are deliberately synchronous: every consumer was written against
 * SharedPreferences' blocking first read, and the file is a handful of
 * scalars cached in memory after the first load. Blocking scope is kept as
 * small as possible (single first()/edit()).
 */
@Suppress("TooManyFunctions")
object SettingsStore {

    private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keepScreenOn")
    private val KEY_COMMAND_HISTORY = booleanPreferencesKey("commandHistory")
    private val KEY_EXTRA_KEYS = stringPreferencesKey("extraKeys")
    private val KEY_APP_LOCK = booleanPreferencesKey("appLockEnabled")
    private val KEY_CRASH_REPORTS = booleanPreferencesKey("crashReportsEnabled")
    private val KEY_TERMINAL_THEME = stringPreferencesKey("terminalTheme")
    private val KEY_TERMINAL_FONT = stringPreferencesKey("terminalFontFamily")

    @Volatile
    private var store: DataStore<Preferences>? = null

    private fun store(context: Context): DataStore<Preferences> {
        val appContext = context.applicationContext
        return store ?: synchronized(this) {
            store ?: PreferenceDataStoreFactory.create(
                // A corrupt preferences_pb (partial backup restore, full disk
                // mid-write) must degrade to defaults, never crash-loop the
                // app at startup. Pinned by SettingsStoreCorruptionRobolectricTest.
                corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
                    androidx.datastore.preferences.core.emptyPreferences()
                },
                migrations = listOf(
                    androidx.datastore.preferences.SharedPreferencesMigration(
                        appContext, "conchapp_settings"
                    )
                ),
            ) {
                appContext.preferencesDataStoreFile("conch_settings")
            }.also { store = it }
        }
    }

    /** Robolectric only: each test gets a fresh Application/dataDir. */
    fun reset() {
        synchronized(this) {
            store = null
        }
    }

    private fun read(context: Context, key: Preferences.Key<Boolean>, default: Boolean): Boolean =
        runBlocking { store(context).data.first()[key] ?: default }

    private fun readString(context: Context, key: Preferences.Key<String>): String? =
        runBlocking { store(context).data.first()[key] }

    private fun write(context: Context, key: Preferences.Key<Boolean>, value: Boolean) {
        runBlocking { store(context).edit { it[key] = value } }
    }

    private fun writeString(context: Context, key: Preferences.Key<String>, value: String?) {
        runBlocking {
            store(context).edit { prefs ->
                if (value == null) prefs.remove(key) else prefs[key] = value
            }
        }
    }

    // ------------------------------------------------------------- toggles

    fun keepScreenOn(context: Context): Boolean = read(context, KEY_KEEP_SCREEN_ON, false)
    fun setKeepScreenOn(context: Context, on: Boolean) = write(context, KEY_KEEP_SCREEN_ON, on)

    fun commandHistory(context: Context): Boolean = read(context, KEY_COMMAND_HISTORY, true)
    fun setCommandHistory(context: Context, on: Boolean) = write(context, KEY_COMMAND_HISTORY, on)

    fun appLockEnabled(context: Context): Boolean = read(context, KEY_APP_LOCK, AppLock.DEFAULT_ENABLED)
    fun setAppLockEnabled(context: Context, on: Boolean) = write(context, KEY_APP_LOCK, on)

    fun crashReportsEnabled(context: Context): Boolean = read(context, KEY_CRASH_REPORTS, false)
    fun setCrashReportsEnabled(context: Context, on: Boolean) = write(context, KEY_CRASH_REPORTS, on)

    // --------------------------------------------------------------- values

    fun extraKeysJson(context: Context): String? = readString(context, KEY_EXTRA_KEYS)
    fun setExtraKeysJson(context: Context, json: String) = writeString(context, KEY_EXTRA_KEYS, json)

    fun terminalTheme(context: Context): String? = readString(context, KEY_TERMINAL_THEME)
    fun setTerminalTheme(context: Context, name: String) = writeString(context, KEY_TERMINAL_THEME, name)

    /** [TerminalFont.id]; absent = [TerminalFont.DEFAULT]. Same key and values as iOS. */
    fun terminalFontFamily(context: Context): String? = readString(context, KEY_TERMINAL_FONT)
    fun setTerminalFontFamily(context: Context, id: String) = writeString(context, KEY_TERMINAL_FONT, id)
}
