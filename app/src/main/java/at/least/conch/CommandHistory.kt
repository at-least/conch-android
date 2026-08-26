package at.least.conch

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One executed command line, scoped to the host it was typed on. */
data class HistoryEntry(
    val hostId: String,
    val text: String,
    val ts: Long,
)

/** Wire shape of one history entry inside command_history.bin. */
@Serializable
private data class HistoryWire(
    val hostId: String = "",
    val text: String = "",
    val ts: Long = 0L,
) {
    companion object {
        fun from(e: HistoryEntry) = HistoryWire(e.hostId, e.text, e.ts)
    }
}

/**
 * AES-256-GCM envelope for the command-history file. The 32-byte key never
 * lives on disk unencrypted: it is generated once and stored in
 * [SecretsStore] (hardware keystore), the same pattern as host passwords.
 * File format: iv[12] || ciphertext+tag. Pure javax.crypto — host-testable.
 */
object HistoryCrypto {

    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun encrypt(key: ByteArray, plaintext: String): ByteArray {
        require(key.size == 32) { "history key must be 256-bit" }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ct
    }

    fun decrypt(key: ByteArray, blob: ByteArray): String? {
        if (blob.size < 12 + 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Per-host command history persisted as encrypted JSON:
 *   filesDir/command_history.bin  = HistoryCrypto.encrypt(key, historyToJson(entries))
 *
 * The store logic is split so plain JUnit can exercise it: persistence and
 * the key are injected as functions/parameters; the Android constructor
 * binds them to the real file and the keystore-guarded key.
 */
class CommandHistoryStore(
    private val readFile: () -> ByteArray?,
    private val writeFile: (ByteArray) -> Unit,
    private val key: ByteArray?,
) {

    constructor(context: Context) : this(
        readFile = {
            File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.takeIf { it.length() > 0 }?.readBytes()
        },
        writeFile = { bytes -> AtomicFile.write(File(context.filesDir, FILE_NAME), bytes) },
        key = loadOrCreateKey(),
    )

    fun load(): MutableList<HistoryEntry> = synchronized(FILE_LOCK) {
        val k = key ?: return mutableListOf()
        val blob = readFile() ?: return mutableListOf()
        val json = HistoryCrypto.decrypt(k, blob) ?: return mutableListOf()
        historyFromJson(json).toMutableList()
    }

    fun record(hostId: String, text: String, ts: Long = System.currentTimeMillis()) {
        synchronized(FILE_LOCK) {
            val k = key ?: return
            val next = append(load(), hostId, text, ts)
            writeFile(HistoryCrypto.encrypt(k, historyToJson(next)))
        }
    }

    fun search(hostId: String, q: String): List<HistoryEntry> = synchronized(FILE_LOCK) {
        search(load(), hostId, q)
    }

    fun clear() = synchronized(FILE_LOCK) { writeFile(ByteArray(0)) }

    /**
     * Decides what to do with the stored history key:
     * - [KeyPlan.USE] — the stored value decodes to a valid 256-bit key;
     * - [KeyPlan.REGENERATE] — no key yet, or the stored value is corrupt
     *   (safe to replace: history is already unreadable);
     * - [KeyPlan.DISABLED] — the alias exists but reading it failed
     *   (transient keystore trouble). Regenerating would PERMANENTLY
     *   brick the existing history file, so the store goes read-only for
     *   this session instead.
     */
    enum class KeyPlan { USE, REGENERATE, DISABLED }

    companion object {
        const val MAX_ENTRIES = 1000
        const val KEY_ALIAS = "history-key"
        private const val FILE_NAME = "command_history.bin"

        /** Serializes file read-modify-write cycles ACROSS store instances
         *  (a terminal session recording while Settings clears, etc.). */
        private val FILE_LOCK = Any()

        /** True if [text] has at least one printable, non-whitespace character. */
        fun isRecordable(text: String): Boolean =
            text.any { it.code >= 0x20 && it.code != 0x7F && !it.isWhitespace() }

        /**
         * Pure append: skips blank/control-only lines and consecutive
         * duplicates, caps the ring at [MAX_ENTRIES] (oldest dropped).
         */
        fun append(entries: List<HistoryEntry>, hostId: String, text: String, ts: Long): List<HistoryEntry> {
            if (!isRecordable(text)) return entries
            val lastForHost = entries.lastOrNull { it.hostId == hostId }
            if (lastForHost != null && lastForHost.text == text) return entries
            val out = ArrayList(entries)
            out.add(HistoryEntry(hostId, text, ts))
            if (out.size > MAX_ENTRIES) out.subList(0, out.size - MAX_ENTRIES).clear()
            return out
        }

        /** Pure search: host-scoped, case-insensitive substring, newest first. */
        fun search(entries: List<HistoryEntry>, hostId: String, q: String): List<HistoryEntry> {
            val needle = q.trim().lowercase()
            return entries
                .filter { it.hostId == hostId && (needle.isEmpty() || it.text.lowercase().contains(needle)) }
                .asReversed()
        }

        fun historyToJson(entries: List<HistoryEntry>): String =
            ConchJson.encodeToString(
                ListSerializer(HistoryWire.serializer()),
                entries.map { HistoryWire.from(it) },
            )

        fun historyFromJson(json: String): List<HistoryEntry> {
            return try {
                ConchJson.decodeFromString(ListSerializer(HistoryWire.serializer()), json)
                    .map { HistoryEntry(it.hostId, it.text, it.ts) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /**
         * Decides what to do with the stored history key (see [KeyPlan]).
         */
        fun planForKey(stored: String?, aliasPresent: Boolean): KeyPlan = when {
            stored != null && decodeKey(stored) != null -> KeyPlan.USE
            stored != null -> KeyPlan.REGENERATE
            aliasPresent -> KeyPlan.DISABLED
            else -> KeyPlan.REGENERATE
        }

        fun decodeKey(stored: String): ByteArray? = try {
            java.util.Base64.getDecoder().decode(stored).takeIf { it.size == 32 }
        } catch (_: Exception) {
            null
        }

        fun encodeKey(key: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(key)

        private fun loadOrCreateKey(): ByteArray? {
            val stored = SecretsStore.get(KEY_ALIAS)
            return when (planForKey(stored, SecretsStore.contains(KEY_ALIAS))) {
                KeyPlan.USE -> decodeKey(stored!!)
                KeyPlan.REGENERATE -> HistoryCrypto.newKey().also {
                    SecretsStore.put(KEY_ALIAS, encodeKey(it))
                }
                KeyPlan.DISABLED -> null
            }
        }
    }
}

/**
 * Reassembles typed command lines from the raw input byte stream (IME,
 * hardware keys, extra-keys row all funnel through `TerminalView.onData`).
 *
 * Fidelity rules (conservative — never record a WRONG line):
 *  - printable UTF-8 accumulates; 0x08/0x7F delete the last character;
 *  - CR (and bare LF outside a paste) flushes the line to [onLine];
 *  - any cursor-motion CSI (arrows, Home/End, DEL, ...) or Tab completion
 *    marks the line edited-beyond-tracking: it is dropped on flush, because
 *    up-arrow-recalled commands are already in history and tab-completed
 *    text never reaches the input stream (the remote echoes it);
 *  - Ctrl-U clears the buffer like readline does — the retyped line then
 *    records correctly; Ctrl-C discards the aborted line;
 *  - between `ESC[200~`/`ESC[201~` (bracketed paste markers, constants from
 *    [BracketedPaste]) newlines do not split: one entry per paste, capped
 *    at [MAX_LINE] characters.
 */
class InputLineAssembler(private val onLine: (String) -> Unit) {

    private val buf = StringBuilder()
    private var dropPending = false
    private var inPaste = false
    private var pendingCr = false

    // escape-sequence tracking
    private var esc = false
    private var csi = false
    private val csiBuf = StringBuilder()
    private var pasteEsc = false
    private var pasteCsi = false
    private val pasteCsiBuf = StringBuilder()

    // incremental UTF-8 decoding
    private var utfAccum = 0
    private var utfNeed = 0

    fun feed(data: ByteArray) {
        for (b in data) feedByte(b.toInt() and 0xFF)
    }

    private fun feedByte(b: Int) {
        if (utfNeed > 0) {
            if (b and 0xC0 == 0x80) {
                utfAccum = (utfAccum shl 6) or (b and 0x3F)
                if (--utfNeed == 0) appendPrintableCodePoint(utfAccum)
            } else {
                utfNeed = 0
                feedByte(b) // malformed sequence: reprocess byte fresh
            }
            return
        }
        if (b >= 0x80) {
            when {
                b and 0xE0 == 0xC0 -> {
                    utfAccum = b and 0x1F
                    utfNeed = 1
                }
                b and 0xF0 == 0xE0 -> {
                    utfAccum = b and 0x0F
                    utfNeed = 2
                }
                b and 0xF8 == 0xF0 -> {
                    utfAccum = b and 0x07
                    utfNeed = 3
                }
                else -> Unit // invalid lead byte, drop
            }
            return
        }
        if (csi) {
            handleCsiByte(b)
            return
        }
        if (esc) {
            handleEscByte(b)
            return
        }
        if (pasteCsi) {
            handlePasteCsiByte(b)
            return
        }
        if (pasteEsc) {
            handlePasteEscByte(b)
            return
        }
        when (b) {
            0x1B -> {
                if (inPaste) pasteEsc = true else esc = true
            }
            0x0D, 0x0A ->
                if (inPaste) {
                    // normalize newlines inside a paste blob: CRLF -> one LF
                    if (b == 0x0D) {
                        appendPrintableChar('\n')
                        pendingCr = true
                    } else {
                        if (!pendingCr) appendPrintableChar('\n')
                        pendingCr = false
                    }
                } else {
                    flush()
                }
            0x08, 0x7F ->
                if (inPaste) appendPrintableChar(b.toChar()) else deleteLast()
            0x15 -> if (inPaste) appendPrintableChar(b.toChar()) else buf.clear() // Ctrl-U: readline kill line
            0x03 -> if (inPaste) {
                appendPrintableChar(b.toChar())
            } else {
                buf.clear()
                dropPending = false
            } // Ctrl-C: abort, start fresh
            0x09 -> if (inPaste) {
                appendPrintableChar(
                    b.toChar()
                )
            } else {
                dropPending = true // Tab: completion, record is a prefix
            }
            else -> if (b < 0x20) {
                if (inPaste) appendPrintableChar(b.toChar())
            } else {
                appendPrintableChar(b.toChar())
            }
        }
    }

    private fun handleEscByte(b: Int) {
        if (b == 0x1B) return // double ESC, stay armed
        esc = false
        if (b == '['.code) {
            csi = true
            csiBuf.setLength(0)
        } else {
            // Alt+letter and other escapes mean line editing: drop the line
            dropPending = true
        }
    }

    private fun handleCsiByte(b: Int) {
        when {
            b == 0x1B -> {
                csi = false
                esc = true
            }
            b in 0x30..0x3F -> csiBuf.append(b.toChar()) // params + private markers
            b in 0x40..0x7E -> {
                csi = false
                val seq = "\u001b[" + csiBuf + b.toChar()
                when (seq) {
                    BracketedPaste.PASTE_START -> inPaste = true
                    BracketedPaste.PASTE_END -> inPaste = false
                    else -> dropPending = true // cursor motion / function key
                }
            }
            else -> {
                csi = false
                dropPending = true
            } // control char mid-sequence
        }
    }

    private fun handlePasteEscByte(b: Int) {
        if (b == 0x1B) return
        pasteEsc = false
        if (b == '['.code) {
            pasteCsi = true
            pasteCsiBuf.setLength(0)
        } else {
            appendPrintableChar('\u001b')
            appendPrintableChar(b.toChar())
        }
    }

    private fun handlePasteCsiByte(b: Int) {
        when {
            b == 0x1B -> {
                pasteCsi = false
                appendPrintableChar('\u001b')
                pasteEsc = true
            }
            b in 0x30..0x3F -> pasteCsiBuf.append(b.toChar())
            b in 0x40..0x7E -> {
                pasteCsi = false
                val seq = "\u001b[" + pasteCsiBuf + b.toChar()
                if (seq == BracketedPaste.PASTE_END) {
                    inPaste = false
                } else {
                    for (ch in seq) appendPrintableChar(ch)
                }
            }
            else -> {
                pasteCsi = false
                appendPrintableChar('\u001b')
            }
        }
    }

    private fun deleteLast() {
        // delete a full code point: an astral char is a surrogate PAIR
        if (buf.isEmpty()) return
        val dropTwo = Character.isLowSurrogate(buf[buf.length - 1]) && buf.length >= 2
        buf.setLength(buf.length - (if (dropTwo) 2 else 1))
    }

    private fun appendPrintableChar(c: Char) {
        pendingCr = false
        if (buf.length < MAX_LINE) buf.append(c)
    }

    private fun appendPrintableCodePoint(cp: Int) {
        for (c in Character.toChars(cp)) appendPrintableChar(c)
    }

    private fun flush() {
        val line = buf.toString()
        buf.setLength(0)
        val drop = dropPending
        dropPending = false
        if (drop) return
        if (line.isNotEmpty()) onLine(line)
    }

    companion object {
        const val MAX_LINE = 4096
    }
}
