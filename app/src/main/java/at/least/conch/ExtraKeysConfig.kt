package at.least.conch

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * User-configurable extra-keys row. Persisted as a JSON array of key ids in
 * SharedPreferences so the layout survives sessions (Termux users' top gripe:
 * "button bar can't retain state").
 */
object ExtraKeysConfig {

    data class KeyDef(val id: String, val label: String)

    /** All selectable keys and what they emit. */
    val ALL: List<KeyDef> = listOf(
        KeyDef("CTRL", "CTRL"),
        KeyDef("ESC", "ESC"),
        KeyDef("TAB", "TAB"),
        KeyDef("UP", "↑"),
        KeyDef("DOWN", "↓"),
        KeyDef("LEFT", "←"),
        KeyDef("RIGHT", "→"),
        KeyDef("PGUP", "PGUP"),
        KeyDef("PGDN", "PGDN"),
        KeyDef("HOME", "HOME"),
        KeyDef("END", "END"),
        KeyDef("DEL", "DEL"),
        KeyDef("SLASH", "/"),
        KeyDef("PIPE", "|"),
        KeyDef("DASH", "-"),
        KeyDef("TILDE", "~"),
        KeyDef("QUOTE", "\""),
        KeyDef("EQUAL", "="),
        KeyDef("DOLLAR", "$"),
    )

    internal val DEFAULT = listOf("CTRL", "ESC", "TAB", "LEFT", "UP", "DOWN", "RIGHT", "SLASH", "PIPE", "DASH")

    fun load(context: Context): List<String> = parse(SettingsStore.extraKeysJson(context))

    /** Pure: raw persisted JSON -> ids. Unknown ids dropped; null/corrupt/empty -> default. */
    fun parse(raw: String?): List<String> {
        if (raw == null) return DEFAULT
        val ids = runCatching {
            ConchJson.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
        // drop unknown ids, keep order
        val valid = ids.filter { id -> ALL.any { it.id == id } }
        return valid.ifEmpty { DEFAULT }
    }

    fun save(context: Context, ids: List<String>) {
        SettingsStore.setExtraKeysJson(context, serialize(ids))
    }

    /** Pure: ids -> persisted JSON array string. */
    fun serialize(ids: List<String>): String =
        ConchJson.encodeToString(ListSerializer(String.serializer()), ids)

    fun labelFor(id: String): String = ALL.firstOrNull { it.id == id }?.label ?: id

    /** Emits the key: returns bytes to send, or null for CTRL (state toggle). */
    fun bytesFor(id: String): ByteArray? = when (id) {
        "CTRL" -> null   // handled as a toggle by the terminal
        "ESC" -> byteArrayOf(0x1B)
        "TAB" -> byteArrayOf(0x09)
        "UP" -> "\u001b[A".toByteArray()
        "DOWN" -> "\u001b[B".toByteArray()
        "RIGHT" -> "\u001b[C".toByteArray()
        "LEFT" -> "\u001b[D".toByteArray()
        "PGUP" -> "\u001b[5~".toByteArray()
        "PGDN" -> "\u001b[6~".toByteArray()
        "HOME" -> "\u001b[H".toByteArray()
        "END" -> "\u001b[F".toByteArray()
        "DEL" -> "\u001b[3~".toByteArray()
        "SLASH" -> "/".toByteArray()
        "PIPE" -> "|".toByteArray()
        "DASH" -> "-".toByteArray()
        "TILDE" -> "~".toByteArray()
        "QUOTE" -> "\"".toByteArray()
        "EQUAL" -> "=".toByteArray()
        "DOLLAR" -> "$".toByteArray()
        else -> null
    }
}
