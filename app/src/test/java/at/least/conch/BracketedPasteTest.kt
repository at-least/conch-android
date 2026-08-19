package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BracketedPasteTest {

    // ---------------------------------------------------------------- wrap

    @Test
    fun `wrap produces exact ESC 200 tilde markers`() {
        assertEquals("\u001b[200~hi\u001b[201~", BracketedPaste.wrap("hi"))
        assertEquals(
            "\u001b[200~line1\nline2\u001b[201~",
            BracketedPaste.wrap("line1\nline2")
        )
    }

    @Test
    fun `wrap of empty text is just the markers`() {
        assertEquals("\u001b[200~\u001b[201~", BracketedPaste.wrap(""))
    }

    // ------------------------------------------------------------- sanitize

    @Test
    fun `sanitize converts CRLF to single LF`() {
        assertEquals("a\nb", BracketedPaste.sanitize("a\r\nb"))
    }

    @Test
    fun `sanitize converts lone CR to LF`() {
        assertEquals("a\nb", BracketedPaste.sanitize("a\rb"))
    }

    @Test
    fun `sanitize keeps bare LF unchanged`() {
        assertEquals("a\nb", BracketedPaste.sanitize("a\nb"))
    }

    @Test
    fun `sanitize is a no-op without CR`() {
        assertEquals("hello\tworld", BracketedPaste.sanitize("hello\tworld"))
    }

    @Test
    fun `sanitize empty string is empty`() {
        assertEquals("", BracketedPaste.sanitize(""))
    }

    @Test
    fun `sanitize mixed line endings all become LF`() {
        assertEquals("1\n2\n3\n4", BracketedPaste.sanitize("1\r\n2\r3\n4"))
    }

    // ---------------------------------------------------------- looksLikePaste

    @Test
    fun `looksLikePaste truth table`() {
        assertTrue(BracketedPaste.looksLikePaste("a\nb"))
        assertTrue(BracketedPaste.looksLikePaste("a\rb"))
        assertTrue(BracketedPaste.looksLikePaste("a\r\nb"))
        assertFalse(BracketedPaste.looksLikePaste("plain typing"))
        assertFalse(BracketedPaste.looksLikePaste(""))
        assertFalse(BracketedPaste.looksLikePaste("autocorrected word"))
    }
}
