package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Variation-selector width pins (termux-app open issue: "Some emojis with
 * U+FE0F VARIATION SELECTOR-16 render at half the correct width" — the
 * competitor bug that motivated the CONCH PATCH in WcWidth.java).
 *
 * U+FE0F must be zero-width: it folds into the preceding cell instead of
 * advancing the cursor. Before the patch, "❤️" occupied two cells (base +
 * selector) and misaligned everything printed after it.
 */
class VariationSelectorWidthTest {

    private fun newEmu(cols: Int = 20, rows: Int = 4) = TerminalEmulator(cols, rows)

    @Test
    fun `FE0F is zero-width for the width oracle`() {
        assertEquals(0, com.termux.terminal.WcWidth.width(0xFE0F))
        assertEquals(0, com.termux.terminal.WcWidth.width(0xFE0E))
        assertEquals(0, com.termux.terminal.WcWidth.width(0xFE00))
    }

    @Test
    fun `emoji with FE0F advances the cursor one cell not two`() {
        val emu = newEmu()
        emu.feed("❤️") // U+2764 U+FE0F
        assertEquals(1, emu.cursorCol)
    }

    @Test
    fun `text after an FE0F emoji stays aligned`() {
        val emu = newEmu()
        emu.feed("❤️AB")
        // A lands in column 1, B in column 2 — before the patch A was in 2.
        // getRowText drops zero-width cps (documented rowText behavior), so
        // the selector is absent from the extracted text — alignment is the pin.
        assertEquals("❤AB", emu.getRowText(0).trimEnd())
        assertEquals(3, emu.cursorCol)
    }

    @Test
    fun `keycap sequence folds both selectors`() {
        val emu = newEmu()
        // "1️⃣" = '1' U+FE0F U+20E3 (combining keycap) → one cell total
        emu.feed("1️⃣X")
        assertEquals(2, emu.cursorCol)
        assertEquals("1X", emu.getRowText(0).trimEnd())
    }

    @Test
    fun `family emoji ZWJ sequence does not blow up column math`() {
        val emu = newEmu()
        // 👨‍💻 = 👨 ZWJ 💻; ZWJ (U+200D) is already zero-width upstream,
        // each base emoji is wide(2) → 4 columns
        emu.feed("👨‍💻Y")
        assertEquals(5, emu.cursorCol)
        val row = emu.getRowText(0)
        org.junit.Assert.assertTrue("row keeps the family emoji", row.contains("👨"))
        org.junit.Assert.assertTrue(row.contains("💻"))
        org.junit.Assert.assertTrue(row.contains("Y"))
    }
}
