package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputLineAssemblerTest {

    private fun assemble(vararg chunks: ByteArray): MutableList<String> {
        val lines = mutableListOf<String>()
        val asm = InputLineAssembler { lines.add(it) }
        for (c in chunks) asm.feed(c)
        return lines
    }

    private fun b(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `CR flushes the typed line`() {
        val lines = assemble(b("ls -la"), byteArrayOf(0x0D))
        assertEquals(listOf("ls -la"), lines)
    }

    @Test
    fun `bare LF also flushes`() {
        val lines = assemble(byteArrayOf(0x0A))
        assertTrue(lines.isEmpty()) // empty buffer flush emits nothing
        val lines2 = assemble(b("cd /tmp"), byteArrayOf(0x0A))
        assertEquals(listOf("cd /tmp"), lines2)
    }

    @Test
    fun `control-only input is dropped`() {
        val lines = assemble(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x0D))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `backspace edits the buffer`() {
        val lines = assemble(b("lss"), byteArrayOf(0x08), byteArrayOf(0x0D))
        assertEquals(listOf("ls"), lines)
        // delete then retype
        val retype = assemble(b("ls"), byteArrayOf(0x08), b("s"), byteArrayOf(0x0D))
        assertEquals(listOf("ls"), retype)
    }

    @Test
    fun `DEL 0x7f also deletes`() {
        val lines = assemble(b("ab\u007f"), byteArrayOf(0x0D))
        assertEquals(listOf("a"), lines)
    }

    @Test
    fun `arrow-edited line is dropped not recorded wrong`() {
        // type "ls", arrow-up (recall last command), edit, enter -> dropped
        val lines = assemble(b("cd /va"), b("\u001b[A"), b("r"), byteArrayOf(0x0D))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `home end delete keys drop the line`() {
        assertTrue(assemble(b("echo hi"), b("\u001b[H"), b("X"), byteArrayOf(0x0D)).isEmpty())
        assertTrue(assemble(b("echo hi"), b("\u001b[F"), b("X"), byteArrayOf(0x0D)).isEmpty())
        assertTrue(assemble(b("echo hi"), b("\u001b[3~"), byteArrayOf(0x0D)).isEmpty())
    }

    @Test
    fun `ctrl-U clears buffer and retyped line records`() {
        val lines = assemble(b("wrong command"), byteArrayOf(0x15), b("right"), byteArrayOf(0x0D))
        assertEquals(listOf("right"), lines)
    }

    @Test
    fun `ctrl-C discards aborted line and next line records`() {
        val lines = assemble(b("half typed"), byteArrayOf(0x03), b("next"), byteArrayOf(0x0D))
        assertEquals(listOf("next"), lines)
    }

    @Test
    fun `tab completion marks line untrackable`() {
        val lines = assemble(b("git che"), byteArrayOf(0x09), b("ckout"), byteArrayOf(0x0D))
        assertTrue("tab-completed prefix must not be recorded", lines.isEmpty())
    }

    @Test
    fun `alt-letter escape drops the line`() {
        val lines = assemble(b("echo"), b("\u001bb"), b(" hi"), byteArrayOf(0x0D))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `multi-line paste becomes a single entry`() {
        val paste = BracketedPaste.wrap("echo a\necho b\necho c")
        val lines = assemble(b(paste), byteArrayOf(0x0D))
        assertEquals(listOf("echo a\necho b\necho c"), lines)
    }

    @Test
    fun `paste markers split across feeds still assemble`() {
        val wrapped = BracketedPaste.wrap("one\ntwo")
        val lines = assemble(
            b(wrapped.substring(0, 3)),
            b(wrapped.substring(3, 9)),
            b(wrapped.substring(9)),
            byteArrayOf(0x0D),
        )
        assertEquals(listOf("one\ntwo"), lines)
    }

    @Test
    fun `CR inside paste does not flush`() {
        val lines = assemble(b(BracketedPaste.PASTE_START), b("a\r\nb"), b(BracketedPaste.PASTE_END), byteArrayOf(0x0D))
        assertEquals(listOf("a\nb"), lines)
    }

    @Test
    fun `bare LF inside paste stays one newline`() {
        val lines = assemble(b(BracketedPaste.PASTE_START), b("a\n\nb"), b(BracketedPaste.PASTE_END), byteArrayOf(0x0D))
        assertEquals(listOf("a\n\nb"), lines)
    }

    @Test
    fun `esc inside paste content is preserved`() {
        val blob = BracketedPaste.wrap("echo \u001b[31mred\u001b[0m")
        val lines = assemble(b(blob), byteArrayOf(0x0D))
        assertEquals(listOf("echo \u001b[31mred\u001b[0m"), lines)
    }

    @Test
    fun `unicode survives the assembler`() {
        val lines = assemble("日本語コマンド".toByteArray(Charsets.UTF_8), byteArrayOf(0x0D))
        assertEquals(listOf("日本語コマンド"), lines)
    }

    @Test
    fun `lines are truncated to 4096 chars`() {
        val long = "x".repeat(5000)
        val lines = assemble(b(long), byteArrayOf(0x0D))
        assertEquals(1, lines.size)
        assertEquals(InputLineAssembler.MAX_LINE, lines[0].length)
    }

    @Test
    fun `flush resets drop flag after one dropped line`() {
        val lines = assemble(
            b("dropped"), b("\u001b[A"), byteArrayOf(0x0D), // dropped
            b("kept"), byteArrayOf(0x0D),                   // recorded
        )
        assertEquals(listOf("kept"), lines)
    }

    @Test
    fun `backspace over astral char deletes the whole surrogate pair`() {
        val lines = assemble("a😀b".toByteArray(Charsets.UTF_8), byteArrayOf(0x08), byteArrayOf(0x0D))
        assertEquals(listOf("a😀"), lines)
        // delete twice removes the full emoji + preceding char remains intact
        val lines2 = assemble("😀x".toByteArray(Charsets.UTF_8), byteArrayOf(0x08, 0x08), b("y"), byteArrayOf(0x0D))
        assertEquals(listOf("y"), lines2)
        // recorded entry never contains a lone surrogate
        for (c in lines[0]) assertFalse("lone surrogate in recorded line", Character.isSurrogate(c) &&
            !("\uD83D\uDE00".contains(c)))
    }

    @Test
    fun `empty CR emits nothing`() {
        assertTrue(assemble(byteArrayOf(0x0D), byteArrayOf(0x0D)).isEmpty())
    }
}
